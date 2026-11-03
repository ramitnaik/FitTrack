package com.personal.fittrack

import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RepositoryTests {
    private lateinit var db: FitDatabase
    private lateinit var projects: ProjectRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var nutrition: NutritionRepository
    private lateinit var body: BodyRepository

    @Before fun setUp() = runBlocking {
        db = TestSupport.memoryDb()
        projects = ProjectRepository(db); workouts = WorkoutRepository(db); nutrition = NutritionRepository(db); body = BodyRepository(db)
        Seeds.forceReseed(db)
    }
    @After fun tearDown() { db.close() }

    private suspend fun exId(slug: String) = "ex_$slug"
    private val all = "0000-01-01" to "9999-12-31"

    @Test fun createAndSwitchProjects() = runBlocking {
        val a = TestSupport.project(db, "Lean Bulk 2026", 2800)
        val b = TestSupport.project(db, "Cutting Phase", 2000)
        val list = projects.observeAll().first()
        assertEquals(setOf("Lean Bulk 2026", "Cutting Phase"), list.map { it.name }.toSet())
        assertEquals(2800, projects.get(a.id)!!.targetKcal); assertEquals(2000, projects.get(b.id)!!.targetKcal)
    }

    @Test fun projectsKeepWorkoutsFoodWeightAndMeasurementsSeparate() = runBlocking {
        val a = TestSupport.project(db, "A"); val b = TestSupport.project(db, "B")
        val food = nutrition.saveFood(TestSupport.food())
        nutrition.logFood(a.id, "2026-10-01", "Lunch", food, "g", 150.0)
        val wa = workouts.startEmpty(a.id); workouts.addExercises(wa, listOf(exId("squat"))); workouts.finish(wa, false)
        body.saveWeight(a.id, null, "2026-10-01", 80.0, ""); body.saveMeasurements(a.id, "2026-10-01", mapOf("m_waist" to 85.0), "")

        assertEquals(1, nutrition.observeDay(a.id, "2026-10-01").first().size)
        assertEquals(0, nutrition.observeDay(b.id, "2026-10-01").first().size)
        assertEquals(1, workouts.observeHistory(a.id, "", all.first, all.second, 50).first().size)
        assertEquals(0, workouts.observeHistory(b.id, "", all.first, all.second, 50).first().size)
        assertEquals(1, body.observeWeights(a.id).first().size); assertEquals(0, body.observeWeights(b.id).first().size)
        assertEquals(1, body.observeMeasurements(a.id).first().size); assertEquals(0, body.observeMeasurements(b.id).first().size)
        // previous-performance lookup is project scoped too
        assertTrue(workouts.previousSets(b.id, exId("squat"), "none").isEmpty())
    }

    @Test fun workoutWithMultipleExercisesAndSetsThenEditCompletedWorkout() = runBlocking {
        val p = TestSupport.project(db, "P")
        val w = workouts.startEmpty(p.id)
        workouts.addExercises(w, listOf(exId("bench_press"), exId("barbell_row")))
        val rows = workouts.observeRows(w).first(); assertEquals(2, rows.size)
        val bench = rows[0].we
        val sets = db.workoutDao().getSets(bench.id)
        workouts.setWeight(sets[0].id, 80.0); workouts.setReps(sets[0].id, 8); workouts.setCompleted(sets[0].id, true)
        val s2 = workouts.addSet(bench.id, false); workouts.setCompleted(s2.id, true)
        val w2 = workouts.addSet(bench.id, true); assertEquals(SetType.WARMUP, w2.setType)
        workouts.finish(w, discardIncomplete = true)
        var done = workouts.observeWorkout(w).first()!!
        assertEquals(WorkoutStatus.COMPLETED, done.status)
        assertEquals(2, db.workoutDao().getSets(bench.id).size)          // warm-up (incomplete) discarded

        // edit afterwards: correct reps, add a missed set, change date and name
        workouts.setReps(sets[0].id, 10)
        val missed = workouts.addSet(bench.id, false); workouts.setReps(missed.id, 6); workouts.setWeight(missed.id, 70.0); workouts.setCompleted(missed.id, true)
        workouts.updateWorkout(done.copy(name = "Push (fixed)", date = "2026-09-30"))
        done = workouts.observeWorkout(w).first()!!
        assertEquals("Push (fixed)", done.name); assertEquals("2026-09-30", done.date); assertEquals("2026-09-30", Dates.dateOf(done.startedAt))
        assertEquals(10, db.workoutDao().getSet(sets[0].id)!!.reps)
        assertEquals(3, db.workoutDao().getSets(bench.id).size)
        val item = workouts.observeHistory(p.id, "bench", all.first, all.second, 10).first().single()
        assertEquals(3, item.setCount)
    }

    @Test fun customExerciseCanBeCreatedEditedAndUsed() = runBlocking {
        val e = workouts.saveExercise(ExerciseEntity(id = "", name = "Zercher Squat", primaryMuscle = "Quads", equipment = "BARBELL"))
        assertTrue(e.id.isNotBlank())
        workouts.saveExercise(e.copy(name = "Zercher Squat (paused)"))
        assertEquals("Zercher Squat (paused)", db.exerciseDao().get(e.id)!!.name)
        val p = TestSupport.project(db, "P"); val w = workouts.startEmpty(p.id); workouts.addExercises(w, listOf(e.id))
        // used in history -> delete archives instead of removing
        assertFalse(workouts.deleteOrArchiveExercise(e.id)); assertTrue(db.exerciseDao().get(e.id)!!.archived)
        // unused custom exercise is really deleted
        val u = workouts.saveExercise(ExerciseEntity(id = "", name = "Unused")); assertTrue(workouts.deleteOrArchiveExercise(u.id)); assertNull(db.exerciseDao().get(u.id))
    }

    @Test fun foodNutritionScalesAndDailyTotalsAreCorrect() = runBlocking {
        val p = TestSupport.project(db, "P")
        val chicken = nutrition.saveFood(TestSupport.food())
        val e = nutrition.logFood(p.id, "2026-10-05", "Lunch", chicken, "g", 150.0)
        assertEquals(247.5, e.kcal, 0.001); assertEquals(46.5, e.protein, 0.001)
        val e2 = nutrition.logFood(p.id, "2026-10-05", "Dinner", chicken, "serving", 1.0)   // 120 g fillet
        assertEquals(198.0, e2.kcal, 0.001)
        val totals = NutritionCalc.sum(nutrition.observeDay(p.id, "2026-10-05").first().map { it.nutrients() })
        assertEquals(445.5, totals.kcal, 0.001)
        nutrition.logQuick(p.id, "2026-10-05", "Snacks", "Biscuit", Nutrients(100.0, 2.0, 15.0, 4.0))
        assertEquals(545.5, NutritionCalc.sum(nutrition.observeDay(p.id, "2026-10-05").first().map { it.nutrients() }).kcal, 0.001)
    }

    @Test fun editingFoodDefinitionNeverRewritesHistoryButEditingEntryDoes() = runBlocking {
        val p = TestSupport.project(db, "P")
        val f = nutrition.saveFood(TestSupport.food(165.0))
        val e = nutrition.logFood(p.id, "2026-10-05", "Lunch", f, "g", 100.0)
        nutrition.saveFood(f.copy(kcal100 = 999.0))
        assertEquals(165.0, db.foodEntryDao().get(e.id)!!.kcal, 0.001)           // history intact
        val e2 = nutrition.logFood(p.id, "2026-10-06", "Lunch", nutrition.getFood(f.id)!!, "g", 100.0)
        assertEquals(999.0, e2.kcal, 0.001)                                        // new log uses new definition
        nutrition.updateEntry(e.copy(kcal = 170.0, quantity = 105.0))
        assertEquals(170.0, db.foodEntryDao().get(e.id)!!.kcal, 0.001)
        nutrition.deleteEntry(e.id); assertNull(db.foodEntryDao().get(e.id))
        assertNull(nutrition.deleteFood(f.id)); assertNotNull(db.foodEntryDao().get(e2.id))   // entry survives food deletion
        assertNull(db.foodEntryDao().get(e2.id)!!.foodId)
    }

    @Test fun targetChangesDoNotTouchPastIntake() = runBlocking {
        val p = TestSupport.project(db, "P", 2500)
        val f = nutrition.saveFood(TestSupport.food())
        val e = nutrition.logFood(p.id, "2026-09-01", "Lunch", f, "g", 200.0)
        projects.save(projects.get(p.id)!!.copy(targetKcal = 1800, targetProtein = 160))
        assertEquals(1800, projects.get(p.id)!!.targetKcal)
        assertEquals(e, db.foodEntryDao().get(e.id)!!.copy(updatedAt = e.updatedAt))
    }

    @Test fun recipeNutritionAndLogging() = runBlocking {
        val p = TestSupport.project(db, "P")
        val rice = nutrition.saveFood(FoodEntity(id = "", name = "Rice", kcal100 = 130.0, carbs100 = 28.0))
        val chicken = nutrition.saveFood(TestSupport.food())
        val rid = nutrition.saveRecipe(RecipeEntity(id = "", name = "Rice & chicken", servings = 2.0), listOf(rice.id to 300.0, chicken.id to 200.0))
        val ings = nutrition.ingredients(rid)
        val n = nutrition.nutritionOf(nutrition.getRecipe(rid)!!, ings.map { it.food to it.ingredient.grams })
        assertEquals(720.0, n.total.kcal, 0.001); assertEquals(360.0, n.perServing.kcal, 0.001)
        val entry = nutrition.logRecipe(p.id, "2026-10-07", "Dinner", nutrition.getRecipe(rid)!!, n.perServing, 1.5)
        assertEquals(540.0, entry.kcal, 0.001)
        assertNotNull(nutrition.deleteFood(rice.id))      // blocked: used by a recipe
    }

    @Test fun weightAndMeasurementsWithMissingValuesAndCorrections() = runBlocking {
        val p = TestSupport.project(db, "P")
        body.saveWeight(p.id, null, "2026-10-01", 80.0, "")
        body.saveWeight(p.id, null, "2026-10-08", 79.4, "morning")
        val w = body.observeWeights(p.id).first(); assertEquals(2, w.size)
        body.saveWeight(p.id, w[1].id, "2026-10-08", 79.0, "corrected")
        assertEquals(79.0, body.observeWeights(p.id).first()[1].weightKg, 0.0)
        body.deleteWeight(w[0].id); assertEquals(1, body.observeWeights(p.id).first().size)

        body.saveMeasurements(p.id, "2026-10-01", mapOf("m_waist" to 85.0), "")                  // only waist today
        body.saveMeasurements(p.id, "2026-10-05", mapOf("m_left_arm" to 38.0, "m_body_fat" to 15.5), "")
        val m = body.observeMeasurements(p.id).first()
        assertEquals(3, m.size)
        assertTrue(m.none { it.entry.defId == "m_chest" })      // never stored as zero
        val bf = m.first { it.entry.defId == "m_body_fat" }; assertEquals("PERCENT", bf.kind)
        body.updateMeasurement(bf.entry.copy(value = 15.0)); body.deleteMeasurement(m.first { it.entry.defId == "m_waist" }.entry.id)
        assertEquals(2, body.observeMeasurements(p.id).first().size)
    }

    @Test fun routineStartsWorkoutWithPrefilledSets() = runBlocking {
        val p = TestSupport.project(db, "P")
        val r = workouts.createRoutine("Push"); workouts.addRoutineExercises(r, listOf(exId("bench_press"), exId("ohp")))
        val w = workouts.startFromRoutine(p.id, r)
        assertEquals(2, workouts.observeRows(w).first().size)
        assertEquals(3, workouts.observeSets(w).first().groupBy { it.workoutExerciseId }.values.first().size)
        workouts.moveExercise(workouts.observeRows(w).first()[1].we.id, -1)
        assertEquals(exId("ohp"), workouts.observeRows(w).first()[0].we.exerciseId)
    }

    @Test fun deletingProjectRemovesOnlyItsData() = runBlocking {
        val a = TestSupport.project(db, "A"); val b = TestSupport.project(db, "B")
        val f = nutrition.saveFood(TestSupport.food())
        nutrition.logFood(a.id, "2026-10-01", "Lunch", f, "g", 100.0); nutrition.logFood(b.id, "2026-10-01", "Lunch", f, "g", 100.0)
        projects.delete(a.id)
        assertEquals(0, nutrition.observeDay(a.id, "2026-10-01").first().size); assertEquals(1, nutrition.observeDay(b.id, "2026-10-01").first().size)
        assertNotNull(nutrition.getFood(f.id))        // shared catalog untouched
    }
}
