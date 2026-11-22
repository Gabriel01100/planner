package com.calenflex.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun bottomNavigationAndActivityCreation() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Actividades").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Actividades").performClick()
        compose.onNodeWithContentDescription("Crear actividad").performClick()
        compose.onNodeWithText("Título").performTextInput("Prueba local")
        compose.onNodeWithText("Guardar actividad").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Prueba local").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Prueba local").assertIsDisplayed()
        compose.onNodeWithText("Calendario").performClick()
        compose.onNodeWithText("Semana").assertIsDisplayed()
        compose.onNodeWithText("Estadísticas").performClick()
        compose.onNodeWithText("Tu progreso").assertIsDisplayed()
        compose.onNodeWithText("Perfil").performClick()
        compose.onNodeWithText("Perfil y configuración").assertIsDisplayed()
    }
}
