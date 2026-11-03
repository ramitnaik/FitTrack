package com.personal.fittrack.data

import androidx.room.withTransaction
import com.personal.fittrack.domain.*
import kotlinx.coroutines.flow.Flow

// ======================= Projects =======================

class ProjectRepository(private val db: FitDatabase) {
    private val dao = db.projectDao()
    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()
    fun observe(id: String): Flow<ProjectEntity?> = dao.observe(id)
    suspend fun get(id: String) = dao.get(id)

    suspend fun save(p: ProjectEntity) = dao.upsert(p.copy(updatedAt = nowMs()))

    suspend fun create(p: ProjectEntity): ProjectEntity {
        val now = nowMs()
        val e = p.copy(id = if (p.id.isBlank()) newId() else p.id, createdAt = now, updatedAt = now)
        dao.upsert(e); return e
    }

    /** Copies settings/targets only. Logged data is never copied between projects. */
    suspend fun duplicate(id: String): ProjectEntity? {
        val src = dao.get(id) ?: return null
        return create(src.copy(id = newId(), name = src.name + " (copy)", archived = false))
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        val p = dao.get(id) ?: return
        dao.upsert(p.copy(archived = archived, updatedAt = nowMs()))
    }

    suspend fun delete(id: String) = dao.delete(id)

    data class Counts(val workouts: Int, val foodEntries: Int, val weights: Int)
    suspend fun counts(id: String) = Counts(dao.workoutCount(id), dao.foodEntryCount(id), dao.weightCount(id))
}

// ======================= Workouts =======================

data class WorkoutSnapshot(val workout: WorkoutEntity, val exercises: List<WorkoutExerciseEntity>, val sets: List<WorkoutSetEntity>)
data class PrInfo(val exerciseName: String, val kind: String, val newValue: Double, val oldValue: Double)
data class WorkoutSummary(val durationSec: Int, val exercises: Int, val sets: Int, val volumeKg: Double, val prs: List<PrInfo>)

class WorkoutRepository(private val db: FitDatabase) {
    private val dao = db.workoutDao()
    private val exDao = db.exerciseDao()
    private val routineDao = db.routineDao()

    // ----- observation -----
    fun observeWorkout(id: String) = dao.observe(id)
    fun observeRows(id: String) = dao.observeExerciseRows(id)
    fun observeSets(id: String) = dao.observeSets(id)
    fun observeInProgress(projectId: String) = dao.observeInProgress(projectId)
    fun observeHistory(projectId: String, q: String, from: String, to: String, limit: Int) =
        dao.observeHistory(projectId, q.trim(), from, to, limit)
    fun observeActiveExercises() = exDao.observeActive()
    fun observeAllExercises() = exDao.observeAll()
    fun observeExercise(id: String) = exDao.observe(id)
    fun observeRoutines() = routineDao.observeAll()
    fun observeRoutine(id: String) = routineDao.observe(id)
    fun observeRoutineRows(id: String) = routineDao.observeRows(id)
    fun observeRoutineCount(id: String) = routineDao.observeCount(id)
    fun observeExerciseSets(projectId: String, exerciseId: String) = dao.observeExerciseSets(projectId, exerciseId)
    fun observeCompletedCount(p: String) = dao.observeCompletedCount(p)
    fun observeCountBetween(p: String, from: String, to: String) = dao.observeCountBetween(p, from, to)
    fun observeWeeklyCounts(p: String, limit: Int) = dao.observeWeeklyCounts(p, limit)
    fun observeDurations(p: String, limit: Int) = dao.observeDurations(p, limit)
    fun observeFrequent(p: String, limit: Int) = dao.observeFrequentExercises(p, limit)

    // ----- exercise catalog -----
    suspend fun saveExercise(e: ExerciseEntity): ExerciseEntity {
        val saved = if (e.id.isBlank()) e.copy(id = newId(), createdAt = nowMs()) else e
        exDao.upsert(saved); return saved
    }

    suspend fun duplicateExercise(id: String): ExerciseEntity? {
        val e = exDao.get(id) ?: return null
        return saveExercise(e.copy(id = "", name = e.name + " (copy)", isBuiltIn = false, archived = false))
    }

    suspend fun setExerciseArchived(id: String, archived: Boolean) {
        val e = exDao.get(id) ?: return
        exDao.upsert(e.copy(archived = archived))
    }

    suspend fun setTrackStats(id: String, v: Boolean) = exDao.setTrackStats(id, v)

    /** Returns true if removed, false if archived instead (history keeps pointing at it). */
    suspend fun deleteOrArchiveExercise(id: String): Boolean {
        val e = exDao.get(id) ?: return true
        return if (!e.isBuiltIn && exDao.usageCount(id) == 0) { exDao.delete(id); true }
        else { exDao.upsert(e.copy(archived = true)); false }
    }

    // ----- starting workouts -----
    private fun defaultName(): String = when (Dates.hourOfDay(nowMs())) {
        in 5..11 -> "Morning Workout"; in 12..16 -> "Afternoon Workout"; in 17..21 -> "Evening Workout"; else -> "Night Workout"
    }

    suspend fun startEmpty(projectId: String): String {
        val id = newId()
        val now = nowMs()
        dao.upsert(WorkoutEntity(id, projectId, null, defaultName(), Dates.today(), now))
        return id
    }

    /** A completed, editable workout on a past date (for logging missed sessions). */
    suspend fun createPast(projectId: String, date: String): String {
        val id = newId()
        dao.upsert(WorkoutEntity(id, projectId, null, "Workout", date, Dates.noonOf(date), Dates.noonOf(date), null, WorkoutStatus.COMPLETED))
        return id
    }

    suspend fun startFromRoutine(projectId: String, routineId: String): String = db.withTransaction {
        val routine = routineDao.get(routineId)
        val id = newId()
        dao.upsert(WorkoutEntity(id, projectId, routineId, routine?.name ?: defaultName(), Dates.today(), nowMs()))
        for (row in routineDao.getRows(routineId)) {
            val weId = newId()
            dao.upsertWorkoutExercise(WorkoutExerciseEntity(weId, id, row.exerciseId, row.position, null, row.restSeconds, row.notes))
            val prev = previousSets(projectId, row.exerciseId, id)
            val sets = (0 until row.targetSets.coerceAtLeast(1)).map { i ->
                val p = prev.getOrNull(i)
                WorkoutSetEntity(newId(), weId, i, SetType.WORKING, p?.reps ?: row.targetReps, p?.weightKg, p?.durationSec, p?.distanceM)
            }
            dao.upsertSets(sets)
        }
        id
    }

    /** Repeat: new in-progress workout with the same exercises and values, nothing ticked off. */
    suspend fun startFromWorkout(sourceId: String): String? = db.withTransaction {
        val src = dao.get(sourceId) ?: return@withTransaction null
        val id = newId()
        dao.upsert(WorkoutEntity(id, src.projectId, src.routineId, src.name, Dates.today(), nowMs()))
        for (we in dao.getExercises(sourceId)) {
            val weId = newId()
            dao.upsertWorkoutExercise(we.copy(id = weId, workoutId = id))
            dao.upsertSets(dao.getSets(we.id).map { it.copy(id = newId(), workoutExerciseId = weId, completed = false) })
        }
        id
    }

    suspend fun updateWorkout(w: WorkoutEntity) {
        val old = dao.get(w.id)
        var updated = w
        if (old != null && old.date != w.date) updated = w.copy(startedAt = Dates.moveToDate(old.startedAt, w.date),
            endedAt = old.endedAt?.let { Dates.moveToDate(it, w.date) })
        dao.upsert(updated)
    }

    suspend fun previousSets(projectId: String, exerciseId: String, excludeWorkoutId: String): List<WorkoutSetEntity> {
        val wid = dao.lastWorkoutWith(projectId, exerciseId, excludeWorkoutId) ?: return emptyList()
        return dao.setsOfExerciseInWorkout(wid, exerciseId)
    }

    // ----- exercises inside a workout -----
    suspend fun addExercises(workoutId: String, exerciseIds: List<String>) = db.withTransaction {
        val w = dao.get(workoutId) ?: return@withTransaction
        var pos = dao.maxExercisePosition(workoutId) + 1
        for (exId in exerciseIds) {
            val weId = newId()
            dao.upsertWorkoutExercise(WorkoutExerciseEntity(weId, workoutId, exId, pos++))
            val p = previousSets(w.projectId, exId, workoutId)
            val count = p.size.coerceIn(1, 4)
            dao.upsertSets((0 until count).map { i ->
                val s = p.getOrNull(i)
                WorkoutSetEntity(newId(), weId, i, SetType.WORKING, s?.reps, s?.weightKg, s?.durationSec, s?.distanceM)
            })
        }
    }

    suspend fun replaceExercise(weId: String, exerciseId: String) = dao.replaceExercise(weId, exerciseId)

    suspend fun snapshotExercise(weId: String): Pair<WorkoutExerciseEntity, List<WorkoutSetEntity>>? {
        val we = dao.getWorkoutExercise(weId) ?: return null
        return we to dao.getSets(weId)
    }

    suspend fun removeExercise(weId: String) = db.withTransaction {
        val we = dao.getWorkoutExercise(weId) ?: return@withTransaction
        dao.deleteWorkoutExercise(weId)
        renumber(we.workoutId)
    }

    suspend fun restoreExercise(we: WorkoutExerciseEntity, sets: List<WorkoutSetEntity>) = db.withTransaction {
        dao.upsertWorkoutExercise(we); dao.upsertSets(sets)
        val list = dao.getExercises(we.workoutId).toMutableList()
        list.sortBy { it.position }
        dao.upsertWorkoutExercises(list.mapIndexed { i, e -> e.copy(position = i) })
    }

    private suspend fun renumber(workoutId: String) {
        dao.upsertWorkoutExercises(dao.getExercises(workoutId).mapIndexed { i, e -> e.copy(position = i) })
    }

    suspend fun moveExercise(weId: String, delta: Int) = db.withTransaction {
        val we = dao.getWorkoutExercise(weId) ?: return@withTransaction
        val list = dao.getExercises(we.workoutId).toMutableList()
        val i = list.indexOfFirst { it.id == weId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@withTransaction
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        dao.upsertWorkoutExercises(list.mapIndexed { idx, e -> e.copy(position = idx) })
    }

    suspend fun duplicateExercise(weId: String) = db.withTransaction {
        val we = dao.getWorkoutExercise(weId) ?: return@withTransaction
        val newWe = we.copy(id = newId(), position = we.position + 1, supersetGroup = null)
        val list = dao.getExercises(we.workoutId).toMutableList()
        list.add(list.indexOfFirst { it.id == weId } + 1, newWe)
        dao.upsertWorkoutExercises(list.mapIndexed { idx, e -> e.copy(position = idx) })
        dao.upsertSets(dao.getSets(weId).map { it.copy(id = newId(), workoutExerciseId = newWe.id, completed = false) })
    }

    /** Link this exercise with the one after it into a superset (or unlink it). */
    suspend fun toggleSuperset(weId: String) = db.withTransaction {
        val we = dao.getWorkoutExercise(weId) ?: return@withTransaction
        val list = dao.getExercises(we.workoutId)
        val i = list.indexOfFirst { it.id == weId }
        if (we.supersetGroup != null) {
            dao.upsertWorkoutExercise(we.copy(supersetGroup = null))
            return@withTransaction
        }
        val next = list.getOrNull(i + 1) ?: return@withTransaction
        val group = next.supersetGroup ?: ((list.mapNotNull { it.supersetGroup }.maxOrNull() ?: 0) + 1)
        dao.upsertWorkoutExercise(we.copy(supersetGroup = group))
        dao.upsertWorkoutExercise(next.copy(supersetGroup = group))
    }

    suspend fun setRest(weId: String, seconds: Int?) = dao.setRest(weId, seconds)
    suspend fun setExerciseNotes(weId: String, notes: String) = dao.setExerciseNotes(weId, notes)

    // ----- sets -----
    suspend fun addSet(weId: String, warmup: Boolean, completed: Boolean = false): WorkoutSetEntity {
        val sets = dao.getSets(weId)
        val last = sets.lastOrNull { it.setType == SetType.WORKING } ?: sets.lastOrNull()
        val s = WorkoutSetEntity(newId(), weId, (sets.maxOfOrNull { it.position } ?: -1) + 1,
            if (warmup) SetType.WARMUP else SetType.WORKING, last?.reps, last?.weightKg, last?.durationSec, last?.distanceM, completed = completed)
        dao.upsertSet(s); return s
    }

    suspend fun duplicateSet(setId: String) = db.withTransaction {
        val s = dao.getSet(setId) ?: return@withTransaction
        val all = dao.getSets(s.workoutExerciseId).toMutableList()
        all.add(all.indexOfFirst { it.id == setId } + 1, s.copy(id = newId(), completed = false))
        dao.upsertSets(all.mapIndexed { i, x -> x.copy(position = i) })
    }

    suspend fun deleteSet(setId: String): WorkoutSetEntity? {
        val s = dao.getSet(setId) ?: return null
        dao.deleteSet(setId); return s
    }

    suspend fun restoreSet(s: WorkoutSetEntity) = dao.upsertSet(s)

    suspend fun setWeight(id: String, kg: Double?) = dao.setWeight(id, kg)
    suspend fun setReps(id: String, v: Int?) = dao.setReps(id, v)
    suspend fun setDuration(id: String, v: Int?) = dao.setDuration(id, v)
    suspend fun setDistance(id: String, v: Double?) = dao.setDistance(id, v)
    suspend fun setCompleted(id: String, v: Boolean) = dao.setCompleted(id, v)
    suspend fun setSetType(id: String, v: String) = dao.setSetType(id, v)
    suspend fun setRpeNotes(id: String, rpe: Double?, notes: String) = dao.setRpeNotes(id, rpe, notes)

    // ----- finishing / deleting -----
    suspend fun finish(workoutId: String, discardIncomplete: Boolean) = db.withTransaction {
        val w = dao.get(workoutId) ?: return@withTransaction
        if (discardIncomplete) { dao.deleteIncompleteSets(workoutId); dao.deleteEmptyExercises(workoutId); renumber(workoutId) }
        val end = nowMs()
        dao.upsert(w.copy(status = WorkoutStatus.COMPLETED, endedAt = end, durationSec = ((end - w.startedAt) / 1000).toInt().coerceAtLeast(0)))
    }

    suspend fun snapshotWorkout(id: String): WorkoutSnapshot? {
        val w = dao.get(id) ?: return null
        val ex = dao.getExercises(id)
        return WorkoutSnapshot(w, ex, ex.flatMap { dao.getSets(it.id) })
    }

    suspend fun restoreWorkout(s: WorkoutSnapshot) = db.withTransaction {
        dao.upsert(s.workout); dao.upsertWorkoutExercises(s.exercises); dao.upsertSets(s.sets)
    }

    suspend fun deleteWorkout(id: String) = dao.delete(id)

    suspend fun saveAsRoutine(workoutId: String, name: String): String = db.withTransaction {
        val rid = newId(); val now = nowMs()
        routineDao.upsert(RoutineEntity(rid, name.ifBlank { "Routine" }, "", now, now))
        for (we in dao.getExercises(workoutId)) {
            val sets = dao.getSets(we.id).filter { it.setType == SetType.WORKING }
            routineDao.upsertRow(RoutineExerciseEntity(newId(), rid, we.exerciseId, we.position, sets.size.coerceAtLeast(1),
                sets.firstOrNull()?.reps, we.restSeconds, ""))
        }
        rid
    }

    suspend fun summary(workoutId: String): WorkoutSummary? {
        val w = dao.get(workoutId) ?: return null
        val rows = dao.getExercises(workoutId)
        var sets = 0; var vol = 0.0
        val prs = mutableListOf<PrInfo>()
        for (we in rows) {
            val ex = exDao.get(we.exerciseId) ?: continue
            val done = dao.getSets(we.id).filter { it.completed }
            sets += done.size
            done.filter { it.setType == SetType.WORKING }.forEach { vol += StatsCalc.setVolume(ex.type, it.weightKg, it.reps) }
            if (ex.trackStats && ex.type == ExType.WEIGHT_REPS) {
                val all = dao.exerciseSetsOnce(w.projectId, we.exerciseId).map {
                    SetPoint(it.date, it.workoutId, it.set.setType, it.set.reps, it.set.weightKg, it.set.durationSec, it.set.distanceM)
                }
                val sessions = StatsCalc.sessions(ex.type, all)
                val cur = sessions.firstOrNull { it.workoutId == workoutId }
                val earlier = sessions.filter { it.workoutId != workoutId }
                if (cur != null && earlier.isNotEmpty()) {
                    val ce = cur.bestE1rm; val oe = earlier.mapNotNull { it.bestE1rm }.maxOrNull()
                    if (ce != null && oe != null && ce > oe) prs += PrInfo(ex.name, "Est. 1RM", ce, oe)
                    val cw = cur.maxWeight; val ow = earlier.mapNotNull { it.maxWeight }.maxOrNull()
                    if (cw != null && ow != null && cw > ow) prs += PrInfo(ex.name, "Max weight", cw, ow)
                }
            }
        }
        return WorkoutSummary(w.durationSec ?: 0, rows.size, sets, vol, prs)
    }

    // ----- routines -----
    suspend fun createRoutine(name: String): String {
        val id = newId(); val now = nowMs()
        routineDao.upsert(RoutineEntity(id, name, "", now, now)); return id
    }
    suspend fun saveRoutine(r: RoutineEntity) = routineDao.upsert(r.copy(updatedAt = nowMs()))
    suspend fun deleteRoutine(id: String) = routineDao.delete(id)
    suspend fun duplicateRoutine(id: String) = db.withTransaction {
        val r = routineDao.get(id) ?: return@withTransaction
        val nid = newId(); val now = nowMs()
        routineDao.upsert(r.copy(id = nid, name = r.name + " (copy)", createdAt = now, updatedAt = now))
        routineDao.upsertRows(routineDao.getRows(id).map { it.copy(id = newId(), routineId = nid) })
    }
    suspend fun addRoutineExercises(routineId: String, ids: List<String>) = db.withTransaction {
        var pos = routineDao.maxPosition(routineId) + 1
        for (e in ids) routineDao.upsertRow(RoutineExerciseEntity(newId(), routineId, e, pos++))
    }
    suspend fun updateRoutineRow(r: RoutineExerciseEntity) = routineDao.upsertRow(r)
    suspend fun removeRoutineRow(id: String, routineId: String) = db.withTransaction {
        routineDao.deleteRow(id)
        routineDao.upsertRows(routineDao.getRows(routineId).mapIndexed { i, r -> r.copy(position = i) })
    }
    suspend fun moveRoutineRow(id: String, routineId: String, delta: Int) = db.withTransaction {
        val list = routineDao.getRows(routineId).toMutableList()
        val i = list.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j !in list.indices) return@withTransaction
        val t = list[i]; list[i] = list[j]; list[j] = t
        routineDao.upsertRows(list.mapIndexed { k, r -> r.copy(position = k) })
    }
}

// ======================= Nutrition =======================

data class RecipeNutrition(val total: Nutrients, val perServing: Nutrients)

class NutritionRepository(private val db: FitDatabase) {
    private val foods = db.foodDao()
    private val entries = db.foodEntryDao()
    private val recipes = db.recipeDao()

    fun observeDay(projectId: String, date: String) = entries.observeDay(projectId, date)
    fun observeFoods(q: String, limit: Int) = foods.observeSearch(q.trim(), limit)
    fun observeRecentFoods() = foods.observeRecent()
    fun observeRecipes() = recipes.observeAll()
    fun observeDayTotals(projectId: String, limit: Int) = entries.observeDayTotals(projectId, limit)

    // ----- food catalog -----
    suspend fun getFood(id: String) = foods.get(id)

    /** Editing a food NEVER touches existing diary entries (they hold their own snapshot). */
    suspend fun saveFood(f: FoodEntity): FoodEntity {
        val now = nowMs()
        val e = if (f.id.isBlank()) f.copy(id = newId(), createdAt = now, updatedAt = now) else f.copy(updatedAt = now)
        foods.upsert(e); return e
    }
    suspend fun duplicateFood(id: String): FoodEntity? {
        val f = foods.get(id) ?: return null
        return saveFood(f.copy(id = "", name = f.name + " (copy)", favorite = false, lastUsedAt = null))
    }
    suspend fun setFavorite(id: String, v: Boolean) = foods.setFavorite(id, v)

    /** Returns null if deleted, or a message if blocked. */
    suspend fun deleteFood(id: String): String? {
        val n = foods.recipeUsage(id)
        if (n > 0) return "This food is used in $n recipe ingredient(s). Remove it from those recipes first."
        foods.delete(id); return null
    }

    // ----- diary -----
    suspend fun logFood(projectId: String, date: String, meal: String, food: FoodEntity, unit: String, quantity: Double): FoodEntryEntity {
        val amount = NutritionCalc.amountBase(food.servingSize, unit, quantity)
        val n = NutritionCalc.scale(food.per100(), amount)
        val now = nowMs()
        val e = FoodEntryEntity(newId(), projectId, date, meal, food.id, food.name, quantity, unit, amount,
            n.kcal, n.protein, n.carbs, n.fat, n.fiber, n.sugar, now, now)
        db.withTransaction { entries.upsert(e); foods.touch(food.id, now) }
        return e
    }

    suspend fun logQuick(projectId: String, date: String, meal: String, name: String, n: Nutrients): FoodEntryEntity {
        val now = nowMs()
        val e = FoodEntryEntity(newId(), projectId, date, meal, null, name.ifBlank { "Quick add" }, 1.0, "serving", 0.0,
            r1(n.kcal), r1(n.protein), r1(n.carbs), r1(n.fat), r1(n.fiber), r1(n.sugar), now, now)
        entries.upsert(e); return e
    }

    suspend fun logRecipe(projectId: String, date: String, meal: String, recipe: RecipeEntity, perServing: Nutrients, servings: Double): FoodEntryEntity {
        val n = (perServing * servings).rounded(); val now = nowMs()
        val e = FoodEntryEntity(newId(), projectId, date, meal, null, recipe.name, servings, "serving", 0.0,
            n.kcal, n.protein, n.carbs, n.fat, n.fiber, n.sugar, now, now)
        entries.upsert(e); return e
    }

    suspend fun updateEntry(e: FoodEntryEntity) = entries.upsert(e.copy(updatedAt = nowMs()))
    suspend fun deleteEntry(id: String): FoodEntryEntity? { val e = entries.get(id); entries.delete(id); return e }
    suspend fun restoreEntry(e: FoodEntryEntity) = entries.upsert(e)
    suspend fun duplicateEntry(id: String) {
        val e = entries.get(id) ?: return
        entries.upsert(e.copy(id = newId(), createdAt = nowMs(), updatedAt = nowMs()))
    }

    // ----- recipes -----
    suspend fun getRecipe(id: String) = recipes.get(id)
    suspend fun ingredients(id: String) = recipes.ingredients(id)

    fun nutritionOf(recipe: RecipeEntity, ings: List<Pair<FoodEntity, Double>>): RecipeNutrition {
        val total = NutritionCalc.sum(ings.map { (f, g) -> NutritionCalc.scale(f.per100(), g) })
        val s = recipe.servings.takeIf { it > 0 } ?: 1.0
        return RecipeNutrition(total, (total * (1.0 / s)).rounded())
    }

    suspend fun saveRecipe(r: RecipeEntity, ings: List<Pair<String, Double>>): String = db.withTransaction {
        val now = nowMs()
        val id = if (r.id.isBlank()) newId() else r.id
        val existing = recipes.get(id)
        recipes.upsert(r.copy(id = id, createdAt = existing?.createdAt ?: now, updatedAt = now))
        recipes.deleteIngredients(id)
        recipes.insertIngredients(ings.mapIndexed { i, (fid, g) -> RecipeIngredientEntity(newId(), id, fid, g, i) })
        id
    }
    suspend fun deleteRecipe(id: String) = recipes.delete(id)
}

// ======================= Body =======================

class BodyRepository(private val db: FitDatabase) {
    private val dao = db.bodyDao()
    fun observeWeights(p: String) = dao.observeWeights(p)
    fun observeActiveDefs() = dao.observeActiveDefs()
    fun observeAllDefs() = dao.observeAllDefs()
    fun observeMeasurements(p: String) = dao.observeMeasurements(p)

    suspend fun saveWeight(projectId: String, id: String?, date: String, kg: Double, notes: String) {
        val old = id?.let { dao.getWeight(it) }
        dao.upsertWeight(WeightEntryEntity(old?.id ?: newId(), projectId, date, kg, notes, old?.createdAt ?: nowMs()))
    }
    suspend fun deleteWeight(id: String): WeightEntryEntity? { val w = dao.getWeight(id); dao.deleteWeight(id); return w }
    suspend fun restoreWeight(w: WeightEntryEntity) = dao.upsertWeight(w)

    /** Only the values that were actually entered are stored; missing ones are simply absent. */
    suspend fun saveMeasurements(projectId: String, date: String, values: Map<String, Double>, notes: String) {
        val now = nowMs()
        dao.upsertMeasurements(values.map { (defId, v) -> MeasurementEntryEntity(newId(), projectId, defId, date, v, notes, now) })
    }
    suspend fun updateMeasurement(m: MeasurementEntryEntity) = dao.upsertMeasurement(m)
    suspend fun deleteMeasurement(id: String): MeasurementEntryEntity? { val m = dao.getMeasurement(id); dao.deleteMeasurement(id); return m }
    suspend fun restoreMeasurement(m: MeasurementEntryEntity) = dao.upsertMeasurement(m)

    suspend fun addDef(name: String, kind: String): MeasurementDefEntity {
        val d = MeasurementDefEntity(newId(), name.trim(), kind, false, false, dao.maxDefOrder() + 1)
        dao.upsertDef(d); return d
    }
    suspend fun renameDef(d: MeasurementDefEntity, name: String) = dao.upsertDef(d.copy(name = name.trim()))
    suspend fun setDefArchived(d: MeasurementDefEntity, archived: Boolean) = dao.upsertDef(d.copy(archived = archived))
}
