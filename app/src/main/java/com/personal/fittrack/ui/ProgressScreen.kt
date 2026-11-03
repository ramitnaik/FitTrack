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

class ProgressViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> pf(empty: T, f: (String) -> Flow<T>) = c.projectFlow(empty, f).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), empty)
    val total = pf(0) { c.workouts.observeCompletedCount(it) }
    val last30 = pf(0) { c.workouts.observeCountBetween(it, Dates.shift(Dates.today(), -29), Dates.today()) }
    val weekly = pf(emptyList<PeriodCount>()) { c.workouts.observeWeeklyCounts(it, 12) }
    val durations = pf(emptyList<DurationPoint>()) { c.workouts.observeDurations(it, 50) }
    val frequent = pf(emptyList<FrequentExercise>()) { c.workouts.observeFrequent(it, 8) }
    val nutritionLimit = MutableStateFlow(30)
    val dayTotals = nutritionLimit.flatMapLatest { l -> c.projectFlow(emptyList<DayTotal>()) { c.nutrition.observeDayTotals(it, l) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val weights = pf(emptyList<WeightEntryEntity>()) { c.body.observeWeights(it) }
    val measurements = pf(emptyList<MeasurementRow>()) { c.body.observeMeasurements(it) }
    val defs = c.body.observeActiveDefs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    fun saveWeight(id: String?, date: String, kg: Double, notes: String) = viewModelScope.launch { c.activeProject.value?.let { c.body.saveWeight(it.id, id, date, kg, notes) } }
    fun deleteWeight(id: String) = viewModelScope.launch {
        c.body.deleteWeight(id)?.let { w -> messages.emit(UiMessage("Weight entry deleted", "Undo") { c.body.restoreWeight(w) }) }
    }
    fun saveMeasurements(date: String, values: Map<String, Double>, notes: String) = viewModelScope.launch { c.activeProject.value?.let { c.body.saveMeasurements(it.id, date, values, notes) } }
    fun updateMeasurement(m: MeasurementEntryEntity) = viewModelScope.launch { c.body.updateMeasurement(m) }
    fun deleteMeasurement(id: String) = viewModelScope.launch {
        c.body.deleteMeasurement(id)?.let { m -> messages.emit(UiMessage("Measurement deleted", "Undo") { c.body.restoreMeasurement(m) }) }
    }
    fun addDef(name: String, kind: String) = viewModelScope.launch { c.body.addDef(name, kind) }
    fun setHeight(cm: Double?) = viewModelScope.launch { c.activeProject.value?.let { c.projects.save(it.copy(heightCm = cm)) } }
}

@Composable
fun ProgressScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { ProgressViewModel(c) }
    val project by c.activeProject.collectAsStateWithLifecycle()
    val tab by c.progressTab.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    AppScaffold("Progress", snackbar = snackbar) { pad ->
        val p = project
        if (p == null) { NoProject { nav.navigate("project/new") }; return@AppScaffold }
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Workouts", "Nutrition", "Body").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { c.progressTab.value = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> WorkoutProgress(vm, p, nav)
                1 -> NutritionProgress(vm, p, nav)
                else -> BodyProgress(vm, p)
            }
        }
    }
}

@Composable
private fun WorkoutProgress(vm: ProgressViewModel, p: ProjectEntity, nav: NavController) {
    val total by vm.total.collectAsStateWithLifecycle()
    val last30 by vm.last30.collectAsStateWithLifecycle()
    val weekly by vm.weekly.collectAsStateWithLifecycle()
    val durations by vm.durations.collectAsStateWithLifecycle()
    val frequent by vm.frequent.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Workouts completed", total.toString(), Modifier.weight(1f))
                StatTile("Last 30 days", last30.toString(), Modifier.weight(1f))
                p.weeklyWorkoutGoal?.let { StatTile("Weekly goal", it.toString(), Modifier.weight(1f)) }
            }
        }
        if (total == 0) item { EmptyState(Icons.Default.FitnessCenter, "No completed workouts yet", "Finish a workout in “${p.name}” and your frequency, duration and records appear here.") }
        if (weekly.isNotEmpty()) item {
            SectionCard("Workouts per week (last ${weekly.size} active weeks)") {
                BarChart(weekly.reversed().map { ChartPoint(it.period, it.count.toDouble()) }, targetLine = p.weeklyWorkoutGoal?.toDouble())
                Text("Weeks with no workouts are not listed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (durations.isNotEmpty()) item {
            SectionCard("Workout duration (min, latest ${durations.size})") {
                LineChart(durations.reversed().map { ChartPoint(it.date, it.durationSec / 60.0) }, unit = "min")
                Text("Average ${fmt(durations.map { it.durationSec }.average() / 60.0, 0)} min", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (frequent.isNotEmpty()) item {
            SectionCard("Most performed exercises") {
                frequent.forEach { f -> Row(Modifier.fillMaxWidth().clickable { nav.navigate("exercise/${f.exerciseId}") }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(f.name); Text("${f.workouts}×", color = MaterialTheme.colorScheme.primary) } }
                TextButton(onClick = { nav.navigate("exercises") }) { Text("All exercises & records") }
            }
        }
    }
}

@Composable
private fun NutritionProgress(vm: ProgressViewModel, p: ProjectEntity, nav: NavController) {
    val c = LocalContainer.current
    val days by vm.dayTotals.collectAsStateWithLifecycle()
    val limit by vm.nutritionLimit.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (days.isEmpty()) item { EmptyState(Icons.Default.Restaurant, "No food logged yet", "Log meals in the Nutrition tab to see daily trends for “${p.name}”.") }
        else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Avg kcal (${days.size} logged days)", fmt(days.map { it.kcal }.average(), 0), Modifier.weight(1f))
                    StatTile("Avg protein", fmt(days.map { it.protein }.average(), 0) + " g", Modifier.weight(1f))
                }
            }
            item { SectionCard("Calories per logged day") { BarChart(days.reversed().map { ChartPoint(it.date, it.kcal) }, targetLine = p.targetKcal?.toDouble()) } }
            item { SectionCard("Protein per logged day (g)") { LineChart(days.reversed().map { ChartPoint(it.date, it.protein) }, unit = "g", targetLine = p.targetProtein?.toDouble()) } }
            item { Text("Days", style = MaterialTheme.typography.titleMedium) }
            items(days, key = { it.date }) { d ->
              Column {
                Row(Modifier.fillMaxWidth().clickable { c.selectedDate.value = d.date; nav.navigate("nutrition") }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Dates.pretty(d.date))
                    Text("${fmt(d.kcal, 0)} kcal · P ${fmt(d.protein, 0)} C ${fmt(d.carbs, 0)} F ${fmt(d.fat, 0)}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
              }
            }
            if (days.size >= limit) item { OutlinedButton(onClick = { vm.nutritionLimit.value = limit + 30 }, modifier = Modifier.fillMaxWidth()) { Text("Load more days") } }
        }
    }
}

@Composable
private fun BodyProgress(vm: ProgressViewModel, p: ProjectEntity) {
    val weights by vm.weights.collectAsStateWithLifecycle()
    val meas by vm.measurements.collectAsStateWithLifecycle()
    val defs by vm.defs.collectAsStateWithLifecycle()
    val wUnit = WeightUnit.of(p.weightUnit); val lUnit = LengthUnit.of(p.lengthUnit)
    var weightDialog by remember { mutableStateOf<WeightEntryEntity?>(null) }
    var newWeight by remember { mutableStateOf(false) }
    var measDialog by remember { mutableStateOf(false) }
    var heightDialog by remember { mutableStateOf(false) }
    var selectedDef by remember { mutableStateOf<String?>(null) }
    var editMeas by remember { mutableStateOf<MeasurementRow?>(null) }
    val withData = defs.filter { d -> meas.any { it.entry.defId == d.id } }
    val sel = defs.firstOrNull { it.id == selectedDef } ?: withData.firstOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard("Body weight", trailing = { Button(onClick = { newWeight = true }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Log") } }) {
                if (weights.isEmpty()) Text("No weight entries in this project yet. Weigh in whenever you like — daily entries aren’t required.", style = MaterialTheme.typography.bodyMedium)
                else {
                    val latest = weights.last(); val first = weights.first()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column { Text("${fmt(Units.kgTo(latest.weightKg, wUnit), 1)} ${wUnit.label}", style = MaterialTheme.typography.headlineSmall); Text("Latest · ${Dates.pretty(latest.date)}", style = MaterialTheme.typography.bodySmall) }
                        if (weights.size > 1) {
                            val diff = Units.kgTo(latest.weightKg - first.weightKg, wUnit)
                            Text("${if (diff >= 0) "+" else ""}${fmt(diff, 1)} ${wUnit.label} since start (${fmt(Units.kgTo(first.weightKg, wUnit), 1)})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    LineChart(weights.map { ChartPoint(it.date, Units.kgTo(it.weightKg, wUnit)) }, unit = wUnit.label)
                }
            }
        }
        items(weights.reversed(), key = { it.id }) { w ->
          Column {
            Row(Modifier.fillMaxWidth().clickable { weightDialog = w }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text(Dates.pretty(w.date)); if (w.notes.isNotBlank()) Text(w.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("${fmt(Units.kgTo(w.weightKg, wUnit), 1)} ${wUnit.label}", style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider()
          }
        }
        item {
            SectionCard("Height", trailing = { TextButton(onClick = { heightDialog = true }) { Text(if (p.heightCm == null) "Set" else "Edit") } }) {
                Text(p.heightCm?.let { "${fmt(Units.cmTo(it, lUnit), 1)} ${lUnit.label}" } ?: "Not set (optional)")
            }
        }
        item {
            SectionCard("Body measurements", trailing = { Button(onClick = { measDialog = true }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Log") } }) {
                if (meas.isEmpty()) Text("Log only the measurements you care about — each can be entered on a different day. Missing ones are simply left out.", style = MaterialTheme.typography.bodyMedium)
                else {
                    withData.forEach { d ->
                        val rows = meas.filter { it.entry.defId == d.id }
                        val latest = rows.last(); val first = rows.first()
                        val disp = { v: Double -> if (d.kind == "PERCENT") "${fmt(v, 1)} %" else "${fmt(Units.cmTo(v, lUnit), 1)} ${lUnit.label}" }
                        Row(Modifier.fillMaxWidth().clickable { selectedDef = d.id }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(d.name, color = if (sel?.id == d.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(disp(latest.entry.value) + if (rows.size > 1) "  (" + (if (latest.entry.value - first.entry.value >= 0) "+" else "") +
                                (if (d.kind == "PERCENT") fmt(latest.entry.value - first.entry.value, 1) else fmt(Units.cmTo(latest.entry.value - first.entry.value, lUnit), 1)) + ")" else "")
                        }
                    }
                }
            }
        }
        if (sel != null) {
            val rows = meas.filter { it.entry.defId == sel.id }
            if (rows.isNotEmpty()) {
                val conv = { v: Double -> if (sel.kind == "PERCENT") v else Units.cmTo(v, lUnit) }
                val unitL = if (sel.kind == "PERCENT") "%" else lUnit.label
                item { SectionCard("${sel.name} over time") { LineChart(rows.map { ChartPoint(it.entry.date, conv(it.entry.value)) }, unit = unitL) } }
                items(rows.reversed(), key = { it.entry.id }) { r ->
                  Column {
                    Row(Modifier.fillMaxWidth().clickable { editMeas = r }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text(Dates.pretty(r.entry.date)); if (r.entry.notes.isNotBlank()) Text(r.entry.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("${fmt(conv(r.entry.value), 1)} $unitL", style = MaterialTheme.typography.titleSmall)
                    }
                    HorizontalDivider()
                  }
                }
            }
        }
    }

    if (newWeight) WeightDialog(wUnit, Dates.today(), weights.lastOrNull()?.weightKg, "", "Log weight", { d, kg, n -> vm.saveWeight(null, d, kg, n) }, { newWeight = false })
    weightDialog?.let { w -> WeightDialog(wUnit, w.date, w.weightKg, w.notes, "Edit weight", { d, kg, n -> vm.saveWeight(w.id, d, kg, n) }, { weightDialog = null }, onDelete = { vm.deleteWeight(w.id) }) }
    if (measDialog) MeasurementsDialog(defs, lUnit, { d, v, n -> vm.saveMeasurements(d, v, n) }, { name, kind -> vm.addDef(name, kind) }, { measDialog = false })
    editMeas?.let { r -> MeasurementEditDialog(r, lUnit, { vm.updateMeasurement(it) }, { vm.deleteMeasurement(r.entry.id) }, { editMeas = null }) }
    if (heightDialog) {
        var v by remember { mutableStateOf(p.heightCm?.let { fmt(Units.cmTo(it, lUnit), 1) } ?: "") }
        AlertDialog(onDismissRequest = { heightDialog = false }, title = { Text("Height") },
            text = { NumField("Height", v, { v = it }, Modifier.fillMaxWidth(), suffix = lUnit.label) },
            confirmButton = { TextButton(onClick = { vm.setHeight(v.toD()?.takeIf { it > 0 }?.let { Units.toCm(it, lUnit) }); heightDialog = false }) { Text(if (v.isBlank()) "Clear" else "Save") } },
            dismissButton = { TextButton(onClick = { heightDialog = false }) { Text("Cancel") } })
    }
}

@Composable
private fun MeasurementsDialog(defs: List<MeasurementDefEntity>, lUnit: LengthUnit, onSave: (String, Map<String, Double>, String) -> Unit, onNewDef: (String, String) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(Dates.today()) }
    val values = remember { mutableStateMapOf<String, String>() }
    var notes by remember { mutableStateOf("") }
    var newDef by remember { mutableStateOf(false) }
    val parsed = values.mapNotNull { (id, t) -> t.toD()?.takeIf { it > 0 }?.let { id to it } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Log measurements") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { DateButton(date, { date = it }, Modifier.fillMaxWidth()) }
                item { Text("Fill in only what you measured today; leave the rest blank.", style = MaterialTheme.typography.bodySmall) }
                items(defs, key = { it.id }) { d ->
                    NumField(d.name, values[d.id] ?: "", { values[d.id] = it }, Modifier.fillMaxWidth(), suffix = if (d.kind == "PERCENT") "%" else lUnit.label)
                }
                item { TextButton(onClick = { newDef = true }) { Text("+ New measurement type") } }
                item { TextF("Notes (optional)", notes, { notes = it }) }
            }
        },
        confirmButton = { TextButton(enabled = parsed.isNotEmpty(), onClick = {
            onSave(date, parsed.associate { (id, v) -> id to (if (defs.first { it.id == id }.kind == "PERCENT") v else Units.toCm(v, lUnit)) }, notes.trim()); onDismiss() }) { Text("Save ${parsed.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (newDef) NewMeasurementDefDialog(onNewDef) { newDef = false }
}

@Composable
fun NewMeasurementDefDialog(onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("LENGTH") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New measurement type") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { TextF("Name (e.g. Upper chest)", name, { name = it }); DropdownField("Type", listOf("LENGTH", "PERCENT"), kind, { if (it == "LENGTH") "Length (cm / in)" else "Percentage" }, { kind = it }) } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), kind); onDismiss() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun MeasurementEditDialog(r: MeasurementRow, lUnit: LengthUnit, onSave: (MeasurementEntryEntity) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val pct = r.kind == "PERCENT"
    var date by remember { mutableStateOf(r.entry.date) }
    var v by remember { mutableStateOf(fmt(if (pct) r.entry.value else Units.cmTo(r.entry.value, lUnit), 2)) }
    var notes by remember { mutableStateOf(r.entry.notes) }
    val d = v.toD()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit ${r.defName}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { DateButton(date, { date = it }); NumField("Value", v, { v = it }, Modifier.fillMaxWidth(), suffix = if (pct) "%" else lUnit.label); TextF("Notes", notes, { notes = it }) } },
        confirmButton = { TextButton(enabled = d != null && d > 0, onClick = { onSave(r.entry.copy(date = date, value = if (pct) d!! else Units.toCm(d!!, lUnit), notes = notes.trim())); onDismiss() }) { Text("Save") } },
        dismissButton = { Row { TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text("Cancel") } } })
}
