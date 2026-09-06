# Карта модулей

| Путь | Роль |
| --- | --- |
| `MainActivity.kt` | Composition root, playback/voice wiring и permission flow |
| `data/api/RockserverApi.kt` | HTTP boundary серверного каталога |
| `data/dto/RockserverDtos.kt` | Строгий разбор station JSON |
| `data/stations/StationSources.kt` | Rockserver и bundled RockCast источники |
| `data/stations/StationIconLoader.kt` | Bounded fetch/decode/cache иконок станций |
| `data/repository/StationRepository.kt` | Remote-first и fallback policy |
| `domain/model/Station.kt` | Независимая модель станции и каталога |
| `settings/SettingsRepository.kt` | Официальный RockServer URL; удаление legacy bearer из открытых preferences |
| `account/AccountModels.kt` | Доменные модели pairing/session и UI-состояния аккаунта |
| `account/AccountGateway.kt` | Фактический RockServer G1/G2/native account HTTP-контракт |
| `account/KeystoreCredentialStore.kt` | AES-GCM session/profile storage с ключом из Android Keystore |
| `account/AccountViewModel.kt` | Pairing lifecycle, refresh/logout/revoke и безопасный fallback |
| `account/AccountScreen.kt` | Читаемый экран подключения телефона, QR/deep link и список устройств |
| `devicecontrol/DirectoryDtos.kt` | Строгие REST/WebSocket directory DTOs и safe unknown variants |
| `devicecontrol/DirectoryApi.kt` | Authenticated typed directory REST boundary |
| `devicecontrol/DirectorySocket.kt` | Bounded native-session controller subscription and reconnect events |
| `devicecontrol/TargetDirectoryRepository.kt` | Owner-scoped revision store and explicit selection invariant |
| `devicecontrol/TargetDirectoryViewModel.kt`, `TargetSelector.kt` | Lifecycle bridge and Compose selector without controls |
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
