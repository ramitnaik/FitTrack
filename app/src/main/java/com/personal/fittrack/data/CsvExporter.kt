package com.personal.fittrack.data

import com.personal.fittrack.domain.fmt

enum class CsvKind(val label: String, val fileName: String) {
    WORKOUTS("Workouts (every set)", "fittrack_workouts.csv"),
    FOOD("Food diary", "fittrack_food.csv"),
    WEIGHT("Body weight", "fittrack_weight.csv"),
    MEASUREMENTS("Body measurements", "fittrack_measurements.csv")
}

class CsvExporter(db: FitDatabase) {
    private val dao = db.backupDao()

    private fun esc(v: Any?): String {
        val s = when (v) { null -> ""; is Double -> fmt(v, 3); is Boolean -> if (v) "1" else "0"; else -> v.toString() }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun line(vararg v: Any?) = v.joinToString(",") { esc(it) } + "\r\n"

    /** projectId == null exports every project. Weights are in kg, lengths in cm, distances in metres. */
    suspend fun build(kind: CsvKind, projectId: String?, from: String = "0000-01-01", to: String = "9999-12-31"): String {
        val sb = StringBuilder()
        when (kind) {
            CsvKind.WORKOUTS -> {
                sb.append(line("project", "date", "workout", "workout_duration_sec", "exercise", "set_number", "set_type", "reps", "weight_kg", "duration_sec", "distance_m", "rpe", "completed", "notes"))
                dao.csvWorkouts(projectId, from, to).forEach { sb.append(line(it.project, it.date, it.workoutName, it.durationSec, it.exercise, it.setNumber, it.setType, it.reps, it.weightKg, it.durationSecSet, it.distanceM, it.rpe, it.completed, it.setNotes)) }
            }
            CsvKind.FOOD -> {
                sb.append(line("project", "date", "meal", "food", "quantity", "unit", "kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g"))
                dao.csvFood(projectId, from, to).forEach { sb.append(line(it.project, it.date, it.meal, it.name, it.quantity, it.unit, it.kcal, it.protein, it.carbs, it.fat, it.fiber, it.sugar)) }
            }
            CsvKind.WEIGHT -> {
                sb.append(line("project", "date", "weight_kg", "notes"))
                dao.csvWeights(projectId, from, to).forEach { sb.append(line(it.project, it.date, it.weightKg, it.notes)) }
            }
            CsvKind.MEASUREMENTS -> {
                sb.append(line("project", "date", "measurement", "unit", "value", "notes"))
                dao.csvMeasurements(projectId, from, to).forEach { sb.append(line(it.project, it.date, it.measurement, if (it.kind == "PERCENT") "%" else "cm", it.value, it.notes)) }
            }
        }
        return sb.toString()
    }
}
