package ru.mars.network.local

import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

/**
 * Доступ к транспорту W1 (`OkHttpLocalChatClient`) без жёсткой зависимости на его класс.
 *
 * Зачем так: контракт §3 фиксирует только интерфейс [LocalChatClient], а имя конкретной
 * реализации живёт в задании W1 (§4, п. 2) и сама реализация в момент написания тестов
 * ещё не была создана. Прямая ссылка на несуществующий класс не дала бы собраться ВСЕМУ
 * тестовому модулю (а в нём десятки чужих тестов), поэтому реализация ищется по имени,
 * а если её нет — тест помечается пропущенным с внятным сообщением (см. [missing]).
 *
 * Как только W1 добавит класс, тесты бьют по настоящему коду: сценарии проверяют
 * наблюдаемое поведение (`Flow<LocalChatEvent>`), а не внутреннее устройство парсера.
 */
internal object LocalClientFixture {

    /** Имена, под которыми может появиться реализация: контрактное — первым. */
    private val CANDIDATES = listOf(
        "ru.mars.network.local.OkHttpLocalChatClient",
        "ru.mars.network.local.LocalOkHttpChatClient",
        "ru.mars.network.local.LocalChatClientImpl",
    )

    /** Сообщение для `Assume.assumeTrue`: почему тест пропущен. */
    const val MISSING_MESSAGE: String =
        "реализация LocalChatClient (OkHttpLocalChatClient, W1) ещё не создана — тест пропущен"

    /** Реализация транспорта или `null`, если W1 её ещё не написал. */
    fun clientOrNull(): LocalChatClient? {
        for (name in CANDIDATES) {
            val type = runCatching { Class.forName(name) }.getOrNull() ?: continue
            val instance = runCatching { instantiate(type) }.getOrNull() ?: continue
            if (instance is LocalChatClient) return instance
        }
        return null
    }

    private fun instantiate(type: Class<*>): Any? {
        // object-реализация: единственный экземпляр в INSTANCE.
        runCatching { type.getField("INSTANCE").get(null) }
            .getOrNull()
            ?.let { return it }

        val constructors = type.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) }
            .sortedBy { it.parameterCount }

        for (constructor in constructors) {
            val args = fill(constructor) ?: continue
            runCatching { constructor.newInstance(*args) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Подставляет аргументы только для известных типов; иначе конструктор не подходит. */
    private fun fill(constructor: Constructor<*>): Array<Any?>? {
        val args = arrayOfNulls<Any?>(constructor.parameterCount)
        constructor.parameterTypes.forEachIndexed { index, parameter ->
            args[index] = when {
                parameter == OkHttpClient::class.java -> OkHttpClient()
                parameter == Call.Factory::class.java -> OkHttpClient()
                parameter == Dispatcher::class.java -> Dispatcher()
                else -> return null
            }
        }
        return args
    }
}
