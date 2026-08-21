# Архитектура

```text
StationsScreen ── StationsViewModel ── StationRepository
                                           ├── RockserverStationSource
                                           └── RockcastAssetStationSource (pinned schema-v1 JSON)

StationsScreen ── PlaybackController ── MediaController ── MediaSessionService ── ExoPlayer

StationsScreen ── VoiceCommandController ── VoiceRecorder / RockserverVoiceClient
                                              └── VoicePlaybackActions ── PlaybackController
```

## Каталог

`StationRepository` реализует remote-first policy. Network, timeout, HTTP, malformed/empty response приводят к чтению проверенного bundled `stations.v1.json`. Loader сверяет SHA-256, catalogVersion и schemaVersion, проверяет canonical ID и правила primary stream. Отмена coroutine пробрасывается и не считается fallback. Если оба источника недоступны, ViewModel показывает фатальную ошибку. Ошибки Media3 не поступают в repository и не могут переключить каталог.

Фильтрация существует только в `StationsViewModel` и работает с общей моделью `Station`. Voice-result заменяет видимый список ранжированными кандидатами Rockserver и сразу запускает выбранную станцию.

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
