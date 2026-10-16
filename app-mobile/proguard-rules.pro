# Mars app-mobile — R8 keep-правила для release (minify + shrinkResources).
#
# Рефлексионный путь сериализации в ru.mars.network — это MusicRepository И модели
# музыкального графа из ru.mars.network.model (см. блок ниже): MusicRepository строит
# Moshi с KotlinJsonAdapterFactory и парсит вложенные data-классы (Session, TrackDto,
# HomeEnvelope, ShelfDto, ...), а модели музыки передаются в moshi.adapter(...) как
# типы-аргументы из MusicRepository/экранов. KotlinJsonAdapterFactory читает
# Kotlin-метаданные, primary-конструктор и имена свойств, поэтому и вложенные классы
# MusicRepository, и перечисленные ниже модели ru.mars.network.model обязаны дожить
# до рантайма с именами и членами.
#
# Остальной слой ru.mars.network (прочие репозитории, кэши, credential, API)
# десериализуется в Map<String, Any?> — Moshi над generic-типами Map/List/String,
# без рефлексии по классам-моделям — и вызывается напрямую из Kotlin-кода.
# Class.forName/доступ к полям и методам по имени в проде не используются
# (retrofit.create не вызывается, Gson/kotlinx.serialization/org.json отсутствуют),
# поэтому остальной пакет можно обфусцировать и сокращать средствами R8. Это снимает
# прежнее широкое правило `-keep class ru.mars.network.** { *; }`, из-за которого
# имена и все члены 490+ классов оставались нетронутыми.
#
# ГРАБЛИ: проверять сужение по mapping.txt, а не только по зелёной сборке. Дебажные
# тесты R8 не покрывают; гейт-проверка — scripts/apk-contract-check.py (ловит
# переименование классов рефлексионного графа).
-keep class ru.mars.network.repository.MusicRepository { *; }
-keep class ru.mars.network.repository.MusicRepository$* { *; }

# ВАЖНО: MusicModels.kt лежит в ru.mars.network.model, но эти классы — часть ТОГО ЖЕ
# рефлексионного графа: moshi.adapter(LyricsResult::class.java) и типы полей вложенных
# envelope-ов (MusicTrack/MusicShelf/MusicPlayerState/PlaybackStatus/HistoryEntry/Playlist).
# KotlinJsonAdapterFactory читает kotlin-метаданные, поэтому R8 переименование имён и
# свойств ломает парсинг: необязательные поля молча становятся null, обязательные
# (MusicTrack.id) бросают JsonDataException. Дебажные тесты этого НЕ видят — R8 там не работает.
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
