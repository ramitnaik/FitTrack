package com.personal.fittrack.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class RestTimer(val total: Int, val remaining: Int, val running: Boolean)

class WorkoutViewModel(private val c: AppContainer, val workoutId: String) : ViewModel() {
    private val repo = c.workouts
    private val scope get() = viewModelScope
    val workout = repo.observeWorkout(workoutId).stateIn(scope, SharingStarted.WhileSubscribed(5000), null)
    val rows = repo.observeRows(workoutId).stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sets = repo.observeSets(workoutId).map { l -> l.groupBy { it.workoutExerciseId } }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val project = workout.map { it?.projectId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else c.projects.observe(id) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)
    val exercises = repo.observeActiveExercises().stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Previous performance (same project only) keyed by exerciseId. */
    val prev: StateFlow<Map<String, List<WorkoutSetEntity>>> =
        combine(workout.map { it?.projectId }.distinctUntilChanged(), rows.map { r -> r.map { it.we.exerciseId }.distinct() }.distinctUntilChanged()) { p, ids -> p to ids }
            .mapLatest { (p, ids) -> if (p == null) emptyMap() else ids.associateWith { repo.previousSets(p, it, workoutId) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val summary = MutableStateFlow<WorkoutSummary?>(null)

    // ----- rest timer (in memory only) -----
    val timer = MutableStateFlow<RestTimer?>(null)
    val timerDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var timerJob: Job? = null

    fun startTimer(seconds: Int) { timer.value = RestTimer(seconds, seconds, true); runTimer() }
    private fun runTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(1000)
                val t = timer.value ?: break
                if (!t.running) break
                val r = t.remaining - 1
                if (r <= 0) { timer.value = t.copy(remaining = 0, running = false); timerDone.tryEmit(Unit); break }
                timer.value = t.copy(remaining = r)
            }
        }
    }
    fun pauseResumeTimer() {
        val t = timer.value ?: return
        if (t.running) { timerJob?.cancel(); timer.value = t.copy(running = false) }
        else { timer.value = t.copy(remaining = if (t.remaining == 0) t.total else t.remaining, running = true); runTimer() }
    }
    fun resetTimer() { val t = timer.value ?: return; timerJob?.cancel(); timer.value = t.copy(remaining = t.total, running = false) }
    fun adjustTimer(delta: Int) {
        val t = timer.value ?: return
        timer.value = t.copy(remaining = (t.remaining + delta).coerceAtLeast(1), total = (t.total + delta).coerceAtLeast(1))
        if (t.running) runTimer()
    }
    fun stopTimer() { timerJob?.cancel(); timer.value = null }

    // ----- actions -----
    private fun go(block: suspend () -> Unit) { scope.launch { block() } }

    fun updateWorkout(f: (WorkoutEntity) -> WorkoutEntity) = go { workout.value?.let { repo.updateWorkout(f(it)) } }
    fun addExercises(ids: List<String>) = go { repo.addExercises(workoutId, ids) }
    fun saveNewExercise(e: ExerciseEntity) = go { repo.saveExercise(e) }
    fun replaceExercise(weId: String, exId: String) = go { repo.replaceExercise(weId, exId) }
    fun moveExercise(weId: String, d: Int) = go { repo.moveExercise(weId, d) }
    fun duplicateExercise(weId: String) = go { repo.duplicateExercise(weId) }
    fun toggleSuperset(weId: String) = go { repo.toggleSuperset(weId) }
    fun setRest(weId: String, s: Int?) = go { repo.setRest(weId, s) }
    fun setExerciseNotes(weId: String, n: String) = go { repo.setExerciseNotes(weId, n) }
    fun removeExercise(weId: String) = go {
        val snap = repo.snapshotExercise(weId)
        repo.removeExercise(weId)
        if (snap != null) messages.emit(UiMessage("Exercise removed", "Undo") { repo.restoreExercise(snap.first, snap.second) })
    }
    fun addSet(weId: String, warmup: Boolean) = go { repo.addSet(weId, warmup) }
    fun duplicateSet(id: String) = go { repo.duplicateSet(id) }
    fun removeSet(id: String) = go {
        val s = repo.deleteSet(id)
        if (s != null) messages.emit(UiMessage("Set removed", "Undo") { repo.restoreSet(s) })
    }
    fun setWeight(id: String, kg: Double?) = go { repo.setWeight(id, kg) }
    fun setReps(id: String, v: Int?) = go { repo.setReps(id, v) }
    fun setDuration(id: String, v: Int?) = go { repo.setDuration(id, v) }
    fun setDistance(id: String, v: Double?) = go { repo.setDistance(id, v) }
    fun setType(id: String, t: String) = go { repo.setSetType(id, t) }
    fun setRpeNotes(id: String, rpe: Double?, n: String) = go { repo.setRpeNotes(id, rpe, n) }

    fun toggleComplete(set: WorkoutSetEntity, restSeconds: Int?) = go {
        val nowDone = !set.completed
        repo.setCompleted(set.id, nowDone)
        if (nowDone && workout.value?.status == WorkoutStatus.IN_PROGRESS && set.setType == SetType.WORKING) startTimer(restSeconds ?: c.prefs.restSeconds.value)
    }

    fun finish(discardIncomplete: Boolean) = go {
        repo.finish(workoutId, discardIncomplete)
        stopTimer()
        summary.value = repo.summary(workoutId)
    }
    fun deleteWorkout(onDone: () -> Unit) = go { repo.deleteWorkout(workoutId); onDone() }
    fun repeat(onNew: (String) -> Unit) = go { repo.startFromWorkout(workoutId)?.let(onNew) }
    fun saveAsRoutine(name: String) = go { repo.saveAsRoutine(workoutId, name); messages.emit(UiMessage("Saved as routine “$name”")) }
}

private fun initialTexts(type: String, s: WorkoutSetEntity?, unit: WeightUnit): Pair<String, String> {
    if (s == null) return "" to ""
    val imperial = unit == WeightUnit.LB
    return when (type) {
        ExType.WEIGHT_REPS, ExType.BODYWEIGHT_REPS -> (s.weightKg?.let { fmt(Units.kgTo(it, unit), 2) } ?: "") to (s.reps?.toString() ?: "")
        ExType.DURATION -> (s.durationSec?.let { fmt(it / 60.0, 2) } ?: "") to ""
        else -> (s.distanceM?.let { fmt(Units.metersTo(it, imperial), 2) } ?: "") to (s.durationSec?.let { fmt(it / 60.0, 2) } ?: "")
    }
}

@Composable
fun WorkoutScreen(nav: NavController, workoutId: String) {
    val c = LocalContainer.current
    val vm = vm(key = "workout_$workoutId") { WorkoutViewModel(c, workoutId) }
    val workout by vm.workout.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val setsMap by vm.sets.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val prev by vm.prev.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(Unit) { vm.timerDone.collect { vibrate(ctx) } }

    var picker by remember { mutableStateOf(false) }
    var replaceId by remember { mutableStateOf<String?>(null) }
    var finishDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var detailsDialog by remember { mutableStateOf(false) }
    var routineDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val w = workout
    val unit = WeightUnit.of(project?.weightUnit)
    val inProgress = w?.status == WorkoutStatus.IN_PROGRESS

    AppScaffold(
        title = w?.name ?: "Workout", onBack = { nav.popBackStack() }, snackbar = snackbar,
        actions = {
            if (w != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit details") }, onClick = { menu = false; detailsDialog = true })
                    if (!inProgress) DropdownMenuItem(text = { Text("Repeat this workout") }, onClick = { menu = false; vm.repeat { id -> nav.navigate("workout/$id") { popUpTo("workouts") } } })
                    DropdownMenuItem(text = { Text("Save as routine…") }, onClick = { menu = false; routineDialog = true })
                    DropdownMenuItem(text = { Text(if (inProgress) "Discard workout" else "Delete workout", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleteDialog = true })
                }
            }
        },
        bottomBar = {
            if (inProgress) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    timer?.let { RestTimerBar(it, vm) }
                    Button(onClick = { finishDialog = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Finish workout") }
                }
            }
        }
    ) { pad ->
        if (w == null) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        val elapsed by produceState(0L, w.startedAt, w.status) {
            while (w.status == WorkoutStatus.IN_PROGRESS) { value = (System.currentTimeMillis() - w.startedAt) / 1000; delay(1000) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(Dates.pretty(w.date), style = MaterialTheme.typography.titleSmall)
                            Text(if (inProgress) "Started ${Dates.timeOfDay(w.startedAt)} · in progress" else w.durationSec?.let { "Duration ${mmss(it)}" } ?: "Duration not recorded",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (inProgress) Text(mmss(elapsed.toInt()), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { detailsDialog = true }) { Text("Edit") }
                    }
                    var notes by remember(w.id) { mutableStateOf(w.notes) }
                    OutlinedTextField(notes, { notes = it; vm.updateWorkout { x -> x.copy(notes = it) } }, label = { Text("Workout notes") }, modifier = Modifier.fillMaxWidth())
                    if (!inProgress) Text("Tick a set’s ✓ to count it in history and statistics. Changes save instantly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(rows, key = { it.we.id }) { row ->
                ExerciseCard(
                    row = row, index = rows.indexOf(row), count = rows.size, sets = setsMap[row.we.id].orEmpty(),
                    prev = prev[row.we.exerciseId].orEmpty(), unit = unit, vm = vm,
                    onReplace = { replaceId = row.we.id }, onStats = { nav.navigate("exercise/${row.we.exerciseId}") }
                )
            }
            item {
                Column {
                    OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add exercise") }
                    Spacer(Modifier.height(60.dp))
                }
            }
        }
    }

    if (picker) ExercisePickerDialog(exercises, multi = true, title = "Add exercises", onPick = vm::addExercises, onSaveNew = vm::saveNewExercise, onDismiss = { picker = false })
    replaceId?.let { id -> ExercisePickerDialog(exercises, multi = false, title = "Replace exercise", onPick = { vm.replaceExercise(id, it.first()) }, onSaveNew = vm::saveNewExercise, onDismiss = { replaceId = null }) }

    if (finishDialog) {
        val all = setsMap.values.flatten()
        val done = all.count { it.completed }; val open = all.size - done
        if (done == 0) ConfirmDialog("Nothing completed", "No sets are ticked off yet. Discard this workout, or go back and log some sets?", "Discard", true,
            onConfirm = { vm.deleteWorkout { nav.popBackStack() } }, onDismiss = { finishDialog = false })
        else {
            var discard by remember { mutableStateOf(true) }
            AlertDialog(onDismissRequest = { finishDialog = false }, title = { Text("Finish workout?") },
                text = {
                    Column {
                        Text("$done set${if (done == 1) "" else "s"} completed" + if (open > 0) ", $open not completed." else ".")
                        if (open > 0) Row(Modifier.clickable { discard = !discard }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(discard, { discard = it }); Text("Remove the $open unfinished set${if (open == 1) "" else "s"}")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.finish(discard && open > 0); finishDialog = false }) { Text("Finish") } },
                dismissButton = { TextButton(onClick = { finishDialog = false }) { Text("Keep going") } })
        }
    }
    if (deleteDialog) ConfirmDialog(if (inProgress) "Discard workout?" else "Delete workout?",
        if (inProgress) "This unfinished workout and everything logged in it will be removed." else "This permanently deletes the workout, its exercises and all its sets.",
        if (inProgress) "Discard" else "Delete", true, onConfirm = { vm.deleteWorkout { nav.popBackStack() } }, onDismiss = { deleteDialog = false })
    if (detailsDialog && w != null) WorkoutDetailsDialog(w, onSave = { name, date, durMin, notes ->
        vm.updateWorkout { it.copy(name = name, date = date, notes = notes, durationSec = if (it.status == WorkoutStatus.COMPLETED) durMin?.let { m -> (m * 60).roundToInt() } else it.durationSec) }
    }, onDismiss = { detailsDialog = false })
    if (routineDialog) {
        var rn by remember { mutableStateOf(w?.name ?: "") }
        AlertDialog(onDismissRequest = { routineDialog = false }, title = { Text("Save as routine") },
            text = { Column { Text("Creates a reusable template from this workout’s exercises and set counts."); Spacer(Modifier.height(8.dp)); TextF("Routine name", rn, { rn = it }) } },
            confirmButton = { TextButton(enabled = rn.isNotBlank(), onClick = { vm.saveAsRoutine(rn.trim()); routineDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { routineDialog = false }) { Text("Cancel") } })
    }
    summary?.let { s ->
        AlertDialog(onDismissRequest = {}, title = { Text("Workout complete 🎉") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Duration: ${mmss(s.durationSec)}")
                    Text("Exercises: ${s.exercises} · Sets completed: ${s.sets}")
                    if (s.volumeKg > 0) Text("Total volume: ${fmt(Units.kgTo(s.volumeKg, unit), 0)} ${unit.label}")
                    if (s.prs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp)); Text("New personal records", fontWeight = FontWeight.Bold)
                        s.prs.forEach { Text("• ${it.exerciseName}: ${it.kind} ${fmt(Units.kgTo(it.newValue, unit), 1)} ${unit.label} (was ${fmt(Units.kgTo(it.oldValue, unit), 1)})") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.summary.value = null; nav.popBackStack() }) { Text("Done") } })
    }
}

@Composable
private fun WorkoutDetailsDialog(w: WorkoutEntity, onSave: (String, String, Double?, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(w.name) }
    var date by remember { mutableStateOf(w.date) }
    var dur by remember { mutableStateOf(w.durationSec?.let { fmt(it / 60.0, 1) } ?: "") }
    var notes by remember { mutableStateOf(w.notes) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Workout details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextF("Name", name, { name = it })
                DateButton(date, { date = it })
                if (w.status == WorkoutStatus.COMPLETED) NumField("Duration", dur, { dur = it }, suffix = "min", modifier = Modifier.fillMaxWidth())
                TextF("Notes", notes, { notes = it }, singleLine = false)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), date, dur.toD(), notes); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RestTimerBar(t: RestTimer, vm: WorkoutViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.remaining == 0) "Rest over" else "Rest ${mmss(t.remaining)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 6.dp))
            TextButton(onClick = { vm.adjustTimer(-15) }) { Text("−15s") }
            TextButton(onClick = { vm.adjustTimer(15) }) { Text("+15s") }
            IconButton(onClick = { vm.pauseResumeTimer() }) { Icon(if (t.running) Icons.Default.Pause else Icons.Default.PlayArrow, if (t.running) "Pause" else "Start") }
            IconButton(onClick = { vm.resetTimer() }) { Icon(Icons.Default.Refresh, "Reset") }
            IconButton(onClick = { vm.stopTimer() }) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

@Composable
private fun ExerciseCard(
    row: WorkoutExerciseRow, index: Int, count: Int, sets: List<WorkoutSetEntity>, prev: List<WorkoutSetEntity>,
    unit: WeightUnit, vm: WorkoutViewModel, onReplace: () -> Unit, onStats: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var restDialog by remember { mutableStateOf(false) }
    var notesDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    val type = row.exerciseType
    val imperial = unit == WeightUnit.LB
    val group = row.we.supersetGroup
    Card(Modifier.fillMaxWidth(), border = if (group != null) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onStats)) {
                    Text(row.exerciseName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (group != null) Text("Superset ${'A' + ((group - 1) % 26)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    if (row.we.notes.isNotBlank()) Text(row.we.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Exercise options") }
                    DropdownMenu(menu, { menu = false }) {
                        if (index > 0) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }, onClick = { menu = false; vm.moveExercise(row.we.id, -1) })
                        if (index < count - 1) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }, onClick = { menu = false; vm.moveExercise(row.we.id, 1) })
                        DropdownMenuItem(text = { Text("Replace exercise") }, onClick = { menu = false; onReplace() })
                        DropdownMenuItem(text = { Text("Duplicate exercise") }, onClick = { menu = false; vm.duplicateExercise(row.we.id) })
                        if (group != null) DropdownMenuItem(text = { Text("Remove from superset") }, onClick = { menu = false; vm.toggleSuperset(row.we.id) })
                        else if (index < count - 1) DropdownMenuItem(text = { Text("Superset with next") }, onClick = { menu = false; vm.toggleSuperset(row.we.id) })
                        DropdownMenuItem(text = { Text("Rest timer: ${row.we.restSeconds?.let { "${it}s" } ?: "default"}") }, onClick = { menu = false; restDialog = true })
                        DropdownMenuItem(text = { Text("Exercise notes") }, onClick = { menu = false; notesDialog = true })
                        DropdownMenuItem(text = { Text("Remove exercise", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; removeDialog = true })
                    }
                }
            }
            SetHeader(type, unit)
            var workingNo = 0
            sets.forEachIndexed { i, s ->
                val label = if (s.setType == SetType.WARMUP) "W" else { workingNo++; workingNo.toString() }
                key(s.id) { SetRow(s, label, prev.getOrNull(i), type, unit, imperial, row.we.restSeconds, vm) }
            }
            Row {
                TextButton(onClick = { vm.addSet(row.we.id, false) }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Set") }
                TextButton(onClick = { vm.addSet(row.we.id, true) }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Warm-up") }
            }
        }
    }
    if (removeDialog) ConfirmDialog("Remove ${row.exerciseName}?", "Its ${sets.size} set(s) in this workout will be removed. You can undo right after.", "Remove", true, { vm.removeExercise(row.we.id) }, { removeDialog = false })
    if (restDialog) {
        var v by remember { mutableStateOf(row.we.restSeconds?.toString() ?: "") }
        AlertDialog(onDismissRequest = { restDialog = false }, title = { Text("Rest timer for this exercise") },
            text = { NumField("Seconds (blank = app default)", v, { v = it }, decimal = false, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.setRest(row.we.id, v.toIntOrNull()); restDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { restDialog = false }) { Text("Cancel") } })
    }
    if (notesDialog) {
        var v by remember { mutableStateOf(row.we.notes) }
        AlertDialog(onDismissRequest = { notesDialog = false }, title = { Text("Exercise notes") },
            text = { TextF("Notes", v, { v = it }, singleLine = false) },
            confirmButton = { TextButton(onClick = { vm.setExerciseNotes(row.we.id, v.trim()); notesDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { notesDialog = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SetHeader(type: String, unit: WeightUnit) {
    val (a, b) = when (type) {
        ExType.WEIGHT_REPS -> unit.label to "reps"
        ExType.BODYWEIGHT_REPS -> "+${unit.label}" to "reps"
        ExType.DURATION -> "min" to ""
        else -> (if (unit == WeightUnit.LB) "mi" else "km") to "min"
    }
    val st = MaterialTheme.typography.labelSmall
    val col = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("SET", st, color = col, modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
        Text("PREVIOUS", st, color = col, modifier = Modifier.weight(1.3f), textAlign = TextAlign.Center)
        Text(a.uppercase(), st, color = col, modifier = Modifier.weight(if (b.isEmpty()) 2f else 1f), textAlign = TextAlign.Center)
        if (b.isNotEmpty()) Text(b.uppercase(), st, color = col, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Spacer(Modifier.width(44.dp))
    }
}

@Composable
private fun SetRow(s: WorkoutSetEntity, label: String, prev: WorkoutSetEntity?, type: String, unit: WeightUnit, imperial: Boolean, exRest: Int?, vm: WorkoutViewModel) {
    val init = remember(s.id) { initialTexts(type, s, unit) }
    var a by remember(s.id) { mutableStateOf(init.first) }
    var b by remember(s.id) { mutableStateOf(init.second) }
    var menu by remember { mutableStateOf(false) }
    var rpeDialog by remember { mutableStateOf(false) }

    fun commitA(t: String) {
        val d = t.toD()
        when (type) {
            ExType.WEIGHT_REPS, ExType.BODYWEIGHT_REPS -> vm.setWeight(s.id, d?.let { Units.toKg(it, unit) })
            ExType.DURATION -> vm.setDuration(s.id, d?.let { (it * 60).roundToInt() })
            else -> vm.setDistance(s.id, d?.let { Units.toMeters(it, imperial) })
        }
    }
    fun commitB(t: String) {
        when (type) {
            ExType.WEIGHT_REPS, ExType.BODYWEIGHT_REPS -> vm.setReps(s.id, t.toIntOrNull())
            ExType.DISTANCE_DURATION -> vm.setDuration(s.id, t.toD()?.let { (it * 60).roundToInt() })
        }
    }
    val prevText = when {
        prev == null -> "—"
        type == ExType.WEIGHT_REPS || type == ExType.BODYWEIGHT_REPS -> "${prev.weightKg?.let { fmt(Units.kgTo(it, unit), 1) } ?: "–"} × ${prev.reps ?: "–"}"
        type == ExType.DURATION -> "${prev.durationSec?.let { fmt(it / 60.0, 1) } ?: "–"} min"
        else -> "${prev.distanceM?.let { fmt(Units.metersTo(it, imperial), 1) } ?: "–"} · ${prev.durationSec?.let { fmt(it / 60.0, 1) } ?: "–"}m"
    }
    val bg = if (s.completed) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent
    Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(34.dp)) {
            Text(label, Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 10.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                color = if (s.setType == SetType.WARMUP) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (s.setType == SetType.WARMUP) "Make working set" else "Make warm-up set") }, onClick = { menu = false; vm.setType(s.id, if (s.setType == SetType.WARMUP) SetType.WORKING else SetType.WARMUP) })
                DropdownMenuItem(text = { Text("RPE / set notes…") }, onClick = { menu = false; rpeDialog = true })
                DropdownMenuItem(text = { Text("Duplicate set") }, onClick = { menu = false; vm.duplicateSet(s.id) })
                DropdownMenuItem(text = { Text("Delete set", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; vm.removeSet(s.id) })
            }
        }
        Text(prevText, Modifier.weight(1.3f).clickable(enabled = prev != null) {
            val p = initialTexts(type, prev, unit); a = p.first; b = p.second; commitA(a); commitB(b)
        }, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val single = type == ExType.DURATION
        CompactNum(a, { a = it; commitA(it) }, "0", Modifier.weight(if (single) 2f else 1f), allowNegative = type == ExType.BODYWEIGHT_REPS)
        if (!single) CompactNum(b, { b = it; commitB(it) }, "0", Modifier.weight(1f), decimal = type == ExType.DISTANCE_DURATION)
        IconButton(onClick = { vm.toggleComplete(s, exRest) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.CheckCircle, if (s.completed) "Completed" else "Mark complete",
                tint = if (s.completed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)
        }
    }
    if (s.rpe != null || s.notes.isNotBlank()) Text(listOfNotNull(s.rpe?.let { "RPE ${fmt(it, 1)}" }, s.notes.takeIf { it.isNotBlank() }).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp))
    if (rpeDialog) {
        var rpe by remember { mutableStateOf(s.rpe?.let { fmt(it, 1) } ?: "") }
        var notes by remember { mutableStateOf(s.notes) }
        val v = rpe.toD()
        AlertDialog(onDismissRequest = { rpeDialog = false }, title = { Text("Set details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("RPE (1–10, optional)", rpe, { rpe = it }, error = if (v != null && (v < 1 || v > 10)) "Use 1–10" else null, modifier = Modifier.fillMaxWidth())
                    TextF("Notes", notes, { notes = it })
                }
            },
            confirmButton = { TextButton(enabled = v == null || v in 1.0..10.0, onClick = { vm.setRpeNotes(s.id, v, notes.trim()); rpeDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rpeDialog = false }) { Text("Cancel") } })
    }
}
