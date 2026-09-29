# Camus Reader Linux portable archive plan

The `.tar.gz` deliverable is a **runnable Linux application image**, not a compressed copy of the website or of the source tree. The existing `desktopMain` target is only a shell today. A source archive can be shared now; the runnable archive follows the reading work below.

## 1. Establish the build baseline

- Reproduce the user's Android `assembleDebug` result and fix any Gradle or Kotlin errors before adding another platform-specific renderer.
- Run `./gradlew :composeApp:desktopRun` on Linux and check the window at laptop and large-monitor sizes, in light and dark modes.
- Keep shared page order and tear-sheet navigation in `commonMain`; make the document engines platform-specific.

**Pass:** Both targets compile, the desktop shell opens, and the existing page-sequence tests pass.

## 2. Make the desktop reader real

- Add a Linux file picker for PDF and EPUB. Open files directly from disk; do not route to the hosted site.
- Render real PDF pages with text and document coordinates available to the annotation layer. Preserve page aspect ratio and allow page, continuous scroll, and free-form zoom.
- Embed an EPUB layout engine for local books with reflow at the actual viewport width. Use a stable EPUB location identifier, such as a CFI, for position and annotation anchors.
- Route both engines through the existing `ReaderController` sequence. A tear sheet remains one distinct leaf between two original pages; its front and back have independent text.

**Pass:** Open a sample PDF and EPUB, go to page 5, add a sheet, turn to sheet 6 and original page 7, return to sheet 6 and then page 5. Repeat after restarting the app.

## 3. Complete reading controls and persistence

- Save book paths, reading position, sheet faces, page color, bookmarks, and zoom in a versioned local store. Show a clear recovery state when a moved book cannot be found.
- Implement PDF and EPUB text selection, highlight, eraser, strikethrough, and click-to-open annotation notes. Avoid stacking highlight opacity on repeated selection.
- Add Zen mode and accessible keyboard and mouse controls. Keep the document viewport sized to the available monitor without covering content with toolbars or navigation arrows.

**Pass:** The same annotations and reading state survive restart, selection remains stable while a mouse button is held, and no page flashes when annotations or sidebar panels change.

## 4. Package the Linux application image

- Build on the target Linux architecture with JDK 17 or newer. Run `./gradlew :composeApp:createDistributable` to create the self-contained app image, then `./gradlew :composeApp:runDistributable` as a packaging smoke test.
- Inspect `composeApp/build/compose/binaries/main/app/` to identify the generated app directory. Archive that **entire directory**, preserving executables and the bundled runtime, as `camus-reader-linux-<arch>.tar.gz`. Include a short `RUNNING.md` with the launcher path and supported architecture.
- Unpack the tarball into a fresh temporary directory and launch it without Gradle or a separately installed JDK. Open both formats and verify assets and saved data location.
- After the portable archive works, run `./gradlew :composeApp:packageRpm` on Linux and test the installed RPM separately.

**Pass:** A fresh Linux user can extract and run the archive, open an EPUB and PDF, close and reopen them, and keep notes. RPM is a separate output of the same reader code.

## Current boundary

`composeApp/src/desktopMain/kotlin/com/camus/reader/DesktopMain.kt` currently opens the shared reader shell with a document placeholder. Packaging it now would produce an app image that launches but cannot read a book. Do not label that archive as a finished Linux reader.
