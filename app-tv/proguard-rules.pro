# Mars app-tv — R8 keep-правила для release (minify + shrinkResources).
#
# Обоснование: data/network-слой (модуль :core-network) сериализуется Moshi
# через рефлексию (Moshi.Builder().build(), KotlinJsonAdapterFactory, org.json),
# а Retrofit создаёт реализации API-интерфейсов динамически. Имена классов,
# полей и generic-сигнатуры этого слоя обязаны дожить до рантайма.

# Data-слой целиком: модели, репозитории, кэши, credential, API-интерфейсы.
-keep class ru.mars.network.** { *; }

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
