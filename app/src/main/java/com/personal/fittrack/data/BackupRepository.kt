package com.personal.fittrack.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import java.time.Instant

enum class RestoreMode { MERGE, REPLACE_ALL }

data class ImportResult(val added: Int, val skipped: Int, val perType: Map<String, Pair<Int, Int>>)

class BackupRepository(private val ctx: Context, private val db: FitDatabase, private val prefs: Prefs) {
    private val dao = db.backupDao()

    suspend fun build(projectId: String?): BackupFile {
        val appVersion = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val projects = if (projectId == null) dao.projects() else dao.project(projectId)
        val ids = projects.map { it.id }
        val workouts = ids.flatMap { dao.workoutsFor(it) }
        val wex = ids.flatMap { dao.workoutExercisesFor(it) }
        val sets = ids.flatMap { dao.workoutSetsFor(it) }
        val entries = ids.flatMap { dao.foodEntriesFor(it) }
        val weights = ids.flatMap { dao.weightsFor(it) }
        val meas = ids.flatMap { dao.measurementEntriesFor(it) }
        val full = projectId == null
        return BackupFile(
            appVersion = appVersion, exportedAt = Instant.now().toString(), scope = if (full) "FULL" else "PROJECT",
            preferences = if (full) prefs.snapshot() else emptyMap(),
            projects = projects,
            exercises = if (full) dao.exercises() else wex.map { it.exerciseId }.distinct().chunked(500).flatMap { dao.exercisesByIds(it) },
            foods = if (full) dao.foods() else entries.mapNotNull { it.foodId }.distinct().chunked(500).flatMap { dao.foodsByIds(it) },
            measurementDefs = if (full) dao.measurementDefs() else meas.map { it.defId }.distinct().chunked(500).flatMap { dao.defsByIds(it) },
            recipes = if (full) dao.recipes() else emptyList(),
            recipeIngredients = if (full) dao.recipeIngredients() else emptyList(),
            routines = if (full) dao.routines() else emptyList(),
            routineExercises = if (full) dao.routineExercises() else emptyList(),
            workouts = workouts, workoutExercises = wex, workoutSets = sets,
            foodEntries = entries, weightEntries = weights, measurementEntries = meas
        )
    }

    suspend fun writeTo(uri: Uri, projectId: String?): BackupFile {
        val file = build(projectId)
        val text = BackupCodec.encode(file)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Could not open the chosen location for writing.")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return file
    }

    fun writeText(uri: Uri, text: String) {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Could not open the chosen location for writing.")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun readFile(uri: Uri): BackupFile {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw BackupException("Could not open the chosen file.")
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        return BackupCodec.parse(text)
    }

    private suspend fun existingIds() = ExistingIds(dao.exerciseIds().toSet(), dao.foodIds().toSet(), dao.defIds().toSet())

    /** Existing records (same stable ID) are never overwritten on MERGE, so importing the same file twice adds nothing. */
    suspend fun import(file: BackupFile, mode: RestoreMode): ImportResult {
        if (mode == RestoreMode.REPLACE_ALL && file.scope != "FULL") throw BackupException("Only a full backup can replace all data. Use Merge for a single-project backup.")
        val problems = BackupCodec.validate(file, if (mode == RestoreMode.REPLACE_ALL) ExistingIds() else existingIds())
        if (problems.isNotEmpty()) throw BackupException("The backup has inconsistent data:\n• " + problems.joinToString("\n• "))

        if (mode == RestoreMode.REPLACE_ALL) safetyCopy()
        val per = linkedMapOf<String, Pair<Int, Int>>()
        fun rec(name: String, r: List<Long>) { per[name] = r.count { it >= 0 } to r.count { it < 0 } }

        try {
            db.withTransaction {
                if (mode == RestoreMode.REPLACE_ALL) wipeAll()
                val validFoods = file.foods.map { it.id }.toSet() + dao.foodIds().toSet()
                rec("Projects", dao.insertProjects(file.projects))
                rec("Exercises", dao.insertExercises(file.exercises))
                rec("Foods", dao.insertFoods(file.foods))
                rec("Measurement types", dao.insertDefs(file.measurementDefs))
                rec("Recipes", dao.insertRecipes(file.recipes))
                rec("Recipe ingredients", dao.insertRecipeIngredients(file.recipeIngredients))
                rec("Routines", dao.insertRoutines(file.routines))
                rec("Routine exercises", dao.insertRoutineExercises(file.routineExercises))
                rec("Workouts", dao.insertWorkouts(file.workouts))
                rec("Workout exercises", dao.insertWorkoutExercises(file.workoutExercises))
                rec("Workout sets", dao.insertWorkoutSets(file.workoutSets))
                rec("Food entries", dao.insertFoodEntries(file.foodEntries.map { if (it.foodId != null && it.foodId !in validFoods) it.copy(foodId = null) else it }))
                rec("Weight entries", dao.insertWeights(file.weightEntries))
                rec("Measurements", dao.insertMeasurementEntries(file.measurementEntries))
            }
        } catch (e: BackupException) { throw e }
        catch (e: Exception) { throw BackupException("The restore failed and nothing was changed: ${e.message ?: e.javaClass.simpleName}") }

        if (mode == RestoreMode.REPLACE_ALL) {
            if (file.preferences.isNotEmpty()) prefs.restore(file.preferences)
            Seeds.forceReseed(db)
        }
        return ImportResult(per.values.sumOf { it.first }, per.values.sumOf { it.second }, per)
    }

    private suspend fun wipeAll() {
        dao.wipeSets(); dao.wipeWorkoutExercises(); dao.wipeWorkouts(); dao.wipeRoutineExercises(); dao.wipeRoutines()
        dao.wipeFoodEntries(); dao.wipeRecipeIngredients(); dao.wipeRecipes(); dao.wipeFoods()
        dao.wipeWeights(); dao.wipeMeasurementEntries(); dao.wipeDefs(); dao.wipeExercises(); dao.wipeProjects()
    }

    /** Erase everything (after the UI's typed confirmation). A safety copy is written to app-private storage first. */
    suspend fun deleteAllData() {
        safetyCopy()
        db.withTransaction { wipeAll() }
        prefs.setActiveProject(null)
        Seeds.forceReseed(db)
    }

    /** App-private pre-restore copy so a mistaken "Replace all" can still be undone from Settings. Keeps the 3 most recent. */
    suspend fun safetyCopy(): File? = try {
        val dir = File(ctx.filesDir, "auto_backups").apply { mkdirs() }
        val f = File(dir, "pre_change_${System.currentTimeMillis()}.json")
        f.writeText(BackupCodec.encode(build(null)))
        dir.listFiles()?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
        f
    } catch (e: Exception) { null }

    fun latestSafetyCopy(): File? = File(ctx.filesDir, "auto_backups").listFiles()?.maxByOrNull { it.name }

    fun databaseSizeBytes(): Long = ctx.getDatabasePath(FitDatabase.DB_NAME).let { if (it.exists()) it.length() else 0L }
}
