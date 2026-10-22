package ru.mars.mobile

/**
 * Тексты и ключи голосового слоя — бывшие `const val` внутри [ChatActivity].
 *
 * Вынесены при разбиении God Object (W4): голосовые секции-расширения живут в
 * отдельных файлах, а `private const` внутри класса им не виден. Значения не менялись.
 */

/** Приватное хранилище режима автоозвучивания ответов (голосовой вывод). */
internal const val VOICE_OUTPUT_PREFS = "mars-voice-output"
internal const val KEY_AUTO_SPEAK = "auto_speak"
/** TTS-движок отсутствует/сломался — статус вместо краша и ретраев. */
internal const val VOICE_OUTPUT_UNAVAILABLE = "Озвучивание недоступно на этом устройстве"
/** Индикация голосового ввода (запись → распознавание) и понятные отказы. */
internal const val VOICE_LISTENING_STATUS = "Слушаю… (тап — остановить и отправить)"
internal const val VOICE_TRANSCRIBING_STATUS = "Распознаю…"
internal const val VOICE_BUSY_STATUS = "Голосовой ввод уже выполняется"
internal const val VOICE_EMPTY_STATUS = "Запись пустая — скажите чуть дольше"
internal const val VOICE_PERMISSION_DENIED_STATUS = "Нет доступа к микрофону — разрешите его Марсу в настройках"
internal const val VOICE_MIC_UNAVAILABLE_STATUS = "Микрофон недоступен на этом устройстве"
internal const val VOICE_NO_CREDENTIAL_STATUS = "Устройство не привязано — распознавание недоступно"
internal const val VOICE_UNREACHABLE_STATUS = "Нет связи с сервером — запись не распознана"
internal const val VOICE_REJECTED_STATUS = "Сервер не принял запись — попробуйте ещё раз"
internal const val VOICE_UNAUTHORIZED_STATUS = "Доступ устройства истёк — войдите заново"
internal const val VOICE_TOO_LONG_STATUS = "Запись слишком длинная — не удалось распознать"
internal const val VOICE_DISABLED_STATUS = "Распознавание речи выключено на сервере"
internal const val VOICE_FAILED_STATUS = "Не удалось распознать речь — попробуйте ещё раз"
/** Hands-free диалог: состояния цикла и отказы. */
internal const val VOICE_IDLE_STATUS = "Голосовой диалог выключен"
internal const val VOICE_LISTENING_HANDS_FREE_STATUS = "Слушаю… (долгое нажатие на микрофон — выключить)"
internal const val VOICE_SPEAKING_STATUS = "Отвечаю вслух…"
internal const val VOICE_SEGMENT_DROPPED_STATUS = "Ответ ещё звучит — реплика не отправлена"
/**
* L3 (перебивание озвучки голосом). Значение по умолчанию — [DEFAULT_VOICE_BARGE_IN]:
* перебивание включено, но допуск решает [BargeInGate] вместе с политикой маршрута
* ([AudioRoutePolicy]). На динамике без AEC принимается только текстовое доказательство
* (partial ASR, слов которого нет в произносимом тексте), на наушниках/Bluetooth —
* накопленная речь. «Просто слушать и перебивать» без этого гейта означало бы, что цикл
* перебивает сам себя собственным динамиком. Живые замеры (POCO F5) остаются
* обязательными: пока акустика не проверена, гейт — единственная защита.
*/
internal const val KEY_VOICE_BARGE_IN = "barge_in"

/**
* Перебивание включено по умолчанию — вместе с гейтом, а не вместо него.
*
* Раньше было `false`: без [BargeInGate] и маршрута это действительно было опасно.
* Теперь опасность закрыта политикой (гейт + [AudioRoutePolicy] + `spokenText` для
* echo-проверки), и выключенный по умолчанию флаг означал бы, что семь улучшений
* голосового чата не работают у человека из коробки.
*/
internal const val DEFAULT_VOICE_BARGE_IN = true
