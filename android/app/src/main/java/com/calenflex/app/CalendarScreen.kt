package com.calenflex.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun CalendarScreen(state: State, detail: (Activity) -> Unit, complete: (Activity, String) -> Unit, initialDate: LocalDate = today(state.preferences.timezone)) {
    var selected by rememberSaveable { mutableStateOf(initialDate.toString()) }
    var mode by rememberSaveable { mutableStateOf("Mes") }
    val date = LocalDate.parse(selected)
    val spanish = Locale.forLanguageTag("es-AR")
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected = when(mode) { "Mes" -> date.minusMonths(1); "Semana" -> date.minusWeeks(1); else -> date.minusDays(1) }.toString() }) { Icon(Icons.Outlined.ChevronLeft, "Período anterior") }
            Text(date.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)).replaceFirstChar { it.uppercase() }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { selected = today(state.preferences.timezone).toString() }) { Text("Hoy") }
            IconButton(onClick = { selected = when(mode) { "Mes" -> date.plusMonths(1); "Semana" -> date.plusWeeks(1); else -> date.plusDays(1) }.toString() }) { Icon(Icons.Outlined.ChevronRight, "Período siguiente") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Mes", "Semana", "Día").forEach { label -> FilterChip(selected = mode == label, onClick = { mode = label }, label = { Text(label) }) }
        }
        if(mode == "Mes") MonthGrid(state, date) { selected = it.toString(); mode = "Día" }
        else {
            val first = if(mode == "Semana") date.minusDays((date.dayOfWeek.value-1).toLong()) else date
            TimeGrid(state, (0 until if(mode == "Semana") 7 else 1).map { first.plusDays(it.toLong()) }, detail)
        }
    }
}

fun monthCells(date: LocalDate): List<LocalDate> {
    val first = date.withDayOfMonth(1)
    val start = first.minusDays((first.dayOfWeek.value-1).toLong())
    return (0L until 42L).map { start.plusDays(it) }
}

@Composable private fun MonthGrid(state: State, selected: LocalDate, open: (LocalDate) -> Unit) {
    val days = monthCells(selected)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).semantics { contentDescription = "Calendario mensual" }) {
        Row(Modifier.fillMaxWidth()) { listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom").forEach { Text(it, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall) } }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) { week.forEach { day ->
                val slots = state.schedule.filter { it.date == day.toString() }.sortedBy { it.start }
                val isToday = day == today(state.preferences.timezone)
                val background = when { isToday -> MaterialTheme.colorScheme.primaryContainer; day.month != selected.month -> MaterialTheme.colorScheme.surfaceVariant; else -> MaterialTheme.colorScheme.surface }
                Column(Modifier.weight(1f).heightIn(min = 82.dp).border(.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(background).clickable { open(day) }.padding(4.dp)
                    .semantics { contentDescription = "${day}, ${slots.size} actividades. Abrir día" }) {
                    Text(day.dayOfMonth.toString(), fontWeight = if(isToday) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelLarge)
                    slots.take(2).forEach { slot ->
                        val a = state.activities.find { it.id == slot.activityId }
                        Text((if(slot.completed) "✓ " else "") + (a?.title ?: "Actividad"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if(slots.size > 2) Text("+${slots.size-2}", style = MaterialTheme.typography.labelSmall)
                }
            } }
        }
        Text("Tocá un día para ver sus horarios.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun TimeGrid(state: State, days: List<LocalDate>, detail: (Activity) -> Unit) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val width = if(days.size == 1) 272.dp else 112.dp
    val hourHeight = 60.dp
    val activities = state.activities.associateBy { it.id }
    val format = DateTimeFormatter.ofPattern("EEE d", Locale.forLanguageTag("es-AR"))
    Column(Modifier.fillMaxWidth().horizontalScroll(horizontal).semantics { contentDescription = if(days.size == 1) "Calendario diario por horas" else "Calendario semanal por horas" }) {
        Row { Spacer(Modifier.width(44.dp)); days.forEach { Text(it.format(format), Modifier.width(width).padding(8.dp), style = MaterialTheme.typography.labelLarge) } }
        Row(Modifier.weight(1f).verticalScroll(vertical)) {
            Column(Modifier.width(44.dp)) { repeat(24) { Text("%02d:00".format(it), Modifier.height(hourHeight), style = MaterialTheme.typography.labelSmall) } }
            days.forEach { day ->
                Box(Modifier.width(width).height(hourHeight*24).border(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    state.availability.filter { day.dayOfWeek.value-1 in it.days }.forEach { window ->
                        Box(Modifier.offset(y = window.start.dp).fillMaxWidth().height((window.end-window.start).dp).background(MaterialTheme.colorScheme.primary.copy(alpha=.05f)))
                    }
                    repeat(24) { Box(Modifier.offset(y = hourHeight*it).fillMaxWidth().height(.5.dp).background(MaterialTheme.colorScheme.outlineVariant)) }
                    state.blocked.filter { it.date == day.toString() }.forEach { block ->
                        Box(Modifier.offset(y = block.start.dp).fillMaxWidth().height((block.end-block.start).dp).background(MaterialTheme.colorScheme.outline.copy(alpha=.22f)).semantics { contentDescription = "Bloqueado ${clock(block.start)} a ${clock(block.end)}" })
                    }
                    state.schedule.filter { it.date == day.toString() }.forEach { slot -> activities[slot.activityId]?.let { activity ->
                        Column(Modifier.offset(y = slot.start.dp).padding(horizontal = 3.dp).fillMaxWidth().height((slot.end-slot.start).coerceAtLeast(24).dp)
                            .clip(RoundedCornerShape(6.dp)).background(if(slot.completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
                            .clickable { detail(activity) }.padding(4.dp)
                            .semantics { contentDescription = "${activity.title}, $day, ${clock(slot.start)} a ${clock(slot.end)}${if(slot.completed) ", completada" else ""}" }) {
                            Text(activity.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            if(slot.end-slot.start >= 40) Text("${clock(slot.start)}–${clock(slot.end)}", style = MaterialTheme.typography.labelSmall)
                        }
                    } }
                }
            }
        }
    }
}
