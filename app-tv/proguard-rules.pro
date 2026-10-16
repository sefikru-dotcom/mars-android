# Mars app-tv — R8 keep-правила для release (minify + shrinkResources).
#
# Обоснование: рефлексионный путь сериализации data/network-слоя
# (:core-network) — это MusicRepository с KotlinJsonAdapterFactory и модели
# музыкального графа из ru.mars.network.model (блок ниже). Остальной слой
# десериализуется в Map<String, Any?> над generic-типами — без рефлексии по
# классам-моделям. Прежнее широкое правило и его доводы про «Retrofit создаёт
# реализации API динамически» и org.json ложны для текущего дерева: ссылок на
# `Lretrofit2/` в dex — 0 (импорт в api/MarsApi.kt мёртв, Retrofit.create не
# вызывается), а org.json (TvSkillsPolicy, TvProjectsMetricsParser) парсит JSON
# динамически и не резолвит классы по имени. Имена и члены 500+ классов
# оставались нетронутыми без нужды.

# Рефлексионный граф Moshi: MusicRepository (единственный держатель
# KotlinJsonAdapterFactory) и модели музыки, подаваемые в moshi.adapter(...).
-keep class ru.mars.network.repository.MusicRepository { *; }
-keep class ru.mars.network.repository.MusicRepository$* { *; }
-keep class ru.mars.network.model.MusicTrack { *; }
-keep class ru.mars.network.model.PlaybackStatus { *; }
-keep class ru.mars.network.model.MusicPlayerState { *; }
-keep class ru.mars.network.model.MusicSearchResult { *; }
-keep class ru.mars.network.model.LyricsResult { *; }
-keep class ru.mars.network.model.Playlist { *; }
-keep class ru.mars.network.model.MusicShelf { *; }
-keep class ru.mars.network.model.HistoryEntry { *; }

# Moshi: аннотации и generic-сигнатуры нужны рефлексионным адаптерам.
-keep @interface com.squareup.moshi.Json
-keep @interface com.squareup.moshi.JsonClass
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# Kotlin-метаданные: moshi-kotlin (KotlinJsonAdapterFactory) определяет
# Kotlin-классы и nullability по ним.
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
}

# Kotlin data-классы вне ru.mars.network, если когда-либо будут парситься
# Moshi: сохранять конструктор по умолчанию и все аргументы.
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>();
}

# Отсутствующие опциональные зависимости OkHttp/conscrypt — не ошибка сборки.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
