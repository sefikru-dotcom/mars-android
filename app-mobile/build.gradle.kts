import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 27
        versionName = "1.5.1"
        // Instrumented UI-тесты (Espresso) в src/androidTest: ручная View-вёрстка
        // проверяется на эмуляторе; harness детерминированный (фейковый credential +
        // seeded офлайн-кэш, радио выключено — боевой сервер не задействуется).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
    }

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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // --- Instrumented UI-тесты (src/androidTest) ---
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
}
