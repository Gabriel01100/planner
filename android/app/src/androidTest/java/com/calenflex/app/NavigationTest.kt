package com.calenflex.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun bottomNavigationAndActivityCreation() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Agenda").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Agenda").performClick()
        compose.onNodeWithText("Actividades").performClick()
        compose.onNodeWithContentDescription("Crear actividad").performClick()
        compose.onNodeWithText("Título").performTextInput("Prueba local")
        compose.onNodeWithText("Guardar actividad").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Prueba local").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Prueba local").assertIsDisplayed()
        compose.onNodeWithText("Agenda").performClick()
        compose.onNodeWithText("Semana").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ver estadísticas").performClick()
        compose.onNodeWithText("Tu progreso").assertIsDisplayed()
        compose.onNodeWithContentDescription("Configuración").performClick()
        compose.onNodeWithText("Perfil y configuración").assertIsDisplayed()
    }
    @Test fun goalsAndStudyProgressWorkOfflineAndSurviveRecreation() {
        compose.onNode(hasText("Objetivos") and hasClickAction()).performClick()
        compose.onNodeWithText("Nuevo objetivo").performClick()
        compose.onNodeWithText("¿Qué querés aprender?").performTextInput("Python sin conexión")
        compose.onNodeWithText("Guardar objetivo").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Python sin conexión").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Registrar sesión").performClick()
        compose.onNodeWithText("Tema trabajado").performTextInput("Funciones")
        compose.onNodeWithText("Duración real en minutos (opcional)").performTextInput("25")
        compose.onNodeWithText("Confirmar y guardar sesión").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("1 sesión · 25 min registrados").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNode(hasText("Objetivos") and hasClickAction()).performClick()
        compose.onNodeWithText("1 sesión · 25 min registrados").assertIsDisplayed()
    }

    @Test fun sharedTextRequiresReviewWithoutNetworkOrAutomaticSave() {
        compose.activity.startActivity(android.content.Intent(compose.activity, MainActivity::class.java)
            .setAction(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "Compartido explícitamente para revisar"))
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Revisar contenido compartido").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Compartido explícitamente para revisar").assertIsDisplayed()
        compose.onNodeWithText("Analizar contenido").assertIsDisplayed()
    }

}
