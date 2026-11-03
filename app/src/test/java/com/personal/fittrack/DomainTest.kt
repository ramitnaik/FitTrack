package com.personal.fittrack

import com.personal.fittrack.domain.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val chicken = Nutrients(kcal = 165.0, protein = 31.0, carbs = 0.0, fat = 3.6)

    @Test fun scalesPer100gFoodTo150g() {
        val n = NutritionCalc.scale(chicken, 150.0)
        assertEquals(247.5, n.kcal, 0.001)
        assertEquals(46.5, n.protein, 0.001)
        assertEquals(5.4, n.fat, 0.001)
    }

    @Test fun servingUnitUsesServingSize() {
        assertEquals(90.0, NutritionCalc.amountBase(30.0, "serving", 3.0), 0.0001)
        assertEquals(250.0, NutritionCalc.amountBase(30.0, "g", 250.0), 0.0001)
        assertEquals(0.0, NutritionCalc.amountBase(null, "serving", 2.0), 0.0001)
    }

    @Test fun scalingIsRoundedConsistentlyToOneDecimal() {
        val n = NutritionCalc.scale(Nutrients(kcal = 33.33), 100.0 / 3.0)
        assertEquals(11.1, n.kcal, 0.0001)
    }

    @Test fun sumAddsAllFields() {
        val s = NutritionCalc.sum(listOf(Nutrients(100.0, 10.0, 5.0, 2.0), Nutrients(50.5, 1.0, 1.0, 1.0)))
        assertEquals(150.5, s.kcal, 0.0001); assertEquals(11.0, s.protein, 0.0001); assertEquals(3.0, s.fat, 0.0001)
    }

    @Test fun weightUnitRoundTrip() {
        val kg = 82.5
        val lb = Units.kgTo(kg, WeightUnit.LB)
        assertEquals(181.88, lb, 0.01)
        assertEquals(kg, Units.toKg(lb, WeightUnit.LB), 1e-9)
        assertEquals(100.0, Units.toCm(Units.cmTo(100.0, LengthUnit.IN), LengthUnit.IN), 1e-9)
    }

    @Test fun fmtTrimsZeros() {
        assertEquals("80", fmt(80.0)); assertEquals("80.5", fmt(80.50)); assertEquals("0.33", fmt(0.3333, 2))
    }

    @Test fun parsesCommaDecimals() {
        assertEquals(12.5, "12,5".toD()!!, 0.0); assertNull("".toD()); assertNull("abc".toD())
    }

    @Test fun epleyEstimate() {
        assertEquals(100.0, StatsCalc.epley1RM(100.0, 1)!!, 0.0001)
        assertEquals(133.333, StatsCalc.epley1RM(100.0, 10)!!, 0.001)
        assertNull(StatsCalc.epley1RM(100.0, 13))   // too many reps to be reliable
        assertNull(StatsCalc.epley1RM(0.0, 5))
    }

    @Test fun volumeOnlyWhereMeaningful() {
        assertEquals(800.0, StatsCalc.setVolume(ExType.WEIGHT_REPS, 100.0, 8), 0.0)
        assertEquals(0.0, StatsCalc.setVolume(ExType.BODYWEIGHT_REPS, null, 12), 0.0)       // pure bodyweight: no load volume
        assertEquals(0.0, StatsCalc.setVolume(ExType.BODYWEIGHT_REPS, -20.0, 8), 0.0)       // assisted
        assertEquals(100.0, StatsCalc.setVolume(ExType.BODYWEIGHT_REPS, 10.0, 10), 0.0)
        assertEquals(0.0, StatsCalc.setVolume(ExType.DURATION, 10.0, 10), 0.0)
    }

    private fun sp(date: String, wid: String, type: String, reps: Int, w: Double) = SetPoint(date, wid, type, reps, w, null, null)

    @Test fun sessionsExcludeWarmupsAndComputeRecords() {
        val pts = listOf(
            sp("2026-01-01", "a", SetType.WARMUP, 10, 40.0),
            sp("2026-01-01", "a", SetType.WORKING, 8, 80.0),
            sp("2026-01-08", "b", SetType.WORKING, 5, 90.0),
            sp("2026-01-08", "b", SetType.WORKING, 5, 90.0)
        )
        val sessions = StatsCalc.sessions(ExType.WEIGHT_REPS, pts)
        assertEquals(2, sessions.size)
        assertEquals(80.0, sessions[0].maxWeight!!, 0.0)           // warm-up 40 ignored, not max
        assertEquals(1, sessions[0].sets.size)
        val rec = StatsCalc.records(sessions)
        assertEquals(90.0, rec.maxWeight!!.first, 0.0); assertEquals("2026-01-08", rec.maxWeight!!.second)
        assertEquals(90.0 * (1 + 5 / 30.0), rec.bestE1rm!!.first, 0.001)
        assertEquals(900.0, rec.bestVolume!!.first, 0.0)
    }

    @Test fun datesShiftAndValidate() {
        assertEquals("2026-03-01", Dates.shift("2026-02-28", 1))
        assertEquals("2028-02-29", Dates.shift("2028-02-28", 1))
        assertTrue(Dates.isValid("2026-10-23")); assertFalse(Dates.isValid("2026-13-40")); assertFalse(Dates.isValid("nope"))
    }

    @Test fun movingTimestampKeepsTimeOfDayOnNewDate() {
        val noon = Dates.noonOf("2026-05-10")
        val moved = Dates.moveToDate(noon, "2026-05-12")
        assertEquals("2026-05-12", Dates.dateOf(moved))
        assertEquals(Dates.timeOfDay(noon), Dates.timeOfDay(moved))
    }
}
