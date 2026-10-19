import java.io.File

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.gms.google-services") }

// Release signing contract — идентичен app-mobile: секреты только из env CI или
// локального ~/.gradle/gradle.properties (не из git). Без полного контракта
// release остаётся UNSIGNED; debug-ключ release не подписывает никогда.
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
        "[app-tv] Release signing не настроен (MARS_RELEASE_KEYSTORE/_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD): " +
            "assembleRelease даст unsigned APK. Dev-сборка — только variant debug."
    )
}

android { namespace = "ru.mars.tv"; compileSdk = 34
    defaultConfig { applicationId = "ru.mars.tv"; minSdk = 28; targetSdk = 34; versionCode = 32; versionName = "1.5.8"
        // Локали: свои строки только в values/ (ru), каталогов других локалей в
        // модуле нет. Библиотечные локали срезаются так же, как в app-mobile:
        // 196 КБ локализованных строк + 262 КБ уникальных строковых данных,
        // которые TV-клиент не показывает никогда.
        resourceConfigurations += listOf("ru") }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Норма раздачи — v3-only: v1/v2 отключены, включается только
                // APK Signature Scheme v3. Тот же контракт, что в app-mobile.
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
    }
}

dependencies {
    implementation(project(":core-network"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // androidx.leanback:leanback удалён: библиотека была мертва (волна 20.10).
    // Доказательства из аудита перфа: 0 ссылок `androidx.leanback` из ru/mars
    // (проверено по dex: ни одного const-class/invoke/superclass), 0 наследований
    // от leanback-классов, 559 ресурсов lb_* не использовались ни одним layout.
    // Категория LEANBACK_LAUNCHER в манифесте библиотеку не требует — это
    // системный intent-filter, а не зависимость. В APK зависимость держала
    // 90 классов (46 КБ кода) + 83 КБ ресурсов + записи arsc только потому,
    // что consumer-keep-правила AAR не давали R8 доказать недостижимость.
    // Media3/ExoPlayer вместо android.media.MediaPlayer: на Mi TV (Android 9, MSSP1)
    // MediaPlayer с AAC-стримом уходит в compressed-offload ветку AudioFlinger
    // («Offload request, forcing to Direct Output», flags #10 COMPRESS_OFFLOAD) и вывод
    // на Speaker/Bluetooth глохнет. ExoPlayer 1.2.1 offload не имеет вовсе: только
    // программное декодирование в PCM и обычный AudioTrack через микшер — как системные
    // звуки, которые на этом ТВ слышно всегда. Версия зафиксирована: в 1.4+ у
    // DefaultRenderersFactory появился audio-offload режим, его сюда пускать нельзя.
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")
    // FCM push-уведомления (волна 4) — те же версии, что в app-mobile (BoM 32.8.1
    // совместим с AGP 8.2.2 / compileSdk 34 / Kotlin 1.9.22).
    implementation(platform("com.google.firebase:firebase-bom:32.8.1"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
