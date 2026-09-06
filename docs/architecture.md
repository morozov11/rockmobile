# Архитектура

```text
StationsScreen ── StationsViewModel ── StationRepository
                                           ├── RockserverStationSource
                                           └── RockcastAssetStationSource (pinned schema-v1 JSON)

StationsScreen ── PlaybackController ── MediaController ── MediaSessionService ── ExoPlayer

StationsScreen ── VoiceCommandController ── VoiceRecorder / RockserverVoiceClient
                                               └── VoicePlaybackActions ── PlaybackController

AccountScreen ── AccountViewModel ── AccountGateway ── RockserverApi
                                  └── CredentialStore ── Android Keystore

AccountScreen ── TargetDirectoryViewModel ── TargetDirectoryRepository
                              ├── DeviceControlDirectoryApi (typed REST DTOs)
                              ├── authenticated controller WSS
                              └── SettingsRepository (explicit target ID only)
```

## Каталог

`StationRepository` реализует local-first policy, как RockCast: первый экран читает проверенный bundled `stations.v1.json` и не подменяет его результатом поиска. В фоне `GET /v1/catalog/stations` постранично загружает серверную лексику фильтров, но не заменяет видимый стартовый список. Текст и любой выбранный жанр/страна/язык отправляются как естественный запрос в `POST /v1/search`; при ошибке серверного поиска ViewModel использует offline search, если он доступен. Loader сверяет SHA-256, catalogVersion и schemaVersion, а baseline повторяет порядок RockCast. Отмена coroutine пробрасывается и не считается fallback. Если локальные источники недоступны, ViewModel показывает фатальную ошибку. Ошибки Media3 не поступают в repository и не могут переключить каталог.

`StationFilterOptions` хранит полный набор вариантов, полученный из серверного каталога, либо локальный набор при offline fallback. `StationsViewModel` отвечает за debounce, отмену устаревших запросов и возврат к baseline после очистки поиска. Voice-result заменяет видимый список ранжированными кандидатами Rockserver и сразу запускает выбранную станцию.

`StationsScreen` теперь отвечает только за композицию экрана; каталоговые компоненты и `PlayerScreen` находятся отдельно в том же UI-пакете. Это уменьшает размер экранного файла, не превращая каждый небольшой composable в самостоятельный слой.

## Иконки станций (MVP)

При наличии валидного `faviconUrl` клиент загружает изображение напрямую; иначе — обычный `/favicon.ico` с официального `homepageUrl` (без HTML-scrape). Fetch, decode и disk-cache (`cacheDir/station-icons`) выполняются вне UI; лимиты — 512 KiB на wire и thumbnail ≤ 64px. Ошибки оставляют letter-tile.

## Официальный RockServer

Release-клиент использует `https://alex.vault57.ru`. Публичные `POST /v1/search` и `wss://…/v1/voice/stream` идут без Bearer. Legacy LAN/emulator URL и старый bootstrap-токен scrub'ятся при старте `SettingsRepository`; native account tokens живут только в Keystore-защищённом хранилище.

## Аккаунт и pairing

`AccountViewModel` создаёт pairing для `RockMobile — <модель устройства>`, держит одноразовые
proofs только в памяти, открывает G2-ссылку `/?code=…&secret=…` и после возврата из браузера
продолжает polling в lifecycle foreground. Native completion отправляет только `desktop_token`;
account/device display names сохраняются вместе с credentials в зашифрованном Keystore-файле.
Если native список устройств недоступен, успешный pairing не блокируется и UI явно сообщает о
временной недоступности центра устройств.

Внутри account-пакета разделены четыре ответственности: модели и UI-state, HTTP gateway,
зашифрованное хранилище и lifecycle ViewModel. UI не знает о формате HTTP-ответов, а ViewModel
зависит от `AccountGateway`/`CredentialStore`, поэтому G3/G7 можно добавить без подмены
неподдерживаемых маршрутов.

## Target directory (DC-015)

После явного подключения аккаунта selector читает только разрешённую server-side directory
projection через тот же short-lived native session. `devicecontrol` изолирует strict
`kotlinx.serialization` DTOs, REST и WebSocket envelopes от domain/UI. Unknown capabilities and
safe unknown messages do not cross the boundary; malformed known messages are rejected there.
The repository applies monotonic snapshots/upserts/removals and reloads after a revision gap,
directory resync request or connection loss. It never exposes a command path.

Only an explicitly tapped target ID is saved, scoped by account and controller device. A missing,
revoked, offline, stale or unknown selected target is cleared rather than replaced from a display
name; the UI shows a requires-selection state. Ordinary account inventory, pairing, radio and
offline catalogue remain independent.

## Воспроизведение

`PlaybackController` — activity-scoped proxy к `MediaController`; он строит очередь и публикует `PlaybackState`. Единственный владелец ExoPlayer — `RockmobileMediaSessionService`, поэтому радио продолжает играть в фоне, а Media3 обслуживает notification, Bluetooth и lock screen.

## Голос и lifecycle

`VoiceCommandController` — state machine: permission → recording → processing → success/no-match/error. Запись и сеть работают вне main thread, допускается одна активная операция, а микрофон освобождается при каждом исходе. Перед записью контроллер приглушает плеер, а затем восстанавливает громкость.

| Контекст | Ответственность | Не должен делать |
| --- | --- | --- |
| Compose / main | Рендеринг, клики, launcher разрешения | Блокировать сетью, записью, декодированием |
| `viewModelScope` | Загрузка и состояние каталога | Владеть ExoPlayer |
| Voice scope | Одна voice-операция | Передавать непроверенные данные в player |
| MediaSessionService | ExoPlayer и media-сессия | Владеть UI |
