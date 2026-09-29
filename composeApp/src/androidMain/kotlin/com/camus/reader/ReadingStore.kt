package com.camus.reader

import org.json.JSONArray
import org.json.JSONObject

internal data class SavedNativeBook(
    val selection: BookSelection,
    val sheets: List<TearSheet>,
    /** As [ReaderController.bookmarkMarks]: leaf keys, or "at:" text positions for EPUB pages. */
    val bookmarks: List<String>,
    val cursor: String,
    val color: ReaderPageColor,
    val darkMode: Boolean,
    val zoom: Float,
    val position: Int,
    val total: Int,
    val pdfHighlights: Map<String, List<PdfHighlight>> = emptyMap(),
    /** EPUB highlights (and their notes), grouped by chapter. */
    val textHighlights: Map<String, List<TextHighlight>> = emptyMap(),
    /** EPUB: the reading place as a text position, so it survives the book being laid out differently. */
    val cursorAnchor: String? = null,
)

/** All app-specific reading records go through the password-unlocked encrypted vault. */
internal class ReadingStore(private val vault: LocalAccountVault) {
    fun themeDark(): Boolean = root().optBoolean("themeDark", false)

    fun setThemeDark(value: Boolean) {
        root().put("themeDark", value).also(::writeRoot)
    }

    /** App theme; falls back to the older light/dark flag for profiles saved before themes existed. */
    fun appTheme(): AppTheme = root().let { data ->
        AppTheme.entries.firstOrNull { it.name == data.optString("appTheme") }
            ?: if (data.optBoolean("themeDark", false)) AppTheme.BLACK else AppTheme.LIGHT
    }

    fun setAppTheme(value: AppTheme) {
        root().put("appTheme", value.name).put("themeDark", value.dark).also(::writeRoot)
    }

    fun appFont(): AppFont = AppFont.entries.firstOrNull { it.name == root().optString("appFont") } ?: AppFont.SYSTEM

    fun setAppFont(value: AppFont) {
        root().put("appFont", value.name).also(::writeRoot)
    }

    /** File name (inside the app's fonts folder) of the imported reading font, or null for the book default. */
    fun readingFont(): String? = root().optString("readingFont").ifBlank { null }

    fun setReadingFont(name: String?) {
        root().put("readingFont", name ?: "").also(::writeRoot)
    }

    /** Last filter and sort chosen on the library page (enum names). */
    fun libraryView(): Pair<String, String> = root().let { it.optString("libraryFilter", "ALL") to it.optString("libraryOrder", "RECENT") }

    fun setLibraryView(filter: String, order: String) {
        root().put("libraryFilter", filter).put("libraryOrder", order).also(::writeRoot)
    }

    /** Whether the user allows Pull the Guten (network) access. Off by default: the app is offline-first. */
    fun ptgEnabled(): Boolean = root().optBoolean("ptgEnabled", false)

    fun setPtgEnabled(value: Boolean) {
        root().put("ptgEnabled", value).also(::writeRoot)
    }

    /** Whether PTG downloads a book inside Camus Reader and adds it to the library (on by default), or just opens its Gutenberg page. */
    fun ptgAutoAdd(): Boolean = root().optBoolean("ptgAutoAdd", true)

    fun setPtgAutoAdd(value: Boolean) {
        root().put("ptgAutoAdd", value).also(::writeRoot)
    }

    /** Reader-wide EPUB paragraph preferences (line/paragraph spacing, indent, justification). */
    fun paragraphSettings(): ParagraphSettings = runCatching {
        val json = JSONObject(root().optString("paragraph", ""))
        val defaults = ParagraphSettings()
        ParagraphSettings(
            lineSpacing = json.optDouble("lineSpacing", defaults.lineSpacing.toDouble()).toFloat(),
            paragraphSpacing = json.optDouble("paragraphSpacing", defaults.paragraphSpacing.toDouble()).toFloat(),
            firstLineIndent = json.optDouble("firstLineIndent", defaults.firstLineIndent.toDouble()).toFloat(),
            justify = json.optBoolean("justify", defaults.justify),
        ).normalized()
    }.getOrDefault(ParagraphSettings())

    fun setParagraphSettings(settings: ParagraphSettings) {
        val json = JSONObject()
            .put("lineSpacing", settings.lineSpacing.toDouble())
            .put("paragraphSpacing", settings.paragraphSpacing.toDouble())
            .put("firstLineIndent", settings.firstLineIndent.toDouble())
            .put("justify", settings.justify)
        root().put("paragraph", json.toString()).also(::writeRoot)
    }

    fun lastBook(): BookSelection? = runCatching {
        val json = JSONObject(root().optString("lastBook", ""))
        BookSelection(json.getString("name"), json.getString("uri"), BookKind.valueOf(json.getString("kind")))
    }.getOrNull()

    fun setLastBook(selection: BookSelection) {
        val json = JSONObject().put("name", selection.displayName).put("uri", selection.location).put("kind", selection.kind.name)
        root().put("lastBook", json.toString()).also(::writeRoot)
    }

    fun library(): List<LibraryEntry> {
        val data = root()
        val saved = runCatching {
            val list = JSONArray(data.optString("library", "[]"))
            (0 until list.length()).map { index ->
                val entry = list.getJSONObject(index)
                val book = BookSelection(entry.getString("name"), entry.getString("uri"), BookKind.valueOf(entry.getString("kind")))
                val progress = load(book)
                LibraryEntry(book, entry.optLong("addedAt"), entry.optLong("lastOpenedAt"), progress?.position ?: 0, progress?.total ?: 0, entry.optBoolean("ptg", false))
            }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) return saved
        return lastBook()?.let { listOf(LibraryEntry(it, 0, 0, load(it)?.position ?: 0, load(it)?.total ?: 0)) } ?: emptyList()
    }

    /** Adds a book to the library without opening it. A book already in the library is left as is. */
    fun importBook(selection: BookSelection, fromPtg: Boolean = false): List<LibraryEntry> {
        val current = library()
        if (current.any { it.book.location == selection.location }) return current
        val entries = listOf(LibraryEntry(selection, System.currentTimeMillis(), 0, fromPtg = fromPtg)) + current
        writeLibrary(entries)
        return entries
    }

    fun addOrOpenBook(selection: BookSelection, fromPtg: Boolean = false): List<LibraryEntry> {
        val now = System.currentTimeMillis()
        val current = library()
        val existing = current.firstOrNull { it.book.location == selection.location }
        val entries = listOf(LibraryEntry(selection, existing?.addedAt ?: now, now, existing?.position ?: 0, existing?.total ?: 0, fromPtg || existing?.fromPtg == true)) +
            current.filterNot { it.book.location == selection.location }
        writeLibrary(entries)
        setLastBook(selection)
        return entries
    }

    fun removeBook(selection: BookSelection): List<LibraryEntry> {
        val entries = library().filterNot { it.book.location == selection.location }
        val data = root()
        data.remove("book:${selection.location}")
        if (runCatching { JSONObject(data.optString("lastBook", "")).optString("uri") == selection.location }.getOrDefault(false)) data.remove("lastBook")
        writeLibrary(entries, data)
        return entries
    }

    private fun writeLibrary(entries: List<LibraryEntry>, data: JSONObject = root()) {
        val list = JSONArray()
        entries.forEach { entry ->
            list.put(JSONObject().put("name", entry.book.displayName).put("uri", entry.book.location)
                .put("kind", entry.book.kind.name).put("addedAt", entry.addedAt).put("lastOpenedAt", entry.lastOpenedAt).put("ptg", entry.fromPtg))
        }
        data.put("library", list.toString())
        writeRoot(data)
    }

    fun load(selection: BookSelection): SavedNativeBook? = runCatching {
        val json = JSONObject(root().optString("book:${selection.location}", ""))
        val list = json.optJSONArray("sheets") ?: JSONArray()
        val sheets = (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            TearSheet(item.getString("id"), item.getInt("afterPage"), item.optString("title", "Loose leaf"), item.optString("body", ""), item.optString("backTitle", ""), item.optString("backBody", ""),
                item.optString("anchor").ifBlank { null })
        }
        val marks = json.optJSONArray("bookmarks") ?: JSONArray()
        SavedNativeBook(
            selection,
            sheets,
            (0 until marks.length()).map { marks.getString(it) },
            json.optString("cursor", "page-1"),
            ReaderPageColor.entries.firstOrNull { it.name == json.optString("color") } ?: ReaderPageColor.PAPER,
            json.optBoolean("darkMode"),
            json.optDouble("zoom", 1.0).toFloat(),
            json.optInt("position"),
            json.optInt("total"),
            readPdfHighlights(json.optJSONObject("pdfHighlights")),
            readTextHighlights(json.optJSONObject("textHighlights")),
            json.optString("cursorAnchor").ifBlank { null },
        )
    }.getOrNull()

    fun save(book: SavedNativeBook) {
        val sheets = JSONArray()
        book.sheets.forEach { sheet ->
            sheets.put(JSONObject().put("id", sheet.id).put("afterPage", sheet.afterPage)
                .put("title", sheet.title).put("body", sheet.body)
                .put("backTitle", sheet.backTitle).put("backBody", sheet.backBody)
                .apply { sheet.anchor?.let { put("anchor", it) } })
        }
        val marks = JSONArray().apply { book.bookmarks.forEach { put(it) } }
        val json = JSONObject().put("sheets", sheets).put("bookmarks", marks).put("cursor", book.cursor)
            .put("color", book.color.name).put("darkMode", book.darkMode).put("zoom", book.zoom.toDouble())
            .put("position", book.position).put("total", book.total)
            .put("pdfHighlights", writePdfHighlights(book.pdfHighlights))
            .put("textHighlights", writeTextHighlights(book.textHighlights))
            .apply { book.cursorAnchor?.let { put("cursorAnchor", it) } }
        root().put("book:${book.selection.location}", json.toString()).also(::writeRoot)
    }

    private fun writePdfHighlights(all: Map<String, List<PdfHighlight>>): JSONObject {
        val out = JSONObject()
        all.forEach { (key, list) ->
            val array = JSONArray()
            list.forEach { h ->
                array.put(JSONArray().put(h.rect.left.toDouble()).put(h.rect.top.toDouble()).put(h.rect.right.toDouble())
                    .put(h.rect.bottom.toDouble()).put(h.color.name).put(h.group).put(h.note))
            }
            out.put(key, array)
        }
        return out
    }

    private fun writeTextHighlights(all: Map<String, List<TextHighlight>>): JSONObject {
        val out = JSONObject()
        all.forEach { (group, list) ->
            val array = JSONArray()
            list.forEach { h -> array.put(JSONArray().put(h.block).put(h.start).put(h.end).put(h.color.name).put(h.note)) }
            out.put(group, array)
        }
        return out
    }

    private fun readTextHighlights(json: JSONObject?): Map<String, List<TextHighlight>> {
        if (json == null) return emptyMap()
        val out = HashMap<String, List<TextHighlight>>()
        json.keys().forEach { group ->
            val array = json.optJSONArray(group) ?: return@forEach
            val list = (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONArray(i) ?: return@mapNotNull null
                val color = HighlightColor.entries.firstOrNull { it.name == row.optString(3) } ?: return@mapNotNull null
                TextHighlight(row.optInt(0), row.optInt(1), row.optInt(2), color, row.optString(4, ""))
            }
            if (list.isNotEmpty()) out[group] = list
        }
        return out
    }

    private fun readPdfHighlights(json: JSONObject?): Map<String, List<PdfHighlight>> {
        if (json == null) return emptyMap()
        val out = HashMap<String, List<PdfHighlight>>()
        json.keys().forEach { key ->
            val array = json.optJSONArray(key) ?: return@forEach
            out[key] = (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONArray(i) ?: return@mapNotNull null
                val color = HighlightColor.entries.firstOrNull { it.name == row.optString(4) } ?: return@mapNotNull null
                PdfHighlight(PdfRect(row.optDouble(0).toFloat(), row.optDouble(1).toFloat(), row.optDouble(2).toFloat(), row.optDouble(3).toFloat()), color, row.optInt(5), row.optString(6, ""))
            }
        }
        return out
    }

    private fun root(): JSONObject = JSONObject(vault.readData())
    private fun writeRoot(data: JSONObject) = vault.writeData(data.toString())
}
