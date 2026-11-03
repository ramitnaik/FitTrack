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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val keep = SharingStarted.WhileSubscribed(5000)
    private val pid = c.activeProject.map { it?.id }.distinctUntilChanged()
    val inProgress = c.projectFlow<WorkoutEntity?>(null) { c.workouts.observeInProgress(it) }.stateIn(viewModelScope, keep, null)
    val dayEntries = combine(pid, c.selectedDate) { p, d -> p to d }
        .flatMapLatest { (p, d) -> if (p == null) flowOf(emptyList()) else c.nutrition.observeDay(p, d) }.stateIn(viewModelScope, keep, emptyList())
    val dayWorkouts = combine(pid, c.selectedDate) { p, d -> p to d }
        .flatMapLatest { (p, d) -> if (p == null) flowOf(emptyList()) else c.workouts.observeHistory(p, "", d, d, 10) }.stateIn(viewModelScope, keep, emptyList())
    val recent = c.projectFlow(emptyList<WorkoutListItem>()) { c.workouts.observeHistory(it, "", MIN_DATE, MAX_DATE, 3) }.stateIn(viewModelScope, keep, emptyList())
    val weights = c.projectFlow(emptyList<WeightEntryEntity>()) { c.body.observeWeights(it) }.stateIn(viewModelScope, keep, emptyList())

    fun startWorkout(onId: (String) -> Unit) = viewModelScope.launch { c.activeProject.value?.let { onId(c.workouts.startEmpty(it.id)) } }
    fun saveWeight(date: String, kg: Double, notes: String) = viewModelScope.launch { c.activeProject.value?.let { c.body.saveWeight(it.id, null, date, kg, notes) } }
}

@Composable
fun HomeScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { HomeViewModel(c) }
    val project by c.activeProject.collectAsStateWithLifecycle()
    val all by c.allProjects.collectAsStateWithLifecycle()
    val date by c.selectedDate.collectAsStateWithLifecycle()
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val entries by vm.dayEntries.collectAsStateWithLifecycle()
    val dayWorkouts by vm.dayWorkouts.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val meals by c.prefs.mealNames.collectAsStateWithLifecycle()
    var switcher by remember { mutableStateOf(false) }
    var weightDialog by remember { mutableStateOf(false) }
    val p = project
    val wUnit = WeightUnit.of(p?.weightUnit)

    AppScaffold("FitTrack", actions = { IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") } }) { pad ->
        if (all == null) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        if (p == null) { Box(Modifier.padding(pad)) { NoProject { nav.navigate("project/new") } }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Box {
                    Row(Modifier.clickable { switcher = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("ACTIVE PROJECT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(p.name, style = MaterialTheme.typography.headlineSmall)
                        }
                        Icon(Icons.Default.ArrowDropDown, "Switch project")
                    }
                    DropdownMenu(switcher, { switcher = false }) {
                        all.orEmpty().filter { !it.archived }.forEach { x ->
                            DropdownMenuItem(text = { Text(x.name) }, trailingIcon = { if (x.id == p.id) Icon(Icons.Default.Check, null) }, onClick = { switcher = false; c.prefs.setActiveProject(x.id) })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Manage projects") }, onClick = { switcher = false; nav.navigate("projects") })
                    }
                }
            }
            item { DateNavigator(date) { c.selectedDate.value = it } }

            item {
                SectionCard(if (date == Dates.today()) "Today’s workout" else "Workout · ${Dates.pretty(date)}") {
                    if (inProgress != null && date == Dates.today()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("In progress: ${inProgress!!.name}", style = MaterialTheme.typography.titleSmall); Text("Started ${Dates.timeOfDay(inProgress!!.startedAt)}", style = MaterialTheme.typography.bodySmall) }
                            Button(onClick = { nav.navigate("workout/${inProgress!!.id}") }) { Text("Resume") }
                        }
                    } else {
                        if (dayWorkouts.isEmpty()) Text(if (date == Dates.today()) "No workout logged yet today." else "No workout logged on this day.", style = MaterialTheme.typography.bodyMedium)
                        dayWorkouts.forEach { w -> Row(Modifier.fillMaxWidth().clickable { nav.navigate("workout/${w.workout.id}") }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("✓ ${w.workout.name}", color = MaterialTheme.colorScheme.secondary); Text("${w.setCount} sets", style = MaterialTheme.typography.bodySmall) } }
                        if (date == Dates.today()) Button(onClick = { vm.startWorkout { nav.navigate("workout/$it") } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start workout") }
                        else OutlinedButton(onClick = { nav.navigate("workouts") }, modifier = Modifier.fillMaxWidth()) { Text("Workouts") }
                    }
                }
            }

            item { NutritionSummaryCard(NutritionCalc.sum(entries.map { it.nutrients() }), p) }
            item {
                Button(onClick = { c.logMeal.value = meals.firstOrNull() ?: "Breakfast"; nav.navigate("logfood") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Log food for ${if (date == Dates.today()) "today" else Dates.pretty(date)}")
                }
            }

            item {
                SectionCard("Body weight", trailing = { TextButton(onClick = { weightDialog = true }) { Text("Log") } }) {
                    if (weights.isEmpty()) Text("No weight logged in this project yet.", style = MaterialTheme.typography.bodyMedium)
                    else {
                        val latest = weights.last()
                        Text("${fmt(Units.kgTo(latest.weightKg, wUnit), 1)} ${wUnit.label}", style = MaterialTheme.typography.headlineSmall)
                        Text("on ${Dates.pretty(latest.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (weights.size > 1) LineChart(weights.takeLast(30).map { ChartPoint(it.date, Units.kgTo(it.weightKg, wUnit)) }, height = 90.dp, unit = wUnit.label)
                    }
                }
            }

            item { Text("Recent workouts", style = MaterialTheme.typography.titleMedium) }
            if (recent.isEmpty()) item { Text("Completed workouts will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(recent, key = { it.workout.id }) { WorkoutCard(it, wUnit) { nav.navigate("workout/${it.workout.id}") } }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { c.progressTab.value = 2; nav.navigate("progress") }, label = { Text("Measurements") }, leadingIcon = { Icon(Icons.Default.Straighten, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { nav.navigate("history") }, label = { Text("History") }, leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) })
                    AssistChip(onClick = { nav.navigate("foods") }, label = { Text("Foods") }, leadingIcon = { Icon(Icons.Default.Fastfood, null, Modifier.size(18.dp)) })
                }
            }
        }
    }
    if (weightDialog) WeightDialog(wUnit, Dates.today(), weights.lastOrNull()?.weightKg, "", "Log weight", { d, kg, n -> vm.saveWeight(d, kg, n) }, { weightDialog = false })
}
