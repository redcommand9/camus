package com.camus.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Xml
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/**
 * A from-scratch, dependency-free EPUB reader: no WebView, no epub.js, no headless browser.
 * It unzips the book with [java.util.zip], reads the container/OPF with Android's built-in
 * [XmlPullParser], and turns each chapter's XHTML into a flat list of [EpubBlock]s that
 * [EpubPagination] paginates and Compose renders directly as text/images.
 *
 * Deliberately out of scope (documented, not silently dropped): embedded CSS/fonts, footnote
 * popovers, and EPUB CFI-addressed highlighting. Typography is Camus Reader's own consistent reading
 * style rather than each book's stylesheet - the same trade-off most native e-readers make.
 */
internal enum class EpubBlockKind { HEADING, PARAGRAPH, IMAGE }

internal data class EpubBlock(
    val kind: EpubBlockKind,
    val text: AnnotatedString? = null,
    val headingLevel: Int = 0,
    val image: Bitmap? = null,
)

/** [anchors] maps an element id inside the chapter file to the index of the block it precedes. */
internal data class EpubChapter(val title: String, val blocks: List<EpubBlock>, val anchors: Map<String, Int> = emptyMap())

/** One line of the table of contents, resolved to a position inside the parsed book. */
internal data class EpubTocEntry(val title: String, val chapterIndex: Int, val blockIndex: Int)

internal class NativeEpubBook private constructor(
    val title: String,
    val chapters: List<EpubChapter>,
    /** The book's own table of contents (nav/NCX), or one entry per chapter when it has none. */
    val toc: List<EpubTocEntry>,
) {
    companion object {
        private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024

        fun load(resolver: ContentResolver, uri: Uri): NativeEpubBook {
            val entries = readZipEntries(resolver, uri)
            val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
                ?: throw IllegalArgumentException("This EPUB is missing its container.xml file.")
            val opfPath = extractOpfPath(containerXml)
                ?: throw IllegalArgumentException("This EPUB's container.xml has no package reference.")
            val opfNormalized = normalize(opfPath)
            val opfXml = entries[opfNormalized]?.toString(Charsets.UTF_8)
                ?: throw IllegalArgumentException("This EPUB is missing its package document.")
            val opfDir = opfNormalized.substringBeforeLast('/', "")
            val opf = parseOpf(opfXml)

            val chapters = ArrayList<EpubChapter>()
            val chapterIndexByPath = HashMap<String, Int>()
            opf.spine.forEach { idref ->
                val href = opf.manifest[idref] ?: return@forEach
                val fullPath = normalize(resolvePath(opfDir, href))
                val chapterBytes = entries[fullPath] ?: return@forEach
                val baseDir = fullPath.substringBeforeLast('/', "")
                val parsed = parseChapterBlocks(chapterBytes.toString(Charsets.UTF_8), baseDir, entries)
                if (parsed.blocks.isNotEmpty()) {
                    // Headings often contain <br> ("CHAPTER I.<br/>Down the Rabbit-Hole"); collapse
                    // every kind of whitespace so the title is one readable line of text.
                    val heading = parsed.blocks.firstOrNull { it.kind == EpubBlockKind.HEADING }?.text?.text
                        ?.let(::cleanTitle)?.takeIf { it.isNotEmpty() }
                    chapterIndexByPath[fullPath] = chapters.size
                    chapters.add(EpubChapter(heading ?: "Chapter ${chapters.size + 1}", parsed.blocks, parsed.anchors))
                }
            }
            if (chapters.isEmpty()) throw IllegalArgumentException("No readable chapters were found in this EPUB.")
            val toc = buildToc(entries, opf, opfDir, chapters, chapterIndexByPath)
                .ifEmpty { chapters.mapIndexed { index, chapter -> EpubTocEntry(chapter.title, index, 0) } }
            return NativeEpubBook(opf.title.ifBlank { "Untitled" }, chapters, toc)
        }

        private fun readZipEntries(resolver: ContentResolver, uri: Uri): Map<String, ByteArray> {
            val keep = setOf("xhtml", "html", "htm", "opf", "ncx", "xml", "jpg", "jpeg", "png", "gif", "webp", "bmp")
            val result = HashMap<String, ByteArray>()
            val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("This EPUB could not be opened.")
            input.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.trim('/')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (!entry.isDirectory && (ext in keep || name.equals("META-INF/container.xml", true))) {
                            result[normalize(name)] = readEntry(zip)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            return result
        }

        private fun readEntry(zip: ZipInputStream): ByteArray {
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = zip.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_ENTRY_BYTES) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

private data class OpfData(
    val manifest: Map<String, String>,
    val spine: List<String>,
    val title: String,
    val navHref: String? = null,
    val ncxHref: String? = null,
)

private class RawTocEntry(val title: String, val href: String)

private fun cleanTitle(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(160)

private fun normalize(path: String): String = path.trim('/')

/** Resolves an href relative to a base directory, collapsing "." and ".." segments. */
private fun resolvePath(baseDir: String, href: String): String {
    val clean = href.substringBefore('#')
    if (clean.startsWith("/")) return clean.trimStart('/')
    val stack = ArrayDeque<String>()
    if (baseDir.isNotEmpty()) baseDir.split('/').forEach { if (it.isNotEmpty()) stack.addLast(it) }
    clean.split('/').forEach { part ->
        when (part) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(part)
        }
    }
    return stack.joinToString("/")
}

private fun extractOpfPath(containerXml: String): String? =
    Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)

/** Common named HTML entities EPUB XHTML sometimes relies on without declaring them. */
private val NAMED_ENTITIES = mapOf(
    "nbsp" to "\u00A0", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
    "ldquo" to "\u201C", "rdquo" to "\u201D", "lsquo" to "\u2018", "rsquo" to "\u2019",
    "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122", "deg" to "\u00B0",
    "times" to "\u00D7", "divide" to "\u00F7", "eacute" to "\u00E9", "egrave" to "\u00E8",
    "agrave" to "\u00E0", "ccedil" to "\u00E7", "uuml" to "\u00FC", "ouml" to "\u00F6",
    "auml" to "\u00E4", "szlig" to "\u00DF", "shy" to "", "middot" to "\u00B7",
)

/** Strips DOCTYPE/xml-declaration and swaps risky named entities for literal characters so the
 *  non-validating [XmlPullParser] doesn't choke on real-world, slightly-sloppy EPUB markup. */
private fun sanitizeXml(raw: String): String {
    val withoutDecls = raw
        .replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<\\?xml[^>]*\\?>"), "")
    return Regex("&(\\w+);").replace(withoutDecls) { match ->
        when (val name = match.groupValues[1]) {
            "amp", "lt", "gt", "quot", "apos" -> match.value
            else -> NAMED_ENTITIES[name] ?: " "
        }
    }
}

private fun newParser(xml: String): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    setInput(StringReader(sanitizeXml(xml)))
}

private fun parseOpf(xml: String): OpfData {
    val parser = newParser(xml)
    val manifest = HashMap<String, String>()
    val spine = ArrayList<String>()
    var title = ""
    var titleCaptured = false
    var capturingTitle = false
    var navHref: String? = null
    var ncxHref: String? = null
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                "item" -> {
                    val id = parser.getAttributeValue(null, "id")
                    val href = parser.getAttributeValue(null, "href")
                    if (id != null && href != null) {
                        manifest[id] = href
                        val properties = parser.getAttributeValue(null, "properties") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        if (properties.split(' ').contains("nav")) navHref = href
                        if (mediaType == "application/x-dtbncx+xml") ncxHref = href
                    }
                }
                "itemref" -> {
                    val idref = parser.getAttributeValue(null, "idref")
                    val linear = parser.getAttributeValue(null, "linear")
                    if (idref != null && linear != "no") spine.add(idref)
                }
                "title" -> if (!titleCaptured) capturingTitle = true
            }
            XmlPullParser.TEXT -> if (capturingTitle && !titleCaptured && parser.text.isNotBlank()) {
                title = parser.text.trim()
                titleCaptured = true
            }
            XmlPullParser.END_TAG -> if (parser.name.substringAfter(':').lowercase() == "title") capturingTitle = false
        }
        event = try { parser.next() } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
    }
    return OpfData(manifest, spine, title, navHref, ncxHref)
}

/**
 * Reads the book's real table of contents (EPUB3 nav document, else the EPUB2 NCX) and
 * resolves each entry - including "file.xhtml#section" targets in the middle of a file -
 * to a chapter and block. Entries pointing at files that produced no readable content are
 * dropped. Returns an empty list when the book has no usable TOC.
 */
private fun buildToc(
    entries: Map<String, ByteArray>,
    opf: OpfData,
    opfDir: String,
    chapters: List<EpubChapter>,
    chapterIndexByPath: Map<String, Int>,
): List<EpubTocEntry> {
    fun resolve(tocHref: String?, parse: (String) -> List<RawTocEntry>): List<EpubTocEntry> {
        if (tocHref == null) return emptyList()
        val tocPath = normalize(resolvePath(opfDir, tocHref))
        val xml = entries[tocPath]?.toString(Charsets.UTF_8) ?: return emptyList()
        val tocDir = tocPath.substringBeforeLast('/', "")
        return parse(xml).mapNotNull { raw ->
            val chapterIndex = chapterIndexByPath[normalize(resolvePath(tocDir, raw.href))] ?: return@mapNotNull null
            val fragment = raw.href.substringAfter('#', "")
            val blockIndex = chapters[chapterIndex].anchors[fragment] ?: 0
            EpubTocEntry(raw.title, chapterIndex, blockIndex)
        }
    }
    return resolve(opf.navHref, ::parseNavToc).ifEmpty { resolve(opf.ncxHref, ::parseNcxToc) }
}

/** EPUB3: the <nav epub:type="toc"> list. Nested lists are flattened in document order. */
private fun parseNavToc(xml: String): List<RawTocEntry> = try {
    val parser = newParser(xml)
    val out = ArrayList<RawTocEntry>()
    var inToc = false
    var done = false
    var href: String? = null
    val label = StringBuilder()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT && !done) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val name = parser.name.substringAfter(':').lowercase()
                if (name == "nav" && !inToc) {
                    val type = parser.getAttributeValue(null, "epub:type") ?: parser.getAttributeValue(null, "type") ?: ""
                    if (type.split(' ').contains("toc")) inToc = true
                } else if (inToc && name == "a") {
                    href = parser.getAttributeValue(null, "href")
                    label.setLength(0)
                }
            }
            XmlPullParser.TEXT -> if (inToc && href != null) label.append(parser.text)
            XmlPullParser.END_TAG -> {
                val name = parser.name.substringAfter(':').lowercase()
                if (inToc && name == "a") {
                    val target = href
                    val title = cleanTitle(label.toString())
                    if (target != null && title.isNotEmpty()) out.add(RawTocEntry(title, target))
                    href = null
                } else if (inToc && name == "nav") {
                    done = true
                }
            }
        }
        event = try { parser.next() } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
    }
    out
} catch (_: Exception) {
    emptyList()
}

/** EPUB2: the NCX navMap. Each navPoint's label precedes its content element. */
private fun parseNcxToc(xml: String): List<RawTocEntry> = try {
    val parser = newParser(xml)
    val out = ArrayList<RawTocEntry>()
    var inNavMap = false
    var inLabel = false
    val label = StringBuilder()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                "navmap" -> inNavMap = true
                "navlabel" -> if (inNavMap) { inLabel = true; label.setLength(0) }
                "content" -> if (inNavMap) {
                    val src = parser.getAttributeValue(null, "src")
                    val title = cleanTitle(label.toString())
                    if (src != null && title.isNotEmpty()) out.add(RawTocEntry(title, src))
                }
            }
            XmlPullParser.TEXT -> if (inLabel) label.append(parser.text)
            XmlPullParser.END_TAG -> when (parser.name.substringAfter(':').lowercase()) {
                "navlabel" -> inLabel = false
                "navmap" -> inNavMap = false
            }
        }
        event = try { parser.next() } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
    }
    out
} catch (_: Exception) {
    emptyList()
}

private val BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "div", "tr")
private val BOLD_TAGS = setOf("b", "strong")
private val ITALIC_TAGS = setOf("i", "em", "cite")
// Note: "svg" is deliberately NOT skipped - EPUB cover pages commonly wrap the cover <image> in
// an <svg> wrapper (<svg><image xlink:href="cover.jpg"/></svg>), and skipping its subtree would
// silently drop the cover.
private val SKIP_TAGS = setOf("head", "style", "script", "nav")

private class ParsedChapter(val blocks: List<EpubBlock>, val anchors: Map<String, Int>)

private fun AnnotatedString.trimTrailingWhitespace(): AnnotatedString {
    var end = text.length
    while (end > 0 && text[end - 1].isWhitespace()) end--
    return if (end == text.length) this else subSequence(0, end)
}

private fun parseChapterBlocks(html: String, baseDir: String, entries: Map<String, ByteArray>): ParsedChapter = try {
    val parser = newParser(html)
    val blocks = ArrayList<EpubBlock>()
    val anchors = HashMap<String, Int>()
    var builder: AnnotatedString.Builder? = null
    var headingLevel = 0
    var skipDepth = 0
    val pushedStyle = ArrayDeque<Boolean>()

    fun flush() {
        val text = builder?.toAnnotatedString()?.trimTrailingWhitespace()
        if (text != null && text.text.isNotBlank()) {
            blocks.add(
                if (headingLevel > 0) EpubBlock(EpubBlockKind.HEADING, text, headingLevel)
                else EpubBlock(EpubBlockKind.PARAGRAPH, text),
            )
        }
        builder = null
        headingLevel = 0
    }

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                val name = parser.name.substringAfter(':').lowercase()
                var didPushStyle = false
                when {
                    name in SKIP_TAGS -> skipDepth++
                    skipDepth > 0 -> {}
                    name == "br" -> { if (builder == null) builder = AnnotatedString.Builder(); builder!!.append("\n") }
                    name == "img" || name == "image" -> {
                        flush()
                        // Namespace processing is off, so a namespaced attribute like svg's
                        // xlink:href arrives under its literal prefixed name, not a URI lookup.
                        val src = parser.getAttributeValue(null, "src")
                            ?: parser.getAttributeValue(null, "xlink:href")
                            ?: parser.getAttributeValue(null, "href")
                        val bitmap = src?.let { loadImage(resolvePath(baseDir, it), entries) }
                        if (bitmap != null) blocks.add(EpubBlock(EpubBlockKind.IMAGE, image = bitmap))
                    }
                    name in BLOCK_TAGS -> {
                        flush()
                        if (name.length == 2 && name[0] == 'h' && name[1].isDigit()) headingLevel = name[1] - '0'
                    }
                    name in BOLD_TAGS -> {
                        if (builder == null) builder = AnnotatedString.Builder()
                        builder!!.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        didPushStyle = true
                    }
                    name in ITALIC_TAGS -> {
                        if (builder == null) builder = AnnotatedString.Builder()
                        builder!!.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        didPushStyle = true
                    }
                }
                // Remember where "#fragment" links (table-of-contents targets) point. By now any
                // block tag has flushed, so blocks.size is the index of the block this element
                // begins or precedes.
                parser.getAttributeValue(null, "id")?.let { id ->
                    if (id.isNotEmpty() && id !in anchors) anchors[id] = blocks.size
                }
                pushedStyle.addLast(didPushStyle)
            }
            XmlPullParser.TEXT -> if (skipDepth == 0) {
                val collapsedRaw = parser.text.replace(Regex("\\s+"), " ")
                // Source markup indents its lines; without this every paragraph began with a space.
                val atBlockStart = builder == null || builder!!.length == 0
                val collapsed = if (atBlockStart) collapsedRaw.trimStart() else collapsedRaw
                if (collapsed.isNotEmpty() && (collapsed.isNotBlank() || builder != null)) {
                    if (builder == null) builder = AnnotatedString.Builder()
                    builder!!.append(collapsed)
                }
            }
            XmlPullParser.END_TAG -> {
                val name = parser.name.substringAfter(':').lowercase()
                val didPushStyle = pushedStyle.removeLastOrNull() ?: false
                if (didPushStyle) builder?.pop()
                when {
                    name in SKIP_TAGS -> skipDepth = (skipDepth - 1).coerceAtLeast(0)
                    name in BLOCK_TAGS -> flush()
                }
            }
        }
        event = try { parser.next() } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
    }
    flush()
    ParsedChapter(blocks, anchors)
} catch (_: Exception) {
    val plain = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    ParsedChapter(
        if (plain.isBlank()) emptyList() else listOf(EpubBlock(EpubBlockKind.PARAGRAPH, AnnotatedString(plain))),
        emptyMap(),
    )
}

private fun loadImage(path: String, entries: Map<String, ByteArray>): Bitmap? {
    val bytes = entries[normalize(path)] ?: return null
    return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) { null }
}
