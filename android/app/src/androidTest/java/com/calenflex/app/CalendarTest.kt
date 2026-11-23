package com.calenflex.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.LocalDate
import java.io.File
import java.io.FileOutputStream
import androidx.test.platform.app.InstrumentationRegistry

class CalendarTest {
    @get:Rule val compose = createComposeRule()
    @Test fun monthOpensDayAndTimedEventThenWeek() {
        val date = LocalDate.of(2027,1,4)
        val a = Activity(title="Estudiar funciones",startDate=date.toString())
        var opened = ""
        val state = State(activities=listOf(a),schedule=listOf(Slot(a.id,date.toString(),540,600)))
        compose.setContent { MaterialTheme { CalendarScreen(state, { opened = it.id }, { _, _ -> }, date) } }
        compose.onNodeWithContentDescription("Calendario mensual").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.externalCacheDir,"screenshots").apply { mkdirs() }
        FileOutputStream(File(directory,"agenda-month.png")).use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNode(hasContentDescription("2027-01-04, 1 actividades. Abrir día")).performClick()
        compose.onNodeWithContentDescription("Calendario diario por horas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Estudiar funciones, 2027-01-04, 09:00 a 10:00").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(a.id, opened) }
        FileOutputStream(File(directory,"agenda-day.png")).use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithText("Semana").performClick()
        compose.onNodeWithContentDescription("Calendario semanal por horas").assertIsDisplayed()
    }
}
