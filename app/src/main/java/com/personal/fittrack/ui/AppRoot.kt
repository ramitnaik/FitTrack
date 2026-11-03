package com.personal.fittrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = remember {
        listOf(
            TabItem("home", "Home", Icons.Default.Home),
            TabItem("workouts", "Workouts", Icons.Default.FitnessCenter),
            TabItem("nutrition", "Nutrition", Icons.Default.Restaurant),
            TabItem("progress", "Progress", Icons.Default.ShowChart),
            TabItem("projects", "Projects", Icons.Default.Folder)
        )
    }
    val showBar = tabs.any { it.route == route }
    val imeVisible = WindowInsets.isImeVisible

    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (showBar && !imeVisible) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        val idArg = listOf(navArgument("id") { type = NavType.StringType })
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("workouts") { WorkoutsScreen(nav) }
            composable("nutrition") { NutritionScreen(nav) }
            composable("progress") { ProgressScreen(nav) }
            composable("projects") { ProjectsScreen(nav) }
            composable("settings") { SettingsScreen(nav) }
            composable("history") { HistoryScreen(nav) }
            composable("routines") { RoutinesScreen(nav) }
            composable("exercises") { ExercisesScreen(nav) }
            composable("foods") { FoodListScreen(nav, logMode = false) }
            composable("logfood") { FoodListScreen(nav, logMode = true) }
            composable("recipes") { RecipesScreen(nav) }
            composable("workout/{id}", idArg) { WorkoutScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("routine/{id}", idArg) { RoutineEditScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("exercise/{id}", idArg) { ExerciseStatsScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("food/{id}", idArg) { FoodEditScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("recipe/{id}", idArg) { RecipeEditScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("project/{id}", idArg) { ProjectEditScreen(nav, it.arguments?.getString("id").orEmpty()) }
        }
    }
}
