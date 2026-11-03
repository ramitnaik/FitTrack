package com.personal.fittrack.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

class ProjectsViewModel(private val c: AppContainer) : ViewModel() {
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    fun activate(id: String) = c.prefs.setActiveProject(id)
    fun archive(id: String, v: Boolean) = viewModelScope.launch {
        c.projects.setArchived(id, v)
        messages.emit(UiMessage(if (v) "Project archived. Its data is kept and can be restored." else "Project restored"))
    }
    fun duplicate(id: String) = viewModelScope.launch { c.projects.duplicate(id); messages.emit(UiMessage("Copied the project’s settings and targets (no logged data).")) }
    fun delete(id: String) = viewModelScope.launch { c.projects.delete(id); messages.emit(UiMessage("Project deleted")) }
}

@Composable
fun ProjectsScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { ProjectsViewModel(c) }
    val all by c.allProjects.collectAsStateWithLifecycle()
    val active by c.activeProject.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    var deleteP by remember { mutableStateOf<ProjectEntity?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val live = all.orEmpty().filter { !it.archived }
    val archived = all.orEmpty().filter { it.archived }
    AppScaffold("Projects", snackbar = snackbar, actions = { IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") } },
        fab = { ExtendedFloatingActionButton(onClick = { nav.navigate("project/new") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New project") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (all != null && live.isEmpty() && archived.isEmpty()) item {
                EmptyState(Icons.Default.Folder, "Create your first project", "A project (e.g. “Lean Bulk 2026”, “Cutting Phase”) keeps its own workouts, food diary, targets, weight and measurements.", "New project", { nav.navigate("project/new") })
            }
            items(live, key = { it.id }) { p -> ProjectCard(p, p.id == active?.id, nav, vm, onDelete = { deleteP = p }) }
            if (archived.isNotEmpty()) {
                item { TextButton(onClick = { showArchived = !showArchived }) { Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})") } }
                if (showArchived) items(archived, key = { it.id }) { p -> ProjectCard(p, false, nav, vm, onDelete = { deleteP = p }) }
            }
        }
    }
    deleteP?.let { p -> TypedConfirmDialog("Delete “${p.name}” permanently?", "This permanently deletes every workout, food entry, weight and measurement in this project. Consider exporting it or archiving instead.", "DELETE", { vm.delete(p.id) }, { deleteP = null }) }
}

@Composable
private fun ProjectCard(p: ProjectEntity, isActive: Boolean, nav: NavController, vm: ProjectsViewModel, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { if (!p.archived) vm.activate(p.id) else nav.navigate("project/${p.id}") },
        colors = CardDefaults.cardColors(containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    if (isActive) AssistChip(onClick = {}, label = { Text("Active") })
                    if (p.archived) AssistChip(onClick = {}, label = { Text("Archived") })
                }
                if (p.goal.isNotBlank()) Text(p.goal, style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(p.startDate?.let { "from ${Dates.pretty(it)}" }, p.endDate?.let { "to ${Dates.pretty(it)}" },
                    p.targetKcal?.let { "$it kcal" }, p.targetProtein?.let { "${it}g protein" }).joinToString(" · ").ifBlank { "No dates or targets set" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                DropdownMenu(menu, { menu = false }) {
                    if (!p.archived && !isActive) DropdownMenuItem(text = { Text("Switch to this project") }, onClick = { menu = false; vm.activate(p.id) })
                    DropdownMenuItem(text = { Text("Edit / export") }, onClick = { menu = false; nav.navigate("project/${p.id}") })
                    DropdownMenuItem(text = { Text("Duplicate settings") }, onClick = { menu = false; vm.duplicate(p.id) })
                    DropdownMenuItem(text = { Text(if (p.archived) "Restore" else "Archive") }, onClick = { menu = false; vm.archive(p.id, !p.archived) })
                    DropdownMenuItem(text = { Text("Delete permanently…", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun ProjectEditScreen(nav: NavController, id: String) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val isNew = id == "new"
    var loaded by remember { mutableStateOf(isNew) }
    var existing by remember { mutableStateOf<ProjectEntity?>(null) }
    var name by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var goal by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }
    var start by remember { mutableStateOf<String?>(null) }; var end by remember { mutableStateOf<String?>(null) }
    var kcal by remember { mutableStateOf("") }; var pro by remember { mutableStateOf("") }; var carb by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }; var fib by remember { mutableStateOf("") }
    var weekly by remember { mutableStateOf("") }; var wGoal by remember { mutableStateOf("") }
    var wUnit by remember { mutableStateOf(c.prefs.defaultWeightUnit.value) }; var lUnit by remember { mutableStateOf(c.prefs.defaultLengthUnit.value) }
    var height by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<ProjectRepository.Counts?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(id) {
        if (!isNew) c.projects.get(id)?.let { p ->
            existing = p; name = p.name; desc = p.description; goal = p.goal; notes = p.notes; start = p.startDate; end = p.endDate
            kcal = p.targetKcal?.toString() ?: ""; pro = p.targetProtein?.toString() ?: ""; carb = p.targetCarbs?.toString() ?: ""
            fat = p.targetFat?.toString() ?: ""; fib = p.targetFiber?.toString() ?: ""; weekly = p.weeklyWorkoutGoal?.toString() ?: ""
            wGoal = p.workoutGoal; wUnit = p.weightUnit; lUnit = p.lengthUnit
            height = p.heightCm?.let { fmt(Units.cmTo(it, LengthUnit.of(p.lengthUnit)), 1) } ?: ""
            counts = c.projects.counts(id)
        }
        loaded = true
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val msg = try { withContext(Dispatchers.IO) { c.backup.writeTo(uri, id) }; "Project exported" } catch (e: Exception) { "Export failed: ${e.message}" }
            snackbar.showSnackbar(msg)
        }
    }
    val dateErr = if (start != null && end != null && end!! < start!!) "End date is before the start date" else null
    val nameErr = if (tried && name.isBlank()) "Project name is required" else null

    AppScaffold(if (isNew) "New project" else "Edit project", onBack = { nav.popBackStack() }, snackbar = snackbar) { pad ->
        if (!loaded) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TextF("Project name", name, { name = it }, error = nameErr) }
            item { TextF("Goal (optional, e.g. Lean bulk to 75 kg)", goal, { goal = it }) }
            item { TextF("Description (optional)", desc, { desc = it }, singleLine = false) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateButton(start, { start = it }, Modifier.weight(1f), "Start date")
                    DateButton(end, { end = it }, Modifier.weight(1f), "End date")
                    if (start != null || end != null) IconButton(onClick = { start = null; end = null }) { Icon(Icons.Default.Clear, "Clear dates") }
                }
            }
            if (dateErr != null) item { Text(dateErr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            item { Text("Daily nutrition targets (editable any time — past logs never change)", style = MaterialTheme.typography.titleSmall) }
            item { NumField("Calories", kcal, { kcal = it }, Modifier.fillMaxWidth(), suffix = "kcal", decimal = false) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Protein", pro, { pro = it }, Modifier.weight(1f), suffix = "g", decimal = false); NumField("Carbs", carb, { carb = it }, Modifier.weight(1f), suffix = "g", decimal = false); NumField("Fat", fat, { fat = it }, Modifier.weight(1f), suffix = "g", decimal = false) } }
            item { NumField("Fibre (optional)", fib, { fib = it }, Modifier.fillMaxWidth(), suffix = "g", decimal = false) }
            item { Text("Training", style = MaterialTheme.typography.titleSmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Workouts / week", weekly, { weekly = it }, Modifier.weight(1f), decimal = false); TextF("Workout goal (optional)", wGoal, { wGoal = it }, Modifier.weight(2f)) } }
            item { Text("Units & body", style = MaterialTheme.typography.titleSmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Weight unit", WeightUnit.entries, WeightUnit.of(wUnit), { it.label }, { wUnit = it.name }, Modifier.weight(1f))
                DropdownField("Length unit", LengthUnit.entries, LengthUnit.of(lUnit), { it.label }, { lUnit = it.name }, Modifier.weight(1f)) } }
            item { NumField("Height (optional)", height, { height = it }, Modifier.fillMaxWidth(), suffix = LengthUnit.of(lUnit).label) }
            item { TextF("Notes (optional)", notes, { notes = it }, singleLine = false) }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    tried = true
                    if (name.isNotBlank() && dateErr == null) scope.launch {
                        val lu = LengthUnit.of(lUnit)
                        val base = existing ?: ProjectEntity(id = "", name = "")
                        val p = base.copy(name = name.trim(), description = desc.trim(), goal = goal.trim(), notes = notes.trim(), startDate = start, endDate = end,
                            targetKcal = kcal.toIntOrNull()?.takeIf { it > 0 }, targetProtein = pro.toIntOrNull()?.takeIf { it > 0 }, targetCarbs = carb.toIntOrNull()?.takeIf { it > 0 },
                            targetFat = fat.toIntOrNull()?.takeIf { it > 0 }, targetFiber = fib.toIntOrNull()?.takeIf { it > 0 },
                            weeklyWorkoutGoal = weekly.toIntOrNull()?.takeIf { it in 1..14 }, workoutGoal = wGoal.trim(), weightUnit = wUnit, lengthUnit = lUnit,
                            heightCm = height.toD()?.takeIf { it > 0 }?.let { Units.toCm(it, lu) })
                        if (isNew) { val created = c.projects.create(p); c.prefs.setActiveProject(created.id) } else c.projects.save(p)
                        nav.popBackStack()
                    }
                }) { Text(if (isNew) "Create project" else "Save changes") }
            }
            if (!isNew) {
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    counts?.let { Text("Contains ${it.workouts} workouts, ${it.foodEntries} food entries, ${it.weights} weight entries.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item { OutlinedButton(onClick = { exportLauncher.launch("fittrack_project_${name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "project" }}_${Dates.today()}.json") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("Export this project (JSON)") } }
                item {
                    val a = existing?.archived == true
                    OutlinedButton(onClick = { scope.launch { c.projects.setArchived(id, !a); nav.popBackStack() } }, modifier = Modifier.fillMaxWidth()) { Text(if (a) "Restore project" else "Archive project (keeps all data)") }
                }
                item { OutlinedButton(onClick = { delete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete project permanently…", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (delete) TypedConfirmDialog("Delete “${name}” permanently?", "This permanently deletes every workout, food entry, weight and measurement in this project.", "DELETE", { scope.launch { c.projects.delete(id); nav.popBackStack() } }, { delete = false })
}
