plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "ru.mars.tv"; compileSdk = 34
    defaultConfig { applicationId = "ru.mars.tv"; minSdk = 28; targetSdk = 34; versionCode = 4; versionName = "1.0.3" }
}

dependencies {
    implementation(project(":core-network"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.leanback:leanback:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
