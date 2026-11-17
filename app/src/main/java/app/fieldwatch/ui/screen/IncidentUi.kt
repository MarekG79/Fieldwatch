package app.fieldwatch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fieldwatch.FieldwatchApp
import app.fieldwatch.domain.Incident
import app.fieldwatch.domain.LanguageText
import app.fieldwatch.domain.Sighting
import app.fieldwatch.incident.IncidentSaver
import kotlinx.coroutines.launch

/**
 * Antidron: "Save event" for an alarm. One tap saves every radio of the last 5 minutes
 * (and the last 10 s of sound for a sound alarm); then the name can be changed.
 */
@Composable
internal fun SaveEventBar(alarm: Incident.Alarm?, onRed: Boolean = true) {
    val app = LocalContext.current.applicationContext as FieldwatchApp
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<IncidentSaver.Saved?>(null) }
    var failed by remember { mutableStateOf(false) }
    val fg = if (onRed) Color.White else MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val done = saved
        if (done == null) {
            OutlinedButton(
                onClick = {
                    if (saving) return@OutlinedButton
                    saving = true
                    scope.launch {
                        val r = IncidentSaver.saveEvent(app, alarm)
                        saved = r
                        failed = r == null
                        saving = false
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = fg),
            ) {
                Icon(Icons.Outlined.Save, null)
                Text("  " + LanguageText.text(if (saving) "incident.saving" else "incident.save"))
            }
            if (failed) ResultText(LanguageText.text("incident.save.failed"), fg)
        } else {
            SavedLine(done, fg) { saved = it }
        }
    }
}

/** Filters → "Save surroundings…": the radios in the current filter heard in the last 10 minutes. */
@Composable
internal fun SaveSurroundingsRow(filtered: List<Sighting>) {
    val app = LocalContext.current.applicationContext as FieldwatchApp
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<IncidentSaver.Saved?>(null) }
    var failed by remember { mutableStateOf(false) }
    val fg = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                draft = IncidentSaver.defaultSurroundingsName()
                asking = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Save, null)
            Text("  " + LanguageText.text("incident.surroundings.save"))
        }
        Text(
            LanguageText.text("incident.surroundings.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        saved?.let { SavedLine(it, fg) { r -> saved = r } }
        if (failed) ResultText(LanguageText.text("incident.save.failed"), fg)
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(LanguageText.text("incident.surroundings.save")) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Incident.NAME_MAX) },
                    label = { Text(LanguageText.text("ui.name.label")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    val name = draft
                    val rows = filtered
                    scope.launch {
                        val r = IncidentSaver.saveSurroundings(app, name, rows)
                        saved = r
                        failed = r == null
                    }
                }) { Text(LanguageText.text("ui.save.label")) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(LanguageText.text("language.cancel")) }
            },
        )
    }
}

/** "Saved as … (n radios)" with a Rename button. */
@Composable
private fun SavedLine(saved: IncidentSaver.Saved, fg: Color, onRenamed: (IncidentSaver.Saved) -> Unit) {
    val app = LocalContext.current.applicationContext as FieldwatchApp
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(saved.name) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ResultText(
            LanguageText.text(
                "incident.saved.detail",
                mapOf("name" to saved.name, "n" to saved.radios.toString(), "folder" to IncidentSaver.FOLDER),
            ),
            fg,
            Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                draft = saved.name
                renaming = true
            },
            colors = ButtonDefaults.textButtonColors(contentColor = fg),
        ) { Text(LanguageText.text("incident.rename")) }
    }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(LanguageText.text("incident.rename")) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Incident.NAME_MAX) },
                    label = { Text(LanguageText.text("ui.name.label")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    val name = draft
                    scope.launch { onRenamed(IncidentSaver.rename(app, saved, name)) }
                }) { Text(LanguageText.text("ui.save.label")) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text(LanguageText.text("language.cancel")) }
            },
        )
    }
}

@Composable
private fun ResultText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}
