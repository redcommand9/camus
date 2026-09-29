package com.camus.reader

import kotlin.math.log10
import kotlin.math.roundToInt

// The Pull the Guten (PTG) catalog: everything here is plain Kotlin so the same rules build the
// index, run a search and are unit-tested. SQLite and networking live in GutenbergIndex.kt.

const val CATALOG_PAGE = 40

data class GutenbergBook(
    val id: Int,
    val title: String,
    val subtitle: String,
    val authors: List<String>,
    val languages: List<String>,
)

data class CatalogFacet(val key: String, val label: String, val count: Int)

data class CatalogQuery(
    val text: String = "",
    /** Language code such as "en"; empty means every language. */
    val language: String = "",
    /** Category key from [CatalogIndexState.Ready.categories]; empty means every topic. */
    val category: String = "",
    val limit: Int = CATALOG_PAGE,
)

data class CatalogResult(
    val books: List<GutenbergBook>,
    val total: Int,
    /** The search text these results answer (the box may already hold newer text). */
    val text: String,
    /** True when nothing was typed and the list is simply the catalog's most popular books. */
    val browsing: Boolean,
    /** A corrected spelling that finds more books than the query as typed. */
    val suggestion: String? = null,
)

sealed interface CatalogIndexState {
    /** The catalog on this device hasn't been looked at yet. */
    data object Unknown : CatalogIndexState

    data class Building(val message: String, val progress: Float?, val firstTime: Boolean) : CatalogIndexState

    data class Ready(
        val bookCount: Int,
        /** Whole days since the catalog was last confirmed up to date with Project Gutenberg. */
        val ageDays: Int,
        /** Identifies this build of the index, so a finished refresh triggers a fresh search. */
        val builtAt: Long,
        val languages: List<CatalogFacet>,
        val categories: List<CatalogFacet>,
        val updating: Boolean = false,
        val updateError: String? = null,
    ) : CatalogIndexState

    data class Failed(val message: String) : CatalogIndexState
}

/** A book being added to the library from the catalog. */
sealed interface CatalogDownload {
    /** [fraction] is null until the size is known. */
    data class Running(val fraction: Float?) : CatalogDownload

    data class Failed(val message: String) : CatalogDownload
}

data class CatalogUiState(
    val index: CatalogIndexState = CatalogIndexState.Unknown,
    val result: CatalogResult? = null,
    val searching: Boolean = false,
    /** Gutenberg ids of catalog books already on the shelf. */
    val libraryIds: Set<Int> = emptySet(),
    val downloads: Map<Int, CatalogDownload> = emptyMap(),
)

// ---------------------------------------------------------------------------
// Words
// ---------------------------------------------------------------------------

internal object CatalogText {
    private val accentGroups = listOf(
        "aàáâãäåāăą", "cçćĉċč", "dďđð", "eèéêëēĕėęě", "gĝğġģ", "hĥħ", "iìíîïĩīĭįı", "jĵ", "kķ", "lĺļľŀł",
        "nñńņň", "oòóôõöøōŏő", "rŕŗř", "sśŝşš", "tţťŧ", "uùúûüũūŭůűų", "wŵ", "yýÿŷ", "zźżž",
    )
    private val fold: Map<Char, String> = buildMap {
        accentGroups.forEach { group -> group.drop(1).forEach { put(it, group.take(1)) } }
        put('ß', "ss"); put('æ', "ae"); put('œ', "oe"); put('þ', "th")
    }

    fun isIdeographic(ch: Char): Boolean = ch in '぀'..'ヿ' || ch in '㐀'..'䶿' || ch in '一'..'鿿' || ch in '豈'..'﫿'

    /**
     * Lower-case words with accents folded away ("Misérables" -> "miserables"), split on anything that
     * isn't a letter or digit. Every Chinese/Japanese ideograph is its own word since there are no spaces.
     */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { out += current.toString(); current.clear() }
        }
        for (raw in text) {
            val ch = raw.lowercaseChar()
            if (ch in '̀'..'ͯ') continue // combining accent left over from decomposed text
            val folded = fold[ch]
            when {
                folded != null -> current.append(folded)
                isIdeographic(ch) -> { flush(); out += ch.toString() }
                ch.isLetterOrDigit() -> current.append(ch)
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** Words joined by single spaces with a space on each end, so " war " finds only the whole word. */
    fun padded(words: List<String>): String = if (words.isEmpty()) "" else words.joinToString(" ", prefix = " ", postfix = " ")

    /** Whether the last word is still being typed (no trailing space), so it should match as a prefix. */
    fun lastWordIsPartial(text: String, words: List<String>): Boolean =
        words.isNotEmpty() && text.isNotEmpty() && text.last().isLetterOrDigit() && words.last().length >= 2 && !isIdeographic(words.last().first())
}

// ---------------------------------------------------------------------------
// The catalog file (pg_catalog.csv)
// ---------------------------------------------------------------------------

/** Reads CSV one record at a time from a character source ([next] returns -1 at the end). Quoted fields may span lines. */
internal class CsvReader(private val next: () -> Int) {
    private var pending = NONE

    private fun take(): Int = if (pending != NONE) pending.also { pending = NONE } else next()

    /** The next record, or null at the end. A blank line comes back as a single empty field. */
    fun readRecord(): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var started = false
        while (true) {
            val c = take()
            if (c < 0) {
                if (!started) return null
                fields += field.toString()
                return fields
            }
            started = true
            if (quoted) {
                if (c == '"'.code) {
                    val after = take()
                    if (after == '"'.code) field.append('"') else { quoted = false; pending = after }
                } else field.append(c.toChar())
            } else when (c) {
                '"'.code -> if (field.isEmpty()) quoted = true else field.append('"')
                ','.code -> { fields += field.toString(); field.clear() }
                '\r'.code -> {}
                '\n'.code -> { fields += field.toString(); return fields }
                else -> field.append(c.toChar())
            }
        }
    }

    private companion object { const val NONE = -2 }
}

internal class CatalogColumns(header: List<String>) {
    private val index = header.mapIndexed { i, name -> name.removePrefix("﻿").trim() to i }.toMap()
    val valid: Boolean get() = "Text#" in index && "Title" in index && "Authors" in index
    fun get(row: List<String>, name: String): String = index[name]?.let { row.getOrNull(it) }.orEmpty()
}

internal class CatalogAuthor(val display: String, val words: List<String>, val family: String, val primary: Boolean)

private fun withoutParentheses(text: String): String {
    val out = StringBuilder()
    var depth = 0
    for (ch in text) when {
        ch == '(' -> depth++
        ch == ')' -> if (depth > 0) depth--
        depth == 0 -> out.append(ch)
    }
    return out.toString().replace(Regex("\\s+"), " ").trim()
}

/**
 * One entry of the catalog's Authors column: "Austen, Jane, 1775-1817" or "Underhill, Evelyn, 1875-1941 [Editor]".
 * Only authors without a role (or a doubtful/pseudonym one) count as the book's authors; translators,
 * illustrators and the like are still searchable but never shown as the author.
 */
internal fun parseCatalogAuthor(entry: String): CatalogAuthor? {
    var name = entry.trim()
    var role = ""
    if (name.endsWith("]")) {
        val open = name.lastIndexOf('[')
        if (open >= 0) { role = name.substring(open + 1, name.length - 1).trim(); name = name.substring(0, open).trim() }
    }
    val parts = name.split(',').map { it.trim() }.filter { part -> part.isNotEmpty() && part.none { it.isDigit() } }
    if (parts.isEmpty()) return null
    val clean = parts.map(::withoutParentheses).filter { it.isNotEmpty() }
    val display = when {
        clean.size >= 2 -> if (clean[1].endsWith(clean[0])) clean[1] else "${clean[1]} ${clean[0]}"
        clean.size == 1 -> clean[0]
        else -> parts[0]
    }
    val family = when {
        parts.size >= 2 -> clean.firstOrNull() ?: parts[0]
        CatalogText.words(parts[0]).size <= 2 && '.' !in parts[0] -> parts[0]
        else -> ""
    }
    val primary = role.isEmpty() || role.equals("Dubious author", ignoreCase = true) || role.startsWith("pseud", ignoreCase = true)
    return CatalogAuthor(display, CatalogText.words(parts.joinToString(" ")), family, primary)
}

/** A catalog row reduced to what the index stores. */
internal class CatalogRecord(
    val id: Int,
    val title: String,
    val subtitle: String,
    val authors: List<String>,
    val authorWords: String,
    val familyWords: String,
    val languages: List<String>,
    val categories: List<String>,
    /** Every searchable word of the book: title, all credited people, subjects and bookshelves. */
    val searchText: String,
    /** Words of the title and authors, used to spot mistyped words. */
    val vocabulary: Set<String>,
)

/** Turns one CSV row into a record; anything that isn't a readable text (audio, images, blank titles) is skipped. */
internal fun parseCatalogRecord(columns: CatalogColumns, row: List<String>): CatalogRecord? {
    if (columns.get(row, "Type") != "Text") return null
    val id = columns.get(row, "Text#").trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    val lines = columns.get(row, "Title").split('\n').map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val title = lines.first()
    val subtitle = lines.drop(1).joinToString(" ").take(240)
    val credited = columns.get(row, "Authors").splitList().mapNotNull(::parseCatalogAuthor)
    val primary = credited.filter { it.primary }
    val shelves = columns.get(row, "Bookshelves").splitList()
    val categories = shelves.filter { it.startsWith("Category: ") }.map { it.removePrefix("Category: ").trim() }
    val titleWords = CatalogText.words("$title $subtitle")
    val authorWords = primary.flatMap { it.words }
    val everything = titleWords + credited.flatMap { it.words } + CatalogText.words(columns.get(row, "Subjects") + " " + shelves.joinToString(" "))
    return CatalogRecord(
        id = id,
        title = title,
        subtitle = subtitle,
        authors = primary.map { it.display }.distinct(),
        authorWords = CatalogText.padded(authorWords),
        familyWords = CatalogText.padded(CatalogText.words(primary.joinToString(" ") { it.family })),
        languages = columns.get(row, "Language").splitList(),
        categories = categories,
        searchText = everything.distinct().joinToString(" "),
        vocabulary = (titleWords + authorWords).toSet(),
    )
}

private fun String.splitList(): List<String> = split(';').map { it.trim() }.filter { it.isNotEmpty() }

/** Download counts from gutenberg.org's "Top 1000 EBooks last 30 days" page, by book id. */
internal fun parsePopularDownloads(html: String): Map<Int, Int> {
    val start = html.indexOf("id=\"books-last30\"")
    if (start < 0) return emptyMap()
    val end = html.indexOf("id=\"authors-last30\"", start).let { if (it < 0) html.length else it }
    val link = Regex("""href="/ebooks/(\d+)">[^<]*?\s*\((\d+)\)</a>""")
    return link.findAll(html.substring(start, end)).mapNotNull { match ->
        val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val downloads = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        id to downloads
    }.toMap()
}

/**
 * How far a book is nudged up or down before any query is considered: popular books first, and the
 * catalog's "Index of / Complete Works of" bundle pages last, since they are rarely what someone wants.
 */
internal fun catalogBoost(title: String, downloads: Int): Int {
    var boost = if (downloads > 100) (300 * log10(downloads / 100.0)).roundToInt() else 0
    val lower = title.lowercase()
    if (lower.startsWith("index of the project gutenberg works")) boost -= 600
    else if ("project gutenberg works" in lower) boost -= 400
    return boost
}

// ---------------------------------------------------------------------------
// Ranking
// ---------------------------------------------------------------------------

/** A stored book as the ranker sees it. Word fields are already folded and padded (see [CatalogText.padded]). */
internal class CatalogEntry(
    val id: Int,
    val title: String,
    val subtitle: String,
    val authors: List<String>,
    val languages: List<String>,
    val authorWords: String,
    val familyWords: String,
    val boost: Int,
) {
    fun toBook() = GutenbergBook(id, title, subtitle, authors, languages)
}

private class Scored(val entry: CatalogEntry, val strong: Boolean, val score: Int)

/**
 * Orders books that all contain the query's words. A book is "strong" when every word is a whole word of
 * its title, subtitle or author; strong books come first. Within each group an exact title beats a title
 * that starts with the query, which beats one that merely contains it; a search that is exactly an
 * author's name puts that author's books ahead of books that only mention the name, and popularity
 * settles the rest. When [lastWordIsPartial], the last word also counts as a prefix ("pride and prej").
 */
internal fun rankCatalog(words: List<String>, lastWordIsPartial: Boolean, candidates: List<CatalogEntry>): List<CatalogEntry> {
    if (words.isEmpty()) return candidates.sortedWith(compareByDescending<CatalogEntry> { it.boost }.thenBy { it.id })
    val phrase = CatalogText.padded(words)
    val phraseStart = phrase.trimEnd()
    val scored = candidates.map { entry ->
        val main = CatalogText.padded(CatalogText.words(entry.title))
        val sub = CatalogText.padded(CatalogText.words(entry.subtitle))
        var mainHits = 0.0
        var subHits = 0.0
        var authorHits = 0.0
        var allMain = true
        var allTitle = true
        var allAuthor = true
        var strong = true
        var family = false
        words.forEachIndexed { i, word ->
            val whole = " $word "
            val partial = lastWordIsPartial && i == words.lastIndex
            val inMain = whole in main
            val inSub = whole in sub
            val inAuthor = whole in entry.authorWords
            mainHits += if (inMain) 1.0 else if (partial && " $word" in main) 0.3 else 0.0
            if (!inMain) subHits += if (inSub) 1.0 else if (partial && " $word" in sub) 0.3 else 0.0
            authorHits += if (inAuthor) 1.0 else if (partial && " $word" in entry.authorWords) 0.3 else 0.0
            allMain = allMain && inMain
            allTitle = allTitle && (inMain || inSub)
            allAuthor = allAuthor && inAuthor
            strong = strong && (inMain || inSub || inAuthor)
            family = family || whole in entry.familyWords
        }
        var relevance = 0
        if (main == phrase) relevance += 500
        relevance += if (main.startsWith(phrase)) 200 else if (lastWordIsPartial && main.startsWith(phraseStart)) 100 else 0
        relevance += when {
            phrase in main -> 100
            lastWordIsPartial && phraseStart in main -> 50
            phrase in sub -> 40
            else -> 0
        }
        relevance += (mainHits * 30 + subHits * 10 + authorHits * 40).roundToInt()
        relevance += if (allMain) 150 else if (allTitle) 60 else 0
        if (allAuthor && family) relevance += 700
        Scored(entry, strong, relevance + entry.boost)
    }
    return scored.sortedWith(compareByDescending<Scored> { it.strong }.thenByDescending { it.score }.thenBy { it.entry.id }).map { it.entry }
}

// ---------------------------------------------------------------------------
// Spelling
// ---------------------------------------------------------------------------

/** Edits (insert, delete, replace, swap two neighbours) needed to turn [a] into [b]; [limit] + 1 when more than [limit]. */
internal fun editDistance(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    var before = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val row = IntArray(b.length + 1)
        row[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var best = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, before[j - 2] + 1)
            row[j] = best
        }
        before = previous
        previous = row
    }
    return previous[b.length].coerceAtMost(limit + 1)
}

/** How many edits away a corrected word may be: none for short words, one for most, two for long names. */
internal fun spellingTolerance(word: String): Int = when {
    word.length < 4 || word.any { it.isDigit() } || CatalogText.isIdeographic(word.first()) -> 0
    word.length <= 7 -> 1
    else -> 2
}

/**
 * The most likely intended word among [candidates] (word to number of books using it): the closest one,
 * then the most common. A candidate must be clearly more common than [ownBooks], the books that already
 * use the typed word, so a real but rare word isn't "corrected" to a popular neighbour.
 */
internal fun bestCorrection(word: String, ownBooks: Int, candidates: List<Pair<String, Int>>): String? {
    val tolerance = spellingTolerance(word)
    if (tolerance == 0) return null
    var best: Triple<Int, Int, String>? = null
    for ((term, books) in candidates) {
        if (term == word || books < 3 || books < ownBooks * 10) continue
        val distance = editDistance(word, term, tolerance)
        if (distance > tolerance) continue
        val current = best
        if (current == null || distance < current.first || (distance == current.first && books > current.second)) best = Triple(distance, books, term)
    }
    return best?.third
}

// ---------------------------------------------------------------------------
// Display helpers
// ---------------------------------------------------------------------------

private val languageNames = mapOf(
    "en" to "English", "fr" to "French", "de" to "German", "fi" to "Finnish", "nl" to "Dutch", "it" to "Italian",
    "es" to "Spanish", "pt" to "Portuguese", "hu" to "Hungarian", "zh" to "Chinese", "sv" to "Swedish", "el" to "Greek",
    "eo" to "Esperanto", "la" to "Latin", "ca" to "Catalan", "da" to "Danish", "pl" to "Polish", "ru" to "Russian",
    "ja" to "Japanese", "no" to "Norwegian", "cs" to "Czech", "hi" to "Hindi", "bn" to "Bengali", "tl" to "Tagalog",
    "is" to "Icelandic", "he" to "Hebrew", "ar" to "Arabic", "ro" to "Romanian", "tr" to "Turkish", "sa" to "Sanskrit",
)

fun catalogLanguageName(code: String): String = languageNames[code] ?: code.uppercase()

/** 78223 -> "78,223" */
fun groupedNumber(value: Int): String {
    val digits = value.toString()
    val out = StringBuilder()
    digits.forEachIndexed { i, ch ->
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
        out.append(ch)
    }
    return out.toString()
}
