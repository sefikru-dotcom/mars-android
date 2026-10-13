import java.io.File

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

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
    defaultConfig { applicationId = "ru.mars.tv"; minSdk = 28; targetSdk = 34; versionCode = 23; versionName = "1.4.1" }

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
}

dependencies {
    implementation(project(":core-network"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    // Media3/ExoPlayer вместо android.media.MediaPlayer: на Mi TV (Android 9, MSSP1)
    // MediaPlayer с AAC-стримом уходит в compressed-offload ветку AudioFlinger
    // («Offload request, forcing to Direct Output», flags #10 COMPRESS_OFFLOAD) и вывод
    // на Speaker/Bluetooth глохнет. ExoPlayer 1.2.1 offload не имеет вовсе: только
    // программное декодирование в PCM и обычный AudioTrack через микшер — как системные
    // звуки, которые на этом ТВ слышно всегда. Версия зафиксирована: в 1.4+ у
    // DefaultRenderersFactory появился audio-offload режим, его сюда пускать нельзя.
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
