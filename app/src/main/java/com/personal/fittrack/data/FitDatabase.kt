package com.personal.fittrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ProjectEntity::class, ExerciseEntity::class, RoutineEntity::class, RoutineExerciseEntity::class,
        WorkoutEntity::class, WorkoutExerciseEntity::class, WorkoutSetEntity::class,
        FoodEntity::class, FoodEntryEntity::class, RecipeEntity::class, RecipeIngredientEntity::class,
        WeightEntryEntity::class, MeasurementDefEntity::class, MeasurementEntryEntity::class
    ],
    version = FitDatabase.VERSION,
    exportSchema = true
)
abstract class FitDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun foodDao(): FoodDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun bodyDao(): BodyDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val VERSION = 1
        const val DB_NAME = "fittrack.db"

        fun build(ctx: Context): FitDatabase =
            Room.databaseBuilder(ctx.applicationContext, FitDatabase::class.java, DB_NAME)
                .addMigrations(*ALL_MIGRATIONS)
                // Deliberately NO fallbackToDestructiveMigration(): an update must never wipe user data.
                .build()
    }
}

/**
 * Add one Migration per schema bump here, e.g.
 *   val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE ...") } }
 * and append it to this array. Schema JSON files are exported to app/schemas/ on every build
 * so migrations can be verified with MigrationTestHelper.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf()
