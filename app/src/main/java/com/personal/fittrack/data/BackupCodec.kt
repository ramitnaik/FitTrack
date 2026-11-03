package com.personal.fittrack.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class BackupException(message: String) : Exception(message)

/** The complete, human-readable JSON backup document. */
@Serializable
data class BackupFile(
    val format: String = BackupCodec.FORMAT,
    val backupVersion: Int = BackupCodec.VERSION,
    val schemaVersion: Int = 1,
    val appVersion: String = "",
    val exportedAt: String = "",
    /** "FULL" (everything) or "PROJECT" (one project plus the catalog items it references). */
    val scope: String = "FULL",
    val preferences: Map<String, String> = emptyMap(),
    val projects: List<ProjectEntity> = emptyList(),
    val exercises: List<ExerciseEntity> = emptyList(),
    val foods: List<FoodEntity> = emptyList(),
    val measurementDefs: List<MeasurementDefEntity> = emptyList(),
    val recipes: List<RecipeEntity> = emptyList(),
    val recipeIngredients: List<RecipeIngredientEntity> = emptyList(),
    val routines: List<RoutineEntity> = emptyList(),
    val routineExercises: List<RoutineExerciseEntity> = emptyList(),
    val workouts: List<WorkoutEntity> = emptyList(),
    val workoutExercises: List<WorkoutExerciseEntity> = emptyList(),
    val workoutSets: List<WorkoutSetEntity> = emptyList(),
    val foodEntries: List<FoodEntryEntity> = emptyList(),
    val weightEntries: List<WeightEntryEntity> = emptyList(),
    val measurementEntries: List<MeasurementEntryEntity> = emptyList()
)

/** Catalog IDs already present in the database (used so a project backup can reference existing exercises/foods). */
data class ExistingIds(
    val exercises: Set<String> = emptySet(),
    val foods: Set<String> = emptySet(),
    val defs: Set<String> = emptySet()
)

/** Pure (Android-free) encode / parse / validate logic so it can be unit-tested on the JVM. */
object BackupCodec {
    const val FORMAT = "fittrack-backup"
    const val VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true   // newer-but-compatible files still load
        coerceInputValues = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun parse(text: String): BackupFile {
        if (text.isBlank()) throw BackupException("The file is empty.")
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException("This is not a valid FitTrack backup (the file is damaged or in a different format).")
        } catch (e: IllegalArgumentException) {
            throw BackupException("This is not a valid FitTrack backup (the file is damaged or in a different format).")
        }
        if (file.format != FORMAT) throw BackupException("This file is not a FitTrack backup.")
        if (file.backupVersion > VERSION)
            throw BackupException("This backup was made by a newer version of the app (backup version ${file.backupVersion}). Update the app to restore it.")
        if (file.backupVersion < 1) throw BackupException("Unsupported backup version ${file.backupVersion}.")
        return file
    }

    /** Returns human-readable problems; empty list = safe to import. */
    fun validate(f: BackupFile, existing: ExistingIds = ExistingIds()): List<String> {
        val errors = mutableListOf<String>()
        val projectIds = f.projects.map { it.id }.toSet()
        val exerciseIds = f.exercises.map { it.id }.toSet() + existing.exercises
        val foodIds = f.foods.map { it.id }.toSet() + existing.foods
        val defIds = f.measurementDefs.map { it.id }.toSet() + existing.defs
        val workoutIds = f.workouts.map { it.id }.toSet()
        val weIds = f.workoutExercises.map { it.id }.toSet()
        val recipeIds = f.recipes.map { it.id }.toSet()
        val routineIds = f.routines.map { it.id }.toSet()

        if (f.scope != "FULL" && f.scope != "PROJECT") errors += "Unknown backup scope '${f.scope}'."
        f.projects.filter { it.id.isBlank() || it.name.isBlank() }.forEach { errors += "A project has no id or name." }
        f.workouts.filter { it.projectId !in projectIds }.forEach { errors += "Workout '${it.name}' belongs to a project that is not in the file." }
        f.workoutExercises.filter { it.workoutId !in workoutIds }.forEach { errors += "A workout exercise points to a missing workout." }
        f.workoutExercises.filter { it.exerciseId !in exerciseIds }.forEach { errors += "A workout uses an exercise (${it.exerciseId}) that is missing from the file." }
        f.workoutSets.filter { it.workoutExerciseId !in weIds }.forEach { errors += "A set points to a missing workout exercise." }
        f.foodEntries.filter { it.projectId !in projectIds }.forEach { errors += "Food entry '${it.name}' belongs to a project that is not in the file." }
        f.weightEntries.filter { it.projectId !in projectIds }.forEach { errors += "A weight entry belongs to a project that is not in the file." }
        f.measurementEntries.filter { it.projectId !in projectIds }.forEach { errors += "A measurement belongs to a project that is not in the file." }
        f.measurementEntries.filter { it.defId !in defIds }.forEach { errors += "A measurement uses a measurement type (${it.defId}) that is missing." }
        f.recipeIngredients.filter { it.recipeId !in recipeIds }.forEach { errors += "A recipe ingredient points to a missing recipe." }
        f.recipeIngredients.filter { it.foodId !in foodIds }.forEach { errors += "A recipe ingredient uses a food that is missing." }
        f.routineExercises.filter { it.routineId !in routineIds }.forEach { errors += "A routine exercise points to a missing routine." }
        f.routineExercises.filter { it.exerciseId !in exerciseIds }.forEach { errors += "A routine uses an exercise that is missing." }
        (f.workouts.map { it.date } + f.foodEntries.map { it.date } + f.weightEntries.map { it.date } + f.measurementEntries.map { it.date })
            .filter { !com.personal.fittrack.domain.Dates.isValid(it) }.distinct().forEach { errors += "Invalid date '$it'." }
        return errors.distinct().take(12)
    }

    fun counts(f: BackupFile): Map<String, Int> = linkedMapOf(
        "Projects" to f.projects.size, "Workouts" to f.workouts.size, "Workout sets" to f.workoutSets.size,
        "Food diary entries" to f.foodEntries.size, "Weight entries" to f.weightEntries.size,
        "Measurements" to f.measurementEntries.size, "Exercises" to f.exercises.size, "Foods" to f.foods.size,
        "Recipes" to f.recipes.size, "Routines" to f.routines.size
    )
}
