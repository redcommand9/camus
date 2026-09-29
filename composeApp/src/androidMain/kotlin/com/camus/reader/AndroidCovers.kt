package com.camus.reader

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Real cover images for the library: the cover declared inside an EPUB, or the first page of a
 * PDF. Each cover is made once, scaled down, and kept on disk (so the shelf opens instantly next
 * time) and in a small memory cache. A book with no usable cover is remembered too, so it is not
 * re-scanned on every visit.
 */
internal class CoverStore(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val dir = File(context.applicationContext.filesDir, "covers").apply { mkdirs() }
    private val memory = LruCache<String, ImageBitmap>(32)

    suspend fun cover(book: BookSelection): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = hash(book.location)
        memory.get(key)?.let { return@withContext it }
        val file = File(dir, "$key.jpg")
        val none = File(dir, "$key.none")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { memory.put(key, it) }?.let { return@withContext it }
        }
        if (none.exists()) return@withContext null
        val bitmap = runCatching {
            when (book.kind) {
                BookKind.PDF -> pdfFirstPage(Uri.parse(book.location))
                BookKind.EPUB -> epubCover(Uri.parse(book.location))
            }
        }.getOrNull()?.let(::scaled)
        if (bitmap == null) {
            runCatching { none.createNewFile() }
            return@withContext null
        }
        runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) } }
        bitmap.asImageBitmap().also { memory.put(key, it) }
    }

    private fun scaled(source: Bitmap): Bitmap {
        val target = 520
        if (source.width <= target) return source
        return Bitmap.createScaledBitmap(source, target, (source.height * target.toFloat() / source.width).toInt().coerceAtLeast(1), true)
    }

    private fun pdfFirstPage(uri: Uri): Bitmap? =
        AndroidPdfDocument(resolver, uri).use { it.render(1, 520) }

    /** The EPUB's declared cover: the manifest's cover-image, the `cover` meta entry, or an image named like a cover. */
    private fun epubCover(uri: Uri): Bitmap? {
        // Pass 1: the package file, which says where the cover is.
        var opfPath: String? = null
        val small = HashMap<String, ByteArray>()
        resolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.trim('/')
                    if (!entry.isDirectory && (name == "META-INF/container.xml" || name.endsWith(".opf", true))) {
                        small[name] = zip.readBytes()
                    }
                    if (opfPath == null && name == "META-INF/container.xml") {
                        opfPath = Regex("full-path=\"([^\"]+)\"").find(small[name]!!.toString(Charsets.UTF_8))?.groupValues?.get(1)?.trim('/')
                    }
                    if (opfPath != null && small.containsKey(opfPath)) break
                }
            }
        }
        val opfName = opfPath ?: small.keys.firstOrNull { it.endsWith(".opf", true) } ?: return null
        val opfXml = small[opfName]?.toString(Charsets.UTF_8) ?: return null
        val coverHref = findCoverHref(opfXml) ?: return null
        val wanted = normalizePath(opfName.substringBeforeLast('/', ""), coverHref)

        // Pass 2: just that one image.
        resolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.trim('/').equals(wanted, ignoreCase = true)) {
                        val bytes = zip.readBytes()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= 800) sample *= 2
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
            }
        }
        return null
    }

    private fun findCoverHref(opf: String): String? {
        class Item(val id: String, val href: String, val type: String, val properties: String)
        val items = ArrayList<Item>()
        var coverId: String? = null
        runCatching {
            val parser = Xml.newPullParser().apply {
                setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(opf.reader())
            }
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "item" -> items += Item(
                            parser.getAttributeValue(null, "id").orEmpty(),
                            parser.getAttributeValue(null, "href").orEmpty(),
                            parser.getAttributeValue(null, "media-type").orEmpty(),
                            parser.getAttributeValue(null, "properties").orEmpty(),
                        )
                        "meta" -> if (parser.getAttributeValue(null, "name") == "cover") coverId = parser.getAttributeValue(null, "content")
                    }
                }
                event = parser.next()
            }
        }
        val images = items.filter { it.type.startsWith("image/") }
        return (
            images.firstOrNull { "cover-image" in it.properties.split(' ') }
                ?: images.firstOrNull { it.id == coverId }
                ?: items.firstOrNull { it.id == coverId && it.type.startsWith("image/") }
                ?: images.firstOrNull { it.id.contains("cover", true) || it.href.contains("cover", true) }
            )?.href
    }

    private fun normalizePath(baseDir: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }.getOrDefault(href)
        val parts = ArrayList<String>()
        (if (decoded.startsWith("/")) decoded else if (baseDir.isEmpty()) decoded else "$baseDir/$decoded").split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
