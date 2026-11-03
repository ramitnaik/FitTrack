package com.personal.fittrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*

@Composable
fun DateNavigator(date: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onChange(Dates.shift(date, -1)) }) { Icon(Icons.Default.ChevronLeft, "Previous day") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            DateButton(date, onChange)
            if (date != Dates.today()) TextButton(onClick = { onChange(Dates.today()) }) { Text("Today") }
        }
        IconButton(onClick = { onChange(Dates.shift(date, 1)) }) { Icon(Icons.Default.ChevronRight, "Next day") }
    }
}

@Composable
fun NutritionSummaryCard(totals: Nutrients, p: ProjectEntity) {
    SectionCard("Daily summary") {
        val target = p.targetKcal
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${fmt(totals.kcal, 0)} kcal", style = MaterialTheme.typography.headlineSmall)
            if (target != null) {
                val diff = target - totals.kcal
                Text(if (diff >= 0) "${fmt(diff, 0)} left of $target" else "${fmt(-diff, 0)} over $target",
                    style = MaterialTheme.typography.titleSmall, color = if (diff >= 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            } else Text("No calorie target set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (target != null) MacroRow("Calories", totals.kcal, target, "kcal")
        MacroRow("Protein", totals.protein, p.targetProtein, "g")
        MacroRow("Carbs", totals.carbs, p.targetCarbs, "g")
        MacroRow("Fat", totals.fat, p.targetFat, "g")
        if (p.targetFiber != null || totals.fiber > 0) MacroRow("Fibre", totals.fiber, p.targetFiber, "g")
    }
}

@Composable
fun QuantityDialog(food: FoodEntity, title: String = "Add ${food.name}", onConfirm: (unit: String, qty: Double) -> Unit, onDismiss: () -> Unit) {
    val hasServing = (food.servingSize ?: 0.0) > 0.0
    val units = if (hasServing) listOf(food.baseUnit(), "serving") else listOf(food.baseUnit())
    var unit by remember { mutableStateOf(if (hasServing) "serving" else food.baseUnit()) }
    var qty by remember { mutableStateOf(if (hasServing) "1" else "100") }
    val q = qty.toD()
    val n = if (q != null && q > 0) NutritionCalc.scale(food.per100(), NutritionCalc.amountBase(food.servingSize, unit, q)) else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Quantity", qty, { qty = it }, Modifier.weight(1f), error = if (qty.isNotEmpty() && (q == null || q <= 0)) "Must be > 0" else null)
                    DropdownField("Unit", units, unit, { if (it == "serving") "${food.servingLabel} (${fmt(food.servingSize ?: 0.0, 1)} ${food.baseUnit()})" else it }, { unit = it }, Modifier.weight(1.4f))
                }
                if (n != null) {
                    Text("${fmt(n.kcal, 0)} kcal", style = MaterialTheme.typography.titleLarge)
                    Text("Protein ${fmt(n.protein)} g · Carbs ${fmt(n.carbs)} g · Fat ${fmt(n.fat)} g" + if (n.fiber > 0) " · Fibre ${fmt(n.fiber)} g" else "")
                }
            }
        },
        confirmButton = { TextButton(enabled = n != null, onClick = { onConfirm(unit, q!!); onDismiss() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun QuickAddDialog(onSave: (String, Nutrients) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }; var f by remember { mutableStateOf("") }
    val k = kcal.toD()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Quick add") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For when you don’t know the exact food. Nothing is added to your food library.", style = MaterialTheme.typography.bodySmall)
                TextF("Label (optional)", name, { name = it })
                NumField("Calories", kcal, { kcal = it }, Modifier.fillMaxWidth(), suffix = "kcal")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Protein", p, { p = it }, Modifier.weight(1f), suffix = "g")
                    NumField("Carbs", c, { c = it }, Modifier.weight(1f), suffix = "g")
                    NumField("Fat", f, { f = it }, Modifier.weight(1f), suffix = "g")
                }
            }
        },
        confirmButton = { TextButton(enabled = k != null || p.toD() != null || c.toD() != null || f.toD() != null, onClick = {
            onSave(name.trim(), Nutrients(k ?: 0.0, p.toD() ?: 0.0, c.toD() ?: 0.0, f.toD() ?: 0.0)); onDismiss() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Edits ONE diary entry only. The saved food definition is never touched. */
@Composable
fun EntryEditDialog(entry: FoodEntryEntity, meals: List<String>, onSave: (FoodEntryEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(entry.name) }
    var meal by remember { mutableStateOf(entry.mealName) }
    var date by remember { mutableStateOf(entry.date) }
    var qty by remember { mutableStateOf(fmt(entry.quantity, 2)) }
    var unit by remember { mutableStateOf(entry.unit) }
    var kcal by remember { mutableStateOf(fmt(entry.kcal, 1)) }
    var pr by remember { mutableStateOf(fmt(entry.protein, 1)) }
    var cb by remember { mutableStateOf(fmt(entry.carbs, 1)) }
    var ft by remember { mutableStateOf(fmt(entry.fat, 1)) }
    var fb by remember { mutableStateOf(fmt(entry.fiber, 1)) }
    var sg by remember { mutableStateOf(fmt(entry.sugar, 1)) }
    fun rescale(newQty: Double?) {
        if (newQty == null || newQty <= 0 || entry.quantity <= 0) return
        val k = newQty / entry.quantity
        kcal = fmt(r1(entry.kcal * k), 1); pr = fmt(r1(entry.protein * k), 1); cb = fmt(r1(entry.carbs * k), 1)
        ft = fmt(r1(entry.fat * k), 1); fb = fmt(r1(entry.fiber * k), 1); sg = fmt(r1(entry.sugar * k), 1)
    }
    val q = qty.toD()
    val allMeals = (meals + entry.mealName).distinct()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit diary entry") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Changes apply to this entry only — the saved food is not modified.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { TextF("Name", name, { name = it }, error = if (name.isBlank()) "Required" else null) }
                item { DropdownField("Meal", allMeals, meal, { it }, { meal = it }) }
                item { DateButton(date, { date = it }, Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Quantity", qty, { qty = it; rescale(it.toD()) }, Modifier.weight(1f), error = if (q == null || q <= 0) "Must be > 0" else null)
                        DropdownField("Unit", listOf("g", "ml", "serving"), unit, { it }, { unit = it }, Modifier.weight(1f))
                    }
                }
                item { Text("Nutrition for this entry (editable)", style = MaterialTheme.typography.labelLarge) }
                item { NumField("Calories", kcal, { kcal = it }, Modifier.fillMaxWidth(), suffix = "kcal") }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Protein", pr, { pr = it }, Modifier.weight(1f), suffix = "g"); NumField("Carbs", cb, { cb = it }, Modifier.weight(1f), suffix = "g"); NumField("Fat", ft, { ft = it }, Modifier.weight(1f), suffix = "g") } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Fibre", fb, { fb = it }, Modifier.weight(1f), suffix = "g"); NumField("Sugar", sg, { sg = it }, Modifier.weight(1f), suffix = "g") } }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && q != null && q > 0, onClick = {
                val factor = if (entry.quantity > 0 && entry.amountBase > 0) q!! / entry.quantity else 1.0
                onSave(entry.copy(name = name.trim(), mealName = meal, date = date, quantity = q!!, unit = unit,
                    amountBase = if (entry.amountBase > 0) r1(entry.amountBase * factor) else 0.0,
                    kcal = kcal.toD() ?: 0.0, protein = pr.toD() ?: 0.0, carbs = cb.toD() ?: 0.0, fat = ft.toD() ?: 0.0, fiber = fb.toD() ?: 0.0, sugar = sg.toD() ?: 0.0))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun FoodPickerDialog(onPick: (FoodEntity) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    var q by remember { mutableStateOf("") }
    val foods by remember(q) { c.nutrition.observeFoods(q, 100) }.collectAsStateWithLifecycle(emptyList())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a food") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Search foods") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (foods.isEmpty()) item { Text("No foods found. Create foods in the Foods library first.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                    items(foods, key = { it.id }) { f ->
                        ListItem(headlineContent = { Text(f.name) }, supportingContent = { Text("${fmt(f.kcal100, 0)} kcal / 100 ${f.baseUnit()}") },
                            modifier = Modifier.clickable { onPick(f); onDismiss() })
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
