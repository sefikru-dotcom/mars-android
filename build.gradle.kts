plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // FCM push (волна 4): 4.4.2 совместим с AGP 8.2.2 / Gradle 8.2. Плагин
    // парсит google-services.json (в git НЕ хранится — приходит из CI-секретов
    // MARS_GOOGLE_SERVICES_{MOBILE,TV}_B64, см. .github/workflows/ci.yml).
    id("com.google.gms.google-services") version "4.4.2" apply false
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }
}
