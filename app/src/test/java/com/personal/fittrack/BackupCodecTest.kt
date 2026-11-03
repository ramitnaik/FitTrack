package com.personal.fittrack

import com.personal.fittrack.data.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun sample(): BackupFile {
        val p1 = ProjectEntity("p1", "Lean Bulk", targetKcal = 2800, createdAt = 1, updatedAt = 1)
        val p2 = ProjectEntity("p2", "Cut", targetKcal = 2100, createdAt = 2, updatedAt = 2)
        val ex = ExerciseEntity("e1", "Bench", "Chest")
        val w = WorkoutEntity("w1", "p1", null, "Push", "2026-10-01", 1000L, status = "COMPLETED")
        val we = WorkoutExerciseEntity("we1", "w1", "e1", 0)
        val s = WorkoutSetEntity("s1", "we1", 0, reps = 8, weightKg = 80.0, completed = true)
        val food = FoodEntity("f1", "Rice", kcal100 = 130.0)
        val fe = FoodEntryEntity("fe1", "p2", "2026-10-02", "Lunch", "f1", "Rice", 150.0, "g", 150.0, 195.0)
        val d = MeasurementDefEntity("m1", "Waist")
        val m = MeasurementEntryEntity("me1", "p1", "m1", "2026-10-03", 81.0)
        return BackupFile(exportedAt = "2026-10-23T00:00:00Z", projects = listOf(p1, p2), exercises = listOf(ex), foods = listOf(food),
            measurementDefs = listOf(d), workouts = listOf(w), workoutExercises = listOf(we), workoutSets = listOf(s),
            foodEntries = listOf(fe), weightEntries = listOf(WeightEntryEntity("we", "p1", "2026-10-01", 75.0)), measurementEntries = listOf(m))
    }

    @Test fun roundTripPreservesEverything() {
        val text = BackupCodec.encode(sample())
        assertTrue("human readable", text.contains("\n") && text.contains("\"Lean Bulk\""))
        val back = BackupCodec.parse(text)
        assertEquals(sample(), back)
    }

    @Test fun validBackupHasNoProblems() = assertTrue(BackupCodec.validate(sample()).isEmpty())

    @Test fun emptyFileRejected() {
        val e = assertThrows(BackupException::class.java) { BackupCodec.parse("   ") }
        assertTrue(e.message!!.contains("empty"))
    }

    @Test fun garbageRejectedWithFriendlyMessage() {
        val e = assertThrows(BackupException::class.java) { BackupCodec.parse("{ this is not json") }
        assertTrue(e.message!!.contains("not a valid"))
    }

    @Test fun wrongFormatRejected() {
        assertThrows(BackupException::class.java) { BackupCodec.parse("""{"format":"something-else","backupVersion":1}""") }
    }

    @Test fun newerVersionRejected() {
        val e = assertThrows(BackupException::class.java) { BackupCodec.parse("""{"format":"fittrack-backup","backupVersion":99}""") }
        assertTrue(e.message!!.contains("newer"))
    }

    @Test fun unknownFieldsFromNewerCompatibleFilesAreIgnored() {
        val f = BackupCodec.parse("""{"format":"fittrack-backup","backupVersion":1,"futureField":123,"projects":[]}""")
        assertTrue(f.projects.isEmpty())
    }

    @Test fun orphanedRecordsAreReported() {
        val s = sample()
        val broken = s.copy(workouts = s.workouts.map { it.copy(projectId = "missing") }, workoutExercises = s.workoutExercises.map { it.copy(exerciseId = "nope") })
        val problems = BackupCodec.validate(broken)
        assertTrue(problems.any { it.contains("project that is not in the file") })
        assertTrue(problems.any { it.contains("exercise") })
    }

    @Test fun existingCatalogIdsSatisfyReferences() {
        val s = sample().copy(exercises = emptyList())
        assertFalse(BackupCodec.validate(s).isEmpty())
        assertTrue(BackupCodec.validate(s, ExistingIds(exercises = setOf("e1"))).isEmpty())
    }

    @Test fun invalidDatesReported() {
        val s = sample(); val bad = s.copy(weightEntries = listOf(s.weightEntries[0].copy(date = "31/12/2026")))
        assertTrue(BackupCodec.validate(bad).any { it.contains("Invalid date") })
    }
}
