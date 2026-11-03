package com.personal.fittrack.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val defs = c.body.observeAllDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val busy = MutableStateFlow(false)
    val pending = MutableStateFlow<BackupFile?>(null)
    val error = MutableStateFlow<String?>(null)
    val result = MutableStateFlow<ImportResult?>(null)

    private fun io(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { withContext(Dispatchers.IO) { block() } } catch (e: BackupException) { error.value = e.message }
        catch (e: Exception) { error.value = e.message ?: e.javaClass.simpleName } finally { busy.value = false }
    }

    fun exportFull(uri: android.net.Uri) = io {
        val f = c.backup.writeTo(uri, null)
        messages.emit(UiMessage("Backup saved (${f.projects.size} projects, ${f.workouts.size} workouts, ${f.foodEntries.size} food entries)"))
    }
    fun readForRestore(uri: android.net.Uri) = io { pending.value = c.backup.readFile(uri) }
    fun restore(mode: RestoreMode) {
        val f = pending.value ?: return
        pending.value = null
        io { result.value = c.backup.import(f, mode) }
    }
    fun exportCsv(uri: android.net.Uri, kind: CsvKind, projectId: String?, from: String, to: String) = io {
        c.backup.writeText(uri, c.csv.build(kind, projectId, from, to))
        messages.emit(UiMessage("${kind.label} exported"))
    }
    fun deleteAll() = io { c.backup.deleteAllData(); messages.emit(UiMessage("All data deleted. A safety copy was kept inside the app.")) }
    fun reseed() = viewModelScope.launch { withContext(Dispatchers.IO) { Seeds.forceReseed(c.db) }; messages.emit(UiMessage("Built-in exercise library refreshed (your edits are kept).")) }
    fun addDef(name: String, kind: String) = viewModelScope.launch { c.body.addDef(name, kind) }
    fun archiveDef(d: MeasurementDefEntity) = viewModelScope.launch { c.body.setDefArchived(d, !d.archived) }
}

@Composable
fun SettingsScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { SettingsViewModel(c) }
    val theme by c.prefs.theme.collectAsStateWithLifecycle()
    val rest by c.prefs.restSeconds.collectAsStateWithLifecycle()
    val meals by c.prefs.mealNames.collectAsStateWithLifecycle()
    val dWu by c.prefs.defaultWeightUnit.collectAsStateWithLifecycle()
    val dLu by c.prefs.defaultLengthUnit.collectAsStateWithLifecycle()
    val defs by vm.defs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val project by c.activeProject.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)

    var csvKind by remember { mutableStateOf(CsvKind.WORKOUTS) }
    var csvAll by remember { mutableStateOf(false) }
    var csvFrom by remember { mutableStateOf<String?>(null) }
    var csvTo by remember { mutableStateOf<String?>(null) }
    var csvDialog by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    var newDef by remember { mutableStateOf(false) }
    var restoreMode by remember { mutableStateOf(RestoreMode.MERGE) }
    var replaceConfirm by remember { mutableStateOf(false) }
    val mealDraft = remember(meals) { mutableStateListOf(*meals.toTypedArray()) }
    var restText by remember(rest) { mutableStateOf(rest.toString()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportFull(uri) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.readForRestore(uri) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri, csvKind, if (csvAll) null else project?.id, csvFrom ?: MIN_DATE, csvTo ?: MAX_DATE)
    }

    AppScaffold("Settings", onBack = { nav.popBackStack() }, snackbar = snackbar) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                SectionCard("Appearance") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark").forEach { (k, l) -> FilterChip(theme == k, { c.prefs.setTheme(k) }, { Text(l) }) }
                    }
                }
            }
            item {
                SectionCard("Units for new projects") {
                    Text("Each project keeps its own units (change them in the project editor).", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WeightUnit.entries.forEach { u -> FilterChip(dWu == u.name, { c.prefs.setDefaultWeightUnit(u.name) }, { Text(u.label) }) }
                        Spacer(Modifier.width(8.dp)); LengthUnit.entries.forEach { u -> FilterChip(dLu == u.name, { c.prefs.setDefaultLengthUnit(u.name) }, { Text(u.label) }) } }
                }
            }
            item {
                SectionCard("Workout") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Default rest timer", restText, { restText = it; it.toIntOrNull()?.takeIf { s -> s in 5..3600 }?.let { s -> c.prefs.setRestSeconds(s) } }, Modifier.weight(1f), suffix = "sec", decimal = false)
                    }
                    Text("Used after you tick a working set. Per-exercise overrides are in each exercise’s menu while training.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard("Meal sections") {
                    mealDraft.forEachIndexed { i, m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(m, { mealDraft[i] = it }, singleLine = true, modifier = Modifier.weight(1f))
                            IconButton(enabled = mealDraft.size > 1, onClick = { mealDraft.removeAt(i) }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { mealDraft.add("") }) { Text("Add meal") }
                        Button(onClick = { c.prefs.setMealNames(mealDraft.toList()) }) { Text("Save meal names") }
                    }
                    Text("Existing diary entries keep their meal name even if you rename or remove a section here.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard("Body measurement types", trailing = { TextButton(onClick = { newDef = true }) { Text("+ New") } }) {
                    defs.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name + if (d.archived) " (hidden)" else "", Modifier.weight(1f))
                            TextButton(onClick = { vm.archiveDef(d) }) { Text(if (d.archived) "Show" else "Hide") }
                        }
                    }
                    Text("Hiding a type only removes it from the entry form; recorded values stay.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard("Backup & restore") {
                    Text("Backups are human-readable JSON files you keep. Nothing is ever uploaded anywhere.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { exportLauncher.launch("fittrack_backup_${Dates.today()}.json") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("Back up everything") }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Restore from a backup file") }
                    Text("Tip: back up regularly and copy the file off the phone — uninstalling the app deletes its data. Single projects can be exported from the project editor.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard("Export CSV (spreadsheets)") {
                    Text("Workouts, food diary, body weight or measurements, optionally for a date range.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { csvDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Export CSV…") }
                }
            }
            item {
                SectionCard("Data tools") {
                    Text("Database size: ${"%.2f".format(c.backup.databaseSizeBytes() / 1024.0 / 1024.0)} MB (no app-imposed limit on history)", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { vm.reseed() }, modifier = Modifier.fillMaxWidth()) { Text("Refresh built-in exercise library") }
                    OutlinedButton(onClick = { wipe = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete ALL data…", color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                SectionCard("About & help") {
                    Text("FitTrack 1.0.0 · personal, offline, no account, no ads, no tracking, no internet permission.")
                    Text("• Projects keep workouts, food, weight and measurements separate.\n• Tap a set’s number to mark it warm-up, add RPE/notes, duplicate or delete it.\n• Tap “previous” beside a set to copy last time’s numbers.\n• Editing a food only affects future logs; edit a diary entry to change a past one.\n• 1RM uses the Epley formula.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (newDef) NewMeasurementDefDialog({ n, k -> vm.addDef(n, k) }) { newDef = false }

    if (csvDialog) {
        AlertDialog(onDismissRequest = { csvDialog = false }, title = { Text("Export CSV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField("Data", CsvKind.entries, csvKind, { it.label }, { csvKind = it })
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(csvAll, { csvAll = it }); Text("All projects (otherwise: ${project?.name ?: "active project"})") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DateButton(csvFrom, { csvFrom = it }, Modifier.weight(1f), "From"); DateButton(csvTo, { csvTo = it }, Modifier.weight(1f), "To") }
                    Text("Weights are in kg, lengths in cm, distances in metres.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = csvAll || project != null, onClick = { csvDialog = false; csvLauncher.launch(csvKind.fileName) }) { Text("Choose location") } },
            dismissButton = { TextButton(onClick = { csvDialog = false }) { Text("Cancel") } })
    }

    pending?.let { f ->
        AlertDialog(onDismissRequest = { vm.pending.value = null }, title = { Text("Restore backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${if (f.scope == "FULL") "Full backup" else "Single-project backup"} · exported ${f.exportedAt.take(10)} · app ${f.appVersion.ifBlank { "?" }}", style = MaterialTheme.typography.bodySmall)
                    BackupCodec.counts(f).filter { it.value > 0 }.forEach { (k, v) -> Text("• $k: $v") }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.clickable { restoreMode = RestoreMode.MERGE }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(restoreMode == RestoreMode.MERGE, { restoreMode = RestoreMode.MERGE })
                        Column { Text("Merge (recommended)"); Text("Adds what’s missing; records you already have are left untouched. Importing the same file twice adds nothing.", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (f.scope == "FULL") Row(Modifier.clickable { restoreMode = RestoreMode.REPLACE_ALL }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(restoreMode == RestoreMode.REPLACE_ALL, { restoreMode = RestoreMode.REPLACE_ALL })
                        Column { Text("Replace everything", color = MaterialTheme.colorScheme.error); Text("Erases current data first (a safety copy is kept inside the app).", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (restoreMode == RestoreMode.REPLACE_ALL) replaceConfirm = true else vm.restore(RestoreMode.MERGE) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { vm.pending.value = null }) { Text("Cancel") } })
    }
    if (replaceConfirm) ConfirmDialog("Replace ALL current data?", "Everything currently in the app will be erased and replaced by the backup.", "Replace everything", true,
        { vm.restore(RestoreMode.REPLACE_ALL); restoreMode = RestoreMode.MERGE }, { replaceConfirm = false })
    error?.let { msg -> AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text("Couldn’t complete that") }, text = { Text(msg) }, confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("OK") } }) }
    result?.let { r ->
        AlertDialog(onDismissRequest = { vm.result.value = null }, title = { Text("Restore complete") },
            text = { Column { Text("${r.added} records added, ${r.skipped} already present (skipped)."); r.perType.filter { it.value.first + it.value.second > 0 }.forEach { (k, v) -> Text("• $k: +${v.first}" + if (v.second > 0) ", ${v.second} existing" else "", style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { vm.result.value = null }) { Text("OK") } })
    }
    if (wipe) TypedConfirmDialog("Delete ALL data?", "Deletes every project, workout, food, recipe and measurement. A safety copy is saved inside the app first, but you should make a normal backup before continuing.", "DELETE", { vm.deleteAll() }, { wipe = false })
}
