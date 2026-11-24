# Волна 7: память и пересборка документов

Две фичи. Обе работают на телефоне целиком, без сервера Марса.

## §1. Память модели (на подобие серверного Марса)

СЕЙЧАС В ЛОКАЛЬНОМ ЧАТЕ НЕТ ДАЖЕ СИСТЕМНОГО ПРОМПТА. `LocalRole.SYSTEM` в ядре
поддерживается (`roleWire` маппит в `"system"`), но ни одного сообщения с этой ролью
никто не создаёт. Модель каждый раз начинает с чистого листа.

Как устроено на сервере Марса: два markdown-файла — `USER.md` (кто владелец) и
`MEMORY.md` (рабочие факты), оба вставляются в контекст каждой сессии, между записями
разделитель `§`.

### Что делать (три части)

**1. Хранилище** — `MemoryStore` в `core-network/src/main/java/ru/mars/network/local/`:
- файл `memory.md` в каталоге приложения (путь передаёт вызывающий, модуль чистый);
- записи через `§` на отдельной строке, как на сервере;
- `fun entries(): List<String>`, `fun add(text: String): Boolean` (обрезка, лимит
  8000 символов как на сервере, отказ при переполнении с причиной),
  `fun remove(index: Int): Boolean`, `fun replace(index: Int, text: String): Boolean`,
  `fun clear()`, `fun systemPromptBlock(): String` — готовый блок для системного
  промпта, пустая строка если записей нет;
- атомарная запись: `.tmp` + переименование, как в `LocalSessionStore`;
- битый/пустой файл не роняет чтение.

**2. Профиль владельца** — `OwnerProfile` там же:
- поля: `name`, `city`, `about` (свободный текст), `updatedAt`;
- хранение в `profile.json`, атомарно, с обратной совместимостью;
- `fun toPromptSection(): String` — блок «О владельце», пустой если не заполнено.

**3. Инструмент записи** — `localtools/RememberTool.kt` (реализует `LocalDeviceTool`):
- схема `remember(text: String)` — «Сохранить факт о владельце в долгую память»;
- `requiresConfirmation = false` (запись факта безобидна);
- пишет через `MemoryStore.add`, при переполнении возвращает понятную ошибку;
- зарегистрировать в `LocalToolCatalog`.

### Соединение (это делает координатор, не воркер)

`LocalChatPresenter.send` собирает системное сообщение:
`OwnerProfile.toPromptSection() + MemoryStore.systemPromptBlock() + «правила»`
и кладёт первым в `history` перед пользовательским. Одна строка в KDoc:
системный промпт НЕ попадает в ленту (`LocalRole.SYSTEM` уже фильтруется в `upsert`).

## §2. Пересборка документов

Модель не может вернуть файл — только текст. Значит собираем файл на телефоне из
текста, который она написала.

### Что делать

Создать `app-mobile/src/main/java/ru/mars/mobile/DocumentBuilder.kt`:
- `object DocumentBuilder`
- `fun buildDocx(text: String): ByteArray` — минимальный корректный DOCX:
  `[Content_Types].xml`, `_rels/.rels`, `word/document.xml`, `word/_rels/document.xml.rels`.
  Абзацы по `\n`, пустая строка = пустой `<w:p/>`. XML-энтити экранировать.
- `fun buildXlsx(rows: List<List<String>>): ByteArray` — минимальный XLSX с одним
  листом: `[Content_Types].xml`, `_rels/.rels`, `xl/workbook.xml`,
  `xl/_rels/workbook.xml.rels`, `xl/worksheets/sheet1.xml`. Inline-строки
  (`t="inlineStr"`), числа — как числа.
- `fun buildTxt(text: String): ByteArray` — UTF-8.
- `fun rowsFromText(text: String): List<List<String>>` — текст → строки таблицы:
  разбивка по `\n`, ячейки по `\t`; если табуляций нет — по `|` (markdown-таблица,
  с отбрасыванием строки-разделителя `---`).
- Никаких новых зависимостей: `java.util.zip.ZipOutputStream`, `ZipEntry`.
- ВАЖНО: ZIP-запись строго в UTF-8, `ZipEntry` создавать с явным именем без
  ведущего слэша (иначе Word ругается на файл).

Проверить обязательно на реальных байтах: собрать DOCX/XLSX, распаковать обратно
`ZipInputStream` и убедиться, что XML валидный и текст на месте. Плюс — если на
машине есть Word/LibreOffice, открыть нельзя, поэтому проверять структуру.

### Подключение (координатор)

Кнопка на сообщении ассистента «Сохранить как файл» → диалог выбора формата →
`DocumentBuilder` → `ACTION_CREATE_DOCUMENT` через `ActivityResultContracts.CreateDocument`.

## §3. Запреты для воркеров

Не запускать Gradle, не трогать git, не менять чужие файлы, не добавлять зависимости,
не печатать ключ API. Отчёт: что проверено и ЧЕМ (обязательно реальный прогон), что
НЕ проверено.
