<p align="center">
  <img src="branding/camus-reader-icon.svg" alt="Camus Reader icon" width="128">
</p>

# Camus Reader Android and Linux source

The Android app is a fully native Jetpack Compose reader: a document picker, optional
password-based encryption for local reading data, real PDF rendering via Android's own
`PdfRenderer`, and a from-scratch EPUB parser/paginator that renders chapters with plain Compose
text and images. Library, Settings, Gutenberg catalog search, bookmarks, and two-sided inserted "tear sheets" are
all native Compose screens shared with the Linux desktop target (`composeApp/src/commonMain`).

Encryption is off by default. In Settings → Profile & vault you can see whether device encryption
is on; turning it on or off currently requires creating/removing the local profile from the first
unlock screen. When it's on, the library, reading positions, bookmarks, and inserted-sheet contents 
are encrypted with AES-GCM, with the key derived via PBKDF2-HMAC-SHA256. The original PDF and EPUB 
files themselves are never moved, copied, or modified - Camus Reader only keeps a persistable read
permission to the file the system document picker returned. App backup is disabled, and an encrypted 
profile locks when the app moves to the background (except while the document picker is open). Saves 
are debounced and flushed in the background, with pending writes forced through when the app backgrounds or 
locks.

Add book uses Android's document picker (PDF or EPUB) and adds the file to the library; tapping
its cover opens it.

## Pull the Guten (PTG) catalog

PTG searches a copy of Project Gutenberg's own catalog (`pg_catalog.csv.gz`) that
Camus Reader keeps in a SQLite database on the device (`files/gutenberg/catalog.db`). Searching
never touches the network; the network is used only to download the catalog the first time PTG is
opened and to check for a newer one when the copy is over a week old (a conditional request, so an
unchanged catalog costs nothing). PTG stays behind the Settings switch, so with it off Camus Reader never
connects.

- **Index**: `GutenbergIndex.kt` builds the database (books table, an FTS4 full-text table over
  title, credited people, subjects and bookshelves, a word list for spelling suggestions, language and
  topic facets). A new catalog is built beside the old one and swapped in whole, so a failed or
  interrupted refresh never damages the working copy.
- **Ranking and text rules** live in `CatalogIndex.kt` (plain Kotlin, unit-tested in
  `CatalogIndexTest`): accents are folded, the last word matches as a prefix while typing, an exact
  title beats a title that merely contains the words, an author search lists that author's books
  first, and gutenberg.org's 30-day download list nudges popular editions to the top.
- **Search box**: title, author or subject; language and topic filters; "did you mean" for
  misspelled names; and an explanation when nothing matches (Project Gutenberg only lists US
  public-domain books, so some well-known authors are simply not in it).
- **Adding books**: with Settings -> Gutenberg network -> "Add downloaded books to my library" on (the
  default), "Add to library" downloads that one book's EPUB from gutenberg.org into Camus Reader's own storage
  (`files/ptg-books/pg<id>.epub`, `PtgFiles.kt`) and puts it on the shelf with its real title and cover,
  tagged as a PTG book. Removing it from the library deletes that copy; you can add it again. With the
  option off, "Download options" opens the book's page in the external browser and you import the file
  yourself via "Add book". Camus Reader only downloads a book when you tap it, one at a time.
- **Choice**: App gives you choice to block PTG completely so your app can not send any network request at all.
  To do this you must turn off PTG from settings. 

## Known gaps versus the website

The native EPUB renderer intentionally does not attempt pixel-parity with the website's
epub.js/pdf.js-based reader. In particular, it does not (yet) have:
- Per-book CSS/font fidelity (native reader uses one consistent serif typography, similar to
  most native e-readers)
- Text-selection highlighting/annotations addressed by EPUB CFI
- Continuous (non-chapter-boundary) page reflow - each chapter currently starts on a fresh page
- A Settings toggle to turn device encryption on/off mid-session without recreating the profile
- Zen mode's animated progress glow and double-page "spread" view

PDF rendering, page colors/night mode, zoom, bookmarks, inserted sheets, and the EPUB "Chapters"
jump list are implemented and wired end-to-end.

## Build the Android app

Install JDK 17 and Android SDK Platform 36 with Build Tools 36.0.0. Point `ANDROID_HOME` to that
SDK. Android Studio is not needed.

```bash
./gradlew :composeApp:assembleDebug
```

The APK will be at `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

## Linux

I am working on the rpm and tar.gz.
