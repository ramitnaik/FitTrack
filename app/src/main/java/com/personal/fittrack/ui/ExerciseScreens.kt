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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ExercisesViewModel(private val c: AppContainer) : ViewModel() {
    val all = c.workouts.observeAllExercises().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    fun save(e: ExerciseEntity) = viewModelScope.launch { c.workouts.saveExercise(e) }
    fun duplicate(id: String) = viewModelScope.launch { c.workouts.duplicateExercise(id) }
    fun archive(id: String, v: Boolean) = viewModelScope.launch { c.workouts.setExerciseArchived(id, v) }
    fun delete(id: String) = viewModelScope.launch {
        val removed = c.workouts.deleteOrArchiveExercise(id)
        messages.emit(UiMessage(if (removed) "Exercise deleted" else "Exercise is used in your history, so it was archived instead (history stays intact)."))
    }
}

@Composable
fun ExercisesScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { ExercisesViewModel(c) }
    val all by vm.all.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf<String?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ExerciseEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    val list = remember(all, query, muscle, showArchived) {
        all.filter { (showArchived || !it.archived) && (muscle == null || it.primaryMuscle == muscle) && (query.isBlank() || it.name.contains(query.trim(), true)) }
    }
    AppScaffold("Exercise library", onBack = { nav.popBackStack() }, snackbar = snackbar,
        actions = { TextButton(onClick = { showArchived = !showArchived }) { Text(if (showArchived) "Hide archived" else "Show archived") } },
        fab = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New exercise") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${all.count { !it.archived }} exercises") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(12.dp, 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(muscle == null, { muscle = null }, { Text("All") }) }
                items(Seeds.MUSCLES) { m -> FilterChip(muscle == m, { muscle = if (muscle == m) null else m }, { Text(m) }) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(list, key = { it.id }) { e ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(e.name + if (e.archived) " (archived)" else "") },
                        supportingContent = { Text(listOf(e.primaryMuscle, pretty(e.equipment), if (e.isBuiltIn) "built-in" else "custom").joinToString(" · ")) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Statistics") }, onClick = { menu = false; nav.navigate("exercise/${e.id}") })
                                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = e })
                                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicate(e.id) })
                                    DropdownMenuItem(text = { Text(if (e.archived) "Restore" else "Archive") }, onClick = { menu = false; vm.archive(e.id, !e.archived) })
                                    if (!e.isBuiltIn) DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleteId = e.id })
                                }
                            }
                        },
                        modifier = Modifier.clickable { nav.navigate("exercise/${e.id}") }
                    )
                }
            }
        }
    }
    if (creating) ExerciseEditDialog(null, { vm.save(it) }, { creating = false })
    editing?.let { e -> ExerciseEditDialog(e, { vm.save(it) }, { editing = null }) }
    deleteId?.let { id -> ConfirmDialog("Delete exercise?", "If it appears in any workout or routine it will be archived instead so your history stays intact.", "Delete", true, { vm.delete(id) }, { deleteId = null }) }
}

class ExerciseStatsViewModel(private val c: AppContainer, val exerciseId: String) : ViewModel() {
    val exercise = c.workouts.observeExercise(exerciseId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val points = c.projectFlow(emptyList<SetPoint>()) { p ->
        c.workouts.observeExerciseSets(p, exerciseId).map { rows -> rows.map { SetPoint(it.date, it.workoutId, it.set.setType, it.set.reps, it.set.weightKg, it.set.durationSec, it.set.distanceM) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun setTrack(v: Boolean) = viewModelScope.launch { c.workouts.setTrackStats(exerciseId, v) }
}

@Composable
fun ExerciseStatsScreen(nav: NavController, exerciseId: String) {
    val c = LocalContainer.current
    val vm = vm(key = "exstats_$exerciseId") { ExerciseStatsViewModel(c, exerciseId) }
    val ex by vm.exercise.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val project by c.activeProject.collectAsStateWithLifecycle()
    val unit = WeightUnit.of(project?.weightUnit)
    val imperial = unit == WeightUnit.LB
    val e = ex
    AppScaffold(e?.name ?: "Exercise", onBack = { nav.popBackStack() }) { pad ->
        if (e == null) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        val sessions = remember(points, e.type) { StatsCalc.sessions(e.type, points) }
        val recs = remember(sessions) { StatsCalc.records(sessions) }
        val distUnit = if (imperial) "mi" else "km"
        fun w(kg: Double) = fmt(Units.kgTo(kg, unit), 1)
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Text("${e.primaryMuscle} · ${pretty(e.equipment)} · ${ExType.label(e.type)}", style = MaterialTheme.typography.bodyMedium)
                    if (e.instructions.isNotBlank()) Text(e.instructions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include in statistics & PRs")
                            Text("Only this project’s completed working sets are used; warm-ups are excluded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(e.trackStats, { vm.setTrack(it) })
                    }
                }
            }
            if (!e.trackStats) item { Text("Statistics are switched off for this exercise.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (sessions.isEmpty()) item { EmptyState(Icons.Default.BarChart, "No history yet", "Complete a workout containing this exercise in “${project?.name ?: "this project"}” to see charts and records.") }
            else {
                item {
                    SectionCard("Personal records") {
                        recs.maxWeight?.let { if (e.type == ExType.WEIGHT_REPS || e.type == ExType.BODYWEIGHT_REPS) Text("Heaviest set: ${w(it.first)} ${unit.label}  (${it.second})") }
                        recs.bestE1rm?.let { Text("Best estimated 1RM: ${w(it.first)} ${unit.label}  (${it.second})") }
                        recs.maxReps?.let { if (e.type != ExType.DURATION && e.type != ExType.DISTANCE_DURATION) Text("Most reps in a set: ${it.first}  (${it.second})") }
                        recs.bestVolume?.let { Text("Best session volume: ${fmt(Units.kgTo(it.first, unit), 0)} ${unit.label}  (${it.second})") }
                        recs.longestDurationSec?.let { Text("Longest session time: ${mmss(it.first)}  (${it.second})") }
                        recs.longestDistanceM?.let { Text("Longest distance: ${fmt(Units.metersTo(it.first, imperial), 2)} $distUnit  (${it.second})") }
                        if (e.type == ExType.WEIGHT_REPS) Text("1RM method — ${StatsCalc.E1RM_METHOD}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (e.type == ExType.WEIGHT_REPS) {
                    item { ChartCard("Estimated 1RM (${unit.label})", sessions.mapNotNull { s -> s.bestE1rm?.let { ChartPoint(s.date, Units.kgTo(it, unit)) } }, unit.label) }
                    item { ChartCard("Heaviest set (${unit.label})", sessions.mapNotNull { s -> s.maxWeight?.let { ChartPoint(s.date, Units.kgTo(it, unit)) } }, unit.label) }
                }
                if (e.type == ExType.WEIGHT_REPS || e.type == ExType.BODYWEIGHT_REPS) {
                    item { ChartCard("Reps in best set", sessions.mapNotNull { s -> s.maxReps?.let { ChartPoint(s.date, it.toDouble()) } }, "reps") }
                    val vol = sessions.filter { it.volume > 0 }
                    if (vol.isNotEmpty()) item { ChartCard("Training volume (${unit.label})", vol.map { ChartPoint(it.date, Units.kgTo(it.volume, unit)) }, unit.label) }
                }
                if (e.type == ExType.DURATION || e.type == ExType.DISTANCE_DURATION)
                    item { ChartCard("Total time (min)", sessions.map { ChartPoint(it.date, it.totalDurationSec / 60.0) }, "min") }
                if (e.type == ExType.DISTANCE_DURATION)
                    item { ChartCard("Distance ($distUnit)", sessions.map { ChartPoint(it.date, Units.metersTo(it.totalDistanceM, imperial)) }, distUnit) }
            }
            if (sessions.isNotEmpty()) {
                item { Text("History", style = MaterialTheme.typography.titleMedium) }
                items(sessions.reversed(), key = { it.workoutId }) { s ->
                    Card(Modifier.fillMaxWidth().clickable { nav.navigate("workout/${s.workoutId}") }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(Dates.pretty(s.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(s.sets.joinToString("  ·  ") { sp ->
                                when (e.type) {
                                    ExType.WEIGHT_REPS, ExType.BODYWEIGHT_REPS -> "${sp.weightKg?.let { w(it) } ?: "BW"}×${sp.reps ?: "–"}"
                                    ExType.DURATION -> "${fmt((sp.durationSec ?: 0) / 60.0, 1)} min"
                                    else -> "${fmt(Units.metersTo(sp.distanceM ?: 0.0, imperial), 2)} $distUnit / ${fmt((sp.durationSec ?: 0) / 60.0, 1)} min"
                                }
                            }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChartCard(title: String, points: List<ChartPoint>, unit: String) {
    if (points.isEmpty()) return
    SectionCard(title) { LineChart(points, unit = unit) }
}
