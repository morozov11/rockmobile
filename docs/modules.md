# Карта модулей

| Путь | Роль |
| --- | --- |
| `MainActivity.kt` | Composition root, playback/voice wiring и permission flow |
| `data/api/RockserverApi.kt` | HTTP boundary серверного каталога |
| `data/dto/RockserverDtos.kt` | Строгий разбор station JSON |
| `data/personal/PersonalData.kt` | Офлайн-профиль favourites/history: RM-007-A правила, миграция v1→v2 (бэкфилл history `updatedAt` с бэкапом и журналом), применение sync-записей через `update()` и restore-and-remap без карантина (как в RockCast) |
| `data/personal/PersonalSyncApply.kt` | Чистое применение входящих RM-012 записей: tombstones, strict-LWW, пропуск невалидных записей на границе |
| `personalsync/PersonalSyncDtos.kt` | Строгие kotlinx wire-DTO `POST /api/v1/sync` (snake_case, отдельные от локальной camelCase-модели) |
| `personalsync/PersonalSyncApi.kt` | Тонкий sync-клиент над `RockserverApi` и типизированные ошибки (401/429/422/503) |
| `personalsync/PersonalSyncState.kt` | Per-device курсор + acknowledged-base: diff, tombstone-штамп, чанкинг ≤300, next-state, SharedPrefs-персистентность с резетом при новой пайринге/профиле |
| `personalsync/PersonalSyncEngine.kt` | Один push+pull цикл: батчи с прошивкой курсора, один 401-renew-повтор на запрос, backoff |
| `personalsync/PersonalSyncCoordinator.kt` | Event-driven триггеры (старт, дебаунс ~10 с, pull ~5 мин, foreground), статус для аккаунт-экрана, безопасный лог фаз/счётчиков |
| `account/NativeSessionManager.kt` | Переиспользуемый refresh native device-session (access-токен ротируется, device-secret нет) |
| `data/stations/StationSources.kt` | Rockserver и bundled RockCast источники |
| `data/stations/ExtendedCatalogStationSource.kt` | Room-backed расширенный каталог + Room-free `stationsById` (имена/потоки станций вне загруженного каталога для персональных списков) |
| `data/stations/StationIconLoader.kt` | Bounded fetch/decode/cache иконок станций |
| `data/repository/StationRepository.kt` | Remote-first и fallback policy |
| `domain/model/Station.kt` | Независимая модель станции и каталога |
| `settings/SettingsRepository.kt` | Production RockServer URL и build-time HTTPS override только для debug APK; удаление legacy bearer из открытых preferences |
| `account/AccountModels.kt` | Доменные модели pairing/session и UI-состояния аккаунта |
| `account/AccountGateway.kt` | Фактический RockServer G1/G2/native account HTTP-контракт |
| `account/KeystoreCredentialStore.kt` | AES-GCM session/profile storage с ключом из Android Keystore |
| `account/AccountViewModel.kt` | Pairing lifecycle, refresh/logout/revoke и безопасный fallback |
| `account/AccountScreen.kt` | Читаемый экран подключения телефона, QR/deep link и список устройств |
| `devicecontrol/DirectoryDtos.kt` | Строгие REST/WebSocket directory DTOs и safe unknown variants |
| `devicecontrol/CommandModels.kt` | Строгие device-target command DTOs, lifecycle и ephemeral Chromecast receiver models |
| `devicecontrol/DirectoryApi.kt` | Authenticated typed directory REST boundary |
| `devicecontrol/DirectorySocket.kt` | Bounded native-session controller lifecycle, directory subscription and typed command frames |
| `devicecontrol/TargetDirectoryRepository.kt` | Owner-scoped revision/lifecycle store, explicit selection and command authorization invariant |
| `devicecontrol/TargetDirectoryViewModel.kt`, `TargetSelector.kt`, `CapabilityControls.kt` | Lifecycle bridge, explicit selector and capability-derived remote-player Compose controls |
| `settings/UnavailableVoiceStationStore.kt` | Локальная память voice-станций с недоступным потоком |
| `ui/stations/StationsViewModel.kt` | Каталог, фильтры и voice-кандидаты |
| `ui/stations/StationsScreen.kt` | Compose orchestration каталога и playback entry point |
| `ui/stations/StationComponents.kt` | Каталоговые Compose-компоненты, фильтры, таблица и artwork |
| `ui/stations/PlayerScreen.kt` | Отдельный экран текущего проигрывания |
| `playback/PlaybackController.kt` | Очередь, MediaController и ошибки потока |
| `playback/RockmobileMediaSessionService.kt` | Владелец ExoPlayer и MediaSession |
| `voice/VoiceRecorder.kt` | `AudioRecord`, PCM и детектор конца речи |
| `voice/RockserverVoiceClient.kt` | WebSocket и строгий parser событий |
| `voice/VoiceCommandController.kt` | Voice state machine и безопасный playback |

Unit-тесты используют fake recorder/client/playback и HTTP transport, поэтому не требуют настоящего микрофона, сети или Media3-сессии.
