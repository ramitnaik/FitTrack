package com.personal.fittrack.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// ---------- Query result types ----------

data class WorkoutExerciseRow(
    @Embedded val we: WorkoutExerciseEntity,
    val exerciseName: String,
    val exerciseType: String,
    val trackStats: Boolean
)

data class RoutineExerciseRow(
    @Embedded val re: RoutineExerciseEntity,
    val exerciseName: String
)

data class WorkoutListItem(
    @Embedded val workout: WorkoutEntity,
    val exerciseCount: Int,
    val setCount: Int,
    val volumeKg: Double,
    val exerciseNames: String?
)

data class ExerciseSetRow(
    val date: String,
    val workoutId: String,
    @Embedded val set: WorkoutSetEntity
)

data class FrequentExercise(val exerciseId: String, val name: String, val workouts: Int)
data class PeriodCount(val period: String, val count: Int)
data class DurationPoint(val date: String, val durationSec: Int)
data class DayTotal(val date: String, val kcal: Double, val protein: Double, val carbs: Double, val fat: Double)
data class MeasurementRow(@Embedded val entry: MeasurementEntryEntity, val defName: String, val kind: String)
data class IngredientWithFood(
    @Embedded val ingredient: RecipeIngredientEntity,
    @Relation(parentColumn = "foodId", entityColumn = "id") val food: FoodEntity
)
data class RecipeSummary(@Embedded val recipe: RecipeEntity, val ingredientCount: Int)

data class CsvWorkoutRow(
    val project: String, val date: String, val workoutName: String, val startedAt: Long, val durationSec: Int?,
    val exercise: String, val setNumber: Int, val setType: String, val reps: Int?, val weightKg: Double?,
    val durationSecSet: Int?, val distanceM: Double?, val rpe: Double?, val completed: Boolean, val setNotes: String
)
data class CsvFoodRow(
    val project: String, val date: String, val meal: String, val name: String, val quantity: Double, val unit: String,
    val kcal: Double, val protein: Double, val carbs: Double, val fat: Double, val fiber: Double, val sugar: Double
)
data class CsvWeightRow(val project: String, val date: String, val weightKg: Double, val notes: String)
data class CsvMeasurementRow(val project: String, val date: String, val measurement: String, val kind: String, val value: Double, val notes: String)

// ---------- DAOs ----------

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY archived ASC, createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM workouts WHERE projectId = :id AND status = 'COMPLETED'")
    suspend fun workoutCount(id: String): Int

    @Query("SELECT COUNT(*) FROM food_entries WHERE projectId = :id")
    suspend fun foodEntryCount(id: String): Int

    @Query("SELECT COUNT(*) FROM weight_entries WHERE projectId = :id")
    suspend fun weightCount(id: String): Int
}

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises ORDER BY archived ASC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun get(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun observe(id: String): Flow<ExerciseEntity?>

    @Upsert
    suspend fun upsert(e: ExerciseEntity)

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT (SELECT COUNT(*) FROM workout_exercises WHERE exerciseId = :id) + (SELECT COUNT(*) FROM routine_exercises WHERE exerciseId = :id)")
    suspend fun usageCount(id: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(list: List<ExerciseEntity>): List<Long>

    @Query("UPDATE exercises SET trackStats = :v WHERE id = :id")
    suspend fun setTrackStats(id: String, v: Boolean)
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id")
    fun observe(id: String): Flow<RoutineEntity?>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun get(id: String): RoutineEntity?

    @Upsert
    suspend fun upsert(r: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT re.*, e.name AS exerciseName FROM routine_exercises re JOIN exercises e ON e.id = re.exerciseId WHERE re.routineId = :routineId ORDER BY re.position")
    fun observeRows(routineId: String): Flow<List<RoutineExerciseRow>>

    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId ORDER BY position")
    suspend fun getRows(routineId: String): List<RoutineExerciseEntity>

    @Query("SELECT COUNT(*) FROM routine_exercises WHERE routineId = :routineId")
    fun observeCount(routineId: String): Flow<Int>

    @Upsert
    suspend fun upsertRow(r: RoutineExerciseEntity)

    @Upsert
    suspend fun upsertRows(rows: List<RoutineExerciseEntity>)

    @Query("DELETE FROM routine_exercises WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Query("SELECT COALESCE(MAX(position), -1) FROM routine_exercises WHERE routineId = :routineId")
    suspend fun maxPosition(routineId: String): Int
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun get(id: String): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observe(id: String): Flow<WorkoutEntity?>

    @Upsert
    suspend fun upsert(w: WorkoutEntity)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM workouts WHERE projectId = :projectId AND status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    fun observeInProgress(projectId: String): Flow<WorkoutEntity?>

    @Query(
        """
        SELECT w.*,
          (SELECT COUNT(*) FROM workout_exercises we WHERE we.workoutId = w.id) AS exerciseCount,
          (SELECT COUNT(*) FROM workout_sets s JOIN workout_exercises we ON we.id = s.workoutExerciseId WHERE we.workoutId = w.id AND s.completed = 1) AS setCount,
          (SELECT COALESCE(SUM(CASE WHEN s.weightKg > 0 AND s.reps > 0 THEN s.weightKg * s.reps ELSE 0 END), 0) FROM workout_sets s JOIN workout_exercises we ON we.id = s.workoutExerciseId WHERE we.workoutId = w.id AND s.completed = 1 AND s.setType = 'WORKING') AS volumeKg,
          (SELECT group_concat(e.name, ', ') FROM workout_exercises we JOIN exercises e ON e.id = we.exerciseId WHERE we.workoutId = w.id) AS exerciseNames
        FROM workouts w
        WHERE w.projectId = :projectId AND w.status = 'COMPLETED'
          AND w.date >= :from AND w.date <= :to
          AND (:q = '' OR w.name LIKE '%' || :q || '%' OR EXISTS (
                SELECT 1 FROM workout_exercises we JOIN exercises e ON e.id = we.exerciseId
                WHERE we.workoutId = w.id AND e.name LIKE '%' || :q || '%'))
        ORDER BY w.date DESC, w.startedAt DESC
        LIMIT :limit
        """
    )
    fun observeHistory(projectId: String, q: String, from: String, to: String, limit: Int): Flow<List<WorkoutListItem>>

    @Query("SELECT we.*, e.name AS exerciseName, e.type AS exerciseType, e.trackStats AS trackStats FROM workout_exercises we JOIN exercises e ON e.id = we.exerciseId WHERE we.workoutId = :workoutId ORDER BY we.position")
    fun observeExerciseRows(workoutId: String): Flow<List<WorkoutExerciseRow>>

    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId ORDER BY position")
    suspend fun getExercises(workoutId: String): List<WorkoutExerciseEntity>

    @Query("SELECT * FROM workout_exercises WHERE id = :id")
    suspend fun getWorkoutExercise(id: String): WorkoutExerciseEntity?

    @Query("SELECT s.* FROM workout_sets s JOIN workout_exercises we ON we.id = s.workoutExerciseId WHERE we.workoutId = :workoutId ORDER BY s.position")
    fun observeSets(workoutId: String): Flow<List<WorkoutSetEntity>>

    @Query("SELECT * FROM workout_sets WHERE workoutExerciseId = :weId ORDER BY position")
    suspend fun getSets(weId: String): List<WorkoutSetEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM workout_exercises WHERE workoutId = :workoutId")
    suspend fun maxExercisePosition(workoutId: String): Int

    @Upsert
    suspend fun upsertWorkoutExercise(we: WorkoutExerciseEntity)

    @Upsert
    suspend fun upsertWorkoutExercises(list: List<WorkoutExerciseEntity>)

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: String)

    @Upsert
    suspend fun upsertSet(s: WorkoutSetEntity)

    @Upsert
    suspend fun upsertSets(list: List<WorkoutSetEntity>)

    @Query("DELETE FROM workout_sets WHERE id = :id")
    suspend fun deleteSet(id: String)

    @Query("SELECT * FROM workout_sets WHERE id = :id")
    suspend fun getSet(id: String): WorkoutSetEntity?

    // Targeted column updates keep rapid edits race-free.
    @Query("UPDATE workout_sets SET weightKg = :v WHERE id = :id") suspend fun setWeight(id: String, v: Double?)
    @Query("UPDATE workout_sets SET reps = :v WHERE id = :id") suspend fun setReps(id: String, v: Int?)
    @Query("UPDATE workout_sets SET durationSec = :v WHERE id = :id") suspend fun setDuration(id: String, v: Int?)
    @Query("UPDATE workout_sets SET distanceM = :v WHERE id = :id") suspend fun setDistance(id: String, v: Double?)
    @Query("UPDATE workout_sets SET completed = :v WHERE id = :id") suspend fun setCompleted(id: String, v: Boolean)
    @Query("UPDATE workout_sets SET setType = :v WHERE id = :id") suspend fun setSetType(id: String, v: String)
    @Query("UPDATE workout_sets SET rpe = :rpe, notes = :notes WHERE id = :id") suspend fun setRpeNotes(id: String, rpe: Double?, notes: String)
    @Query("UPDATE workout_exercises SET restSeconds = :v WHERE id = :id") suspend fun setRest(id: String, v: Int?)
    @Query("UPDATE workout_exercises SET notes = :v WHERE id = :id") suspend fun setExerciseNotes(id: String, v: String)
    @Query("UPDATE workout_exercises SET exerciseId = :exerciseId WHERE id = :id") suspend fun replaceExercise(id: String, exerciseId: String)

    @Query("DELETE FROM workout_sets WHERE completed = 0 AND workoutExerciseId IN (SELECT id FROM workout_exercises WHERE workoutId = :workoutId)")
    suspend fun deleteIncompleteSets(workoutId: String)

    @Query("DELETE FROM workout_exercises WHERE workoutId = :workoutId AND id NOT IN (SELECT workoutExerciseId FROM workout_sets)")
    suspend fun deleteEmptyExercises(workoutId: String)

    @Query("SELECT w.id FROM workouts w JOIN workout_exercises we ON we.workoutId = w.id WHERE w.projectId = :projectId AND we.exerciseId = :exerciseId AND w.status = 'COMPLETED' AND w.id != :excludeWorkoutId ORDER BY w.date DESC, w.startedAt DESC LIMIT 1")
    suspend fun lastWorkoutWith(projectId: String, exerciseId: String, excludeWorkoutId: String): String?

    @Query("SELECT s.* FROM workout_sets s JOIN workout_exercises we ON we.id = s.workoutExerciseId WHERE we.workoutId = :workoutId AND we.exerciseId = :exerciseId ORDER BY s.position")
    suspend fun setsOfExerciseInWorkout(workoutId: String, exerciseId: String): List<WorkoutSetEntity>

    @Query("SELECT w.date AS date, w.id AS workoutId, s.* FROM workout_sets s JOIN workout_exercises we ON we.id = s.workoutExerciseId JOIN workouts w ON w.id = we.workoutId WHERE w.projectId = :projectId AND we.exerciseId = :exerciseId AND w.status = 'COMPLETED' AND s.completed = 1 ORDER BY w.date ASC, w.startedAt ASC, s.position ASC")
    fun observeExerciseSets(projectId: String, exerciseId: String): Flow<List<ExerciseSetRow>>

    @Query("SELECT w.date AS date, w.id AS workoutId, s.* FROM workout_sets s JOIN workout_exercises we ON we.id = s.workoutExerciseId JOIN workouts w ON w.id = we.workoutId WHERE w.projectId = :projectId AND we.exerciseId = :exerciseId AND w.status = 'COMPLETED' AND s.completed = 1 ORDER BY w.date ASC, w.startedAt ASC, s.position ASC")
    suspend fun exerciseSetsOnce(projectId: String, exerciseId: String): List<ExerciseSetRow>

    @Query("SELECT COUNT(*) FROM workouts WHERE projectId = :projectId AND status = 'COMPLETED'")
    fun observeCompletedCount(projectId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM workouts WHERE projectId = :projectId AND status = 'COMPLETED' AND date >= :from AND date <= :to")
    fun observeCountBetween(projectId: String, from: String, to: String): Flow<Int>

    @Query("SELECT strftime('%Y-%W', date) AS period, COUNT(*) AS count FROM workouts WHERE projectId = :projectId AND status = 'COMPLETED' GROUP BY period ORDER BY period DESC LIMIT :limit")
    fun observeWeeklyCounts(projectId: String, limit: Int): Flow<List<PeriodCount>>

    @Query("SELECT strftime('%Y-%m', date) AS period, COUNT(*) AS count FROM workouts WHERE projectId = :projectId AND status = 'COMPLETED' GROUP BY period ORDER BY period DESC LIMIT :limit")
    fun observeMonthlyCounts(projectId: String, limit: Int): Flow<List<PeriodCount>>

    @Query("SELECT date, durationSec FROM workouts WHERE projectId = :projectId AND status = 'COMPLETED' AND durationSec IS NOT NULL ORDER BY date DESC, startedAt DESC LIMIT :limit")
    fun observeDurations(projectId: String, limit: Int): Flow<List<DurationPoint>>

    @Query("SELECT e.id AS exerciseId, e.name AS name, COUNT(DISTINCT w.id) AS workouts FROM workout_exercises we JOIN workouts w ON w.id = we.workoutId JOIN exercises e ON e.id = we.exerciseId WHERE w.projectId = :projectId AND w.status = 'COMPLETED' GROUP BY e.id ORDER BY workouts DESC, e.name LIMIT :limit")
    fun observeFrequentExercises(projectId: String, limit: Int): Flow<List<FrequentExercise>>
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM foods WHERE archived = 0 AND (name LIKE '%' || :q || '%' OR brand LIKE '%' || :q || '%' OR category LIKE '%' || :q || '%') ORDER BY favorite DESC, name COLLATE NOCASE LIMIT :limit")
    fun observeSearch(q: String, limit: Int): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE archived = 0 AND lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT 15")
    fun observeRecent(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun get(id: String): FoodEntity?

    @Upsert
    suspend fun upsert(f: FoodEntity)

    @Query("DELETE FROM foods WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE foods SET lastUsedAt = :t WHERE id = :id")
    suspend fun touch(id: String, t: Long)

    @Query("UPDATE foods SET favorite = :v WHERE id = :id")
    suspend fun setFavorite(id: String, v: Boolean)

    @Query("SELECT COUNT(*) FROM recipe_ingredients WHERE foodId = :id")
    suspend fun recipeUsage(id: String): Int

    @Query("SELECT COUNT(*) FROM foods")
    suspend fun count(): Int
}

@Dao
interface FoodEntryDao {
    @Query("SELECT * FROM food_entries WHERE projectId = :projectId AND date = :date ORDER BY createdAt, id")
    fun observeDay(projectId: String, date: String): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM food_entries WHERE id = :id")
    suspend fun get(id: String): FoodEntryEntity?

    @Upsert
    suspend fun upsert(e: FoodEntryEntity)

    @Query("DELETE FROM food_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT date, SUM(kcal) AS kcal, SUM(protein) AS protein, SUM(carbs) AS carbs, SUM(fat) AS fat FROM food_entries WHERE projectId = :projectId GROUP BY date ORDER BY date DESC LIMIT :limit")
    fun observeDayTotals(projectId: String, limit: Int): Flow<List<DayTotal>>
}

@Dao
interface RecipeDao {
    @Query("SELECT r.*, (SELECT COUNT(*) FROM recipe_ingredients i WHERE i.recipeId = r.id) AS ingredientCount FROM recipes r ORDER BY favorite DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<RecipeSummary>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: String): RecipeEntity?

    @Transaction
    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY position")
    suspend fun ingredients(recipeId: String): List<IngredientWithFood>

    @Transaction
    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY position")
    fun observeIngredients(recipeId: String): Flow<List<IngredientWithFood>>

    @Upsert
    suspend fun upsert(r: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recipe_ingredients WHERE recipeId = :recipeId")
    suspend fun deleteIngredients(recipeId: String)

    @Insert
    suspend fun insertIngredients(list: List<RecipeIngredientEntity>)
}

@Dao
interface BodyDao {
    @Query("SELECT * FROM weight_entries WHERE projectId = :projectId ORDER BY date ASC, createdAt ASC")
    fun observeWeights(projectId: String): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries WHERE id = :id")
    suspend fun getWeight(id: String): WeightEntryEntity?

    @Upsert
    suspend fun upsertWeight(w: WeightEntryEntity)

    @Query("DELETE FROM weight_entries WHERE id = :id")
    suspend fun deleteWeight(id: String)

    @Query("SELECT * FROM measurement_defs WHERE archived = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeActiveDefs(): Flow<List<MeasurementDefEntity>>

    @Query("SELECT * FROM measurement_defs ORDER BY archived ASC, sortOrder, name COLLATE NOCASE")
    fun observeAllDefs(): Flow<List<MeasurementDefEntity>>

    @Upsert
    suspend fun upsertDef(d: MeasurementDefEntity)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM measurement_defs")
    suspend fun maxDefOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefsIgnore(list: List<MeasurementDefEntity>): List<Long>

    @Query("SELECT m.*, d.name AS defName, d.kind AS kind FROM measurement_entries m JOIN measurement_defs d ON d.id = m.defId WHERE m.projectId = :projectId ORDER BY m.date ASC, m.createdAt ASC")
    fun observeMeasurements(projectId: String): Flow<List<MeasurementRow>>

    @Query("SELECT * FROM measurement_entries WHERE id = :id")
    suspend fun getMeasurement(id: String): MeasurementEntryEntity?

    @Upsert
    suspend fun upsertMeasurement(m: MeasurementEntryEntity)

    @Upsert
    suspend fun upsertMeasurements(list: List<MeasurementEntryEntity>)

    @Query("DELETE FROM measurement_entries WHERE id = :id")
    suspend fun deleteMeasurement(id: String)
}

@Dao
interface BackupDao {
    // ----- full table reads -----
    @Query("SELECT * FROM projects ORDER BY createdAt") suspend fun projects(): List<ProjectEntity>
    @Query("SELECT * FROM exercises") suspend fun exercises(): List<ExerciseEntity>
    @Query("SELECT * FROM foods") suspend fun foods(): List<FoodEntity>
    @Query("SELECT * FROM recipes") suspend fun recipes(): List<RecipeEntity>
    @Query("SELECT * FROM recipe_ingredients") suspend fun recipeIngredients(): List<RecipeIngredientEntity>
    @Query("SELECT * FROM measurement_defs") suspend fun measurementDefs(): List<MeasurementDefEntity>
    @Query("SELECT * FROM routines") suspend fun routines(): List<RoutineEntity>
    @Query("SELECT * FROM routine_exercises") suspend fun routineExercises(): List<RoutineExerciseEntity>

    // ----- per project reads -----
    @Query("SELECT * FROM projects WHERE id = :p") suspend fun project(p: String): List<ProjectEntity>
    @Query("SELECT * FROM workouts WHERE projectId = :p") suspend fun workoutsFor(p: String): List<WorkoutEntity>
    @Query("SELECT * FROM workout_exercises WHERE workoutId IN (SELECT id FROM workouts WHERE projectId = :p)") suspend fun workoutExercisesFor(p: String): List<WorkoutExerciseEntity>
    @Query("SELECT * FROM workout_sets WHERE workoutExerciseId IN (SELECT we.id FROM workout_exercises we JOIN workouts w ON w.id = we.workoutId WHERE w.projectId = :p)") suspend fun workoutSetsFor(p: String): List<WorkoutSetEntity>
    @Query("SELECT * FROM food_entries WHERE projectId = :p") suspend fun foodEntriesFor(p: String): List<FoodEntryEntity>
    @Query("SELECT * FROM weight_entries WHERE projectId = :p") suspend fun weightsFor(p: String): List<WeightEntryEntity>
    @Query("SELECT * FROM measurement_entries WHERE projectId = :p") suspend fun measurementEntriesFor(p: String): List<MeasurementEntryEntity>

    @Query("SELECT * FROM exercises WHERE id IN (:ids)") suspend fun exercisesByIds(ids: List<String>): List<ExerciseEntity>
    @Query("SELECT * FROM foods WHERE id IN (:ids)") suspend fun foodsByIds(ids: List<String>): List<FoodEntity>
    @Query("SELECT * FROM measurement_defs WHERE id IN (:ids)") suspend fun defsByIds(ids: List<String>): List<MeasurementDefEntity>

    @Query("SELECT id FROM exercises") suspend fun exerciseIds(): List<String>
    @Query("SELECT id FROM foods") suspend fun foodIds(): List<String>
    @Query("SELECT id FROM measurement_defs") suspend fun defIds(): List<String>
    @Query("SELECT id FROM projects") suspend fun projectIds(): List<String>

    // ----- inserts (IGNORE = existing IDs are never overwritten on merge) -----
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertProjects(l: List<ProjectEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertExercises(l: List<ExerciseEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFoods(l: List<FoodEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDefs(l: List<MeasurementDefEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRecipes(l: List<RecipeEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRecipeIngredients(l: List<RecipeIngredientEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRoutines(l: List<RoutineEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRoutineExercises(l: List<RoutineExerciseEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorkouts(l: List<WorkoutEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorkoutExercises(l: List<WorkoutExerciseEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorkoutSets(l: List<WorkoutSetEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFoodEntries(l: List<FoodEntryEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWeights(l: List<WeightEntryEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMeasurementEntries(l: List<MeasurementEntryEntity>): List<Long>

    // ----- wipe (dependency order) -----
    @Query("DELETE FROM workout_sets") suspend fun wipeSets()
    @Query("DELETE FROM workout_exercises") suspend fun wipeWorkoutExercises()
    @Query("DELETE FROM workouts") suspend fun wipeWorkouts()
    @Query("DELETE FROM routine_exercises") suspend fun wipeRoutineExercises()
    @Query("DELETE FROM routines") suspend fun wipeRoutines()
    @Query("DELETE FROM food_entries") suspend fun wipeFoodEntries()
    @Query("DELETE FROM recipe_ingredients") suspend fun wipeRecipeIngredients()
    @Query("DELETE FROM recipes") suspend fun wipeRecipes()
    @Query("DELETE FROM foods") suspend fun wipeFoods()
    @Query("DELETE FROM weight_entries") suspend fun wipeWeights()
    @Query("DELETE FROM measurement_entries") suspend fun wipeMeasurementEntries()
    @Query("DELETE FROM measurement_defs") suspend fun wipeDefs()
    @Query("DELETE FROM exercises") suspend fun wipeExercises()
    @Query("DELETE FROM projects") suspend fun wipeProjects()

    // ----- CSV exports (projectId null = all projects; optional date range) -----
    @Query("""SELECT p.name AS project, w.date AS date, w.name AS workoutName, w.startedAt AS startedAt, w.durationSec AS durationSec,
        e.name AS exercise, (s.position + 1) AS setNumber, s.setType AS setType, s.reps AS reps, s.weightKg AS weightKg,
        s.durationSec AS durationSecSet, s.distanceM AS distanceM, s.rpe AS rpe, s.completed AS completed, s.notes AS setNotes
        FROM workout_sets s JOIN workout_exercises we ON we.id = s.workoutExerciseId JOIN workouts w ON w.id = we.workoutId
        JOIN exercises e ON e.id = we.exerciseId JOIN projects p ON p.id = w.projectId
        WHERE (:projectId IS NULL OR w.projectId = :projectId) AND w.date >= :from AND w.date <= :to AND w.status = 'COMPLETED'
        ORDER BY w.date, w.startedAt, we.position, s.position""")
    suspend fun csvWorkouts(projectId: String?, from: String, to: String): List<CsvWorkoutRow>

    @Query("""SELECT p.name AS project, f.date AS date, f.mealName AS meal, f.name AS name, f.quantity AS quantity, f.unit AS unit,
        f.kcal AS kcal, f.protein AS protein, f.carbs AS carbs, f.fat AS fat, f.fiber AS fiber, f.sugar AS sugar
        FROM food_entries f JOIN projects p ON p.id = f.projectId
        WHERE (:projectId IS NULL OR f.projectId = :projectId) AND f.date >= :from AND f.date <= :to
        ORDER BY f.date, f.createdAt""")
    suspend fun csvFood(projectId: String?, from: String, to: String): List<CsvFoodRow>

    @Query("""SELECT p.name AS project, w.date AS date, w.weightKg AS weightKg, w.notes AS notes
        FROM weight_entries w JOIN projects p ON p.id = w.projectId
        WHERE (:projectId IS NULL OR w.projectId = :projectId) AND w.date >= :from AND w.date <= :to ORDER BY w.date""")
    suspend fun csvWeights(projectId: String?, from: String, to: String): List<CsvWeightRow>

    @Query("""SELECT p.name AS project, m.date AS date, d.name AS measurement, d.kind AS kind, m.value AS value, m.notes AS notes
        FROM measurement_entries m JOIN measurement_defs d ON d.id = m.defId JOIN projects p ON p.id = m.projectId
        WHERE (:projectId IS NULL OR m.projectId = :projectId) AND m.date >= :from AND m.date <= :to ORDER BY m.date, d.name""")
    suspend fun csvMeasurements(projectId: String?, from: String, to: String): List<CsvMeasurementRow>
}
