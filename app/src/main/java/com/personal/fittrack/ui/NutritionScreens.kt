package com.personal.fittrack.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.personal.fittrack.data.*
import com.personal.fittrack.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class NutritionViewModel(private val c: AppContainer) : ViewModel() {
    val date = c.selectedDate
    val entries = combine(c.activeProject.map { it?.id }.distinctUntilChanged(), date) { p, d -> p to d }
        .flatMapLatest { (p, d) -> if (p == null) flowOf(emptyList()) else c.nutrition.observeDay(p, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    fun update(e: FoodEntryEntity) = viewModelScope.launch { c.nutrition.updateEntry(e) }
    fun duplicate(id: String) = viewModelScope.launch { c.nutrition.duplicateEntry(id) }
    fun delete(id: String) = viewModelScope.launch {
        val e = c.nutrition.deleteEntry(id)
        if (e != null) messages.emit(UiMessage("Removed ${e.name}", "Undo") { c.nutrition.restoreEntry(e) })
    }
    fun quickAdd(meal: String, name: String, n: Nutrients) = viewModelScope.launch {
        c.activeProject.value?.let { c.nutrition.logQuick(it.id, date.value, meal, name, n) }
    }
}

@Composable
fun NutritionScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { NutritionViewModel(c) }
    val project by c.activeProject.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val mealNames by c.prefs.mealNames.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    var editing by remember { mutableStateOf<FoodEntryEntity?>(null) }
    var quickMeal by remember { mutableStateOf<String?>(null) }
    val meals = remember(mealNames, entries) { mealNames + entries.map { it.mealName }.filter { it !in mealNames }.distinct() }
    val p = project

    AppScaffold("Nutrition", snackbar = snackbar, actions = {
        IconButton(onClick = { nav.navigate("recipes") }) { Icon(Icons.Default.Restaurant, "Recipes & meals") }
        IconButton(onClick = { nav.navigate("foods") }) { Icon(Icons.Default.Fastfood, "Food library") }
    }) { pad ->
        if (p == null) { NoProject { nav.navigate("project/new") }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { DateNavigator(date) { c.selectedDate.value = it } }
            item { NutritionSummaryCard(NutritionCalc.sum(entries.map { it.nutrients() }), p) }
            if (entries.isEmpty()) item { Text("Nothing logged for this day in “${p.name}”. Use “Add food” under a meal below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(meals, key = { it }) { meal ->
                val list = entries.filter { it.mealName == meal }
                SectionCard(meal, trailing = { Text("${fmt(list.sumOf { it.kcal }, 0)} kcal", style = MaterialTheme.typography.labelLarge) }) {
                    list.forEach { e -> EntryRow(e, onClick = { editing = e }, onDuplicate = { vm.duplicate(e.id) }, onDelete = { vm.delete(e.id) }) }
                    Row {
                        TextButton(onClick = { c.logMeal.value = meal; nav.navigate("logfood") }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add food") }
                        TextButton(onClick = { quickMeal = meal }) { Text("Quick add") }
                    }
                }
            }
        }
    }
    editing?.let { e -> EntryEditDialog(e, meals, { vm.update(it) }, { editing = null }) }
    quickMeal?.let { m -> QuickAddDialog({ name, n -> vm.quickAdd(m, name, n) }, { quickMeal = null }) }
}

@Composable
private fun EntryRow(e: FoodEntryEntity, onClick: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmt(e.quantity, 1)} ${e.unit} · P ${fmt(e.protein, 0)} C ${fmt(e.carbs, 0)} F ${fmt(e.fat, 0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${fmt(e.kcal, 0)}", style = MaterialTheme.typography.titleSmall)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Edit / move") }, onClick = { menu = false; onClick() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

// ======================= Food library / logging =======================

class FoodsViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val limit = MutableStateFlow(100)
    val foods = combine(query.debounce(150), limit) { q, l -> q to l }.flatMapLatest { (q, l) -> c.nutrition.observeFoods(q, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recent = c.nutrition.observeRecentFoods().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recipes = c.nutrition.observeRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)

    fun favorite(f: FoodEntity) = viewModelScope.launch { c.nutrition.setFavorite(f.id, !f.favorite) }
    fun duplicate(id: String) = viewModelScope.launch { c.nutrition.duplicateFood(id) }
    fun delete(id: String) = viewModelScope.launch {
        val err = c.nutrition.deleteFood(id)
        messages.emit(UiMessage(err ?: "Food deleted. Past diary entries keep their values."))
    }
    fun log(food: FoodEntity, meal: String, unit: String, qty: Double) = viewModelScope.launch {
        val p = c.activeProject.value ?: return@launch
        val e = c.nutrition.logFood(p.id, c.selectedDate.value, meal, food, unit, qty)
        messages.emit(UiMessage("Added ${fmt(e.kcal, 0)} kcal to $meal", "Undo") { c.nutrition.deleteEntry(e.id) })
    }
    fun quick(meal: String, name: String, n: Nutrients) = viewModelScope.launch {
        val p = c.activeProject.value ?: return@launch
        val e = c.nutrition.logQuick(p.id, c.selectedDate.value, meal, name, n)
        messages.emit(UiMessage("Quick-added ${fmt(e.kcal, 0)} kcal to $meal", "Undo") { c.nutrition.deleteEntry(e.id) })
    }
    fun logRecipe(r: RecipeEntity, perServing: Nutrients, servings: Double, meal: String) = viewModelScope.launch {
        val p = c.activeProject.value ?: return@launch
        val e = c.nutrition.logRecipe(p.id, c.selectedDate.value, meal, r, perServing, servings)
        messages.emit(UiMessage("Logged ${r.name} to $meal", "Undo") { c.nutrition.deleteEntry(e.id) })
    }
}

/** logMode = true: tapping a food logs it into the chosen meal/date. false: manage library. */
@Composable
fun FoodListScreen(nav: NavController, logMode: Boolean) {
    val c = LocalContainer.current
    val vm = vm(key = if (logMode) "foods_log" else "foods_manage") { FoodsViewModel(c) }
    val foods by vm.foods.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val limit by vm.limit.collectAsStateWithLifecycle()
    val meal by c.logMeal.collectAsStateWithLifecycle()
    val date by c.selectedDate.collectAsStateWithLifecycle()
    val mealNames by c.prefs.mealNames.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    var qtyFood by remember { mutableStateOf<FoodEntity?>(null) }
    var deleteFood by remember { mutableStateOf<FoodEntity?>(null) }
    var quick by remember { mutableStateOf(false) }
    var recipePicker by remember { mutableStateOf(false) }
    val recipes by vm.recipes.collectAsStateWithLifecycle()

    AppScaffold(if (logMode) "Add to $meal" else "Food library", onBack = { nav.popBackStack() }, snackbar = snackbar,
        fab = { ExtendedFloatingActionButton(onClick = { nav.navigate("food/new") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New food") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (logMode) Text("Logging to ${Dates.pretty(date)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(query, { vm.query.value = it }, placeholder = { Text("Search your foods") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                    if (logMode) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { quick = true }, label = { Text("Quick add") }, leadingIcon = { Icon(Icons.Default.FlashOn, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { recipePicker = true }, label = { Text("Recipe / meal") }, leadingIcon = { Icon(Icons.Default.Restaurant, null, Modifier.size(18.dp)) })
                        if (mealNames.size > 1) {
                            var m by remember { mutableStateOf(false) }
                            Box {
                                AssistChip(onClick = { m = true }, label = { Text(meal) }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
                                DropdownMenu(m, { m = false }) { mealNames.forEach { n -> DropdownMenuItem(text = { Text(n) }, onClick = { c.logMeal.value = n; m = false }) } }
                            }
                        }
                    }
                }
            }
            if (query.isBlank() && recent.isNotEmpty()) {
                item { Text("Recently used", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(recent.take(6), key = { "r_" + it.id }) { f -> FoodRow(f, vm, logMode, nav, onTap = { if (logMode) qtyFood = f else nav.navigate("food/${f.id}") }, onDelete = { deleteFood = f }) }
                item { Text("All foods", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (foods.isEmpty()) item {
                EmptyState(Icons.Default.Fastfood, if (query.isBlank()) "Your food library is empty" else "No match",
                    "Create your own foods with nutrition per 100 g/ml — chicken breast, cooked rice, whey, homemade meals. Everything works offline.", "Create food", { nav.navigate("food/new") })
            }
            items(foods, key = { it.id }) { f -> FoodRow(f, vm, logMode, nav, onTap = { if (logMode) qtyFood = f else nav.navigate("food/${f.id}") }, onDelete = { deleteFood = f }) }
            if (foods.size >= limit) item { OutlinedButton(onClick = { vm.limit.value = limit + 100 }, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
        }
    }
    qtyFood?.let { f -> QuantityDialog(f, "Add ${f.name} to $meal", { u, q -> vm.log(f, meal, u, q) }, { qtyFood = null }) }
    deleteFood?.let { f -> ConfirmDialog("Delete ${f.name}?", "Removes it from your library. Past diary entries keep their own saved values.", "Delete", true, { vm.delete(f.id) }, { deleteFood = null }) }
    if (quick) QuickAddDialog({ n, nu -> vm.quick(meal, n, nu) }, { quick = false })
    if (recipePicker) RecipeLogDialog(recipes.map { it.recipe }, meal, { r, ps, s -> vm.logRecipe(r, ps, s, meal) }, { recipePicker = false })
}

@Composable
private fun FoodRow(f: FoodEntity, vm: FoodsViewModel, logMode: Boolean, nav: NavController, onTap: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(f.name + if (f.brand.isNotBlank()) " · ${f.brand}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${fmt(f.kcal100, 0)} kcal · P ${fmt(f.protein100)} C ${fmt(f.carbs100)} F ${fmt(f.fat100)} per 100 ${f.baseUnit()}") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.favorite(f) }) { Icon(if (f.favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favourite", tint = if (f.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit food") }, onClick = { menu = false; nav.navigate("food/${f.id}") })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicate(f.id) })
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onTap)
    )
}

// ======================= Food editor =======================

@Composable
fun FoodEditScreen(nav: NavController, id: String) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == "new") }
    var existing by remember { mutableStateOf<FoodEntity?>(null) }
    var name by remember { mutableStateOf("") }; var brand by remember { mutableStateOf("") }; var category by remember { mutableStateOf("") }
    var basis by remember { mutableStateOf("G") }
    var kcal by remember { mutableStateOf("") }; var pr by remember { mutableStateOf("") }; var cb by remember { mutableStateOf("") }
    var ft by remember { mutableStateOf("") }; var fb by remember { mutableStateOf("") }; var sg by remember { mutableStateOf("") }
    var serving by remember { mutableStateOf("") }; var label by remember { mutableStateOf("serving") }; var notes by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != "new") c.nutrition.getFood(id)?.let { f ->
            existing = f; name = f.name; brand = f.brand; category = f.category; basis = f.basis
            kcal = fmt(f.kcal100, 2); pr = fmt(f.protein100, 2); cb = fmt(f.carbs100, 2); ft = fmt(f.fat100, 2)
            fb = if (f.fiber100 > 0) fmt(f.fiber100, 2) else ""; sg = if (f.sugar100 > 0) fmt(f.sugar100, 2) else ""
            serving = f.servingSize?.let { fmt(it, 2) } ?: ""; label = f.servingLabel; notes = f.notes
        }
        loaded = true
    }
    val unitLabel = if (basis == "ML") "ml" else "g"
    val kcalV = kcal.toD()
    val nameErr = if (tried && name.isBlank()) "Name is required" else null
    val kcalErr = if (tried && kcalV == null) "Enter calories (0 if none)" else null

    AppScaffold(if (existing == null) "New food" else "Edit food", onBack = { nav.popBackStack() }) { pad ->
        if (!loaded) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (existing != null) item { Text("Saving changes updates this food for future logging only. Past diary entries keep the nutrition they were logged with.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { TextF("Food name", name, { name = it }, error = nameErr) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextF("Brand (optional)", brand, { brand = it }, Modifier.weight(1f)); TextF("Category (optional)", category, { category = it }, Modifier.weight(1f)) } }
            item { DropdownField("Nutrition values are per 100…", listOf("G", "ML"), basis, { if (it == "G") "100 g" else "100 ml" }, { basis = it }) }
            item { NumField("Calories", kcal, { kcal = it }, Modifier.fillMaxWidth(), suffix = "kcal / 100 $unitLabel", error = kcalErr) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Protein", pr, { pr = it }, Modifier.weight(1f), suffix = "g"); NumField("Carbs", cb, { cb = it }, Modifier.weight(1f), suffix = "g"); NumField("Fat", ft, { ft = it }, Modifier.weight(1f), suffix = "g") } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumField("Fibre (opt.)", fb, { fb = it }, Modifier.weight(1f), suffix = "g"); NumField("Sugar (opt.)", sg, { sg = it }, Modifier.weight(1f), suffix = "g") } }
            item {
                val est = 4 * (pr.toD() ?: 0.0) + 4 * (cb.toD() ?: 0.0) + 9 * (ft.toD() ?: 0.0)
                if (est > 0) TextButton(onClick = { kcal = fmt(est, 0) }) { Text("Estimate calories from macros (≈ ${fmt(est, 0)} kcal)") }
            }
            item { Text("Serving size (optional)", style = MaterialTheme.typography.titleSmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Size", serving, { serving = it }, Modifier.weight(1f), suffix = unitLabel); TextF("Unit name (scoop, cup, egg…)", label, { label = it }, Modifier.weight(1.4f)) } }
            item { TextF("Notes (optional)", notes, { notes = it }, singleLine = false) }
            item {
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    tried = true
                    if (name.isNotBlank() && kcalV != null) scope.launch {
                        c.nutrition.saveFood((existing ?: FoodEntity(id = "", name = "")).copy(
                            name = name.trim(), brand = brand.trim(), category = category.trim(), basis = basis,
                            servingSize = serving.toD()?.takeIf { it > 0 }, servingLabel = label.trim().ifBlank { "serving" },
                            kcal100 = kcalV, protein100 = pr.toD() ?: 0.0, carbs100 = cb.toD() ?: 0.0, fat100 = ft.toD() ?: 0.0,
                            fiber100 = fb.toD() ?: 0.0, sugar100 = sg.toD() ?: 0.0, notes = notes.trim()))
                        nav.popBackStack()
                    }
                }) { Text("Save food") }
            }
        }
    }
}

// ======================= Recipes / meal templates =======================

@Composable
fun RecipeLogDialog(recipes: List<RecipeEntity>, meal: String, onLog: (RecipeEntity, Nutrients, Double) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    var chosen by remember { mutableStateOf<RecipeEntity?>(null) }
    var servings by remember { mutableStateOf("1") }
    val per by produceState<RecipeNutrition?>(null, chosen) {
        value = chosen?.let { r -> c.nutrition.nutritionOf(r, c.nutrition.ingredients(r.id).map { it.food to it.ingredient.grams }) }
    }
    val s = servings.toD()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(chosen?.let { "Log ${it.name}" } ?: "Choose a recipe or meal") },
        text = {
            if (chosen == null) LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (recipes.isEmpty()) item { Text("No recipes yet. Create one from the Nutrition tab → recipes icon.") }
                items(recipes, key = { it.id }) { r -> ListItem(headlineContent = { Text(r.name) }, supportingContent = { Text("${fmt(r.servings)} serving(s) total") }, modifier = Modifier.clickable { chosen = r }) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Servings eaten", servings, { servings = it }, Modifier.fillMaxWidth(), error = if (s == null || s <= 0) "Must be > 0" else null)
                per?.let { n -> if (s != null && s > 0) { val t = (n.perServing * s).rounded(); Text("${fmt(t.kcal, 0)} kcal · P ${fmt(t.protein)} C ${fmt(t.carbs)} F ${fmt(t.fat)}") } }
            }
        },
        confirmButton = { if (chosen != null) TextButton(enabled = per != null && s != null && s > 0, onClick = { onLog(chosen!!, per!!.perServing, s!!); onDismiss() }) { Text("Log to $meal") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

class RecipesViewModel(private val c: AppContainer) : ViewModel() {
    val recipes = c.nutrition.observeRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun delete(id: String) = viewModelScope.launch { c.nutrition.deleteRecipe(id) }
}

@Composable
fun RecipesScreen(nav: NavController) {
    val c = LocalContainer.current
    val vm = vm { RecipesViewModel(c) }
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val mealNames by c.prefs.mealNames.collectAsStateWithLifecycle()
    val date by c.selectedDate.collectAsStateWithLifecycle()
    val project by c.activeProject.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var delId by remember { mutableStateOf<String?>(null) }
    var logRecipe by remember { mutableStateOf<RecipeEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    AppScaffold("Recipes & meals", onBack = { nav.popBackStack() }, snackbar = snackbar,
        fab = { ExtendedFloatingActionButton(onClick = { nav.navigate("recipe/new") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New recipe") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("A recipe is a reusable meal built from your foods (for example rice + chicken + curd). Log it again any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (recipes.isEmpty()) item { EmptyState(Icons.Default.Restaurant, "No recipes yet", "Combine foods from your library into a recipe or meal template.", "Create recipe", { nav.navigate("recipe/new") }) }
            items(recipes, key = { it.recipe.id }) { r ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("recipe/${r.recipe.id}") }) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.recipe.name, style = MaterialTheme.typography.titleSmall)
                            Text("${r.ingredientCount} ingredients · ${fmt(r.recipe.servings)} serving(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(enabled = project != null, onClick = { logRecipe = r.recipe }) { Text("Log") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; nav.navigate("recipe/${r.recipe.id}") })
                                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delId = r.recipe.id })
                            }
                        }
                    }
                }
            }
        }
    }
    delId?.let { id -> ConfirmDialog("Delete recipe?", "Diary entries already logged from it are not affected.", "Delete", true, { vm.delete(id) }, { delId = null }) }
    logRecipe?.let { r ->
        RecipeLogDialogWithMeal(r, mealNames, date, onLog = { m, ps, s ->
            scope.launch { project?.let { c.nutrition.logRecipe(it.id, date, m, r, ps, s); snackbar.showSnackbar("Logged ${r.name} to $m on ${Dates.pretty(date)}") } }
        }, onDismiss = { logRecipe = null })
    }
}

@Composable
private fun RecipeLogDialogWithMeal(r: RecipeEntity, meals: List<String>, date: String, onLog: (String, Nutrients, Double) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    var meal by remember { mutableStateOf(meals.first()) }
    var servings by remember { mutableStateOf("1") }
    val per by produceState<RecipeNutrition?>(null, r.id) { value = c.nutrition.nutritionOf(r, c.nutrition.ingredients(r.id).map { it.food to it.ingredient.grams }) }
    val s = servings.toD()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Log ${r.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Date: ${Dates.pretty(date)}", style = MaterialTheme.typography.bodySmall)
                DropdownField("Meal", meals, meal, { it }, { meal = it })
                NumField("Servings eaten", servings, { servings = it }, Modifier.fillMaxWidth(), error = if (s == null || s <= 0) "Must be > 0" else null)
                per?.let { n -> if (s != null && s > 0) { val t = (n.perServing * s).rounded(); Text("${fmt(t.kcal, 0)} kcal · P ${fmt(t.protein)} C ${fmt(t.carbs)} F ${fmt(t.fat)}") } }
            }
        },
        confirmButton = { TextButton(enabled = per != null && s != null && s > 0, onClick = { onLog(meal, per!!.perServing, s!!); onDismiss() }) { Text("Log") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private data class IngDraft(val key: String, val food: FoodEntity, val grams: String)

@Composable
fun RecipeEditScreen(nav: NavController, id: String) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == "new") }
    var existing by remember { mutableStateOf<RecipeEntity?>(null) }
    var name by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }; var servings by remember { mutableStateOf("1") }
    val ings = remember { mutableStateListOf<IngDraft>() }
    var picker by remember { mutableStateOf(false) }
    var scaleDialog by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != "new") c.nutrition.getRecipe(id)?.let { r ->
            existing = r; name = r.name; notes = r.notes; servings = fmt(r.servings, 2)
            ings.clear(); ings.addAll(c.nutrition.ingredients(id).map { IngDraft(it.ingredient.id, it.food, fmt(it.ingredient.grams, 1)) })
        }
        loaded = true
    }
    val sv = servings.toD()
    val recipeForCalc = (existing ?: RecipeEntity("", "")).copy(servings = sv?.takeIf { it > 0 } ?: 1.0)
    val nut = c.nutrition.nutritionOf(recipeForCalc, ings.map { it.food to (it.grams.toD() ?: 0.0) })

    AppScaffold(if (existing == null) "New recipe" else "Edit recipe", onBack = { nav.popBackStack() }) { pad ->
        if (!loaded) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TextF("Recipe / meal name", name, { name = it }, error = if (tried && name.isBlank()) "Required" else null) }
            item { NumField("Number of servings this makes", servings, { servings = it }, Modifier.fillMaxWidth(), error = if (sv == null || sv <= 0) "Must be > 0" else null) }
            item { Text("Ingredients", style = MaterialTheme.typography.titleMedium) }
            if (ings.isEmpty()) item { Text("Add foods from your library with the amount in grams/ml.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(ings.size, key = { ings[it].key }) { i ->
                val d = ings[i]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.food.name, Modifier.weight(1f), maxLines = 2)
                    NumField("Amount", d.grams, { ings[i] = d.copy(grams = it) }, Modifier.width(120.dp), suffix = d.food.baseUnit())
                    IconButton(onClick = { ings.removeAt(i) }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
            item { OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add ingredient") } }
            item {
                SectionCard("Nutrition") {
                    Text("Whole recipe: ${fmt(nut.total.kcal, 0)} kcal · P ${fmt(nut.total.protein)} C ${fmt(nut.total.carbs)} F ${fmt(nut.total.fat)}")
                    Text("Per serving: ${fmt(nut.perServing.kcal, 0)} kcal · P ${fmt(nut.perServing.protein)} C ${fmt(nut.perServing.carbs)} F ${fmt(nut.perServing.fat)}", style = MaterialTheme.typography.titleSmall)
                }
            }
            item { TextF("Notes (optional)", notes, { notes = it }, singleLine = false) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scaleDialog = true }, enabled = ings.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Scale recipe") }
                    Button(modifier = Modifier.weight(1f), onClick = {
                        tried = true
                        if (name.isNotBlank() && sv != null && sv > 0) scope.launch {
                            c.nutrition.saveRecipe((existing ?: RecipeEntity("", "")).copy(name = name.trim(), notes = notes.trim(), servings = sv),
                                ings.mapNotNull { d -> d.grams.toD()?.takeIf { it > 0 }?.let { d.food.id to it } })
                            nav.popBackStack()
                        }
                    }) { Text("Save recipe") }
                }
            }
        }
    }
    if (picker) FoodPickerDialog({ f -> ings.add(IngDraft(newId(), f, if (f.servingSize != null) fmt(f.servingSize!!, 1) else "100")) }, { picker = false })
    if (scaleDialog) {
        var factor by remember { mutableStateOf("2") }
        val fv = factor.toD()
        AlertDialog(onDismissRequest = { scaleDialog = false }, title = { Text("Scale recipe") },
            text = { Column { Text("Multiplies every ingredient amount and the number of servings (e.g. 2 = double, 0.5 = half)."); Spacer(Modifier.height(8.dp)); NumField("Factor", factor, { factor = it }, Modifier.fillMaxWidth()) } },
            confirmButton = { TextButton(enabled = fv != null && fv > 0, onClick = {
                for (i in ings.indices) ings[i] = ings[i].copy(grams = fmt((ings[i].grams.toD() ?: 0.0) * fv!!, 1))
                servings = fmt((sv ?: 1.0) * fv!!, 2); scaleDialog = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { scaleDialog = false }) { Text("Cancel") } })
    }
}
