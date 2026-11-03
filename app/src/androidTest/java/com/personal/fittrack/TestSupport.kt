package com.personal.fittrack

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.fittrack.data.*

object TestSupport {
    val ctx: Context get() = ApplicationProvider.getApplicationContext()
    fun memoryDb(): FitDatabase = Room.inMemoryDatabaseBuilder(ctx, FitDatabase::class.java).build()
    fun project(db: FitDatabase, name: String, kcal: Int? = null) = kotlinx.coroutines.runBlocking {
        ProjectRepository(db).create(ProjectEntity(id = "", name = name, targetKcal = kcal))
    }
    fun food(per100: Double = 165.0, name: String = "Chicken breast") =
        FoodEntity(id = "", name = name, kcal100 = per100, protein100 = 31.0, carbs100 = 0.0, fat100 = 3.6, servingSize = 120.0, servingLabel = "fillet")
}
