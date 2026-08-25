# Rockmobile

Android-клиент интернет-радио для RockCast. Он работает самостоятельно на встроенном каталоге, а при доступном Rockserver получает серверный каталог и голосовой поиск станций.

## Возможности

- **Радио без сервера** — проверенный pinned snapshot общего каталога доступен и при недоступном Rockserver.
- **Каталог Rockserver** — сервер остаётся основным источником станций, когда доступен.
- **Поиск и фильтры** — по названию, жанру, стране и языку.
- **Фоновое воспроизведение** — Media3, системное уведомление, экран блокировки и Bluetooth-управление.
- **MiniPlayer и экран плеера** — текущая станция, метаданные, очередь и понятная ошибка подключения.
- **Голосовой поиск** — одно нажатие на микрофон; после 1,2 секунды тишины фраза автоматически отправляется в Rockserver, а найденная станция запускается.
- **Защита от звука динамика** — текущее радио приглушается на время записи и не мешает детектору паузы.

## Требования

- Android 8.0 (API 26) или новее.
- Android SDK с `compileSdk 36` для сборки.
- Для server-каталога и голосового поиска — доступный Rockserver и bearer-токен.

## Быстрый старт для разработки

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

Проверки:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

APK: `app/build/outputs/apk/debug/`.

## Как пользоваться

1. Выберите станцию из списка либо найдите её через строку поиска и фильтры.
2. Нажмите строку станции или кнопку Play. MiniPlayer открывает полный экран плеера.
3. Нажмите микрофон и произнесите, например: «включи спокойный джаз», «найди французское радио» или «включи рок».
4. После фразы ничего нажимать не нужно: спустя 1,2 секунды тишины запрос уйдёт автоматически. Stop завершает фразу раньше, крестик отменяет её.

Если Rockserver недоступен, список переключается на встроенный каталог и обычное радио продолжает работать. Voice/AI при этом недоступны, но не останавливают уже играющую станцию.

## Rockserver

| Назначение | Контракт |
| --- | --- |
| Каталог | `POST /v1/search`, JSON с `query`, `locale`, `limit`; `Authorization: Bearer <token>` |
| Голосовой поиск | WebSocket `/api/v1/voice/stream`; PCM S16LE, mono, 16 kHz; `Authorization: Bearer <token>` |

Voice-сессия отправляет `start`, аудиоблоки и `commit`. Клиент принимает только структурированный и валидный результат со станцией и HTTP(S)-потоком; неизвестные или некорректные ответы не выполняются.

Адрес и токен хранятся в `SettingsRepository`. Текущая настройка предназначена для разработки: перед production-распространением нужны Settings Screen и отказ от общего bootstrap-токена. Для эмулятора хост доступен по `http://10.0.2.2:3000`; для физического устройства нужен LAN-адрес сервера.

## Каталог станций

[`app/src/main/assets/stations.v1.json`](app/src/main/assets/stations.v1.json) — bundled schema-v1 snapshot общего каталога. Приложение принимает только pinned release `2026.08.2` с SHA-256 `3fa20dca94fc059bd433a47b9fba9bb6d5e5e1aa2957a5ffb58b2a7b20b1d74d`; ID станции и primary stream берутся из JSON, а не вычисляются из URL.

Единственный authoring source baseline — `C:\repos\rockcast-station-catalog`. Обновление делается только офлайн-командой `release_sync.py sync rockmobile` с последующим `verify`, а не ручным копированием. RockServer остаётся предпочтительным источником при доступности; без него приложение использует проверенный extended SQLite `2026.08.2-mobile.1` (SHA-256 `ad469d405f177d7e476cf9b3d9985497d0e2c6132ac0f3ce14485f4eab402073`), а при его ошибке — pinned curated baseline. Откат — pin предыдущих сохранённых immutable baseline и extended package той же процедурой.

`stations.txt` сохранён только как исторический вход для проверяемой миграции и больше не читается приложением. При загрузке snapshot старые URL-derived значения из `rockmobile:…` сопоставляются с одобренными canonical ID для сохранённого списка недоступных voice-станций; неизвестные значения остаются нетронутыми на переходный релиз.

## Устройство проекта

```text
rockmobile/
├── app/src/main/assets/stations.v1.json # pinned v1 fallback-каталог
├── app/src/main/assets/rockmobile-extended-2026.08.2-mobile.1.sqlite # проверенный offline SQLite
├── app/src/main/java/com/rockmobile/
│   ├── data/                            # API, DTO, источники, repository
│   ├── playback/                        # MediaSessionService и controller
│   ├── ui/stations/                     # Compose UI и ViewModel
│   ├── voice/                           # запись, VAD, WebSocket, команды
│   └── settings/                        # URL и bearer-токен
├── app/src/test/                        # unit-тесты
├── docs/                                # документация для разработки
└── ROADMAP.md
```

Подробнее: [документация для разработки](docs/README.md), [архитектура](docs/architecture.md), [воспроизведение](docs/playback.md), [голосовой поиск](docs/voice.md).

## Ограничения и планы

- Пользовательский Settings Screen — RM-005.
- Единый канонический каталог RockCast/Rockmobile/Rockserver — RM-004. Приложение также bundle-ит проверенный Room/SQLite export `2026.08.2-mobile.1` (16 825 active/playable stations); при его повреждении или несовместимости сохраняется curated v1 baseline.
- Голосовой поиск требует работающего Rockserver и Speech-to-Text на нём; клиент не хранит учётные данные SpeechKit.

Полный план: [ROADMAP.md](ROADMAP.md).

## Лицензия

Лицензия для Rockmobile ещё не определена.
