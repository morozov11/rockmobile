# RockMobile agent instructions

## Android / Gradle checks

- Run Gradle from `C:\repos\rockmobile`.
- In this agent environment, Java may resolve `user.home` as `C:\`, which makes
  Gradle try to use inaccessible `C:\.gradle`. For every Gradle invocation,
  set a process-local value only; do not change global environment settings:

  ```powershell
  $env:JAVA_TOOL_OPTIONS = '-Xmx8G -Xms512m -Duser.home=C:\Users\alex'
  .\gradlew.bat <task> --console=plain
  ```

- Never run Gradle compilation, test, lint, assemble, or install commands in
  parallel. Start the next Gradle command only after the preceding one has
  completed and its result has been checked.
- Do not create project-local replacement Gradle or Android home directories
  (for example, `.gradle-gate001` or `.android-gate001`) merely to work around
  the agent environment.

## Graphify

Use Graphify only when the user explicitly invokes `/graphify` or when a genuinely large,
cross-repository architecture investigation needs relationship traversal that `rg` and targeted file
reading cannot provide. For routine code search, roadmap/status questions, concrete bugs, and
single-repository work, use `rg` and read only the relevant files.

Do not run `graphify update .` after ordinary changes. Never use Graphify merely because
`graphify-out/` exists or is dirty.

After every Graphify invocation, including failure, timeout, or interruption, inspect Python
processes created by that invocation. Gracefully stop only processes positively identified as
Graphify-owned and verify they exited. Never terminate unrelated Python processes.
