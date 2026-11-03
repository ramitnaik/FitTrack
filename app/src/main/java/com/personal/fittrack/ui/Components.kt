package com.personal.fittrack.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.fittrack.data.AppContainer
import com.personal.fittrack.domain.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
inline fun <reified VM : ViewModel> vm(key: String? = null, crossinline make: () -> VM): VM =
    viewModel(key = key, factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = make() as T
    })

// ---------- messages with optional undo ----------
data class UiMessage(val text: String, val undoLabel: String? = null, val undo: (suspend () -> Unit)? = null)

@Composable
fun CollectMessages(flow: Flow<UiMessage>, host: SnackbarHostState) {
    LaunchedEffect(flow) {
        flow.collect { m ->
            val r = host.showSnackbar(m.text, actionLabel = m.undoLabel, duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
        }
    }
}

// ---------- scaffold ----------
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        bottomBar = bottomBar,
        floatingActionButton = fab,
        content = content
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null || trailing != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title ?: "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Delete", destructive: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Requires typing a word to confirm (used for permanent project deletion / wiping everything). */
@Composable
fun TypedConfirmDialog(title: String, text: String, word: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                OutlinedTextField(typed, { typed = it }, label = { Text("Type \"$word\" to confirm") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = typed.trim() == word, onClick = { onConfirm(); onDismiss() }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- inputs ----------
@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null, decimal: Boolean = true, error: String? = null) {
    val re = remember(decimal) { if (decimal) Regex("^\\d*[.,]?\\d*$") else Regex("^\\d*$") }
    OutlinedTextField(
        value = value, onValueChange = { if (it.isEmpty() || re.matches(it)) onChange(it) },
        label = { Text(label) }, singleLine = true, isError = error != null,
        suffix = suffix?.let { { Text(it) } },
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
fun TextF(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, error: String? = null) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = singleLine, isError = error != null,
        supportingText = error?.let { { Text(it) } }, modifier = modifier.fillMaxWidth())
}

@Composable
fun CompactNum(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, allowNegative: Boolean = false, decimal: Boolean = true) {
    val re = remember(allowNegative, decimal) {
        Regex("^" + (if (allowNegative) "-?" else "") + "\\d*" + (if (decimal) "[.,]?\\d*" else "") + "$")
    }
    val bg = MaterialTheme.colorScheme.surfaceVariant
    BasicTextField(
        value = value, onValueChange = { if (it.isEmpty() || re.matches(it)) onChange(it) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.height(42.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().background(bg, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                inner()
            }
        }
    )
}

@Composable
fun DateButton(date: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier, emptyLabel: String = "Any date") {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        val d = date?.takeIf { Dates.isValid(it) }?.let { LocalDate.parse(it) } ?: LocalDate.now()
        DatePickerDialog(ctx, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }, modifier) {
        Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (date.isNullOrBlank()) emptyLabel else Dates.pretty(date), maxLines = 1)
    }
}

@Composable
fun <T> DropdownField(label: String, options: List<T>, selected: T, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = display(selected), onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun MacroRow(label: String, value: Double, target: Int?, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(if (target != null) "${fmt(value, 0)} / $target $unit" else "${fmt(value, 0)} $unit", style = MaterialTheme.typography.bodyMedium)
        }
        if (target != null && target > 0) {
            val frac = (value / target).toFloat()
            LinearProgressIndicator(
                progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (frac > 1.0f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

// ---------- charts ----------
data class ChartPoint(val label: String, val value: Double)

@Composable
fun LineChart(points: List<ChartPoint>, modifier: Modifier = Modifier, height: Dp = 150.dp, unit: String = "", targetLine: Double? = null) {
    if (points.isEmpty()) return
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val targetColor = MaterialTheme.colorScheme.secondary
    var lo = points.minOf { it.value }; var hi = points.maxOf { it.value }
    if (targetLine != null) { lo = minOf(lo, targetLine); hi = maxOf(hi, targetLine) }
    if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
    val pad = (hi - lo) * 0.1; lo -= pad; hi += pad
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("max ${fmt(hi - pad, 1)} $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("min ${fmt(lo + pad, 1)} $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width; val h = size.height
            fun x(i: Int) = if (points.size == 1) w / 2 else w * i / (points.size - 1)
            fun y(v: Double) = (h - ((v - lo) / (hi - lo) * h)).toFloat()
            drawLine(grid, Offset(0f, h), Offset(w, h), 1.dp.toPx())
            drawLine(grid, Offset(0f, 0f), Offset(w, 0f), 1.dp.toPx())
            if (targetLine != null) {
                drawLine(targetColor, Offset(0f, y(targetLine)), Offset(w, y(targetLine)), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            }
            if (points.size > 1) {
                val path = Path()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.value)) else path.lineTo(x(i), y(p.value)) }
                drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (points.size <= 40) points.forEachIndexed { i, p -> drawCircle(line, 3.5.dp.toPx(), Offset(x(i), y(p.value))) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (points.size > 1) Text(points.last().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BarChart(points: List<ChartPoint>, modifier: Modifier = Modifier, height: Dp = 130.dp, targetLine: Double? = null) {
    if (points.isEmpty()) return
    val bar = MaterialTheme.colorScheme.primary
    val over = MaterialTheme.colorScheme.error
    val targetColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val hi = maxOf(points.maxOf { it.value }, targetLine ?: 0.0, 1.0) * 1.1
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width; val h = size.height
            val slot = w / points.size; val bw = slot * 0.65f
            drawLine(grid, Offset(0f, h), Offset(w, h), 1.dp.toPx())
            points.forEachIndexed { i, p ->
                val bh = (p.value / hi * h).toFloat()
                val color = if (targetLine != null && p.value > targetLine * 1.05) over else bar
                drawRect(color, Offset(i * slot + (slot - bw) / 2, h - bh), Size(bw, bh))
            }
            if (targetLine != null) {
                val ty = (h - targetLine / hi * h).toFloat()
                drawLine(targetColor, Offset(0f, ty), Offset(w, ty), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (points.size > 1) Text(points.last().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shared dialog for adding/editing a body-weight entry (value shown in the project's unit). */
@Composable
fun WeightDialog(unit: WeightUnit, initialDate: String, initialKg: Double?, initialNotes: String, title: String, onSave: (String, Double, String) -> Unit, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    var date by remember { mutableStateOf(initialDate) }
    var value by remember { mutableStateOf(initialKg?.let { fmt(Units.kgTo(it, unit), 2) } ?: "") }
    var notes by remember { mutableStateOf(initialNotes) }
    val v = value.toD()
    val err = if (value.isNotEmpty() && (v == null || v <= 0.0 || v > 1000.0)) "Enter a valid weight" else null
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateButton(date, { date = it })
                NumField("Weight", value, { value = it }, suffix = unit.label, error = err, modifier = Modifier.fillMaxWidth())
                TextF("Notes (optional)", notes, { notes = it })
            }
        },
        confirmButton = { TextButton(enabled = v != null && err == null && v > 0, onClick = { onSave(date, Units.toKg(v!!, unit), notes); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row { if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") } }
        }
    )
}

@Composable
fun NoProject(onCreate: () -> Unit) {
    EmptyState(Icons.Default.Folder, "No project yet", "Everything you log lives inside a project (for example “Lean Bulk 2026”). Create your first one to start tracking.", "Create project", onCreate, Modifier.fillMaxSize())
}
