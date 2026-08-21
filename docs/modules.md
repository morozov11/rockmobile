# Карта модулей

| Путь | Роль |
| --- | --- |
| `MainActivity.kt` | Composition root, playback/voice wiring и permission flow |
| `data/api/RockserverApi.kt` | HTTP boundary серверного каталога |
| `data/dto/RockserverDtos.kt` | Строгий разбор station JSON |
| `data/stations/StationSources.kt` | Rockserver и bundled RockCast источники |
| `data/repository/StationRepository.kt` | Remote-first и fallback policy |
| `domain/model/Station.kt` | Независимая модель станции и каталога |
| `settings/SettingsRepository.kt` | Локальные URL и bearer-токен |
| `settings/UnavailableVoiceStationStore.kt` | Локальная память voice-станций с недоступным потоком |
| `ui/stations/StationsViewModel.kt` | Каталог, фильтры и voice-кандидаты |
| `ui/stations/StationsScreen.kt` | Compose-каталог, MiniPlayer, Player Screen |
| `playback/PlaybackController.kt` | Очередь, MediaController и ошибки потока |
| `playback/RockmobileMediaSessionService.kt` | Владелец ExoPlayer и MediaSession |
| `voice/VoiceRecorder.kt` | `AudioRecord`, PCM и детектор конца речи |
| `voice/RockserverVoiceClient.kt` | WebSocket и строгий parser событий |
| `voice/VoiceCommandController.kt` | Voice state machine и безопасный playback |

Unit-тесты используют fake recorder/client/playback и HTTP transport, поэтому не требуют настоящего микрофона, сети или Media3-сессии.
