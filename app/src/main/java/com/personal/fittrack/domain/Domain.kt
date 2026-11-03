package com.personal.fittrack.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** Exercise tracking types. Stored as strings in the database. */
object ExType {
    const val WEIGHT_REPS = "WEIGHT_REPS"
    const val BODYWEIGHT_REPS = "BODYWEIGHT_REPS"
    const val DURATION = "DURATION"
    const val DISTANCE_DURATION = "DISTANCE_DURATION"
    val all = listOf(WEIGHT_REPS, BODYWEIGHT_REPS, DURATION, DISTANCE_DURATION)
    fun label(t: String) = when (t) {
        WEIGHT_REPS -> "Weight × reps"
        BODYWEIGHT_REPS -> "Bodyweight (+/- weight) × reps"
        DURATION -> "Duration"
        DISTANCE_DURATION -> "Distance + duration"
        else -> t
    }
}

object SetType {
    const val WORKING = "WORKING"
    const val WARMUP = "WARMUP"
}

object WorkoutStatus {
    const val IN_PROGRESS = "IN_PROGRESS"
    const val COMPLETED = "COMPLETED"
}

object Dates {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    fun today(): String = LocalDate.now().toString()
    fun shift(date: String, days: Long): String = LocalDate.parse(date).plusDays(days).toString()
    fun isValid(date: String): Boolean = try { LocalDate.parse(date); true } catch (e: Exception) { false }
    fun pretty(date: String): String = try {
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()))
    } catch (e: Exception) { date }
    fun dateOf(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()
    /** Same local time-of-day as [millis], but on [newDate]. Keeps daily boundaries consistent when a date is edited. */
    fun moveToDate(millis: Long, newDate: String): Long =
        Instant.ofEpochMilli(millis).atZone(zone).with(LocalDate.parse(newDate)).toInstant().toEpochMilli()
    fun noonOf(date: String): Long = LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    fun timeOfDay(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    fun hourOfDay(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zone).hour
}

enum class WeightUnit(val label: String, val perKg: Double) {
    KG("kg", 1.0), LB("lb", 2.2046226218);
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: KG }
}

enum class LengthUnit(val label: String, val perCm: Double) {
    CM("cm", 1.0), IN("in", 0.3937007874);
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: CM }
}

object Units {
    fun kgTo(kg: Double, u: WeightUnit) = kg * u.perKg
    fun toKg(v: Double, u: WeightUnit) = v / u.perKg
    fun cmTo(cm: Double, u: LengthUnit) = cm * u.perCm
    fun toCm(v: Double, u: LengthUnit) = v / u.perCm
    const val METERS_PER_KM = 1000.0
    const val METERS_PER_MILE = 1609.344
    fun metersTo(m: Double, imperial: Boolean) = if (imperial) m / METERS_PER_MILE else m / METERS_PER_KM
    fun toMeters(v: Double, imperial: Boolean) = if (imperial) v * METERS_PER_MILE else v * METERS_PER_KM
}

/** Format a number with up to [decimals] decimals and no trailing zeros. */
fun fmt(v: Double, decimals: Int = 1): String {
    val p = Math.pow(10.0, decimals.toDouble())
    val r = Math.round(v * p) / p
    return if (r == floor(r) && abs(r) < 1e15) r.toLong().toString()
    else String.format(Locale.US, "%.${decimals}f", r).trimEnd('0').trimEnd('.')
}

fun String.toD(): Double? = trim().replace(',', '.').toDoubleOrNull()

fun r1(v: Double): Double = Math.round(v * 10.0) / 10.0

data class Nutrients(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0
) {
    operator fun plus(o: Nutrients) = Nutrients(kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat, fiber + o.fiber, sugar + o.sugar)
    operator fun times(f: Double) = Nutrients(kcal * f, protein * f, carbs * f, fat * f, fiber * f, sugar * f)
    fun rounded() = Nutrients(r1(kcal), r1(protein), r1(carbs), r1(fat), r1(fiber), r1(sugar))
}

object NutritionCalc {
    /** Nutrition for [amountBase] grams/millilitres of a food defined per 100 g/ml. Rounded to 1 decimal. */
    fun scale(per100: Nutrients, amountBase: Double): Nutrients = (per100 * (amountBase / 100.0)).rounded()

    /** Quantity in the food's base unit (g/ml). unit == "serving" multiplies by the serving size. */
    fun amountBase(servingSize: Double?, unit: String, quantity: Double): Double =
        if (unit == "serving") quantity * (servingSize ?: 0.0) else quantity

    fun sum(list: List<Nutrients>): Nutrients = list.fold(Nutrients()) { a, b -> a + b }.rounded()
}

/** One logged working/warm-up set from a completed workout, used for statistics. */
data class SetPoint(
    val date: String,
    val workoutId: String,
    val setType: String,
    val reps: Int?,
    val weightKg: Double?,
    val durationSec: Int?,
    val distanceM: Double?
)

data class Session(
    val date: String,
    val workoutId: String,
    val sets: List<SetPoint>,
    val bestE1rm: Double?,
    val maxWeight: Double?,
    val maxReps: Int?,
    val volume: Double,
    val totalDurationSec: Int,
    val totalDistanceM: Double
)

data class PersonalRecords(
    val maxWeight: Pair<Double, String>?,
    val bestE1rm: Pair<Double, String>?,
    val maxReps: Pair<Int, String>?,
    val bestVolume: Pair<Double, String>?,
    val longestDurationSec: Pair<Int, String>?,
    val longestDistanceM: Pair<Double, String>?
)

object StatsCalc {
    const val E1RM_METHOD = "Epley: weight × (1 + reps / 30); sets of 1–12 reps only"
    const val E1RM_MAX_REPS = 12

    fun epley1RM(weight: Double, reps: Int): Double? = when {
        weight <= 0.0 || reps <= 0 || reps > E1RM_MAX_REPS -> null
        reps == 1 -> weight
        else -> weight * (1.0 + reps / 30.0)
    }

    fun setVolume(type: String, weightKg: Double?, reps: Int?): Double = when (type) {
        ExType.WEIGHT_REPS -> (weightKg ?: 0.0) * (reps ?: 0)
        ExType.BODYWEIGHT_REPS -> max(weightKg ?: 0.0, 0.0) * (reps ?: 0)
        else -> 0.0
    }

    /** Group working sets (warm-ups excluded) by workout, in chronological order. */
    fun sessions(type: String, points: List<SetPoint>): List<Session> =
        points.filter { it.setType == SetType.WORKING }
            .groupBy { it.workoutId }
            .map { (id, sets) ->
                Session(
                    date = sets.first().date,
                    workoutId = id,
                    sets = sets,
                    bestE1rm = if (type == ExType.WEIGHT_REPS || type == ExType.BODYWEIGHT_REPS)
                        sets.mapNotNull { s -> if (type == ExType.WEIGHT_REPS && s.weightKg != null && s.reps != null) epley1RM(s.weightKg, s.reps) else null }.maxOrNull()
                    else null,
                    maxWeight = sets.mapNotNull { it.weightKg }.filter { it > 0 }.maxOrNull(),
                    maxReps = sets.mapNotNull { it.reps }.maxOrNull(),
                    volume = sets.sumOf { setVolume(type, it.weightKg, it.reps) },
                    totalDurationSec = sets.sumOf { it.durationSec ?: 0 },
                    totalDistanceM = sets.sumOf { it.distanceM ?: 0.0 }
                )
            }
            .sortedBy { it.date }

    fun records(sessions: List<Session>): PersonalRecords {
        fun <T : Comparable<T>> best(sel: (Session) -> T?): Pair<T, String>? =
            sessions.mapNotNull { s -> sel(s)?.let { it to s.date } }.maxByOrNull { it.first }
        return PersonalRecords(
            maxWeight = best { it.maxWeight },
            bestE1rm = best { it.bestE1rm },
            maxReps = best { it.maxReps },
            bestVolume = best { if (it.volume > 0) it.volume else null },
            longestDurationSec = best { if (it.totalDurationSec > 0) it.totalDurationSec else null },
            longestDistanceM = best { if (it.totalDistanceM > 0) it.totalDistanceM else null }
        )
    }
}
