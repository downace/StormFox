# AGENTS.md — StormFox

## Decisions

* **Two switches, one job each.** The native **Reformat code** in *Settings | Tools | Actions on
  Save* decides whether a file is formatted on save; the plugin's **Use Mago formatter** decides
  whether PHP files are formatted by mago or by the IDE's built-in formatter. The plugin registers
  no entry of its own in *Actions on Save* — two on-save paths would format the file twice.
  `testPluginDoesNotRegisterItsOwnActionsOnSaveEntry` guards it.
* **Keep extension points focused.** `formattingService` and `projectConfigurable` handle formatting
  and settings; `localInspection` and `externalAnnotator` handle diagnostics;
  `fileIconProvider` and `JavaScript.JsonSchema.ProviderFactory` support Mago configuration files.
  The plugin still registers no Actions on Save entry.
* **The plugin never writes to a file.** Formatting and inspections read the editor buffer
  through stdin. Formatting returns the result to the document; inspections return diagnostics.
* **Independent inspections, shared execution.** `MagoLint`, `MagoAnalyze` and `MagoGuard` keep separate
  profile and severity settings. Their project service runs the tools independently; a slow check must
  not delay lint findings. Editor annotators publish cached results, while batch inspection waits
  for complete results. Cache invalidation must account for configuration and dependency changes,
  not just the current document: analysis and architectural checks read other project files.
* **Whole files only.** The formatting service does not offer `FORMAT_FRAGMENTS`, so selections and
  "changed lines only" ranges fall back to the IDE's own formatter.
* **Success is silent.** A successful format says nothing. Only errors surface, as a balloon with an
  **Open settings** action.
* **Naming:** the plugin is StormFox, the tool is **Mago** in prose and in the UI. The binary and its
  command stay lowercase: `mago`, `mago format`, `mago.toml`.
* **One id:** `com.github.downace.stormfox`, root package the same.
* **Supported IDEs: PhpStorm 2024.3 and newer** (`since-build=243`, no `until-build`). The plugin
  uses no platform API newer than 2024.3, with one exception: `prepareForFormatting` — the hook that
  keeps the IDE from saving the file before an external formatter runs — exists only in the newest
  IDEs. On 2024.3 and the 2025.x releases the platform saves the document itself, and the plugin
  cannot prevent it. The override stays in the code; on the IDEs that lack the hook it is simply
  never called.
* **The build compiles against the newest IDE**, not against the oldest supported one: the override
  above only compiles where the hook exists. The JVM target (21) and the Kotlin API level (2.0) are
  pinned to what the oldest supported IDE needs, so the artifact still loads on 2024.3. The
  compiler cannot prove that — it never sees the 2024.3 API — so the Plugin Verifier does, in CI.
  Compiling against an older IDE is not an option: `intellij.platform.vcs.impl.jar`, where
  `InlineIconButton` lives, is not on the compile classpath of the 2025.x distributions.
* **The formatting task lives inside the service class.**
  `AsyncDocumentFormattingService.FormattingTask` was a protected nested interface before build
  261, and a top-level class in another package cannot implement it. The task is therefore a
  nested class of `MagoFormattingService`.
* **The icon is a 40×40 SVG with an embedded 80×80 raster.** The original artwork has tens of
  thousands of paths; a raster keeps the file small, and the higher resolution keeps it sharp on
  HiDPI screens.
* **The marketplace Description comes from `plugin.xml`** (`<description>`, HTML — the IDE renders it
  as HTML, and the marketplace renders it too).

## Working on it

Use JDK 25 through `JAVA_HOME`. The default compile SDK is PhpStorm 2026.3; an installed IDE can
be selected with `"-Pplatform.localPath=/path/to/PhpStorm"`.

1. `./gradlew test` — done when green.
2. `./gradlew buildPlugin` — done when the versioned ZIP in `build/distributions` is rebuilt.
3. `./gradlew verifyPlugin` — runs the JetBrains Plugin Verifier against the IDEs listed in
   `pluginVerification.ides`: the oldest one we support, one in between, and the newest PhpStorm
   release, which `latest()` picks up on its own. The tag-triggered release workflow runs tests,
   builds and verifies the plugin before creating a GitHub release with the ZIP attached.
4. The user installs the artifact and confirms the behaviour in a running IDE. Platform behaviour
   (Actions on Save, waiting for an async formatter) cannot be covered by unit tests.

What to check in the IDE:

| Scenario | Expected |
| --- | --- |
| `Ctrl+Alt+L` | file formatted |
| `Ctrl+S` with the native Reformat code on | file on disk formatted |
| mago fails | balloon with an **Open settings** action |

On Windows, use `./gradlew.bat` instead of `./gradlew`.

Diagnostics: the IDE log (*Help | Show Log in Explorer*), lines prefixed `Mago: `.

## Releases

Set the plugin version in `build.gradle.kts` and replace `<change-notes>` in `plugin.xml` with
the changes for that release only. Tag the commit `v<version>` and push the tag. GitHub release
notes are generated from repository history; the attached ZIP is uploaded to Marketplace manually.
