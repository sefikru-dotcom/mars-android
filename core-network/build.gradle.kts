plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

android { namespace = "ru.mars.network"; compileSdk = 34
    defaultConfig { minSdk = 28 }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    testImplementation("junit:junit:4.13.2")
}
