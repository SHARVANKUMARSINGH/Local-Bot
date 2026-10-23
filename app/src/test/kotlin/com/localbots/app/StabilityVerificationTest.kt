package com.localbots.app

import com.localbots.app.data.model.ApiKeyConfig
import com.localbots.app.data.model.DefaultConnectors
import com.localbots.app.data.repository.AppSettings
import com.localbots.app.theme.ThemeManager
import com.localbots.app.theme.ThemeMode
import com.localbots.app.ui.navigation.Screen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityVerificationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun testDefaultConnectorsContain12Slots() {
        val slots = DefaultConnectors.defaultSlots
        assertEquals(12, slots.size)
        for (i in 1..12) {
            val slot = slots.find { it.slotNumber == i }
            assertNotNull("Slot $i must be defined", slot)
            assertTrue("Slot provider name cannot be empty", slot!!.providerName.isNotEmpty())
        }
    }

    @Test
    fun testApiKeyConfigSerializationCycle() {
        val original = ApiKeyConfig(
            slotNumber = 1,
            providerName = "Google Gemini Test",
            apiKey = "AIzaSyDummyKey1234567890",
            modelName = "gemini-3.8-flash",
            isEnabled = true,
            isHealthy = true,
            totalRequests = 42
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<ApiKeyConfig>(serialized)

        assertEquals(original.slotNumber, deserialized.slotNumber)
        assertEquals(original.providerName, deserialized.providerName)
        assertEquals(original.apiKey, deserialized.apiKey)
        assertEquals(original.modelName, deserialized.modelName)
        assertEquals(original.isEnabled, deserialized.isEnabled)
        assertEquals(original.isHealthy, deserialized.isHealthy)
        assertEquals(original.totalRequests, deserialized.totalRequests)
    }

    @Test
    fun testThemeScheduleTriggerConstants() {
        assertEquals(19, ThemeManager.NIGHT_TRIGGER_HOUR)
        assertEquals(50, ThemeManager.NIGHT_TRIGGER_MINUTE)
        assertEquals(6, ThemeManager.DAY_TRIGGER_HOUR)
        assertEquals(0, ThemeManager.DAY_TRIGGER_MINUTE)
    }

    @Test
    fun testScreenNavigationItemsNotNull() {
        val navItems = Screen.getBottomNavItems()
        assertEquals(4, navItems.size)
        for (screen in navItems) {
            assertNotNull("Navigation screen must not be null", screen)
            assertTrue("Route must not be empty", screen.route.isNotEmpty())
            assertTrue("Title must not be empty", screen.title.isNotEmpty())
        }
        assertEquals(4, Screen.bottomNavRoutes.size)
        assertTrue(Screen.bottomNavRoutes.contains("dashboard"))
        assertTrue(Screen.bottomNavRoutes.contains("chat"))
        assertTrue(Screen.bottomNavRoutes.contains("vfs"))
        assertTrue(Screen.bottomNavRoutes.contains("settings"))
    }

    @Test
    fun testAppSettingsSerializationCycle() {
        val original = AppSettings(
            isSetupCompleted = true,
            isCliInstalled = true,
            cliVersion = "v1.4.2",
            isLoggedInGoogle = true,
            googleUserEmail = "developer@example.com",
            selectedModel = "Gemini 3.8 Flash",
            availableModels = listOf("Gemini 3.8 Flash", "Gemini 3.8 Pro"),
            lastUpdateCheck = 1700000000000L
        )

        val serialized = json.encodeToString(original)
        assertTrue("Serialized string must contain email", serialized.contains("developer@example.com"))
        val deserialized = json.decodeFromString<AppSettings>(serialized)

        assertEquals(original.isSetupCompleted, deserialized.isSetupCompleted)
        assertEquals(original.isCliInstalled, deserialized.isCliInstalled)
        assertEquals(original.cliVersion, deserialized.cliVersion)
        assertEquals(original.isLoggedInGoogle, deserialized.isLoggedInGoogle)
        assertEquals(original.googleUserEmail, deserialized.googleUserEmail)
        assertEquals(original.selectedModel, deserialized.selectedModel)
        assertEquals(original.availableModels, deserialized.availableModels)
        assertEquals(original.lastUpdateCheck, deserialized.lastUpdateCheck)
    }
}
