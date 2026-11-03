package com.personal.fittrack.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

const val MIN_DATE = "0000-01-01"
const val MAX_DATE = "9999-12-31"

@Composable
fun WorkoutCard(item: WorkoutListItem, unit: WeightUnit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.workout.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Dates.pretty(item.workout.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.exerciseNames?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val bits = listOfNotNull(
                "${item.exerciseCount} exercises", "${item.setCount} sets",
                item.volumeKg.takeIf { it > 0 }?.let { "${fmt(Units.kgTo(it, unit), 0)} ${unit.label} volume" },
                item.workout.durationSec?.let { "${it / 60} min" }
            )
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

class WorkoutsViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.workouts
    val inProgress = c.projectFlow<WorkoutEntity?>(null) { repo.observeInProgress(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val recent = c.projectFlow(emptyList<WorkoutListItem>()) { repo.observeHistory(it, "", MIN_DATE, MAX_DATE, 5) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val routines = repo.observeRoutines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startEmpty(onId: (String) -> Unit) = viewModelScope.launch { c.activeProject.value?.let { onId(repo.startEmpty(it.id)) } }
    fun startRoutine(routineId: String, onId: (String) -> Unit) = viewModelScope.launch { c.activeProject.value?.let { onId(repo.startFromRoutine(it.id, routineId)) } }
    fun createPast(date: String, onId: (String) -> Unit) = viewModelScope.launch { c.activeProject.value?.let { onId(repo.createPast(it.id, date)) } }
}

@Composable
fun WorkoutsScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { WorkoutsViewModel(c) }
    val project by c.activeProject.collectAsStateWithLifecycle()
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val routines by vm.routines.collectAsStateWithLifecycle()
    val open = { id: String -> nav.navigate("workout/$id") }
    var pastDialog by remember { mutableStateOf(false) }
    val unit = WeightUnit.of(project?.weightUnit)

    AppScaffold("Workouts", actions = {
        IconButton(onClick = { nav.navigate("exercises") }) { Icon(Icons.Default.FitnessCenter, "Exercise library") }
    }) { pad ->
        if (project == null) { NoProject { nav.navigate("project/new") }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Project: ${project?.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            inProgress?.let { w ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Workout in progress", style = MaterialTheme.typography.titleSmall); Text("${w.name} · started ${Dates.timeOfDay(w.startedAt)}", style = MaterialTheme.typography.bodySmall) }
                            Button(onClick = { open(w.id) }) { Text("Resume") }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startEmpty { open(it) } }, enabled = inProgress == null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Start empty") }
                    OutlinedButton(onClick = { pastDialog = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.History, null); Spacer(Modifier.width(4.dp)); Text("Log past") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Routines", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { nav.navigate("routines") }) { Text("Manage") }
                }
            }
            if (routines.isEmpty()) item {
                EmptyState(Icons.Default.ListAlt, "No routines yet", "Create reusable templates such as Push, Pull, Legs — or save any finished workout as a routine.", "Create routine", { nav.navigate("routines") })
            }
            items(routines, key = { it.id }) { r ->
                val n by c.workouts.observeRoutineCount(r.id).collectAsStateWithLifecycle(0)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(r.name, style = MaterialTheme.typography.titleSmall); Text("$n exercises", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        FilledTonalButton(onClick = { vm.startRoutine(r.id) { open(it) } }, enabled = inProgress == null) { Text("Start") }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { nav.navigate("history") }) { Text("Full history") }
                }
            }
            if (recent.isEmpty()) item { Text("No completed workouts in this project yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(recent, key = { it.workout.id }) { WorkoutCard(it, unit) { open(it.workout.id) } }
        }
    }
    if (pastDialog) {
        var d by remember { mutableStateOf(Dates.today()) }
        AlertDialog(onDismissRequest = { pastDialog = false }, title = { Text("Log a past workout") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Pick the date. You can then add exercises and sets and tick them off."); DateButton(d, { d = it }) } },
            confirmButton = { TextButton(onClick = { pastDialog = false; vm.createPast(d) { open(it) } }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { pastDialog = false }) { Text("Cancel") } })
    }
}

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val from = MutableStateFlow<String?>(null)
    val to = MutableStateFlow<String?>(null)
    val limit = MutableStateFlow(30)
    val items = combine(c.activeProject.map { it?.id }.distinctUntilChanged(), query.debounce(200), from, to, limit) { p, q, f, t, l -> arrayOf<Any?>(p, q, f, t, l) }
        .flatMapLatest { a ->
            val p = a[0] as String?
            if (p == null) flowOf(emptyList()) else c.workouts.observeHistory(p, a[1] as String, (a[2] as String?) ?: MIN_DATE, (a[3] as String?) ?: MAX_DATE, a[4] as Int)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun HistoryScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { HistoryViewModel(c) }
    val project by c.activeProject.collectAsStateWithLifecycle()
    val list by vm.items.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val from by vm.from.collectAsStateWithLifecycle()
    val to by vm.to.collectAsStateWithLifecycle()
    val limit by vm.limit.collectAsStateWithLifecycle()
    val unit = WeightUnit.of(project?.weightUnit)
    AppScaffold("Workout history", onBack = { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(q, { vm.query.value = it }, placeholder = { Text("Search workouts or exercises") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateButton(from, { vm.from.value = it }, Modifier.weight(1f), "From")
                        DateButton(to, { vm.to.value = it }, Modifier.weight(1f), "To")
                        if (from != null || to != null) IconButton(onClick = { vm.from.value = null; vm.to.value = null }) { Icon(Icons.Default.Clear, "Clear dates") }
                    }
                }
            }
            if (list.isEmpty()) item { EmptyState(Icons.Default.History, "Nothing found", "No completed workouts match these filters in this project.") }
            items(list, key = { it.workout.id }) { WorkoutCard(it, unit) { nav.navigate("workout/${it.workout.id}") } }
            if (list.size >= limit) item { OutlinedButton(onClick = { vm.limit.value = limit + 30 }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
        }
    }
}

// ======================= Routines =======================

class RoutinesViewModel(private val c: AppContainer) : ViewModel() {
    val routines = c.workouts.observeRoutines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun create(name: String, onId: (String) -> Unit) = viewModelScope.launch { onId(c.workouts.createRoutine(name)) }
    fun delete(id: String) = viewModelScope.launch { c.workouts.deleteRoutine(id) }
    fun duplicate(id: String) = viewModelScope.launch { c.workouts.duplicateRoutine(id) }
}

@Composable
fun RoutinesScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { RoutinesViewModel(c) }
    val routines by vm.routines.collectAsStateWithLifecycle()
    var newDialog by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    AppScaffold("Routines", onBack = { nav.popBackStack() }, fab = { ExtendedFloatingActionButton(onClick = { newDialog = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New routine") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (routines.isEmpty()) item { EmptyState(Icons.Default.ListAlt, "No routines", "Routines are shared across all projects. Create Push / Pull / Legs, Upper / Lower, or anything you like.") }
            items(routines, key = { it.id }) { r ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("routine/${r.id}") }) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; nav.navigate("routine/${r.id}") })
                                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicate(r.id) })
                                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleteId = r.id })
                            }
                        }
                    }
                }
            }
        }
    }
    if (newDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newDialog = false }, title = { Text("New routine") }, text = { TextF("Name (e.g. Push, Upper, Legs)", name, { name = it }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { newDialog = false; vm.create(name.trim()) { nav.navigate("routine/$it") } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Cancel") } })
    }
    deleteId?.let { id -> ConfirmDialog("Delete routine?", "Workouts already logged from this routine are not affected.", "Delete", true, { vm.delete(id) }, { deleteId = null }) }
}

class RoutineEditViewModel(private val c: AppContainer, val id: String) : ViewModel() {
    val routine = c.workouts.observeRoutine(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val rows = c.workouts.observeRoutineRows(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val exercises = c.workouts.observeActiveExercises().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun rename(name: String) = viewModelScope.launch { routine.value?.let { c.workouts.saveRoutine(it.copy(name = name)) } }
    fun notes(n: String) = viewModelScope.launch { routine.value?.let { c.workouts.saveRoutine(it.copy(notes = n)) } }
    fun add(ids: List<String>) = viewModelScope.launch { c.workouts.addRoutineExercises(id, ids) }
    fun saveNew(e: ExerciseEntity) = viewModelScope.launch { c.workouts.saveExercise(e) }
    fun update(r: RoutineExerciseEntity) = viewModelScope.launch { c.workouts.updateRoutineRow(r) }
    fun remove(r: RoutineExerciseEntity) = viewModelScope.launch { c.workouts.removeRoutineRow(r.id, id) }
    fun move(r: RoutineExerciseEntity, d: Int) = viewModelScope.launch { c.workouts.moveRoutineRow(r.id, id, d) }
}

@Composable
fun RoutineEditScreen(nav: NavController, id: String) {
    val c = LocalContainer.current
    val vm = vm(key = "routine_$id") { RoutineEditViewModel(c, id) }
    val routine by vm.routine.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    AppScaffold(routine?.name ?: "Routine", onBack = { nav.popBackStack() }) { pad ->
        val r = routine
        if (r == null) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                var name by remember(r.id) { mutableStateOf(r.name) }
                var notes by remember(r.id) { mutableStateOf(r.notes) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextF("Routine name", name, { name = it; if (it.isNotBlank()) vm.rename(it) }, error = if (name.isBlank()) "Name can’t be empty" else null)
                    TextF("Notes", notes, { notes = it; vm.notes(it) }, singleLine = false)
                }
            }
            itemsIndexed2(rows) { i, row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.exerciseName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(enabled = i > 0, onClick = { vm.move(row.re, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Up") }
                            IconButton(enabled = i < rows.size - 1, onClick = { vm.move(row.re, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Down") }
                            IconButton(onClick = { vm.remove(row.re) }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                        key(row.re.id) {
                            var sets by remember { mutableStateOf(row.re.targetSets.toString()) }
                            var reps by remember { mutableStateOf(row.re.targetReps?.toString() ?: "") }
                            var rest by remember { mutableStateOf(row.re.restSeconds?.toString() ?: "") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumField("Sets", sets, { sets = it; it.toIntOrNull()?.takeIf { n -> n in 1..50 }?.let { n -> vm.update(row.re.copy(targetSets = n)) } }, Modifier.weight(1f), decimal = false)
                                NumField("Reps", reps, { reps = it; vm.update(row.re.copy(targetReps = it.toIntOrNull())) }, Modifier.weight(1f), decimal = false)
                                NumField("Rest s", rest, { rest = it; vm.update(row.re.copy(restSeconds = it.toIntOrNull())) }, Modifier.weight(1f), decimal = false)
                            }
                        }
                    }
                }
            }
            item { Button(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add exercises") } }
        }
    }
    if (picker) ExercisePickerDialog(exercises, true, "Add to routine", vm::add, vm::saveNew) { picker = false }
}

/** items-with-index helper (avoids importing itemsIndexed overload ambiguity). */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed2(list: List<T>, content: @Composable (Int, T) -> Unit) {
    items(list.size) { i -> content(i, list[i]) }
}
