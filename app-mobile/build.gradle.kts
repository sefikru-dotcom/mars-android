import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// Release signing contract: секреты приходят ТОЛЬКО из переменных окружения CI
// (или gradle-свойств локального ~/.gradle/gradle.properties, который не в git).
// В репозитории не хранятся ни keystore, ни пароли.
//   MARS_RELEASE_KEYSTORE          — путь к файлу keystore
//   MARS_RELEASE_KEYSTORE_PASSWORD — пароль keystore
//   MARS_RELEASE_KEY_ALIAS         — алиас ключа
//   MARS_RELEASE_KEY_PASSWORD      — пароль ключа
// Если контракт не заполнен, release остаётся UNSIGNED (dev-fallback — только
// debug-вариант); debug-ключ никогда не подписывает release.
fun releaseSecret(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val releaseKeystoreFile: File? = releaseSecret("MARS_RELEASE_KEYSTORE")
    ?.let { File(it) }
    ?.takeIf { it.isFile }
val releaseKeystorePassword: String? = releaseSecret("MARS_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = releaseSecret("MARS_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = releaseSecret("MARS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile != null &&
    releaseKeystorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

if (!hasReleaseSigning) {
    logger.warn(
        "[app-mobile] Release signing не настроен (MARS_RELEASE_KEYSTORE/_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD): " +
            "assembleRelease даст unsigned APK. Dev-сборка — только variant debug."
    )
}

android {
    namespace = "ru.mars.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.mars.mobile"
        minSdk = 28
        targetSdk = 34
        versionCode = 39
        versionName = "1.7.6"
        // Локали: свои строки живут ТОЛЬКО в values/ (ru) — каталогов values-en/
        // и других локалей в модуле нет, поэтому весь UI-текст и так русский.
        // Библиотеки (appcompat, firebase, core) тянут ~84 локали: без среза в APK
        // едет 128 КБ локализованных строк и 161 КБ уникальных строковых данных,
        // которые этот клиент никогда не покажет. `ru` оставляет русские
        // переводы библиотек, остальные отбрасываются; значения по умолчанию
        // (values/) не срезаются никогда, поэтому фолбэк не ломается.
        resourceConfigurations += listOf("ru")
        // Instrumented UI-тесты (Espresso) в src/androidTest: ручная View-вёрстка
        // проверяется на эмуляторе; harness детерминированный (фейковый credential +
        // seeded офлайн-кэш, радио выключено — боевой сервер не задействуется).
        // GAP-2 guard: кастомный runner завершает instrumentation на физическом
        // устройстве ДО выполнения любого теста (UiTestHarness дополнительно
        // дублирует guard в каждом изменяющем методе).
        testInstrumentationRunner = "ru.mars.mobile.EmulatorOnlyTestRunner"

        // Локальный ASR (sherpa-onnx): AAR несёт .so для 4 ABI (~50 МБ), а устройства
        // Марса — только arm64. Без фильтра APK раздувается на ~80 МБ мёртвого кода
        // (x86/x86_64 нужны лишь эмулятору, armeabi-v7a — ни одному целевому телефону).
        // Инструментальные UI-тесты идут на arm64-эмуляторе; x86-эмулятор ASR не увидит,
        // но UI-тесты ASR не трогают.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Норма раздачи — v3-only (minSdk 24+): v1 (JAR) и v2 отключены,
                // включается только APK Signature Scheme v3. Совпадает с контрактом
                // scripts/apk-contract-check.py --signing-scheme v3-only, поэтому
                // артефакт CI и артефакт раздачи — один и тот же файл.
                enableV1Signing = false
                enableV2Signing = false
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            // R8 + shrinkResources: keep-правила в proguard-rules.pro
            // (data-слой ru.mars.network парсится Moshi/org.json рефлексивно).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // Изолированный вариант ДЛЯ инструментальных UI-тестов (GAP-2): отдельный
        // applicationId `ru.mars.mobile.uitest` → собственный UID, data-каталог и
        // пространство AndroidKeyStore. Боевая установка `ru.mars.mobile` (debug на
        // устройстве или release) не заменяется и не затрагивается: resetState/seed
        // физически не видят её prefs и credential. Радио — глобальный эффект,
        // поэтому вариант работает ТОЛЬКО в паре с EmulatorOnlyTestRunner.
        // Ручная вёрстка не затрагивается: код/ресурсы идентичны debug, packageName
        // в коде везде динамический (context.packageName), hardcoded-авторитетов нет.
        create("uiTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            // core-network (библиотечный модуль) знает только debug/release:
            // зависимость резолвится в debug-вариант библиотеки.
            matchingFallbacks += listOf("debug")
        }
    }

    // androidTest собирается и подключается только к варианту uiTest:
    // connectedAndroidTest → connectedUiTestAndroidTest (изолированный пакет).
    testBuildType = "uiTest"

    // google-services плагин ищет клиента Firebase по applicationId, а вариант
    // uiTest несёт суффикс ".uitest" (в Firebase не зарегистрирован намеренно:
    // изолированный тестовый вариант без FCM). Задача плагина для этого варианта
    // отключается — uiTest собирается без сгенерированных Firebase-ресурсов
    // (EmulatorOnlyTestRunner в любом случае завершает instrumentation до тестов).
    tasks.matching { it.name == "processUiTestGoogleServices" }.configureEach { enabled = false }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Инструментальные UI-тесты: системные анимации отключаются на устройстве,
        // иначе Espresso-синхронизация flaky на drawer/переходах.
        animationsDisabled = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.media:media:1.6.0")
    implementation(project(":core-network"))
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // Media3/ExoPlayer вместо android.media.MediaPlayer (app-tv/TvMusicPlayer паттерн):
    // MediaPlayer на части устройств уводит AAC-стрим в compressed-offload ветку
    // AudioFlinger, где вывод глохнет; ExoPlayer 1.2.1 offload не имеет вовсе — только
    // программное декодирование в PCM и обычный AudioTrack через микшер. Версия
    // зафиксирована: в 1.4+ у DefaultRenderersFactory появился audio-offload режим,
    // его сюда пускать нельзя. minSdk 28 ≥ требований Media3 (19).
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")
    // FCM push-уведомления (волна 4). BoM фиксирует версии артефактов Firebase;
    // 32.8.1 — линия, совместимая с AGP 8.2.2 / compileSdk 34 / Kotlin 1.9.22
    // (более новые BoM требуют compileSdk 35+).
    implementation(platform("com.google.firebase:firebase-bom:32.8.1"))
    implementation("com.google.firebase:firebase-messaging")
    // --- Локальный ASR (sherpa-onnx) ---
    // Координата именно артефакта AAR (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx`), а НЕ
    // агрегатора `com.github.k2-fsa:sherpa-onnx`: агрегатор тянет ещё `sherpa-onnx-jvm`
    // и native-lib для linux/osx/win, а jvm-модуль содержит те же классы
    // com.k2fsa.sherpa.onnx → duplicate class на мерже dex и +десятки МБ в APK.
    // ABI срезаны abiFilters (defaultConfig).
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    // Распаковка .tar.bz2 модели на устройстве: в Android нет bz2 в stdlib, а тащить
    // нативную libbz2 ради одного раза при первом запуске — лишний .so. commons-compress
    // — чистая Java, ~1 МБ, покрывает bz2+tar.
    implementation("org.apache.commons:commons-compress:1.26.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // MockWebServer — для тестов проводов (какой запрос реально ушёл): та же версия, что
    // в core-network. Без него «прикрепление до сессии» проверялось бы только по исходнику.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // --- Instrumented UI-тесты (src/androidTest) ---
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
}
