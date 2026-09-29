package com.camus.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class LibraryDestination { SHELF, SETTINGS, STORAGE, TYPOGRAPHY, GUTENBERG }

@Composable
fun SettingsScreen(
    onOpenStorage: () -> Unit = {},
    onOpenTypography: () -> Unit = {},
    darkMode: Boolean,
    profileName: String,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onLockProfile: () -> Unit,
    ptgEnabled: Boolean = false,
    onPtgEnabledChange: (Boolean) -> Unit = {},
    ptgAutoAdd: Boolean = false,
    onPtgAutoAddChange: (Boolean) -> Unit = {},
    encryptionEnabled: Boolean = false,
    onRequestEnableEncryption: () -> Unit = {},
    onRequestDisableEncryption: () -> Unit = {},
) {
    val sections = listOf("Profile & vault", "Gutenberg network", "Data operations", "About Camus Reader")
    var selectedSection by remember { mutableStateOf(sections.first()) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clickable(onClick = onBack).semantics { contentDescription = "Back to library" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("camus reader", fontFamily = LocalTitleFont.current, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                Text(".", color = MaterialTheme.colorScheme.primary, fontFamily = LocalTitleFont.current, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleTheme) {
                Icon(
                    if (darkMode) ReaderIcons.LightMode else ReaderIcons.DarkMode,
                    contentDescription = if (darkMode) "Use light mode" else "Use dark mode",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp).widthIn(max = 900.dp).align(Alignment.CenterHorizontally)) {
            Text("LIBRARY  /  SETTINGS", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.55.sp)
            Spacer(Modifier.height(10.dp))
            Text("Settings", fontFamily = LocalTitleFont.current, fontSize = 34.sp)
            Text("Choose how Camus Reader feels and how your books live on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 7.dp))
            Spacer(Modifier.height(20.dp))
            SettingsLink("Storage", "Your books by format, reading progress, and removing titles.", onOpenStorage)
            Spacer(Modifier.height(10.dp))
            SettingsLink("Typography", "Import reading fonts, and choose the app theme and font.", onOpenTypography)
            Spacer(Modifier.height(22.dp))
            Text("PREFERENCES", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 9.dp, bottom = 15.dp)) {
                sections.forEach { item ->
                    FilterChip(selected = selectedSection == item, onClick = { selectedSection = item }, label = { Text(item, maxLines = 1) })
                }
            }
            when (selectedSection) {
                "Profile & vault" -> {
                    SettingsPanel("Local reader profile", "Your books, position, and annotations belong to this device.", listOf(
                        "Profile name" to "$profileName · stored locally on this device.",
                    ))
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f), RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Device encryption", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (encryptionEnabled) "Your library details, bookmarks, notes, and highlights are encrypted with your profile password."
                                else "Reading data is stored on this device without encryption. Turn this on to protect it with a password.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Switch(checked = encryptionEnabled, onCheckedChange = { if (it) onRequestEnableEncryption() else onRequestDisableEncryption() })
                    }
                    if (encryptionEnabled) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = onLockProfile, shape = RoundedCornerShape(9.dp)) { Text("Lock Camus Reader") }
                    }
                }
                "Gutenberg network" -> {
                    SettingsPanel("Gutenberg network", "Camus Reader works fully offline. Internet access is used only for Pull the Guten (PTG), which keeps a copy of Project Gutenberg's catalog of public-domain books on this device.", listOf(
                        "Catalog access" to "When on, PTG downloads the catalog once (about 5 MB) and checks for a newer one about once a week. Searching itself never uses the network. When off, Camus Reader never contacts the network.",
                        "Downloads" to "With the option below on, Camus Reader downloads the EPUB itself and puts it on your shelf. With it off, downloads open on Project Gutenberg in your browser.",
                    ))
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f), RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Allow internet access for PTG", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (ptgEnabled) "On - you can search and download books from your library."
                                else "Off - Pull the Guten is locked.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Switch(checked = ptgEnabled, onCheckedChange = onPtgEnabledChange)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f), RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Add downloaded books to my library", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (ptgAutoAdd) "On - \"Add to library\" in PTG downloads the EPUB inside Camus Reader and puts it on your shelf. Removing it from the library deletes that copy."
                                else "Off - \"Download options\" opens the book's page on Project Gutenberg in your browser, and you add the file yourself.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Switch(checked = ptgAutoAdd, onCheckedChange = onPtgAutoAddChange)
                    }
                }
                "Data operations" -> SettingsPanel("Data operations", "Keep control of your reading data.", listOf(
                    "Encrypted profile" to "The profile vault protects library metadata and reading marks.",
                    "Original files" to "Camus Reader does not move or modify imported source files.",
                ))
                else -> SettingsPanel("About Camus Reader", "A quieter place for books and the notes you make in them.", listOf("Edition" to "Camus Reader for Android", "Your privacy" to "Your library stays on this device."))
            }
        }
    }
}

private enum class StorageSource(val label: String) { ALL("All"), EPUB("EPUB"), PDF("PDF"), PTG("PTG") }
private enum class StorageStatus(val label: String) { ALL("Any status"), COMPLETE("Completed"), READING("In progress"), UNREAD("Not started") }

/** Every book in this profile, grouped by format, with how far each one has been read. */
@Composable
private fun StoragePanel(books: List<LibraryEntry>, onRemoveBooks: (List<BookSelection>) -> Unit) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingRemoval by remember { mutableStateOf<List<BookSelection>?>(null) }
    // Drop selections for books that are gone (removed here or from the library).
    LaunchedEffect(books) { selected = selected.filterTo(HashSet()) { loc -> books.any { it.book.location == loc } } }
    var source by remember { mutableStateOf(StorageSource.ALL) }
    var status by remember { mutableStateOf(StorageStatus.ALL) }
    val complete = books.count { it.completed }
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f), RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(20.dp)) {
        Text("Books in your profile", fontFamily = LocalTitleFont.current, fontSize = 23.sp)
        Text(
            "${books.size} ${if (books.size == 1) "book" else "books"} · $complete completed · ${books.count { it.fromPtg }} from PTG. Files you picked stay where you chose them; books added from PTG are kept by Camus Reader and deleted with the title.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageSource.entries.forEach { item ->
                val count = when (item) {
                    StorageSource.ALL -> books.size
                    StorageSource.EPUB -> books.count { it.book.kind == BookKind.EPUB }
                    StorageSource.PDF -> books.count { it.book.kind == BookKind.PDF }
                    StorageSource.PTG -> books.count { it.fromPtg }
                }
                FilterChip(selected = source == item, onClick = { source = item }, label = { Text("${item.label} ($count)", maxLines = 1) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageStatus.entries.forEach { item ->
                FilterChip(selected = status == item, onClick = { status = item }, label = { Text(item.label, maxLines = 1) })
            }
        }
        val visible = books.filter { entry ->
            when (source) {
                StorageSource.ALL -> true
                StorageSource.EPUB -> entry.book.kind == BookKind.EPUB
                StorageSource.PDF -> entry.book.kind == BookKind.PDF
                StorageSource.PTG -> entry.fromPtg
            } && when (status) {
                StorageStatus.ALL -> true
                StorageStatus.COMPLETE -> entry.completed
                StorageStatus.READING -> entry.started && !entry.completed
                StorageStatus.UNREAD -> !entry.started
            }
        }
        // Remove a title: tap the bin on a row, or Select several (e.g. filter to Completed, Select all).
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Text("${selected.size} selected", fontSize = 13.sp, modifier = Modifier.weight(1f))
                val visibleLocations = visible.map { it.book.location }.toSet()
                val allVisibleSelected = visibleLocations.isNotEmpty() && selected.containsAll(visibleLocations)
                TextButton(onClick = { selected = if (allVisibleSelected) selected - visibleLocations else selected + visibleLocations }, enabled = visible.isNotEmpty()) {
                    Text(if (allVisibleSelected) "Deselect all" else "Select all")
                }
                TextButton(
                    onClick = { pendingRemoval = books.filter { it.book.location in selected }.map { it.book } },
                    enabled = selected.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
                TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("Done") }
            } else {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selecting = true }, enabled = books.isNotEmpty()) { Text("Select") }
            }
        }
        if (visible.isEmpty()) {
            Text(
                if (source == StorageSource.PTG && books.none { it.fromPtg }) "No books from Pull the Guten yet." else "No books match these filters.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp),
            )
        }
        // Grouped by format so EPUBs and PDFs are listed separately.
        listOf(BookKind.EPUB to "EPUB", BookKind.PDF to "PDF").forEach { (kind, label) ->
            val group = visible.filter { it.book.kind == kind }.sortedBy { it.title.lowercase() }
            if (group.isEmpty()) return@forEach
            Text(
                "$label · ${group.size}",
                color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
            )
            group.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
                StorageRow(
                    entry = entry,
                    selecting = selecting,
                    checked = entry.book.location in selected,
                    onCheckedChange = { on -> selected = if (on) selected + entry.book.location else selected - entry.book.location },
                    onRemove = { pendingRemoval = listOf(entry.book) },
                )
            }
        }
    }

    pendingRemoval?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(if (targets.size == 1) "Remove from library?" else "Remove ${targets.size} books?") },
            text = {
                Text(
                    (if (targets.size == 1) "${targets.first().displayName} will leave Camus Reader" else "These books will leave Camus Reader") +
                        " along with their bookmarks, highlights and reading position. " +
                        if (books.any { it.book.location in targets.map(BookSelection::location) && it.fromPtg }) "Books from PTG are deleted from this device too; you can add them again. Other original files stay where they are."
                        else "The original files stay on your device.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveBooks(targets)
                        selected = selected - targets.map { it.location }.toSet()
                        if (selected.isEmpty()) selecting = false
                        pendingRemoval = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StorageRow(
    entry: LibraryEntry,
    selecting: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().then(if (selecting) Modifier.clickable { onCheckedChange(!checked) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    if (selecting) Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (entry.fromPtg) {
                Text(
                    "PTG",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        val (label, color) = when {
            entry.completed -> "Completed" to MaterialTheme.colorScheme.primary
            entry.started -> "In progress · ${(entry.progress * 100).toInt()}% · page ${entry.position} of ${entry.total}" to muted
            else -> "Not started" to muted
        }
        Text(label, color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        if (entry.started && !entry.completed) {
            LinearProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
    if (!selecting) {
        IconButton(onClick = onRemove) {
            Icon(ReaderIcons.Delete, contentDescription = "Remove ${entry.title}", tint = muted, modifier = Modifier.size(20.dp))
        }
    }
    }
}

@Composable
private fun SettingsLink(title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f), RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = LocalTitleFont.current, fontSize = 20.sp)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(ReaderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Frame for a page opened from Settings: back arrow to Settings, section label and title, scrolling body. */
@Composable
private fun SettingsSubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(ReaderIcons.Back, contentDescription = "Back to settings", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(title, fontFamily = LocalTitleFont.current, fontSize = 20.sp)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp).widthIn(max = 900.dp).align(Alignment.CenterHorizontally)) {
            Text("SETTINGS  /  ${title.uppercase()}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.55.sp)
            Spacer(Modifier.height(10.dp))
            Text(title, fontFamily = LocalTitleFont.current, fontSize = 34.sp)
            Spacer(Modifier.height(20.dp))
            content()
        }
    }
}

@Composable
fun StorageScreen(books: List<LibraryEntry>, onRemoveBooks: (List<BookSelection>) -> Unit, onBack: () -> Unit) {
    SettingsSubPage("Storage", onBack) { StoragePanel(books, onRemoveBooks) }
}

@Composable
fun TypographyScreen(
    appTheme: AppTheme,
    onAppTheme: (AppTheme) -> Unit,
    appFont: AppFont,
    onAppFont: (AppFont) -> Unit,
    readingFonts: Map<String, FontFamily>,
    readingFontName: String?,
    onReadingFont: (String?) -> Unit,
    onImportFont: () -> Unit,
    onRemoveFont: (String) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPage("Typography", onBack) {
        ReadingFontPanel(readingFonts, readingFontName, onReadingFont, onImportFont, onRemoveFont)
        Spacer(Modifier.height(14.dp))
        AppearancePanel(appTheme, onAppTheme, appFont, onAppFont)
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f), RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(20.dp),
        content = content,
    )
}

@Composable
private fun OptionRow(selected: Boolean, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, label: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
        Box(Modifier.weight(1f)) { label() }
        trailing?.invoke()
    }
}

/** Fonts the user brings in (.ttf / .otf) for reading EPUBs; PDFs keep their embedded fonts. */
@Composable
private fun ReadingFontPanel(
    fonts: Map<String, FontFamily>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    PanelCard {
        Text("Reading font", fontFamily = LocalTitleFont.current, fontSize = 23.sp)
        Text(
            "Import your own font (.ttf or .otf) to read EPUB books in it. Fonts are copied into Camus Reader and stay on this device.",
            color = muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        OptionRow(selected = selected == null || selected !in fonts, onClick = { onSelect(null) }) {
            Column {
                Text("Book default")
                Text("The quick brown fox jumps over the lazy dog.", fontFamily = FontFamily.Serif, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        fonts.keys.sortedBy { it.lowercase() }.forEach { name ->
            OptionRow(
                selected = selected == name,
                onClick = { onSelect(name) },
                trailing = {
                    IconButton(onClick = { onRemove(name) }) {
                        Icon(ReaderIcons.Delete, contentDescription = "Remove font $name", tint = muted, modifier = Modifier.size(20.dp))
                    }
                },
            ) {
                Column {
                    Text(name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("The quick brown fox jumps over the lazy dog.", fontFamily = fonts[name], color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OutlinedButton(onClick = onImport, shape = RoundedCornerShape(9.dp), modifier = Modifier.padding(top = 10.dp)) {
            Icon(ReaderIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Import font")
        }
    }
}

/** App theme and app font, side by side: one column each, one option per row. */
@Composable
private fun AppearancePanel(theme: AppTheme, onTheme: (AppTheme) -> Unit, font: AppFont, onFont: (AppFont) -> Unit) {
    PanelCard {
        Text("Theme and font", fontFamily = LocalTitleFont.current, fontSize = 23.sp)
        Text(
            "How Camus Reader itself looks. Page colors for books are set from the reader menu.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("THEME", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(bottom = 4.dp))
                AppTheme.entries.forEach { item ->
                    val swatch = when (item) {
                        AppTheme.LIGHT -> Color(0xFFF7F8FC)
                        AppTheme.GRAY -> Color(0xFF2A2B2F)
                        AppTheme.BLACK -> Color(0xFF0D0D0F)
                        AppTheme.AMOLED -> Color.Black
                    }
                    OptionRow(
                        selected = theme == item,
                        onClick = { onTheme(item) },
                        leading = { Box(Modifier.size(16.dp).clip(CircleShape).background(swatch).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) },
                    ) { Text(item.label, maxLines = 1) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("FONT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(bottom = 4.dp))
                AppFont.entries.forEach { item ->
                    OptionRow(selected = font == item, onClick = { onFont(item) }) {
                        Text(item.label, fontFamily = if (item == AppFont.SANS) FontFamily.SansSerif else FontFamily.Default, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(title: String, description: String, rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f), RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(20.dp)) {
        Text(title, fontFamily = LocalTitleFont.current, fontSize = 23.sp)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
            Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                Text(row.first, fontWeight = FontWeight.SemiBold)
                Text(row.second, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
fun GutenbergCatalogScreen(
    state: CatalogUiState,
    onBack: () -> Unit,
    /** Called once when the screen opens so the platform can load, build or refresh the on-device catalog. */
    onOpen: () -> Unit,
    onSearch: (CatalogQuery) -> Unit,
    onRefresh: () -> Unit,
    onDownload: (Int) -> Unit,
    /** When on, each book offers "Add to library" (downloaded inside Camus Reader) instead of opening its Gutenberg page. */
    autoAdd: Boolean = false,
    onAdd: (GutenbergBook) -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(CATALOG_PAGE) }
    var filtersOpen by remember { mutableStateOf(false) }
    val ready = state.index as? CatalogIndexState.Ready
    val focus = LocalFocusManager.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(Unit) { onOpen() }
    // Searching is local and quick; the short delay just avoids a search per keystroke.
    LaunchedEffect(text, language, category, limit, ready?.builtAt) {
        if (ready == null) return@LaunchedEffect
        delay(150)
        onSearch(CatalogQuery(text, language, category, limit))
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(58.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Library") }
            Spacer(Modifier.weight(1f))
            Text("Open shelf", color = muted, fontSize = 12.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            // Books in the catalog, or how many the current search found.
            val shown = when {
                ready == null -> null
                state.result != null && !state.result.browsing -> state.result.total
                else -> ready.bookCount
            }
            if (shown != null) Text("${groupedNumber(shown)} ${if (shown == 1) "book" else "books"}", color = muted, fontSize = 14.sp)
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item {
                Text("PULL THE GUTEN", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Text("Search the catalog.", color = muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 5.dp))
            }
            item {
                val filtered = language.isNotEmpty() || category.isNotEmpty()
                OutlinedTextField(
                    text, { text = it; limit = CATALOG_PAGE },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title, author or subject") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            if (text.isNotEmpty()) IconButton(onClick = { text = ""; limit = CATALOG_PAGE }) {
                                Icon(ReaderIcons.Close, contentDescription = "Clear search", tint = muted, modifier = Modifier.size(20.dp))
                            }
                            if (ready != null) IconButton(onClick = { filtersOpen = true }) {
                                Box {
                                    Icon(
                                        ReaderIcons.Filter, contentDescription = if (filtered) "Filters (on)" else "Filters",
                                        tint = if (filtered) MaterialTheme.colorScheme.primary else muted, modifier = Modifier.size(22.dp),
                                    )
                                    // A dot on the icon shows that a language or topic is narrowing the results.
                                    if (filtered) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(9.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                }
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
            }
            item { CatalogStatus(state.index, onRefresh) }

            val result = state.result
            if (ready != null && result != null) {
                if (result.total > 0) item {
                    Text(
                        if (result.browsing) "Most popular" else "Results for “${result.text.trim()}”",
                        fontFamily = LocalTitleFont.current, fontSize = 19.sp,
                    )
                }
                result.suggestion?.let { suggestion ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable { text = suggestion; limit = CATALOG_PAGE }
                                .background(catalogBoxColor()).padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Did you mean ", color = muted, fontSize = 14.sp)
                            Text(suggestion, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("?", color = muted, fontSize = 14.sp)
                        }
                    }
                }
                if (result.total == 0) item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("Nothing in the catalog matches that.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        val filtered = language.isNotEmpty() || category.isNotEmpty()
                        Text(
                            "Project Gutenberg only lists books that are in the US public domain, so some well-known authors and recent titles " +
                                "aren't there. Try a shorter search or just an author's surname" + if (filtered) ", or clear the language and topic filters." else ".",
                            color = muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 5.dp),
                        )
                        if (filtered) TextButton(onClick = { language = ""; category = ""; limit = CATALOG_PAGE }) { Text("Clear filters") }
                    }
                }
                items(result.books, key = { it.id }) { book ->
                    Column(Modifier.fillMaxWidth().background(catalogBoxColor(), RoundedCornerShape(9.dp)).padding(15.dp)) {
                        Text(book.title, fontFamily = LocalTitleFont.current, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (book.subtitle.isNotBlank()) Text(book.subtitle, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        Text(book.authors.ifEmpty { listOf("Unknown author") }.joinToString(", "), color = muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Text("#${book.id}  ·  ${book.languages.firstOrNull()?.let(::catalogLanguageName) ?: "Book"}", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        CatalogBookAction(
                            autoAdd = autoAdd,
                            inLibrary = book.id in state.libraryIds,
                            download = state.downloads[book.id],
                            onAdd = { onAdd(book) },
                            onOpenPage = { onDownload(book.id) },
                        )
                    }
                }
                if (result.books.size < result.total) item {
                    OutlinedButton(onClick = { limit += CATALOG_PAGE }, modifier = Modifier.fillMaxWidth()) {
                        Text("Show more  ·  ${groupedNumber(result.total - result.books.size)} left")
                    }
                }
            }
        }
    }
    if (filtersOpen && ready != null) {
        CatalogFilterSheet(
            ready, language, category,
            onLanguage = { language = it; limit = CATALOG_PAGE },
            onCategory = { category = it; limit = CATALOG_PAGE },
            onDismiss = { filtersOpen = false },
        )
    }
}

/** The action row at the bottom of a catalog book: add it to the library, or open its Gutenberg page. */
@Composable
private fun CatalogBookAction(autoAdd: Boolean, inLibrary: Boolean, download: CatalogDownload?, onAdd: () -> Unit, onOpenPage: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        when {
            !autoAdd -> TextButton(onClick = onOpenPage) { Text("Download options ↗") }
            inLibrary -> Text("In your library ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
            download is CatalogDownload.Running -> Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (download.fraction != null) LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.weight(1f))
                else LinearProgressIndicator(Modifier.weight(1f))
                Text("Adding…", color = muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
            }
            download is CatalogDownload.Failed -> {
                Text(download.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row {
                    TextButton(onClick = onOpenPage) { Text("Open on Gutenberg ↗") }
                    TextButton(onClick = onAdd) { Text("Try again") }
                }
            }
            else -> TextButton(onClick = onAdd) { Text("Add to library") }
        }
    }
}

/** Book boxes in the catalog: a soft grey that follows the theme (lighter than a dark page, darker than a light one). */
@Composable
private fun catalogBoxColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)

/** Language and topic filters, tucked into a drawer that slides up from the search bar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CatalogFilterSheet(
    ready: CatalogIndexState.Ready,
    language: String,
    category: String,
    onLanguage: (String) -> Unit,
    onCategory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", fontFamily = LocalTitleFont.current, fontSize = 20.sp, modifier = Modifier.weight(1f))
                if (language.isNotEmpty() || category.isNotEmpty()) TextButton(onClick = { onLanguage(""); onCategory("") }) { Text("Clear") }
            }
            Text("LANGUAGE", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChip(selected = language.isEmpty(), onClick = { onLanguage("") }, label = { Text("All") })
                ready.languages.forEach { facet ->
                    FilterChip(selected = language == facet.key, onClick = { onLanguage(if (language == facet.key) "" else facet.key) }, label = { Text(facet.label, maxLines = 1) })
                }
            }
            if (ready.categories.isNotEmpty()) {
                Text("TOPIC", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilterChip(selected = category.isEmpty(), onClick = { onCategory("") }, label = { Text("All") })
                    ready.categories.forEach { facet ->
                        FilterChip(selected = category == facet.key, onClick = { onCategory(if (category == facet.key) "" else facet.key) }, label = { Text(facet.label, maxLines = 1) })
                    }
                }
            }
        }
    }
}

/** Where the on-device catalog stands: being downloaded and indexed, ready (with its age), or failed. */
@Composable
private fun CatalogStatus(index: CatalogIndexState, onRefresh: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (index) {
        CatalogIndexState.Unknown -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is CatalogIndexState.Building -> Column(Modifier.fillMaxWidth().background(catalogBoxColor(), RoundedCornerShape(9.dp)).padding(15.dp)) {
            Text(if (index.firstTime) "Getting the catalog ready" else "Updating the catalog", fontWeight = FontWeight.SemiBold)
            Text(
                if (index.firstTime) "One time only: Camus Reader downloads Project Gutenberg's catalog (about 5 MB) so searching is instant and works offline."
                else index.message,
                color = muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
            )
            if (index.progress != null) LinearProgressIndicator(progress = { index.progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (index.firstTime) Text(index.message, color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        is CatalogIndexState.Failed -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(index.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 20.sp)
            Button(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
        }
        is CatalogIndexState.Ready -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val age = when (index.ageDays) { 0 -> "today"; 1 -> "yesterday"; else -> "${index.ageDays} days ago" }
                Text("Catalog checked $age", color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (index.updating) Text("Updating…", color = muted, fontSize = 12.sp)
                else TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Update", fontSize = 12.sp) }
            }
            index.updateError?.let { Text("$it Showing the copy already on this device.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 17.sp) }
        }
    }
}

@Composable
fun CatalogProgress(loading: Boolean) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
}
