# RM-011-04 — RockMobile foundation (M1–M3)

Дата: 2026-08-29

## Реализовано

Только RM-011 M1, M2 и M3 поверх Wave 2 (A3 + A5), без изменений RockServer,
RockCast, OpenAPI или данных аккаунтов/устройств.

- **M1 identity:** основной заголовок и content description используют `RockMobile`;
  `Rock-аккаунт` сохранён как общий продукт. `versionName` обновлён до `0.1.1`,
  `versionCode` — до `2`, а внизу account dialog показан `0.1.1 (<short revision>)`.
  Package/application ID не менялись.
- **M2 raw device name:** сохранён A5 presentation helper. Default остаётся моделью
  устройства, с fallback `Android device`; label изменён на `Имя устройства`; preview
  показывает один `RockMobile — <raw label>`, в том числе для legacy-prefixed ввода.
  Централизованная byte validation не менялась; миграций нет.
- **M3 disconnected/starting:** добавлен `AccountUiState.Starting`, который появляется до
  create request и блокирует повторный tap. Disconnected показывает согласованные primary и
  secondary тексты, full-width кнопку `Подключить RockMobile`; Starting содержит только короткий
  progress. После создания request поток продолжает существующим `Pairing` (waiting) state.

## Изменённые файлы

- `app/build.gradle.kts`
- `app/src/main/java/com/rockmobile/account/AccountModels.kt`
- `app/src/main/java/com/rockmobile/account/AccountViewModel.kt`
- `app/src/main/java/com/rockmobile/account/AccountScreen.kt`
- `app/src/main/java/com/rockmobile/ui/stations/StationComponents.kt`
- `app/src/test/java/com/rockmobile/account/AccountSessionTest.kt`

## Проверки

Все команды запускались из `C:\repos\rockmobile` с process-local
`JAVA_TOOL_OPTIONS=-Xmx8G -Xms512m -Duser.home=C:\Users\alex`.

| Команда | Результат |
| --- | --- |
| `./gradlew.bat testDebugUnitTest --console=plain` | успешно, 77 unit tests |
| `./gradlew.bat lintDebug --console=plain` | успешно |
| `./gradlew.bat assembleDebug --console=plain` | успешно |
| `git diff --check` | успешно |

Unit tests закрепляют raw/fallback/legacy single-prefix behavior, RockMobile identity и visible
build format, approved M3 copy, duplicate-create blocking и переход `Disconnected → Starting → Pairing`.

## Ограничения

- M4–M8 не реализованы; `Pairing` остаётся текущим waiting UI.
- Не выполнялись staging/device/browser flows, deploy или push.
- Проект не содержит настроенного Compose instrumentation-test harness; UI copy проверяется
  через используемые Compose helpers, а lifecycle — JVM unit test.

## Implementation commit

`616f5ce32f615c3a06ae276fb530cbb1a5aeeb4e`
