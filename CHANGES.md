# PTG: add downloaded books to the library automatically

New Settings option (Gutenberg network -> "Add downloaded books to my library", on by default). When on,
each catalog book offers "Add to library": Camus Reader downloads its EPUB (`epub.images`, falling back to
`epub3.images`, then `epub.noimages`) into `files/ptg-books/`, checks it is a real EPUB, and adds it to
the shelf with the catalog title and the book's own cover. The card shows progress, then "In your
library"; a failure shows the reason with "Try again" and "Open on Gutenberg". Removing a PTG book also
deletes its downloaded copy (the confirmation says so); books you picked yourself are never deleted.
Off restores the old behaviour (open the Gutenberg page in the browser). New: `PtgFiles.kt`.

---

# PTG indexing: on-device catalog search

## Why

PTG searched through a third-party server (`folio-reader.jamaica-kidyneon.chatgpt.site`) that
returned matches in book-ID order with no ranking, no spelling help, and no way to tell "no such
book" from "haven't searched yet" - searching "George Orwell" (who is not on gutenberg.org at all)
left the screen saying "Search for a title or author to browse the catalog." It also meant PTG
stopped working whenever that server did.

## What changed

- New `GutenbergIndex.kt` (Android) downloads Project Gutenberg's official catalog and stores it in
  SQLite with FTS4 full-text search; searching is local and instant, and works offline once built.
- New `CatalogIndex.kt` (shared) holds the pure logic - word folding, CSV and author parsing, ranking,
  spelling correction - covered by 16 unit tests in `CatalogIndexTest`.
- `GutenbergCatalogScreen` rewritten: live search, language and topic filters, "did you mean",
  "show more", real no-results explanation, first-time build progress, "checked N days ago" with an
  Update button, and a retry when a download fails. `GutenbergApi.kt` (the remote client) is removed.
- Verified on a device: first build ~1 minute on Wi-Fi, ~17 MB on disk, searches return in
  milliseconds with Wi-Fi off, and a failed update keeps the existing copy.

## Known limits

- Titles are searched as gutenberg.org lists them (e.g. "Dostoyevsky", not "Dostoevsky" - hence the
  spelling suggestion). Author name variants in other alphabets are not transliterated.
- Popularity ranking uses gutenberg.org's 30-day top-1000 list; it is best effort. If that page can't
  be fetched, ranking falls back to relevance alone and retries at the next check.
- The desktop build has no PTG (as before); `desktopTest` needs its Compose dependencies fetched
  once online, so the unit tests were run with `:composeApp:testDebugUnitTest`.

---

# WebView removal: what was found, what changed

## Diagnosis

Yes — confirmed, and it was more extensive than just "reader mode":

- **The entire Android app**, not just the reader, ran inside a `WebView` (`AndroidWebApp.kt`)
  pointed at a locally-bundled copy of the website (`assets/web/`, built from `web-src/`, a
  near-identical fork of the `Folio-Mobile-Website-Source.zip` you attached — diffing the two
  showed only a handful of lines changed, swapping `localStorage` calls for a small JS↔Kotlin
  bridge and adding an Android back-button handler). The project's own `README.md` said as much:
  *"a WebView that serves the reader from assets inside the APK."*
- Even the **EPUB reader specifically** loaded `epub.js` (`epub.min.js`) inside a second, smaller
  WebView (`AndroidEpubReader.kt`'s old version).
- Interestingly, **PDF rendering was already fully native** (`AndroidPdfDocument`, using Android's
  real `PdfRenderer`) — but it was never actually wired up. So was a whole parallel native shell
  (`CamusReaderApp.kt`, `LibraryScreen.kt`, `ReaderController.kt`, `SettingsAndCatalog.kt`,
  `ReadingStore.kt`, `GutenbergApi.kt`) already used by the **desktop** build. `MainActivity.kt`
  on Android just never called any of it — it only ever built `AndroidWebApp`. That native code
  was a stalled migration, not a starting point I invented.

So the actual job was to finish that stalled migration for the reading path, and write the one
piece that was still missing: a real (non-WebView) EPUB renderer.

## What changed

- **New: a from-scratch EPUB engine, no WebView/epub.js.**
  - `NativeEpubBook.kt` — unzips the EPUB with `java.util.zip`, reads `container.xml`/the OPF with
    Android's built-in `XmlPullParser`, and walks each chapter's XHTML into a flat block model
    (headings, paragraphs with bold/italic spans, images — including SVG-wrapped cover images).
  - `EpubPagination.kt` — packs those blocks into fixed-size pages using Compose's `TextMeasurer`,
    splitting paragraphs at line boundaries like a real page break. Runs off the main thread
    (`Dispatchers.Default`) so a long book doesn't jank the UI.
  - `AndroidEpubReader.kt` (rewritten) — renders the current page with plain Compose `Text`/`Image`.
- **`AndroidPdfReader.kt`** — kept the existing real `PdfRenderer` code, adapted it to the same
  `(controller, leaf, background, ink)` shape as the EPUB renderer, and added page-color tinting
  and a best-effort night-mode invert filter (approximating the website's CSS filter on PDF pages).
- **New: `AndroidBookPage.kt`** — a small dispatcher (PDF → `AndroidPdfBookPage`, EPUB →
  `AndroidEpubBookPage`), passed into the shared `CamusReaderApp` shell as its `renderBookPage` hook.
- **New: `AndroidNativeApp.kt`** — wires that shared shell up on Android: the system document
  picker, `ReadingStore` persistence (already existed, was unused), Gutenberg search, and the
  app-wide theme toggle.
- **`MainActivity.kt`** — now shows `AndroidNativeApp` instead of `AndroidWebApp`.
- **`ReaderController.kt` / `CamusReaderApp.kt`** (shared, used by Android *and* desktop) — added an
  EPUB "Chapters" jump list (`controller.chapters`), and the sidebar's bookmark list is no longer
  gated to PDF-only.
- **Removed**: `AndroidWebApp.kt`, `NativeWebStore.kt`, and the bundled website build
  (`assets/web/`, `reader.html`, `epub.min.js`, `epubjs-LICENSE`) — none of it is loaded anymore.
  `web-src/` at the project root is left alone (untouched) in case you still want it for a
  separate website deployment; it has no effect on the APK now.
- **`README.md`** — rewritten; it described the old WebView setup as current, which was no longer
  true.

## Known, deliberate limitations (not silently dropped)

- Each EPUB chapter starts on a fresh page, rather than the website's continuous reflow. This
  makes the chapter jump-list land exactly on a page boundary and made the pagination logic much
  simpler to get right; it does mean the last page of a chapter can be short.
- No embedded per-book CSS/fonts, no footnote popovers, no CFI-addressed highlighting/annotations.
  Typography is one consistent native reading style, the same trade-off most native e-readers make.
- Settings has no in-session "turn device encryption on/off" toggle yet (the website/old WebView
  build had one via the JS bridge). You can still create an encrypted profile from the first-run
  screen; switching an existing plain install to encrypted, or back, isn't wired into the native
  Settings screen yet.
- Zen mode's animated progress glow and the double-page "spread" view weren't ported.
- The PDF night-mode filter is a `ColorMatrix` approximation of the website's CSS
  `invert()/hue-rotate()/brightness()/contrast()` filter chain (there's no direct hue-rotate
  equivalent in a color matrix) — visually close, not pixel-identical.

## This has not been built or run

This sandbox has no Android SDK and no network access to fetch Gradle/AGP/Compose dependencies,
so none of this could be compiled or tested here. I reviewed every file by hand against the
Compose/Kotlin/Android APIs involved, but please treat it as a careful first draft, not
verified-working code. Before you trust it:

1. **Build it**: `./gradlew :composeApp:assembleDebug` (or open in Android Studio) and fix
   whatever the compiler flags — I'm most unsure about exact Compose Multiplatform API surface
   at your pinned versions (Compose `1.9.3`, Kotlin `2.2.20`) for `TextMeasurer`,
   `rememberTextMeasurer`, and `ColorFilter.colorMatrix`'s exact value-scale semantics.
2. **Try a plain-text EPUB and a PDF** from your library screen — confirm pages turn, zoom works,
   bookmarks/inserted sheets still work, and reading position survives closing/reopening the book.
3. **Try an EPUB with images and a cover page** — this is the newest, least-tested code path
   (zip decoding, SVG-wrapped covers, image scaling within a page).
4. **Check the night-mode PDF filter and page tints** visually — the color-matrix coefficients in
   `AndroidPdfReader.kt` are a starting point, tune them if they look off.
5. **Confirm reading state migrates**: if you'd already been reading books through the old
   WebView build, its data lived under different keys (`webLibrary`/`webState:*`, written by the
   now-removed `NativeWebStore`) than the native path uses (`library`/`book:*`, via
   `ReadingStore`). Existing native-path saves (if any, e.g. from testing the desktop build) will
   carry over; anything read only through the old WebView build will not automatically appear in
   the new library list.
