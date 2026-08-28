# Rockmobile

Android-клиент интернет-радио для RockCast. Стартовый экран использует тот же локальный каталог, что и RockCast, а Rockserver подключается для явного текстового и голосового поиска.

## Возможности

- **Радио без сервера** — проверенный pinned snapshot общего каталога доступен и при недоступном Rockserver.
- **Поиск Rockserver** — серверный поиск включается для текста и выбранных фильтров; стартовый список не заменяется поисковой выдачей.
- **Поиск и фильтры** — полный серверный набор жанров, стран и языков; при недоступном сервере остаётся локальный резерв.
- **Фоновое воспроизведение** — Media3, системное уведомление, экран блокировки и Bluetooth-управление.
- **MiniPlayer и экран плеера** — текущая станция, метаданные, очередь и понятная ошибка подключения.
- **Голосовой поиск** — одно нажатие на микрофон; после 1,2 секунды тишины фраза автоматически отправляется в Rockserver, а найденная станция запускается.
- **Защита от звука динамика** — текущее радио приглушается на время записи и не мешает детектору паузы.

## Требования

- Android 8.0 (API 26) или новее.
- Android SDK с `compileSdk 36` для сборки.
- Для server-каталога и голосового поиска — сеть до официального Rockserver `https://alex.vault57.ru`.

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

1. Выберите станцию из списка либо найдите её через строку поиска и фильтры. При доступном Rockserver фильтр отправляется как обычный естественный запрос, поэтому результат не ограничен стартовыми 41 станцией.
2. Нажмите строку станции или кнопку Play. MiniPlayer открывает полный экран плеера.
3. Нажмите микрофон и произнесите, например: «включи спокойный джаз», «найди французское радио» или «включи рок».
4. После фразы ничего нажимать не нужно: спустя 1,2 секунды тишины запрос уйдёт автоматически. Stop завершает фразу раньше, крестик отменяет её.

Если Rockserver недоступен, список переключается на встроенный каталог и обычное радио продолжает работать. Voice/AI при этом недоступны, но не останавливают уже играющую станцию.

## Rockserver

Официальные сборки используют публичный RockServer `https://alex.vault57.ru` без пользовательской настройки URL/токена. Публичные операции `/v1/*` вызываются без Bearer; legacy LAN-дефолты и bootstrap-токен сбрасываются при загрузке настроек.

| Назначение | Контракт |
| --- | --- |
| Поиск и выбранные фильтры | `POST /v1/search`, JSON с `query`, `locale`, `limit`; без Authorization |
| Варианты фильтров | `GET /v1/catalog/stations?limit=50&cursor=...`, постранично; без Authorization |
| Голосовой поиск | WebSocket `/v1/voice/stream`; PCM S16LE, mono, 16 kHz; без Authorization |

Voice-сессия: `start` → `ready` → PCM-чанки ≤32 KiB → `commit`. Клиент принимает только структурированный и валидный результат со станцией и HTTP(S)-потоком; неизвестные или некорректные ответы не выполняются. HTTPS всегда мапится на WSS с сохранением TLS. Текстовый поиск использует фактический лимит RockServer `limit=20`.

Адрес по умолчанию хранится в `SettingsRepository` как `https://alex.vault57.ru`. Публичные `/v1` операции каталога и voice вызываются без bearer; native account access/refresh tokens выдаются только pairing и хранятся в Android Keystore. Settings Screen — RM-005.

## Каталог станций

[`app/src/main/assets/stations.v1.json`](app/src/main/assets/stations.v1.json) — bundled schema-v1 snapshot общего каталога. Приложение принимает только pinned release `2026.08.2` с SHA-256 `3fa20dca94fc059bd433a47b9fba9bb6d5e5e1aa2957a5ffb58b2a7b20b1d74d`; ID станции и primary stream берутся из JSON, а не вычисляются из URL.

Единственный authoring source baseline — `C:\repos\rockcast-station-catalog`. Обновление делается только офлайн-командой `release_sync.py sync rockmobile` с последующим `verify`, а не ручным копированием. Стартовый экран всегда использует тот же pinned baseline, что и RockCast. При доступном сервере приложение в фоне получает полный набор вариантов через постраничный public catalog, а текст и выбранные фильтры отправляет в `POST /v1/search`; при недоступном сервере радио продолжает работать, а для локального поиска используется проверенный extended SQLite `2026.08.2-mobile.1` (SHA-256 `ad469d405f177d7e476cf9b3d9985497d0e2c6132ac0f3ce14485f4eab402073`) с откатом к pinned baseline.

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
│   └── settings/                        # официальный RockServer URL
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
