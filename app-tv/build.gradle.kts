plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "ru.mars.tv"; compileSdk = 34
    defaultConfig { applicationId = "ru.mars.tv"; minSdk = 28; targetSdk = 28; versionCode = 1; versionName = "1.0" }
}

dependencies { implementation(project(":core-network")); implementation("androidx.core:core-ktx:1.12.0"); implementation("androidx.appcompat:appcompat:1.6.1"); implementation("androidx.leanback:leanback:1.0.0") }
