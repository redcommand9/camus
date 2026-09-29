package com.camus.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Passed as `renderBookPage` to [CamusReaderApp]. Both branches are fully native (real [PdfRenderer]
 * for PDF, the from-scratch parser/paginator in NativeEpubBook.kt + EpubPagination.kt for EPUB) -
 * there is no [android.webkit.WebView] anywhere in this reading path.
 */
@Composable
internal fun AndroidBookPage(
    controller: ReaderController,
    leaf: ReaderLeaf.BookPage,
    background: Color,
    ink: Color,
) {
    val selection = controller.book ?: run { Text("No book is open.", color = ink); return }
    when (selection.kind) {
        BookKind.PDF -> AndroidPdfBookPage(selection, controller, leaf, background, ink)
        BookKind.EPUB -> AndroidEpubBookPage(selection, controller, leaf, background, ink)
    }
}
