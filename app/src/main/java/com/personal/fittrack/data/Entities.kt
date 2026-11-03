package com.personal.fittrack.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personal.fittrack.domain.Nutrients
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun nowMs(): Long = System.currentTimeMillis()

// Dates are stored as local ISO strings (yyyy-MM-dd) so daily logs never shift with time zones.
// Timestamps are epoch milliseconds (UTC).
// Weights are stored in kg, lengths in cm, distances in metres, durations in seconds.

@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val goal: String = "",
    val notes: String = "",
    val startDate: String? = null,
    val endDate: String? = null,
    val archived: Boolean = false,
    val targetKcal: Int? = null,
    val targetProtein: Int? = null,
    val targetCarbs: Int? = null,
    val targetFat: Int? = null,
    val targetFiber: Int? = null,
    val weeklyWorkoutGoal: Int? = null,
    val workoutGoal: String = "",
    val weightUnit: String = "KG",
    val lengthUnit: String = "CM",
    val heightCm: Double? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val primaryMuscle: String = "Other",
    val secondaryMuscles: String = "",
    val category: String = "STRENGTH",
    val equipment: String = "OTHER",
    val type: String = "WEIGHT_REPS",
    val instructions: String = "",
    val trackStats: Boolean = true,
    val isBuiltIn: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = 0L
)

@Serializable
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val notes: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
@Entity(
    tableName = "routine_exercises",
    foreignKeys = [
        ForeignKey(entity = RoutineEntity::class, parentColumns = ["id"], childColumns = ["routineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["routineId", "position"]), Index(value = ["exerciseId"])]
)
data class RoutineExerciseEntity(
    @PrimaryKey val id: String,
    val routineId: String,
    val exerciseId: String,
    val position: Int,
    val targetSets: Int = 3,
    val targetReps: Int? = null,
    val restSeconds: Int? = null,
    val notes: String = ""
)

@Serializable
@Entity(
    tableName = "workouts",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["projectId", "date"]), Index(value = ["projectId", "status"])]
)
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val routineId: String? = null,
    val name: String,
    val date: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationSec: Int? = null,
    val status: String = "IN_PROGRESS",
    val notes: String = ""
)

@Serializable
@Entity(
    tableName = "workout_exercises",
    foreignKeys = [
        ForeignKey(entity = WorkoutEntity::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["workoutId", "position"]), Index(value = ["exerciseId"])]
)
data class WorkoutExerciseEntity(
    @PrimaryKey val id: String,
    val workoutId: String,
    val exerciseId: String,
    val position: Int,
    val supersetGroup: Int? = null,
    val restSeconds: Int? = null,
    val notes: String = ""
)

@Serializable
@Entity(
    tableName = "workout_sets",
    foreignKeys = [ForeignKey(entity = WorkoutExerciseEntity::class, parentColumns = ["id"], childColumns = ["workoutExerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["workoutExerciseId", "position"])]
)
data class WorkoutSetEntity(
    @PrimaryKey val id: String,
    val workoutExerciseId: String,
    val position: Int,
    val setType: String = "WORKING",
    val reps: Int? = null,
    /** Load in kg. For bodyweight exercises this is added (+) or assisted (-) weight. */
    val weightKg: Double? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val rpe: Double? = null,
    val completed: Boolean = false,
    val notes: String = ""
)

@Serializable
@Entity(tableName = "foods", indices = [Index(value = ["name"])])
data class FoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String = "",
    val category: String = "",
    /** "G" or "ML": what the per-100 values refer to. */
    val basis: String = "G",
    val servingSize: Double? = null,
    val servingLabel: String = "serving",
    val kcal100: Double = 0.0,
    val protein100: Double = 0.0,
    val carbs100: Double = 0.0,
    val fat100: Double = 0.0,
    val fiber100: Double = 0.0,
    val sugar100: Double = 0.0,
    val notes: String = "",
    val favorite: Boolean = false,
    val lastUsedAt: Long? = null,
    val archived: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun per100() = Nutrients(kcal100, protein100, carbs100, fat100, fiber100, sugar100)
    fun baseUnit() = if (basis == "ML") "ml" else "g"
}

/**
 * A logged food. Nutrition values are a SNAPSHOT taken when the entry was logged, so later edits to the
 * food definition never rewrite diary history. foodId is only a convenience link (SET NULL on delete).
 */
@Serializable
@Entity(
    tableName = "food_entries",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["foodId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index(value = ["projectId", "date"]), Index(value = ["foodId"])]
)
data class FoodEntryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val date: String,
    val mealName: String,
    val foodId: String? = null,
    val name: String,
    val quantity: Double,
    /** "g", "ml" or "serving". */
    val unit: String,
    val amountBase: Double = 0.0,
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun nutrients() = Nutrients(kcal, protein, carbs, fat, fiber, sugar)
}

@Serializable
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val notes: String = "",
    val servings: Double = 1.0,
    val favorite: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
@Entity(
    tableName = "recipe_ingredients",
    foreignKeys = [
        ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["foodId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["recipeId", "position"]), Index(value = ["foodId"])]
)
data class RecipeIngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val foodId: String,
    val grams: Double,
    val position: Int
)

@Serializable
@Entity(
    tableName = "weight_entries",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["projectId", "date"])]
)
data class WeightEntryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val date: String,
    val weightKg: Double,
    val notes: String = "",
    val createdAt: Long = 0L
)

@Serializable
@Entity(tableName = "measurement_defs")
data class MeasurementDefEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** "LENGTH" (stored in cm) or "PERCENT". */
    val kind: String = "LENGTH",
    val isBuiltIn: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0
)

@Serializable
@Entity(
    tableName = "measurement_entries",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MeasurementDefEntity::class, parentColumns = ["id"], childColumns = ["defId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["projectId", "defId", "date"]), Index(value = ["defId"])]
)
data class MeasurementEntryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val defId: String,
    val date: String,
    val value: Double,
    val notes: String = "",
    val createdAt: Long = 0L
)
