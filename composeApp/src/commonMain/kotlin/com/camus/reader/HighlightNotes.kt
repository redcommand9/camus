package com.camus.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun noteTint(color: HighlightColor): Color = when (color) {
    HighlightColor.AMBER -> Color(0xFFF6C84F)
    HighlightColor.ROSE -> Color(0xFFF27D98)
    HighlightColor.BLUE -> Color(0xFF6FB7FF)
}

/**
 * Bottom drawer for the note attached to a highlight. Opened by tapping a highlight in the reader
 * ([ReaderController.showNote]); a tap on the dimmed page, Done, or system back closes it. Notes are
 * saved as you type.
 */
@Composable
internal fun HighlightNoteDrawer(controller: ReaderController, modifier: Modifier = Modifier) {
    val open = controller.openNote
    val exists = open != null && controller.noteFor(open.ref) != null
    // The highlight can go away while the drawer is open (erased, or re-laid out by a zoom change).
    LaunchedEffect(open, exists) { if (open != null && !exists) controller.closeNote() }

    val focus = LocalFocusManager.current
    val dismiss = { focus.clearFocus(); controller.closeNote() }

    val lastShown = remember { object { var note: OpenNote? = null } }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = open != null && exists, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = .32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss),
            )
        }
        AnimatedVisibility(
            visible = open != null && exists,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            // The last open note stays drawn while the drawer slides away.
            if (open != null) lastShown.note = open
            lastShown.note?.let { NoteDrawerContent(controller, it, dismiss) }
        }
    }
}

@Composable
private fun NoteDrawerContent(controller: ReaderController, open: OpenNote, onDone: () -> Unit) {
    val ref = open.ref
    // The field owns the text while typing; the controller gets every change. Reading it back from
    // the controller instead would reset the cursor as the (immutable) highlight list is rebuilt.
    var text by remember(ref) { mutableStateOf(controller.noteFor(ref).orEmpty()) }
    val color = controller.colorFor(ref) ?: HighlightColor.AMBER
    val page = controller.sequence.indexOfFirst { it.key == ref.key } + 1

    Surface(
        modifier = Modifier.fillMaxWidth().imePadding()
            // Swallows touches so they don't reach the scrim behind.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(noteTint(color)))
                Text(
                    "Note on highlight" + if (page > 0) " · page $page" else "",
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    fontFamily = LocalTitleFont.current,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onDone) { Text("Done") }
            }
            open.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(noteTint(color)))
                    Text(
                        quote.trim(),
                        modifier = Modifier.padding(start = 10.dp),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; controller.setNote(ref, it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).semantics { contentDescription = "Note" },
                placeholder = { Text("Write a note…") },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { controller.removeHighlight(ref); onDone() },
                modifier = Modifier.align(Alignment.Start),
            ) { Text("Remove highlight", color = MaterialTheme.colorScheme.error) }
        }
    }
}
