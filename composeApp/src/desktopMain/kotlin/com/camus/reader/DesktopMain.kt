package com.camus.reader

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    var selectedBook by remember { mutableStateOf<BookSelection?>(null) }
    var library by remember { mutableStateOf(emptyList<LibraryEntry>()) }

    Window(
        onCloseRequest = ::exitApplication,
        title = selectedBook?.displayName ?: "Camus Reader",
    ) {
        CamusReaderApp(
            selection = selectedBook,
            onOpenBook = {
                pickDesktopBook()?.let { book ->
                    library = listOf(LibraryEntry(book, System.currentTimeMillis(), System.currentTimeMillis())) +
                        library.filterNot { it.book.location == book.location }
                    selectedBook = book
                }
            },
            libraryEntries = library,
            onSelectBook = { selectedBook = it },
            onRemoveBook = { book -> library = library.filterNot { it.book.location == book.location } },
            onCloseBook = { selectedBook = null },
        )
    }
}

private fun pickDesktopBook(): BookSelection? {
    val dialog = FileDialog(null as Frame?, "Open PDF or EPUB", FileDialog.LOAD)
    dialog.filenameFilter = java.io.FilenameFilter { _, name -> bookKindForName(name) != null }
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val file = File(directory, name)
    val kind = bookKindForName(file.name) ?: return null
    return BookSelection(file.name, file.absolutePath, kind)
}
