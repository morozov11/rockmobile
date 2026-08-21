# Голосовой поиск

```text
Mic tap → RECORD_AUDIO permission → recording (pulsing button)
        → 1.2 s silence after speech → upload/processing
        → validated station result → candidate list + automatic playback
```

Запись можно завершить кнопкой Stop или отменить. Повторные запуски игнорируются. Отмена останавливает `AudioRecord`, отменяет coroutine и возвращает UI в idle. Недоступность Rockserver не влияет на текущее радио и fallback-каталог.

## Аудио и VAD

- `AudioRecord` с `VOICE_RECOGNITION`.
- PCM S16LE, mono, 16 kHz.
- `SpeechEndDetector` измеряет RMS: он ждёт реальную речь и завершает запись только после 1,2 секунды тишины. Новая речь продлевает запись.
- Лимит записи — 30 секунд; буфер ограничен 10 MiB.

Текущая станция приглушается во время capture и восстанавливает громкость после освобождения микрофона.

## Недоступные станции

Если Media3 не может открыть поток станции, автоматически запущенной голосовым результатом, её ID сохраняется локально в `SharedPreferences`. При следующем voice-поиске эта станция не скрывается, но переносится в конец списка кандидатов. Успешный запуск такой станции снимает отметку, поэтому восстановившийся поток снова участвует в обычном ранжировании.

## Протокол Rockserver

1. WebSocket `ws(s)://<host>/api/v1/voice/stream` с `Authorization: Bearer <token>` при наличии токена.
2. JSON `start`: `locale=ru-RU`, `sample_rate_hz=16000`, `recognizer_mode=buffered_v1`, `limit=10`.
3. Бинарные PCM-фрагменты до 65 536 байт, затем JSON `commit`.
4. События: `ready`, `transcript`, `result`, `error`.

`result` обязан содержать transcript и выбранную станцию с `id`, `name`, `stream_url`. Разрешены только HTTP(S)-URL. Unknown event, malformed JSON и небезопасные данные становятся recoverable error и не управляют плеером.

## Поддерживаемое действие

Текущий согласованный результат Rockserver — подбор станции и её автоматическое воспроизведение. `play`, `pause`, `stop` добавляются только после появления явного структурированного server contract: клиент намеренно не угадывает смысл свободного текста.
