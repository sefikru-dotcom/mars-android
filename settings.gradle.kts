pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (локальный ASR) публикуется только через JitPack.
        // content-фильтр держит JitPack узким: туда ходят лишь за com.github.k2-fsa*,
        // иначе Gradle проверял бы JitPack для каждой зависимости (лишние 404 и время).
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\.k2-fsa(\\..*)?")
            }
        }
    }
}
rootProject.name = "mars-android"
include(":app-tv", ":app-mobile", ":core-network")
