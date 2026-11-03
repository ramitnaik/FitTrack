package com.personal.fittrack.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small SharedPreferences wrapper exposing StateFlows. Persisted across restarts and updates. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fittrack_prefs", Context.MODE_PRIVATE)

    private val _activeProjectId = MutableStateFlow(sp.getString(K_ACTIVE, null))
    val activeProjectId: StateFlow<String?> = _activeProjectId

    private val _theme = MutableStateFlow(sp.getString(K_THEME, "SYSTEM") ?: "SYSTEM")
    val theme: StateFlow<String> = _theme

    private val _restSeconds = MutableStateFlow(sp.getInt(K_REST, 90))
    val restSeconds: StateFlow<Int> = _restSeconds

    private val _mealNames = MutableStateFlow(readMeals())
    val mealNames: StateFlow<List<String>> = _mealNames

    private val _defaultWeightUnit = MutableStateFlow(sp.getString(K_WUNIT, "KG") ?: "KG")
    val defaultWeightUnit: StateFlow<String> = _defaultWeightUnit

    private val _defaultLengthUnit = MutableStateFlow(sp.getString(K_LUNIT, "CM") ?: "CM")
    val defaultLengthUnit: StateFlow<String> = _defaultLengthUnit

    var seedVersion: Int
        get() = sp.getInt(K_SEED, 0)
        set(v) { sp.edit().putInt(K_SEED, v).apply() }

    fun setActiveProject(id: String?) { sp.edit().putString(K_ACTIVE, id).apply(); _activeProjectId.value = id }
    fun setTheme(v: String) { sp.edit().putString(K_THEME, v).apply(); _theme.value = v }
    fun setRestSeconds(v: Int) { sp.edit().putInt(K_REST, v).apply(); _restSeconds.value = v }
    fun setMealNames(v: List<String>) {
        val clean = v.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { DEFAULT_MEALS }
        sp.edit().putString(K_MEALS, clean.joinToString("\n")).apply(); _mealNames.value = clean
    }
    fun setDefaultWeightUnit(v: String) { sp.edit().putString(K_WUNIT, v).apply(); _defaultWeightUnit.value = v }
    fun setDefaultLengthUnit(v: String) { sp.edit().putString(K_LUNIT, v).apply(); _defaultLengthUnit.value = v }

    /** Preferences included in full backups. */
    fun snapshot(): Map<String, String> = mapOf(
        K_THEME to _theme.value, K_REST to _restSeconds.value.toString(), K_MEALS to _mealNames.value.joinToString("\n"),
        K_WUNIT to _defaultWeightUnit.value, K_LUNIT to _defaultLengthUnit.value
    )

    fun restore(m: Map<String, String>) {
        m[K_THEME]?.let { setTheme(it) }
        m[K_REST]?.toIntOrNull()?.let { setRestSeconds(it) }
        m[K_MEALS]?.let { setMealNames(it.split("\n")) }
        m[K_WUNIT]?.let { setDefaultWeightUnit(it) }
        m[K_LUNIT]?.let { setDefaultLengthUnit(it) }
    }

    private fun readMeals(): List<String> =
        sp.getString(K_MEALS, null)?.split("\n")?.filter { it.isNotBlank() }?.ifEmpty { null } ?: DEFAULT_MEALS

    companion object {
        val DEFAULT_MEALS = listOf("Breakfast", "Lunch", "Dinner", "Snacks")
        private const val K_ACTIVE = "active_project"
        private const val K_THEME = "theme"
        private const val K_REST = "rest_seconds"
        private const val K_MEALS = "meal_names"
        private const val K_WUNIT = "default_weight_unit"
        private const val K_LUNIT = "default_length_unit"
        private const val K_SEED = "seed_version"
    }
}
