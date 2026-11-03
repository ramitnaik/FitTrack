package com.personal.fittrack.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.fittrack.data.ExerciseEntity
import com.personal.fittrack.data.Seeds
import com.personal.fittrack.domain.ExType

fun pretty(s: String): String = s.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
fun ExerciseEditDialog(initial: ExerciseEntity?, onSave: (ExerciseEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var muscle by remember { mutableStateOf(initial?.primaryMuscle ?: "Chest") }
    var secondary by remember { mutableStateOf(initial?.secondaryMuscles ?: "") }
    var equipment by remember { mutableStateOf(initial?.equipment ?: "BARBELL") }
    var category by remember { mutableStateOf(initial?.category ?: "STRENGTH") }
    var type by remember { mutableStateOf(initial?.type ?: ExType.WEIGHT_REPS) }
    var notes by remember { mutableStateOf(initial?.instructions ?: "") }
    var track by remember { mutableStateOf(initial?.trackStats ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null || initial.id.isBlank()) "New exercise" else "Edit exercise") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { TextF("Name", name, { name = it }, error = if (name.isBlank()) "Required" else null) }
                item { DropdownField("Primary muscle", Seeds.MUSCLES, muscle, { it }, { muscle = it }) }
                item { TextF("Secondary muscles (optional)", secondary, { secondary = it }) }
                item { DropdownField("Equipment", Seeds.EQUIPMENT, equipment, ::pretty, { equipment = it }) }
                item { DropdownField("Category", Seeds.CATEGORIES, category, ::pretty, { category = it }) }
                item { DropdownField("Tracking type", ExType.all, type, ExType::label, { type = it }) }
                item { TextF("Instructions / personal notes", notes, { notes = it }, singleLine = false) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include in statistics & PRs")
                            Text("Turn off for exercises where PRs make no sense.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(track, { track = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave((initial ?: ExerciseEntity(id = "", name = "")).copy(
                    name = name.trim(), primaryMuscle = muscle, secondaryMuscles = secondary.trim(), equipment = equipment,
                    category = category, type = type, instructions = notes.trim(), trackStats = track))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Full-screen searchable picker. [onSaveNew] persists a brand-new custom exercise (it then appears in the list). */
@Composable
fun ExercisePickerDialog(
    exercises: List<ExerciseEntity>, multi: Boolean, title: String = "Choose exercise",
    onPick: (List<String>) -> Unit, onSaveNew: (ExerciseEntity) -> Unit, onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var creating by remember { mutableStateOf(false) }
    val filtered = remember(exercises, query, muscle) {
        exercises.filter { e -> (muscle == null || e.primaryMuscle == muscle) && (query.isBlank() || e.name.contains(query.trim(), ignoreCase = true)) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Text("New") }
                }
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search exercises") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(muscle == null, { muscle = null }, { Text("All") }) }
                    items(Seeds.MUSCLES) { m -> FilterChip(muscle == m, { muscle = if (muscle == m) null else m }, { Text(m) }) }
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (filtered.isEmpty()) item {
                        EmptyState(Icons.Default.FitnessCenter, "No match", "Create it as a custom exercise — it stays in your library for future workouts.", "Create “${query.trim().ifBlank { "new exercise" }}”", { creating = true })
                    }
                    items(filtered, key = { it.id }) { e ->
                        val on = e.id in selected
                        ListItem(
                            headlineContent = { Text(e.name) },
                            supportingContent = { Text(listOf(e.primaryMuscle, pretty(e.equipment)).joinToString(" · ")) },
                            trailingContent = { if (multi) Checkbox(on, null) },
                            modifier = Modifier.clickable {
                                if (multi) selected = if (on) selected - e.id else selected + e.id
                                else { onPick(listOf(e.id)); onDismiss() }
                            }
                        )
                    }
                }
                if (multi) Button(
                    enabled = selected.isNotEmpty(), onClick = { onPick(selected); onDismiss() },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) { Text(if (selected.isEmpty()) "Select exercises" else "Add ${selected.size} exercise${if (selected.size > 1) "s" else ""}") }
            }
        }
    }
    if (creating) ExerciseEditDialog(
        initial = ExerciseEntity(id = "", name = query.trim()),
        onSave = onSaveNew, onDismiss = { creating = false }
    )
}
