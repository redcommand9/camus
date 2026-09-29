package com.camus.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogIndexTest {
    private fun csv(text: String): CsvReader {
        var i = 0
        return CsvReader { if (i < text.length) text[i++].code else -1 }
    }

    private fun entry(id: Int, title: String, author: String, boost: Int = 0, subtitle: String = ""): CatalogEntry {
        val parsed = parseCatalogAuthor(author)
        return CatalogEntry(
            id, title, subtitle, listOfNotNull(parsed?.display), listOf("en"),
            CatalogText.padded(parsed?.words.orEmpty()), CatalogText.padded(CatalogText.words(parsed?.family.orEmpty())), boost,
        )
    }

    private fun search(text: String, all: List<CatalogEntry>): List<Int> {
        val words = CatalogText.words(text)
        val partial = CatalogText.lastWordIsPartial(text, words)
        val matching = all.filter { e ->
            val haystack = CatalogText.words("${e.title} ${e.subtitle}") + CatalogText.words(e.authorWords)
            words.withIndex().all { (i, w) -> haystack.any { if (partial && i == words.lastIndex) it.startsWith(w) else it == w } }
        }
        return rankCatalog(words, partial, matching).map { it.id }
    }

    @Test
    fun wordsFoldAccentsAndSplitOnPunctuation() {
        assertEquals(listOf("les", "miserables"), CatalogText.words("Les Misérables"))
        assertEquals(listOf("strasse", "tolstoi"), CatalogText.words("Straße, Tolstoï"))
        assertEquals(listOf("moby", "dick", "or", "the", "whale"), CatalogText.words("Moby-Dick; or, The Whale"))
        assertEquals(listOf("cafe"), CatalogText.words("cafe\u0301")) // accent typed as a separate combining mark
        assertEquals(listOf("cafe"), CatalogText.words("café"))
        assertEquals(listOf("三", "国", "志"), CatalogText.words("三国志"))
        assertEquals(emptyList(), CatalogText.words("  ...  "))
    }

    @Test
    fun theLastWordIsAPrefixOnlyWhileItIsBeingTyped() {
        assertTrue(CatalogText.lastWordIsPartial("pride and prej", CatalogText.words("pride and prej")))
        assertFalse(CatalogText.lastWordIsPartial("pride and ", CatalogText.words("pride and ")))
        assertFalse(CatalogText.lastWordIsPartial("a", CatalogText.words("a")))
    }

    @Test
    fun csvHandlesQuotesNewlinesAndEscapedQuotes() {
        val reader = csv("id,title,note\r\n1,\"Two\nlines\",\"He said \"\"hi\"\"\"\r\n2,plain,\r\n")
        assertEquals(listOf("id", "title", "note"), reader.readRecord())
        assertEquals(listOf("1", "Two\nlines", "He said \"hi\""), reader.readRecord())
        assertEquals(listOf("2", "plain", ""), reader.readRecord())
        assertNull(reader.readRecord())
    }

    @Test
    fun csvLastRecordNeedsNoTrailingNewline() {
        val reader = csv("a,b\n1,2")
        reader.readRecord()
        assertEquals(listOf("1", "2"), reader.readRecord())
        assertNull(reader.readRecord())
    }

    @Test
    fun authorsAreShownInReadingOrderWithoutDatesOrRoles() {
        assertEquals("Jane Austen", parseCatalogAuthor("Austen, Jane, 1775-1817")?.display)
        assertEquals("Leo Tolstoy", parseCatalogAuthor("Tolstoy, Leo, graf, 1828-1910")?.display)
        assertEquals("E. M. Forster", parseCatalogAuthor("Forster, E. M. (Edward Morgan), 1879-1970")?.display)
        assertEquals("Sunzi", parseCatalogAuthor("Sunzi, active 6th century B.C.")?.display)
        assertEquals("Homer", parseCatalogAuthor("Homer")?.display)
        assertTrue(parseCatalogAuthor("Austen, Jane, 1775-1817")!!.primary)
        assertFalse(parseCatalogAuthor("Underhill, Evelyn, 1875-1941 [Editor]")!!.primary)
        assertFalse(parseCatalogAuthor("Tagore, Rabindranath, 1861-1941 [Translator]")!!.primary)
        assertNull(parseCatalogAuthor("1861-1941"))
    }

    private val header = listOf("Text#", "Type", "Issued", "Title", "Language", "Authors", "Subjects", "LoCC", "Bookshelves")

    @Test
    fun aCatalogRowBecomesASearchableRecord() {
        val columns = CatalogColumns(listOf("﻿Text#") + header.drop(1))
        assertTrue(columns.valid)
        val record = parseCatalogRecord(
            columns,
            listOf(
                "84", "Text", "1993-10-01", "Frankenstein; or, the modern prometheus\nThe 1818 text", "en",
                "Shelley, Mary Wollstonecraft, 1797-1851; Hunter, J. Paul [Editor]", "Science fiction; Monsters -- Fiction", "PR",
                "Gothic Fiction; Category: Science-Fiction & Fantasy; Category: Novels",
            ),
        )
        assertNotNull(record)
        assertEquals(84, record.id)
        assertEquals("Frankenstein; or, the modern prometheus", record.title)
        assertEquals("The 1818 text", record.subtitle)
        assertEquals(listOf("Mary Wollstonecraft Shelley"), record.authors)
        assertEquals(listOf("Science-Fiction & Fantasy", "Novels"), record.categories)
        assertEquals(listOf("en"), record.languages)
        val searchable = record.searchText.split(' ')
        // The editor, the subjects and the shelf are all findable, but only the author is the author.
        assertTrue("hunter" in searchable && "monsters" in searchable && "gothic" in searchable && "shelley" in searchable)
        assertFalse("hunter" in record.authorWords.split(' '))
    }

    @Test
    fun onlyReadableTextsAreIndexed() {
        val columns = CatalogColumns(header)
        assertNull(parseCatalogRecord(columns, listOf("100", "Sound", "", "An audio book", "en", "", "", "", "")))
        assertNull(parseCatalogRecord(columns, listOf("101", "Text", "", "  ", "en", "", "", "", "")))
        assertNull(parseCatalogRecord(columns, listOf("x", "Text", "", "Bad id", "en", "", "", "", "")))
        assertFalse(CatalogColumns(listOf("a", "b")).valid)
    }

    @Test
    fun popularityIsReadFromTheThirtyDayList() {
        val html = """
            <h2 id="books-last7">Top 1000 EBooks last 7 days</h2><ol><li><a href="/ebooks/1">Wrong list (5)</a></li></ol>
            <h2 id="books-last30">Top 1000 EBooks last 30 days</h2><ol>
            <li><a href="/ebooks/2701">Moby Dick; Or, The Whale by Herman Melville (190821)</a></li>
            <li><a href="/ebooks/1342">Pride and Prejudice (1813) by Jane Austen (186807)</a></li></ol>
            <h2 id="authors-last30">Top 1000 Authors last 30 days</h2><ol><li><a href="/ebooks/author/9">Someone (9)</a></li></ol>
        """
        assertEquals(mapOf(2701 to 190821, 1342 to 186807), parsePopularDownloads(html))
        assertEquals(emptyMap(), parsePopularDownloads("<html>nothing here</html>"))
    }

    @Test
    fun boostFavoursPopularBooksAndDemotesBundlePages() {
        assertTrue(catalogBoost("Moby Dick", 190_821) > catalogBoost("Some Rarity", 12_000))
        assertEquals(0, catalogBoost("Unlisted", 0))
        assertTrue(catalogBoost("Index of the Project Gutenberg Works of Jane Austen", 0) < -500)
        assertTrue(catalogBoost("The Complete Project Gutenberg Works of Jane Austen", 0) < 0)
    }

    private val library = listOf(
        entry(158, "Emma", "Austen, Jane, 1775-1817", boost = 700),
        entry(2162, "Anarchism and Other Essays", "Goldman, Emma, 1869-1940", boost = 900),
        entry(3295, "The Poems of Emma Lazarus, Volume 1", "Lazarus, Emma, 1849-1887"),
        entry(2600, "War and Peace", "Tolstoy, Leo, graf, 1828-1910", boost = 850),
        entry(35211, "War", "Loti, Pierre, 1850-1923"),
        entry(132, "The Art of War", "Sunzi, active 6th century B.C.", boost = 600),
        entry(13549, "The Art of War", "Jomini, Antoine Henri, baron de, 1779-1869"),
        entry(44308, "The Art of War in the Middle Ages", "Oman, Charles, 1860-1946"),
        entry(1342, "Pride and Prejudice", "Austen, Jane, 1775-1817", boost = 900),
        entry(37431, "Pride and Prejudice, a play founded on Jane Austen's novel", "MacKaye, Steele, Mrs., 1845-1924"),
        entry(31100, "The Complete Project Gutenberg Works of Jane Austen", "Austen, Jane, 1775-1817", boost = -400),
        entry(70809, "Jane Austen and her works", "Tytler, Sarah, 1844-1914"),
        entry(15, "Moby-Dick; or, The Whale", "Melville, Herman, 1819-1891"),
        entry(2701, "Moby Dick; Or, The Whale", "Melville, Herman, 1819-1891", boost = 980),
    )

    @Test
    fun anExactTitleBeatsBooksThatOnlyMentionTheWord() {
        assertEquals(158, search("emma", library).first())
        assertEquals(2600, search("war", library).let { hits -> hits.first { it != 35211 } })
        assertEquals(listOf(132, 13549, 44308), search("the art of war", library))
    }

    @Test
    fun anAuthorSearchListsThatAuthorsBooksFirst() {
        val hits = search("jane austen", library)
        assertEquals(listOf(1342, 158), hits.take(2))
        // Her own books outrank the biography and the play that merely name her.
        assertTrue(hits.indexOf(1342) < hits.indexOf(70809))
        assertTrue(hits.indexOf(158) < hits.indexOf(37431))
        // The "Complete Works" bundle is demoted below her actual novels.
        assertTrue(hits.indexOf(1342) < hits.indexOf(31100))
    }

    @Test
    fun aHalfTypedTitleStillFindsTheBook() {
        assertEquals(1342, search("pride and prej", library).first())
        assertEquals(2701, search("moby di", library).first())
        assertTrue(search("moby dick ", library).isNotEmpty())
    }

    @Test
    fun exactWordsAreNotConfusedWithLongerOnesUnlessTyping() {
        assertEquals(emptyList(), search("mob ", library))
        assertTrue(search("mob", library).isNotEmpty())
    }

    @Test
    fun editDistanceCountsASwapAsOneMistake() {
        assertEquals(1, editDistance("frankenstien", "frankenstein", 2))
        assertEquals(1, editDistance("twian", "twain", 2))
        assertEquals(1, editDistance("dikens", "dickens", 2))
        assertEquals(2, editDistance("orwell", "ordeal", 2))
        assertEquals(3, editDistance("orwell", "shakespeare", 2))
        assertEquals(0, editDistance("same", "same", 2))
    }

    @Test
    fun misspelledNamesAreCorrectedButRealWordsAreNot() {
        val vocabulary = listOf("dickens" to 221, "dick" to 300, "chekhov" to 90, "dostoyevsky" to 56, "orwel" to 1, "ordeal" to 40)
        assertEquals("dickens", bestCorrection("dikens", 0, vocabulary))
        assertEquals("chekhov", bestCorrection("chekov", 0, vocabulary))
        assertEquals("dostoyevsky", bestCorrection("dostoevsky", 0, vocabulary))
        // "orwell" is not close enough to anything, so no silly suggestion like "ordeal".
        assertNull(bestCorrection("orwell", 0, vocabulary))
        // A word that already has plenty of books is left alone.
        assertNull(bestCorrection("dickens", 221, vocabulary))
        // Very short words and numbers are never rewritten.
        assertNull(bestCorrection("dik", 0, listOf("dick" to 300)))
        assertNull(bestCorrection("1984", 0, listOf("1914" to 30)))
    }

    @Test
    fun numbersAreGroupedForDisplay() {
        assertEquals("78,223", groupedNumber(78_223))
        assertEquals("999", groupedNumber(999))
        assertEquals("1,000", groupedNumber(1000))
        assertEquals("0", groupedNumber(0))
    }
}
