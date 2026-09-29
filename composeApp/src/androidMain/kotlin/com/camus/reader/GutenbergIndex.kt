package com.camus.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream

internal sealed interface RefreshOutcome {
    /** A newer catalog was downloaded and is now the one in use. */
    data object Updated : RefreshOutcome

    /** Project Gutenberg's catalog hasn't changed since the copy on this device was built. */
    data object Unchanged : RefreshOutcome

    /** Another refresh is already running. */
    data object Busy : RefreshOutcome

    data class Failed(val message: String) : RefreshOutcome
}

/**
 * Pull the Guten's search index: Project Gutenberg's own catalog (pg_catalog.csv, about 78,000 books),
 * kept in a SQLite database on this device. Searching never touches the network; the network is used only
 * to download the catalog the first time and to check for a newer one about once a week.
 *
 * The database has a `books` table with the display fields, a full-text table over every searchable word,
 * a small vocabulary table for spelling suggestions, and facet tables for the language and topic filters.
 * A new catalog is built in a separate file and swapped in whole, so a search never sees a half-built index
 * and a failed refresh leaves the old one in place.
 */
internal class GutenbergIndex(context: Context) {
    private val dir = File(context.filesDir, "gutenberg").apply { mkdirs() }
    private val liveFile = File(dir, "catalog.db")
    private val access = Mutex()
    private val building = Mutex()
    private var db: SQLiteDatabase? = null

    // -- Reading ------------------------------------------------------------

    /** The current index, or null when none has been built yet (or the file on disk is unusable). */
    suspend fun ready(): CatalogIndexState.Ready? = withContext(Dispatchers.IO) {
        access.withLock { runCatching { readyLocked() }.getOrElse { discardLocked(); null } }
    }

    suspend fun search(query: CatalogQuery): CatalogResult = withContext(Dispatchers.IO) {
        access.withLock {
            try {
                searchLocked(query)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                discardLocked()
                throw IOException("The catalog on this device couldn't be read. Update it to download a fresh copy.", error)
            }
        }
    }

    private fun open(): SQLiteDatabase? {
        db?.takeIf { it.isOpen }?.let { return it }
        if (!liveFile.exists()) return null
        val opened = try {
            SQLiteDatabase.openDatabase(liveFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (_: Exception) {
            liveFile.delete()
            return null
        }
        val usable = try { opened.version == SCHEMA_VERSION && meta(opened, "built_at") != null } catch (_: Exception) { false }
        if (!usable) {
            opened.close()
            liveFile.delete()
            return null
        }
        db = opened
        return opened
    }

    private fun discardLocked() {
        runCatching { db?.close() }
        db = null
        liveFile.delete()
    }

    private fun meta(database: SQLiteDatabase, key: String): String? =
        database.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun readyLocked(): CatalogIndexState.Ready? {
        val database = open() ?: return null
        val builtAt = meta(database, "built_at")?.toLongOrNull() ?: return null
        val checkedAt = meta(database, "checked_at")?.toLongOrNull() ?: builtAt
        val languages = database.rawQuery("SELECT code, books FROM languages ORDER BY books DESC LIMIT 14", null).use { c ->
            buildList { while (c.moveToNext()) add(CatalogFacet(c.getString(0), catalogLanguageName(c.getString(0)), c.getInt(1))) }
        }
        val categories = database.rawQuery("SELECT id, name, books FROM categories ORDER BY books DESC LIMIT 16", null).use { c ->
            buildList { while (c.moveToNext()) add(CatalogFacet(c.getInt(0).toString(), c.getString(1), c.getInt(2))) }
        }
        val age = ((System.currentTimeMillis() - checkedAt) / DAY_MS).toInt().coerceAtLeast(0)
        return CatalogIndexState.Ready(meta(database, "book_count")?.toIntOrNull() ?: 0, age, builtAt, languages, categories)
    }

    private fun searchLocked(query: CatalogQuery): CatalogResult {
        val database = open() ?: throw IOException("The catalog isn't on this device yet.")
        val words = CatalogText.words(query.text).take(MAX_QUERY_WORDS)
        val partial = CatalogText.lastWordIsPartial(query.text, words)
        val filters = StringBuilder()
        val filterArgs = ArrayList<String>()
        if (query.language.isNotBlank()) { filters.append(" AND instr(languages, ?) > 0"); filterArgs += " ${query.language} " }
        if (query.category.isNotBlank()) { filters.append(" AND instr(cats, ?) > 0"); filterArgs += " ${query.category} " }
        val limit = query.limit.coerceIn(1, 500)

        if (words.isEmpty()) {
            val total = count(database, "1 = 1$filters", filterArgs)
            val books = load(database, "1 = 1$filters", filterArgs, "boost DESC, id", limit)
            return CatalogResult(books.map(CatalogEntry::toBook), total, query.text, browsing = true)
        }

        val where = "id IN (SELECT docid FROM books_fts WHERE books_fts MATCH ?)$filters"
        val total = count(database, where, listOf(matchExpression(words, partial)) + filterArgs)
        val candidates = load(database, where, listOf(matchExpression(words, partial)) + filterArgs, "boost DESC", MAX_CANDIDATES)
        val ranked = rankCatalog(words, partial, candidates).take(limit).map(CatalogEntry::toBook)
        val suggestion = if (total < SUGGEST_BELOW) suggest(database, words, partial, total, filters.toString(), filterArgs) else null
        return CatalogResult(ranked, total, query.text, browsing = false, suggestion = suggestion)
    }

    /** `"pride" "prej*"`: every word must appear, the last one as a prefix while it is still being typed. */
    private fun matchExpression(words: List<String>, partial: Boolean): String =
        words.mapIndexed { i, word -> if (partial && i == words.lastIndex) "\"$word*\"" else "\"$word\"" }.joinToString(" ")

    private fun count(database: SQLiteDatabase, where: String, args: List<String>): Int =
        database.rawQuery("SELECT count(*) FROM books WHERE $where", args.toTypedArray()).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun load(database: SQLiteDatabase, where: String, args: List<String>, order: String, limit: Int): List<CatalogEntry> =
        database.rawQuery(
            "SELECT id, title, subtitle, authors, languages, author_words, family_words, boost FROM books WHERE $where ORDER BY $order LIMIT $limit",
            args.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CatalogEntry(
                            id = c.getInt(0), title = c.getString(1), subtitle = c.getString(2),
                            authors = c.getString(3).split('\n').filter { it.isNotEmpty() },
                            languages = c.getString(4).trim().split(' ').filter { it.isNotEmpty() },
                            authorWords = c.getString(5), familyWords = c.getString(6), boost = c.getInt(7),
                        ),
                    )
                }
            }
        }

    /** A respelled query, offered only when it finds more books than what was typed. */
    private fun suggest(database: SQLiteDatabase, words: List<String>, partial: Boolean, total: Int, filters: String, filterArgs: List<String>): String? {
        val corrected = words.map { correction(database, it) ?: it }
        if (corrected == words) return null
        val where = "id IN (SELECT docid FROM books_fts WHERE books_fts MATCH ?)$filters"
        val found = count(database, where, listOf(matchExpression(corrected, partial)) + filterArgs)
        return if (found > total) corrected.joinToString(" ") else null
    }

    private fun correction(database: SQLiteDatabase, word: String): String? {
        val tolerance = spellingTolerance(word)
        if (tolerance == 0) return null
        val own = database.rawQuery("SELECT docs FROM vocab WHERE term = ?", arrayOf(word)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val first = word.first()
        val candidates = database.rawQuery(
            // rawQuery binds every argument as text, so the lengths go straight into the SQL.
            "SELECT term, docs FROM vocab WHERE term >= ? AND term < ? AND length(term) BETWEEN ${word.length - tolerance} AND ${word.length + tolerance}",
            arrayOf(first.toString(), (first + 1).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
        return bestCorrection(word, own, candidates)
    }

    // -- Refreshing ---------------------------------------------------------

    /**
     * Builds the index if there is none, or brings it up to date. Unless [force], the copy on this device
     * is kept when Project Gutenberg reports the catalog unchanged. [onProgress] is called from a
     * background thread. The old index stays searchable until the new one replaces it.
     */
    suspend fun refresh(force: Boolean, onProgress: (CatalogIndexState.Building) -> Unit): RefreshOutcome = withContext(Dispatchers.IO) {
        if (!building.tryLock()) return@withContext RefreshOutcome.Busy
        try {
            refreshLocked(force, onProgress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            RefreshOutcome.Failed(friendlyMessage(error))
        } finally {
            File(dir, PART_FILE).delete()
            File(dir, NEW_FILE).delete()
            File(dir, "$NEW_FILE-journal").delete()
            building.unlock()
        }
    }

    private class Known(val etag: String?, val lastModified: String?, val bookCount: Int, val popular: Map<Int, Int>, val popularAt: Long)

    private suspend fun refreshLocked(force: Boolean, onProgress: (CatalogIndexState.Building) -> Unit): RefreshOutcome {
        val known: Known? = access.withLock {
            runCatching {
                open()?.let { d ->
                    Known(
                        etag = meta(d, "etag"), lastModified = meta(d, "last_modified"),
                        bookCount = meta(d, "book_count")?.toIntOrNull() ?: 0,
                        popular = d.rawQuery("SELECT id, downloads FROM popular", null).use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getInt(1)) } },
                        popularAt = meta(d, "popular_at")?.toLongOrNull() ?: 0L,
                    )
                }
            }.getOrNull()
        }
        val firstTime = known == null
        fun building(message: String, progress: Float?) = onProgress(CatalogIndexState.Building(message, progress, firstTime))

        building("Connecting to Project Gutenberg…", null)
        val part = File(dir, PART_FILE)
        val fetched = download(
            part, if (force) null else known?.etag, if (force) null else known?.lastModified,
        ) { fraction -> building("Downloading the catalog…", fraction * 0.4f) }
        if (fetched.notModified) {
            if (known != null && (known.popular.isEmpty() || System.currentTimeMillis() - known.popularAt > POPULAR_REFRESH_MS)) refreshPopularity()
            access.withLock { open()?.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES ('checked_at', ?)", arrayOf(System.currentTimeMillis().toString())) }
            return RefreshOutcome.Unchanged
        }

        building("Checking what's popular…", 0.4f)
        val fetchedPopular = fetchPopularDownloads()
        val popular = fetchedPopular.ifEmpty { known?.popular.orEmpty() }

        val fresh = File(dir, NEW_FILE)
        buildDatabase(part, fresh, popular, fetchedPopular.isNotEmpty(), fetched, expectedBooks = known?.bookCount?.takeIf { it > 0 } ?: TYPICAL_BOOKS) { fraction ->
            building("Indexing books…", 0.4f + fraction * 0.6f)
        }

        access.withLock {
            runCatching { db?.close() }
            db = null
            if (!fresh.renameTo(liveFile)) { liveFile.delete(); if (!fresh.renameTo(liveFile)) throw IOException("The new catalog couldn't be saved.") }
        }
        return RefreshOutcome.Updated
    }

    private class Fetched(val notModified: Boolean, val etag: String?, val lastModified: String?)

    private suspend fun download(target: File, etag: String?, lastModified: String?, onFraction: (Float) -> Unit): Fetched {
        var failure: Exception? = null
        for (source in CATALOG_URLS) {
            try {
                return downloadFrom(source, target, etag, lastModified, onFraction)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                failure = error
            }
        }
        throw failure ?: IOException("Project Gutenberg didn't respond.")
    }

    private suspend fun downloadFrom(source: String, target: File, etag: String?, lastModified: String?, onFraction: (Float) -> Unit): Fetched {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            etag?.let { setRequestProperty("If-None-Match", it) }
            lastModified?.let { setRequestProperty("If-Modified-Since", it) }
        }
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> return Fetched(true, etag, lastModified)
                in 200..299 -> {}
                else -> throw IOException("Project Gutenberg answered with error $code.")
            }
            val length = connection.contentLengthLong.takeIf { it > 0 }
            var received = 0L
            connection.inputStream.use { input ->
                target.outputStream().buffered(BUFFER).use { output ->
                    val chunk = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        output.write(chunk, 0, read)
                        received += read
                        currentCoroutineContext().ensureActive()
                        length?.let { onFraction((received.toFloat() / it).coerceIn(0f, 1f)) }
                    }
                }
            }
            if (length != null && received < length) throw IOException("The catalog download was cut short.")
            return Fetched(false, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
        } finally {
            connection.disconnect()
        }
    }

    /** Best effort: without it books are simply ranked by relevance alone. */
    private fun fetchPopularDownloads(): Map<Int, Int> = runCatching {
        val connection = (URL(POPULAR_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            // The page's gzip stream is cut short in a way Android's HTTP stack rejects; plain text is fine.
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (connection.responseCode !in 200..299) emptyMap()
            else parsePopularDownloads(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.w(TAG, "Couldn't load the popularity list", it) }.getOrDefault(emptyMap())
        .also { if (it.isEmpty()) Log.w(TAG, "The popularity list was empty") }

    /**
     * Re-ranks by a fresh popularity list without rebuilding the index: only books that were or are
     * on the list change their boost.
     */
    private suspend fun refreshPopularity() {
        val fresh = fetchPopularDownloads().takeIf { it.isNotEmpty() } ?: return
        access.withLock {
            val d = open() ?: return@withLock
            d.beginTransaction()
            try {
                val previous = d.rawQuery("SELECT id FROM popular", null).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
                d.execSQL("DELETE FROM popular")
                for ((id, downloads) in fresh) d.execSQL("INSERT OR REPLACE INTO popular(id, downloads) VALUES (?, ?)", arrayOf<Any>(id, downloads))
                for (id in previous.toSet() + fresh.keys) {
                    val title = d.rawQuery("SELECT title FROM books WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null } ?: continue
                    d.execSQL("UPDATE books SET boost = ? WHERE id = ?", arrayOf<Any>(catalogBoost(title, fresh[id] ?: 0), id))
                }
                d.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES ('popular_at', ?)", arrayOf<Any>(System.currentTimeMillis().toString()))
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    private suspend fun buildDatabase(
        source: File,
        target: File,
        popular: Map<Int, Int>,
        popularIsFresh: Boolean,
        fetched: Fetched,
        expectedBooks: Int,
        onFraction: (Float) -> Unit,
    ) {
        target.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(target, null)
        try {
            database.execSQL("PRAGMA auto_vacuum = FULL")
            database.rawQuery("PRAGMA journal_mode = OFF", null).use { it.moveToFirst() }
            database.execSQL("PRAGMA synchronous = OFF")
            SCHEMA.forEach(database::execSQL)

            val languageCounts = HashMap<String, Int>()
            val categoryIds = LinkedHashMap<String, Int>()
            val categoryCounts = HashMap<Int, Int>()
            val vocabulary = HashMap<String, Int>()
            val seen = HashSet<Int>()

            database.beginTransaction()
            try {
                val insertBook = database.compileStatement(
                    "INSERT INTO books(id, title, subtitle, authors, languages, cats, author_words, family_words, boost) VALUES (?,?,?,?,?,?,?,?,?)",
                )
                val insertText = database.compileStatement("INSERT INTO books_fts(docid, terms) VALUES (?,?)")
                GZIPInputStream(source.inputStream().buffered(BUFFER)).reader(Charsets.UTF_8).buffered(BUFFER).use { reader ->
                    val csv = CsvReader { reader.read() }
                    val columns = CatalogColumns(csv.readRecord().orEmpty())
                    if (!columns.valid) throw IOException("Project Gutenberg's catalog has an unexpected format.")
                    while (true) {
                        val row = csv.readRecord() ?: break
                        val record = parseCatalogRecord(columns, row) ?: continue
                        if (!seen.add(record.id)) continue
                        val categories = record.categories.map { categoryIds.getOrPut(it) { categoryIds.size + 1 } }
                        categories.forEach { categoryCounts.merge(it, 1, Int::plus) }
                        record.languages.forEach { languageCounts.merge(it, 1, Int::plus) }
                        record.vocabulary.forEach { vocabulary.merge(it, 1, Int::plus) }

                        insertBook.clearBindings()
                        insertBook.bindLong(1, record.id.toLong())
                        insertBook.bindString(2, record.title)
                        insertBook.bindString(3, record.subtitle)
                        insertBook.bindString(4, record.authors.joinToString("\n"))
                        insertBook.bindString(5, record.languages.joinToString(" ", " ", " "))
                        insertBook.bindString(6, categories.joinToString(" ", " ", " "))
                        insertBook.bindString(7, record.authorWords)
                        insertBook.bindString(8, record.familyWords)
                        insertBook.bindLong(9, catalogBoost(record.title, popular[record.id] ?: 0).toLong())
                        insertBook.executeInsert()
                        insertText.clearBindings()
                        insertText.bindLong(1, record.id.toLong())
                        insertText.bindString(2, record.searchText)
                        insertText.executeInsert()

                        if (seen.size % 1000 == 0) {
                            currentCoroutineContext().ensureActive()
                            onFraction((seen.size.toFloat() / expectedBooks).coerceIn(0f, 0.97f))
                        }
                    }
                }
                if (seen.size < MIN_BOOKS) throw IOException("The downloaded catalog looks incomplete (${seen.size} books).")

                val insertLanguage = database.compileStatement("INSERT INTO languages(code, books) VALUES (?,?)")
                languageCounts.forEach { (code, books) -> insertLanguage.bindString(1, code); insertLanguage.bindLong(2, books.toLong()); insertLanguage.executeInsert() }
                val insertCategory = database.compileStatement("INSERT INTO categories(id, name, books) VALUES (?,?,?)")
                categoryIds.forEach { (name, id) ->
                    insertCategory.bindLong(1, id.toLong()); insertCategory.bindString(2, name)
                    insertCategory.bindLong(3, (categoryCounts[id] ?: 0).toLong()); insertCategory.executeInsert()
                }
                val insertTerm = database.compileStatement("INSERT INTO vocab(term, docs) VALUES (?,?)")
                vocabulary.forEach { (term, docs) ->
                    if (docs >= MIN_VOCAB_BOOKS) { insertTerm.bindString(1, term); insertTerm.bindLong(2, docs.toLong()); insertTerm.executeInsert() }
                }
                val insertPopular = database.compileStatement("INSERT OR REPLACE INTO popular(id, downloads) VALUES (?,?)")
                popular.forEach { (id, downloads) -> insertPopular.bindLong(1, id.toLong()); insertPopular.bindLong(2, downloads.toLong()); insertPopular.executeInsert() }

                val now = System.currentTimeMillis().toString()
                val insertMeta = database.compileStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?,?)")
                mapOf(
                    "built_at" to now, "checked_at" to now, "book_count" to seen.size.toString(),
                    "popular_at" to if (popular.isNotEmpty() && popularIsFresh) now else null,
                    "etag" to fetched.etag, "last_modified" to fetched.lastModified,
                ).forEach { (key, value) -> if (value != null) { insertMeta.bindString(1, key); insertMeta.bindString(2, value); insertMeta.executeInsert() } }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            database.execSQL("INSERT INTO books_fts(books_fts) VALUES ('optimize')")
            database.version = SCHEMA_VERSION
        } finally {
            database.close()
        }
        onFraction(1f)
    }

    private fun friendlyMessage(error: Exception): String = when (error) {
        is UnknownHostException, is SocketTimeoutException, is java.net.ConnectException ->
            "Couldn't reach Project Gutenberg. Check your internet connection and try again."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "The catalog couldn't be updated."
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val BUFFER = 64 * 1024
        const val MAX_QUERY_WORDS = 8
        const val MAX_CANDIDATES = 8000
        const val SUGGEST_BELOW = 5
        const val TYPICAL_BOOKS = 78_000
        const val MIN_BOOKS = 10_000
        const val MIN_VOCAB_BOOKS = 3
        const val TAG = "GutenbergIndex"
        const val POPULAR_REFRESH_MS = 7L * DAY_MS
        const val PART_FILE = "catalog.csv.gz.part"
        const val NEW_FILE = "catalog.db.new"
        const val USER_AGENT = "Camus Reader (Android; catalog refresh, at most weekly)"
        const val POPULAR_URL = "https://www.gutenberg.org/browse/scores/top1000.php"
        val CATALOG_URLS = listOf(
            "https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv.gz",
            "https://gutenberg.pglaf.org/cache/epub/feeds/pg_catalog.csv.gz",
            "https://aleph.pglaf.org/cache/epub/feeds/pg_catalog.csv.gz",
        )
        val SCHEMA = listOf(
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
            """CREATE TABLE books(
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, subtitle TEXT NOT NULL, authors TEXT NOT NULL,
                languages TEXT NOT NULL, cats TEXT NOT NULL, author_words TEXT NOT NULL, family_words TEXT NOT NULL,
                boost INTEGER NOT NULL)""",
            "CREATE VIRTUAL TABLE books_fts USING fts4(content=\"\", matchinfo=fts3, terms, tokenize=simple)",
            "CREATE TABLE vocab(term TEXT PRIMARY KEY, docs INTEGER NOT NULL) WITHOUT ROWID",
            "CREATE TABLE languages(code TEXT PRIMARY KEY, books INTEGER NOT NULL) WITHOUT ROWID",
            "CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL, books INTEGER NOT NULL)",
            "CREATE TABLE popular(id INTEGER PRIMARY KEY, downloads INTEGER NOT NULL)",
        )
    }
}
