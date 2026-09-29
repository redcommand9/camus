package com.camus.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Books brought in through Pull the Guten when "Add downloaded books to my library" is on. Each one is
 * downloaded once, as an EPUB, into Camus Reader's own storage (`files/ptg-books/pg<id>.epub`) and opened from
 * there like any other book. Removing the book from the library deletes the copy; it can be added again.
 */
internal class PtgFiles(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "ptg-books").apply { mkdirs() }

    private fun fileFor(id: Int) = File(dir, "pg$id.epub")

    /** The Gutenberg id of a library location that points at one of these downloads, otherwise null. */
    fun idOf(location: String): Int? = NAME.find(location)?.groupValues?.get(1)?.toIntOrNull()

    /** Deletes the downloaded copy behind [location], if it is one of ours. Other files are never touched. */
    fun delete(location: String) {
        idOf(location)?.let { fileFor(it).delete() }
    }

    /**
     * Downloads book [id] (or reuses a copy already on the device) and returns it ready to add to the
     * library. [onProgress] gets the fraction done, or null while the size is unknown.
     */
    suspend fun fetch(id: Int, title: String, onProgress: (Float?) -> Unit): BookSelection = withContext(Dispatchers.IO) {
        val target = fileFor(id)
        if (!(target.isFile && looksLikeEpub(target))) {
            val part = File(dir, "pg$id.epub.part")
            try {
                var lastError: NotAvailable? = null
                for (variant in VARIANTS) {
                    try {
                        download("https://www.gutenberg.org/ebooks/$id.$variant", part, onProgress)
                        lastError = null
                        break
                    } catch (error: NotAvailable) {
                        lastError = error
                    }
                }
                lastError?.let { throw IOException("Project Gutenberg has no EPUB of this book. Open its page there to see other formats.") }
                if (!looksLikeEpub(part)) throw IOException("What came back wasn't a readable EPUB. Try again in a moment.")
                target.delete()
                if (!part.renameTo(target)) throw IOException("The book couldn't be saved on this device.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw IOException(friendly(error), error)
            } finally {
                part.delete()
            }
        }
        BookSelection(displayName(title), Uri.fromFile(target).toString(), BookKind.EPUB)
    }

    private class NotAvailable : IOException()

    private suspend fun download(source: String, target: File, onProgress: (Float?) -> Unit) {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Camus Reader (Android; one book at a time, on request)")
        }
        try {
            when (val code = connection.responseCode) {
                in 200..299 -> {}
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> throw NotAvailable()
                else -> throw IOException("Project Gutenberg answered with error $code.")
            }
            val length = connection.contentLengthLong.takeIf { it > 0 }
            var received = 0L
            var reported = -1
            onProgress(if (length != null) 0f else null)
            connection.inputStream.use { input ->
                target.outputStream().buffered(BUFFER).use { output ->
                    val chunk = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        output.write(chunk, 0, read)
                        received += read
                        currentCoroutineContext().ensureActive()
                        if (length != null) {
                            val percent = (received * 100 / length).toInt()
                            if (percent >= reported + 3) { reported = percent; onProgress(percent / 100f) }
                        }
                    }
                }
            }
            if (length != null && received < length) throw IOException("The download was cut short.")
        } finally {
            connection.disconnect()
        }
    }

    /** An EPUB is a zip file: it starts with "PK". Catches error pages saved in place of a book. */
    private fun looksLikeEpub(file: File): Boolean = file.length() > 1024 && runCatching {
        file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
    }.getOrDefault(false)

    private fun displayName(title: String): String =
        title.replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "Book" } + ".epub"

    private fun friendly(error: IOException): String = when (error) {
        is UnknownHostException, is SocketTimeoutException, is java.net.ConnectException ->
            "Couldn't reach Project Gutenberg. Check your internet connection and try again."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "The download failed."
    }

    private companion object {
        const val BUFFER = 64 * 1024
        val NAME = Regex("/ptg-books/pg(\\d+)\\.epub$")
        // The classic EPUB first (the most widely readable), then the newer one, then the text-only edition.
        val VARIANTS = listOf("epub.images", "epub3.images", "epub.noimages")
    }
}
