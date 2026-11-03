package com.personal.fittrack.data

import com.personal.fittrack.domain.ExType

/**
 * Built-in catalog (exercise library + standard measurement types). This is reference data, not user data:
 * IDs are deterministic so re-seeding never duplicates and never overwrites user edits (INSERT IGNORE).
 * No projects, workouts, foods or diary entries are ever created automatically.
 */
object Seeds {
    const val VERSION = 1
    val MUSCLES = listOf("Chest", "Back", "Shoulders", "Biceps", "Triceps", "Forearms", "Quads", "Hamstrings", "Glutes", "Calves", "Core", "Traps", "Full Body", "Cardio", "Other")
    val EQUIPMENT = listOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT", "KETTLEBELL", "BAND", "CARDIO", "OTHER")
    val CATEGORIES = listOf("STRENGTH", "CARDIO", "MOBILITY", "OTHER")

    private fun ex(slug: String, name: String, muscle: String, secondary: String, equip: String, type: String = ExType.WEIGHT_REPS) =
        ExerciseEntity(
            id = "ex_$slug", name = name, primaryMuscle = muscle, secondaryMuscles = secondary, equipment = equip, type = type,
            category = if (equip == "CARDIO") "CARDIO" else "STRENGTH", isBuiltIn = true, createdAt = 0L
        )

    private const val BW = ExType.BODYWEIGHT_REPS
    private const val DU = ExType.DURATION
    private const val DD = ExType.DISTANCE_DURATION

    val exercises: List<ExerciseEntity> = listOf(
        ex("bench_press", "Barbell Bench Press", "Chest", "Triceps, Shoulders", "BARBELL"),
        ex("incline_bench", "Incline Barbell Bench Press", "Chest", "Shoulders, Triceps", "BARBELL"),
        ex("db_bench", "Dumbbell Bench Press", "Chest", "Triceps, Shoulders", "DUMBBELL"),
        ex("incline_db_press", "Incline Dumbbell Press", "Chest", "Shoulders, Triceps", "DUMBBELL"),
        ex("machine_chest_press", "Machine Chest Press", "Chest", "Triceps", "MACHINE"),
        ex("cable_fly", "Cable Fly", "Chest", "Shoulders", "CABLE"),
        ex("db_fly", "Dumbbell Fly", "Chest", "", "DUMBBELL"),
        ex("push_up", "Push-Up", "Chest", "Triceps, Shoulders", "BODYWEIGHT", BW),
        ex("chest_dip", "Chest Dip", "Chest", "Triceps", "BODYWEIGHT", BW),
        ex("deadlift", "Deadlift", "Back", "Glutes, Hamstrings", "BARBELL"),
        ex("barbell_row", "Barbell Row", "Back", "Biceps", "BARBELL"),
        ex("db_row", "One-Arm Dumbbell Row", "Back", "Biceps", "DUMBBELL"),
        ex("seated_cable_row", "Seated Cable Row", "Back", "Biceps", "CABLE"),
        ex("lat_pulldown", "Lat Pulldown", "Back", "Biceps", "CABLE"),
        ex("pull_up", "Pull-Up", "Back", "Biceps", "BODYWEIGHT", BW),
        ex("chin_up", "Chin-Up", "Back", "Biceps", "BODYWEIGHT", BW),
        ex("t_bar_row", "T-Bar Row", "Back", "Biceps", "BARBELL"),
        ex("face_pull", "Face Pull", "Shoulders", "Back", "CABLE"),
        ex("ohp", "Overhead Press", "Shoulders", "Triceps", "BARBELL"),
        ex("db_shoulder_press", "Dumbbell Shoulder Press", "Shoulders", "Triceps", "DUMBBELL"),
        ex("lateral_raise", "Lateral Raise", "Shoulders", "", "DUMBBELL"),
        ex("cable_lateral_raise", "Cable Lateral Raise", "Shoulders", "", "CABLE"),
        ex("rear_delt_fly", "Rear Delt Fly", "Shoulders", "Back", "DUMBBELL"),
        ex("arnold_press", "Arnold Press", "Shoulders", "Triceps", "DUMBBELL"),
        ex("shrug", "Barbell Shrug", "Traps", "Forearms", "BARBELL"),
        ex("barbell_curl", "Barbell Curl", "Biceps", "Forearms", "BARBELL"),
        ex("db_curl", "Dumbbell Curl", "Biceps", "Forearms", "DUMBBELL"),
        ex("hammer_curl", "Hammer Curl", "Biceps", "Forearms", "DUMBBELL"),
        ex("preacher_curl", "Preacher Curl", "Biceps", "", "MACHINE"),
        ex("cable_curl", "Cable Curl", "Biceps", "", "CABLE"),
        ex("triceps_pushdown", "Triceps Pushdown", "Triceps", "", "CABLE"),
        ex("skull_crusher", "Skull Crusher", "Triceps", "", "BARBELL"),
        ex("overhead_tri_ext", "Overhead Triceps Extension", "Triceps", "", "DUMBBELL"),
        ex("close_grip_bench", "Close-Grip Bench Press", "Triceps", "Chest", "BARBELL"),
        ex("bench_dip", "Bench Dip", "Triceps", "Chest", "BODYWEIGHT", BW),
        ex("squat", "Barbell Squat", "Quads", "Glutes, Hamstrings", "BARBELL"),
        ex("front_squat", "Front Squat", "Quads", "Glutes, Core", "BARBELL"),
        ex("leg_press", "Leg Press", "Quads", "Glutes", "MACHINE"),
        ex("hack_squat", "Hack Squat", "Quads", "Glutes", "MACHINE"),
        ex("rdl", "Romanian Deadlift", "Hamstrings", "Glutes, Back", "BARBELL"),
        ex("leg_extension", "Leg Extension", "Quads", "", "MACHINE"),
        ex("leg_curl", "Leg Curl", "Hamstrings", "", "MACHINE"),
        ex("walking_lunge", "Walking Lunge", "Quads", "Glutes", "DUMBBELL"),
        ex("bulgarian_split_squat", "Bulgarian Split Squat", "Quads", "Glutes", "DUMBBELL"),
        ex("hip_thrust", "Hip Thrust", "Glutes", "Hamstrings", "BARBELL"),
        ex("standing_calf_raise", "Standing Calf Raise", "Calves", "", "MACHINE"),
        ex("seated_calf_raise", "Seated Calf Raise", "Calves", "", "MACHINE"),
        ex("plank", "Plank", "Core", "", "BODYWEIGHT", DU),
        ex("hanging_leg_raise", "Hanging Leg Raise", "Core", "", "BODYWEIGHT", BW),
        ex("cable_crunch", "Cable Crunch", "Core", "", "CABLE"),
        ex("crunch", "Crunch", "Core", "", "BODYWEIGHT", BW),
        ex("ab_wheel", "Ab Wheel Rollout", "Core", "", "OTHER", BW),
        ex("treadmill", "Treadmill Run", "Cardio", "", "CARDIO", DD),
        ex("walking", "Walking", "Cardio", "", "CARDIO", DD),
        ex("cycling", "Cycling", "Cardio", "", "CARDIO", DD),
        ex("rowing_machine", "Rowing Machine", "Cardio", "", "CARDIO", DD),
        ex("elliptical", "Elliptical", "Cardio", "", "CARDIO", DD),
        ex("stair_climber", "Stair Climber", "Cardio", "", "CARDIO", DU),
        ex("jump_rope", "Jump Rope", "Cardio", "", "CARDIO", DU)
    )

    val measurementDefs: List<MeasurementDefEntity> = listOf(
        "Waist", "Chest", "Shoulders", "Hips", "Neck", "Left Arm", "Right Arm", "Left Forearm", "Right Forearm",
        "Left Thigh", "Right Thigh", "Left Calf", "Right Calf"
    ).mapIndexed { i, n ->
        MeasurementDefEntity("m_" + n.lowercase().replace(' ', '_'), n, "LENGTH", true, false, i)
    } + MeasurementDefEntity("m_body_fat", "Body Fat %", "PERCENT", true, false, 100)

    suspend fun ensure(db: FitDatabase, prefs: Prefs) {
        if (prefs.seedVersion >= VERSION) return
        forceReseed(db)
        prefs.seedVersion = VERSION
    }

    suspend fun forceReseed(db: FitDatabase) {
        db.exerciseDao().insertIgnore(exercises.map { it.copy(createdAt = nowMs()) })
        db.bodyDao().insertDefsIgnore(measurementDefs)
    }
}
