package ru.mars.mobile

/**
 * Решение о миграции ключей провайдера из открытого вида в Keystore-хранилище.
 *
 * Зачем отдельный объект, а не код внутри [LocalProviderPrefsStore]: миграция — это
 * единственное место, где решается «что зашифровать, что стереть», и ошибиться здесь
 * значит потерять ключ человека (он введёт его заново, а до этого чат молча отвечает
 * «нет ключа»). Поэтому решение вынесено в чистую функцию от снимка `SharedPreferences`:
 * его можно проверить JVM-тестом без устройства, без AndroidKeyStore и без шифрования.
 *
 * Что считается «старым ключом». Слот `api_key.<id>` исторически существовал в двух видах:
 * - пара `api_key.<id>` + `api_key_iv.<id>` — уже шифротекст AES/GCM, ключ шифрования в
 *   AndroidKeyStore; трогать нельзя, иначе человек потеряет рабочий ключ;
 * - только `api_key.<id>` без IV — ключ лежал открытым текстом (или IV потерялся).
 *   Такой слот шифруется и стирается: в prefs не должно остаться ни одного секрета.
 *
 * Правило «стирать только после успешного шифрования» держит вызывающая сторона
 * (`LocalProviderPrefsStore.migrateLegacyKeys`): сначала шифрует все [Plan.toSeal], и лишь
 * если это удалось — одним `commit()` стирает [Plan.toErase] и пишет шифротекст. Если
 * Keystore недоступен, ключ остаётся на месте, и человек не вводит его заново.
 *
 * Значения слотов наружу не отдаются ни в лог, ни в `toString`: [Secret.toString]
 * перекрыт, потому что план миграции содержит открытый ключ.
 */
internal object LocalProviderKeyMigration {

    /** Слот шифротекста (или старого открытого ключа) профиля: `api_key.<profileId>`. */
    const val SLOT_PREFIX = "api_key."

    /** Слот IV для [SLOT_PREFIX]: `api_key_iv.<profileId>`. */
    const val IV_PREFIX = "api_key_iv."

    /** Ключ, который надо зашифровать. `toString` намеренно не печатает [plaintext]. */
    data class Secret(val profileId: String, val plaintext: String) {
        override fun toString(): String = "Secret(profileId=$profileId, plaintext=***)"
    }

    /**
     * План миграции.
     *
     * [toSeal] — открытые ключи, которые надо зашифровать в Keystore-схему.
     * [toErase] — слоты prefs, которые обязаны исчезнуть (открытый ключ, IV без ключа,
     * пустое значение). Слот из [toSeal] тоже попадает сюда: вызывающая сторона сначала
     * удаляет его, затем пишет по тому же имени шифротекст — открытый ключ не остаётся
     * в файле ни на один коммит.
     * [toKeep] — слоты, которые уже в порядке; их не переписываем.
     */
    data class Plan(
        val toSeal: List<Secret> = emptyList(),
        val toErase: List<String> = emptyList(),
        val toKeep: List<String> = emptyList(),
    ) {
        /** Нечего делать: снимок prefs уже в целевом виде. */
        val isEmpty: Boolean get() = toSeal.isEmpty() && toErase.isEmpty()
    }

    /**
     * Разобрать снимок `SharedPreferences.getAll()` и вернуть решение.
     *
     * Прочие слоты (URL, тип API, имя модели, `profiles`, `active_id`) не попадают ни в
     * один список: миграция не имеет права переписывать открытые параметры профиля.
     */
    fun plan(entries: Map<String, *>): Plan {
        val toSeal = mutableListOf<Secret>()
        val toErase = mutableListOf<String>()
        val toKeep = mutableListOf<String>()

        // Сортировка — чтобы план был детерминированным и тест сравнивал списки целиком.
        for (key in entries.keys.sorted()) {
            when {
                key.startsWith(IV_PREFIX) -> {
                    val id = key.removePrefix(IV_PREFIX)
                    val slot = SLOT_PREFIX + id
                    val slotValue = entries[slot] as? String
                    // IV без шифротекста — мусор от неудачной записи; с парой его не трогаем.
                    if (id.isBlank() || slotValue.isNullOrBlank()) toErase += key else toKeep += key
                }

                key.startsWith(SLOT_PREFIX) -> {
                    val id = key.removePrefix(SLOT_PREFIX)
                    // Пустая строка — не ключ: приводим к null один раз, чтобы ниже
                    // не пришлось полагаться на контракт isNullOrBlank при смарт-касте.
                    val value = (entries[key] as? String)?.takeIf { it.isNotBlank() }
                    val iv = entries[IV_PREFIX + id] as? String
                    when {
                        id.isBlank() || value == null -> {
                            // Пустой слот — не ключ: стираем его и осиротевший IV.
                            toErase += key
                            if (id.isNotBlank() && !iv.isNullOrBlank()) toErase += IV_PREFIX + id
                        }

                        iv.isNullOrBlank() -> {
                            // Открытый ключ (или шифротекст с потерянным IV): шифруем и стираем.
                            toSeal += Secret(profileId = id, plaintext = value)
                            toErase += key
                        }

                        else -> toKeep += key
                    }
                }
            }
        }
        return Plan(toSeal = toSeal, toErase = toErase.distinct(), toKeep = toKeep.distinct())
    }
}
