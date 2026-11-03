package com.personal.fittrack

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.fittrack.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupRestoreTests {
    private lateinit var db: FitDatabase
    private lateinit var backup: BackupRepository
    private lateinit var prefs: Prefs

    @Before fun setUp() = runBlocking {
        db = TestSupport.memoryDb(); prefs = Prefs(TestSupport.ctx)
        backup = BackupRepository(TestSupport.ctx, db, prefs); Seeds.forceReseed(db)
    }
    @After fun tearDown() { db.close() }

    private suspend fun populate(): Pair<ProjectEntity, ProjectEntity> {
        val workouts = WorkoutRepository(db); val nutrition = NutritionRepository(db); val body = BodyRepository(db)
        val a = TestSupport.project(db, "Lean Bulk", 2800); val b = TestSupport.project(db, "Cut", 2000)
        val food = nutrition.saveFood(TestSupport.food())
        nutrition.logFood(a.id, "2026-10-01", "Lunch", food, "g", 150.0); nutrition.logFood(b.id, "2026-10-02", "Dinner", food, "g", 200.0)
        val w = workouts.startEmpty(a.id); workouts.addExercises(w, listOf("ex_squat")); workouts.finish(w, false)
        body.saveWeight(a.id, null, "2026-10-01", 80.0, ""); body.saveWeight(b.id, null, "2026-10-02", 79.0, "")
        body.saveMeasurements(b.id, "2026-10-02", mapOf("m_waist" to 84.0), "")
        workouts.saveExercise(ExerciseEntity(id = "", name = "My Custom Lift"))
        return a to b
    }

    @Test fun fullBackupRoundTripRestoresMultipleProjects() = runBlocking {
        val (a, b) = populate()
        val text = BackupCodec.encode(backup.build(null))
        val parsed = BackupCodec.parse(text)
        val res = backup.import(parsed, RestoreMode.REPLACE_ALL)
        assertTrue(res.added > 0)
        assertEquals(setOf(a.id, b.id), db.backupDao().projectIds().toSet())
        assertEquals(1, NutritionRepository(db).observeDay(a.id, "2026-10-01").first().size)
        assertEquals(1, NutritionRepository(db).observeDay(b.id, "2026-10-02").first().size)
        assertEquals(1, BodyRepository(db).observeMeasurements(b.id).first().size)
        assertTrue(db.backupDao().exercises().any { it.name == "My Custom Lift" })
        assertEquals(1, db.backupDao().workoutsFor(a.id).size)
        assertEquals(0, db.backupDao().workoutsFor(b.id).size)
    }

    @Test fun importingSameBackupTwiceAddsNothing() = runBlocking {
        populate()
        val f = BackupCodec.parse(BackupCodec.encode(backup.build(null)))
        val r = backup.import(f, RestoreMode.MERGE)
        assertEquals(0, r.added); assertTrue(r.skipped > 0)
        assertEquals(2, db.backupDao().projects().size)
    }

    @Test fun projectBackupIsSelfContainedAndMergesIntoFreshInstall() = runBlocking {
        val (a, _) = populate()
        val only = backup.build(a.id)
        assertEquals("PROJECT", only.scope); assertEquals(listOf(a.id), only.projects.map { it.id })
        assertTrue(only.foodEntries.all { it.projectId == a.id })
        db.close()
        db = TestSupport.memoryDb(); backup = BackupRepository(TestSupport.ctx, db, prefs)     // "new phone"
        backup.import(BackupCodec.parse(BackupCodec.encode(only)), RestoreMode.MERGE)
        assertEquals(1, db.backupDao().projects().size)
        assertEquals(1, db.backupDao().foodEntriesFor(a.id).size)
        assertEquals(1, db.backupDao().workoutsFor(a.id).size)
        assertThrows(BackupException::class.java) { runBlocking { backup.import(only, RestoreMode.REPLACE_ALL) } }   // project file can't replace everything
    }

    @Test fun inconsistentBackupIsRejectedAndNothingChanges() = runBlocking {
        populate()
        val good = backup.build(null)
        val before = db.backupDao().projects().size
        val bad = good.copy(workouts = good.workouts.map { it.copy(projectId = "ghost", id = "new-id") })
        assertThrows(BackupException::class.java) { runBlocking { backup.import(bad, RestoreMode.MERGE) } }
        assertEquals(before, db.backupDao().projects().size)
        assertThrows(BackupException::class.java) { BackupCodec.parse("not a backup") }
    }

    @Test fun csvExportsContainHeadersAndRows() = runBlocking {
        val (a, _) = populate()
        val csv = CsvExporter(db)
        val food = csv.build(CsvKind.FOOD, a.id)
        assertTrue(food.startsWith("project,date,meal")); assertEquals(2, food.trim().lines().size)
        assertEquals(3, csv.build(CsvKind.FOOD, null).trim().lines().size)
        assertEquals(1, csv.build(CsvKind.FOOD, null, "2026-10-02", "2026-10-02").trim().lines().size - 1)
        assertTrue(csv.build(CsvKind.WORKOUTS, a.id).contains("Barbell Squat"))
        assertEquals(2, csv.build(CsvKind.WEIGHT, a.id).trim().lines().size)
    }
}

class PersistenceAndScaleTests {
    @Test fun dataSurvivesClosingAndReopeningTheDatabase() = runBlocking {
        val ctx = TestSupport.ctx; ctx.deleteDatabase("persist_test.db")
        var db = Room.databaseBuilder(ctx, FitDatabase::class.java, "persist_test.db").build()
        val p = ProjectRepository(db).create(ProjectEntity(id = "", name = "Persistent"))
        val food = NutritionRepository(db).saveFood(TestSupport.food())
        NutritionRepository(db).logFood(p.id, "2026-10-01", "Lunch", food, "g", 100.0)
        db.close()                                                     // simulates app being closed / killed
        db = Room.databaseBuilder(ctx, FitDatabase::class.java, "persist_test.db").build()
        assertEquals("Persistent", ProjectRepository(db).get(p.id)!!.name)
        assertEquals(1, NutritionRepository(db).observeDay(p.id, "2026-10-01").first().size)
        db.close(); ctx.deleteDatabase("persist_test.db")
    }

    @Test fun largeHistoryIsNeverTruncated() = runBlocking {
        val db = TestSupport.memoryDb()
        val p = TestSupport.project(db, "Years of data")
        val dao = db.backupDao()
        val base = java.time.LocalDate.of(2020, 1, 1)
        val entries = (0 until 30_000).map { i ->
            FoodEntryEntity("fe$i", p.id, base.plusDays((i % 2000).toLong()).toString(), "Lunch", null, "Item $i", 1.0, "serving", 0.0, 100.0 + i % 50, 5.0, 10.0, 2.0, 0.0, 0.0, i.toLong(), i.toLong())
        }
        entries.chunked(5000).forEach { dao.insertFoodEntries(it) }
        val workouts = (0 until 4000).map { i -> WorkoutEntity("w$i", p.id, null, "W$i", base.plusDays((i % 1500).toLong()).toString(), i.toLong(), 1L, 3600, "COMPLETED") }
        workouts.chunked(2000).forEach { dao.insertWorkouts(it) }

        assertEquals(2000, db.foodEntryDao().observeDayTotals(p.id, Int.MAX_VALUE).first().size)            // all days present
        assertEquals(4000, db.workoutDao().observeCompletedCount(p.id).first())
        assertEquals(30, db.workoutDao().observeHistory(p.id, "", "0000-01-01", "9999-12-31", 30).first().size)  // paging
        assertEquals(4000, db.workoutDao().observeHistory(p.id, "", "0000-01-01", "9999-12-31", Int.MAX_VALUE).first().size)
        assertEquals(30_000, dao.foodEntriesFor(p.id).size)
        db.close()
    }
}

class MigrationTests {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FitDatabase::class.java)

    /** Opens the v1 schema (exported to app/schemas on build) and runs all registered migrations; add real cases when v2 exists. */
    @Test fun schemaV1OpensAndAllMigrationsValidate() {
        helper.createDatabase("migration_test.db", 1).close()
        helper.runMigrationsAndValidate("migration_test.db", FitDatabase.VERSION, true, *ALL_MIGRATIONS)
    }
}
