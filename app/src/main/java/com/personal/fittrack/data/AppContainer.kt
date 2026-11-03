package com.personal.fittrack.data

import android.app.Application
import com.personal.fittrack.domain.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Manual dependency container (one per process). */
class AppContainer(app: Application) {
    val db: FitDatabase = FitDatabase.build(app)
    val prefs = Prefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val projects = ProjectRepository(db)
    val workouts = WorkoutRepository(db)
    val nutrition = NutritionRepository(db)
    val body = BodyRepository(db)
    val backup = BackupRepository(app, db, prefs)
    val csv = CsvExporter(db)

    /** null until the first database read completes (UI shows a loader, not a false "no projects" state). */
    val allProjects: StateFlow<List<ProjectEntity>?> =
        projects.observeAll().map<List<ProjectEntity>, List<ProjectEntity>?> { it }.stateIn(scope, SharingStarted.Eagerly, null)

    val activeProject: StateFlow<ProjectEntity?> =
        combine(prefs.activeProjectId, allProjects) { id, list ->
            list?.firstOrNull { it.id == id && !it.archived } ?: list?.firstOrNull { !it.archived }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Date shown on Home and Nutrition. */
    val selectedDate = MutableStateFlow(Dates.today())
    /** Meal the next "add food" screen logs into. */
    val logMeal = MutableStateFlow("Breakfast")
    /** Which Progress sub-tab is showing (0 workouts, 1 nutrition, 2 body). */
    val progressTab = MutableStateFlow(0)

    init { scope.launch(Dispatchers.IO) { Seeds.ensure(db, prefs) } }

    fun <T> projectFlow(empty: T, f: (String) -> Flow<T>): Flow<T> =
        activeProject.map { it?.id }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(empty) else f(id) }
}
