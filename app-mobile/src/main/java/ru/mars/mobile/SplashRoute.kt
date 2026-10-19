package ru.mars.mobile

/**
 * Маршрут заставки: куда уходит пользователь после бренд-экрана.
 *
 * Вынесено из [SplashActivity] в чистую функцию, потому что это единственное
 * РЕШЕНИЕ на экране, а всё остальное — рисование и таймеры. Так правило «есть
 * credential — в чат, нет — в логин» проверяется JUnit-тестом без Android, и его
 * нельзя случайно разойтись с тем, что делает `MainActivity` при холодном старте.
 *
 * Проверка наличия токена здесь НЕ выполняется: политика получает уже готовый
 * факт. Чтение AndroidKeyStore — блокирующая операция, её место в IO-диспетчере
 * вызывающего, а не в решении о маршруте.
 */
object SplashRoute {

    /** Целевой экран после заставки. */
    enum class Destination {
        /** Токен есть — сразу чат, без промежуточной формы логина. */
        CHAT,

        /** Токена нет — экран авторизации устройства. */
        LOGIN,
    }

    /**
     * Маршрут по факту наличия сохранённого credential.
     *
     * @param hasStoredCredential `true`, если `AndroidKeyStoreCredentialStore.read()`
     *   вернул запись. Ошибка чтения трактуется вызывающим как «токена нет»:
     *   битый keystore должен приводить к логину, а не к пустому чату.
     */
    fun destinationFor(hasStoredCredential: Boolean): Destination =
        if (hasStoredCredential) Destination.CHAT else Destination.LOGIN
}
