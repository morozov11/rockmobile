# Воспроизведение

```text
Нажатие station / voice-result
  → PlaybackController.play(station, queue)
  → MediaController.setMediaItems + prepare + play
  → RockmobileMediaSessionService / ExoPlayer
  → notification, Bluetooth, lock screen, audio output
```

`PlaybackController` хранит очередь, поэтому Previous/Next работают и для каталога, и для voice-кандидатов. Пока асинхронный `MediaController` подключается, первый вызов `play` хранится как pending и выполняется после подключения.

## Удалённый RockCast

Выбор RockCast на экране «Устройства» или в селекторе «Играть на» направляет последующие нажатия и результат голосового поиска выбранному устройству. Выбор сохраняется при временном offline/stale состоянии; команды в этот период отклоняются с причиной и доступны после восстановления свежего состояния. Удалённый запуск подтверждается только опубликованным RockCast `runtime_state`, а не успешной отправкой команды.

Для подтверждённого `station_id`, отсутствующего в стартовом списке, `StationRepository.resolveStation` запрашивает точную запись у RockServer, затем использует проверенный extended SQLite как резерв. `LiveMiniPlayer` и экран станции показывают имя этой записи и необязательный `track_title` из состояния RockCast. При отсутствии трековых метаданных строка трека не подменяется названием станции.

## Ошибки и повтор

Ошибка Media3 отображается как `Couldn't connect to this station`. Она не влияет на `StationRepository`: недоступность одного stream URL не означает, что серверный каталог следует заменить bundled-каталогом. Новый выбор станции или **Try again** очищает ошибку и повторно подготавливает плеер.

## Voice capture

`beginVoiceCapture()` сохраняет текущую громкость и временно устанавливает локальную громкость MediaController в ноль. `endVoiceCapture()` вызывается при любом исходе voice-операции, включая отмену и исключение. Это убирает звук динамика из микрофона без остановки текущего потока.
