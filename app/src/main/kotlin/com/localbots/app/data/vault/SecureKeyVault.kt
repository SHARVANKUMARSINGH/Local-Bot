package com.localbots.app.data.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.localbots.app.data.model.ApiKeyConfig
import com.localbots.app.data.model.DefaultConnectors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecureKeyVault(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "localbots_secure_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // Fallback for sandboxed emulators or custom ROMs where KeyStore might be restricted
            context.getSharedPreferences("localbots_vault_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _slotsState = MutableStateFlow<List<ApiKeyConfig>>(emptyList())
    val slotsState: StateFlow<List<ApiKeyConfig>> = _slotsState.asStateFlow()

    init {
        loadAllSlots()
    }

    fun loadAllSlots(): List<ApiKeyConfig> {
        val list = mutableListOf<ApiKeyConfig>()
        val defaults = DefaultConnectors.defaultSlots

        for (i in 1..12) {
            val jsonStr = prefs.getString("slot_$i", null)
            if (jsonStr != null) {
                try {
                    val config = json.decodeFromString<ApiKeyConfig>(jsonStr)
                    list.add(config)
                } catch (e: Exception) {
                    val defaultItem = defaults.find { it.slotNumber == i } ?: ApiKeyConfig(i, "Slot $i")
                    list.add(defaultItem)
                }
            } else {
                val defaultItem = defaults.find { it.slotNumber == i } ?: ApiKeyConfig(i, "Slot $i")
                list.add(defaultItem)
            }
        }
        _slotsState.value = list
        return list
    }

    fun updateSlot(slot: ApiKeyConfig) {
        val serialized = json.encodeToString(slot)
        prefs.edit().putString("slot_${slot.slotNumber}", serialized).apply()
        loadAllSlots()
    }

    fun toggleSlotEnabled(slotNumber: Int, enabled: Boolean) {
        val current = _slotsState.value.find { it.slotNumber == slotNumber } ?: return
        val updated = current.copy(isEnabled = enabled)
        updateSlot(updated)
    }

    /**
     * Failover rotation: Find the next healthy, enabled connector slot.
     */
    fun getNextAvailableSlot(afterSlotNumber: Int = 0): ApiKeyConfig? {
        val slots = _slotsState.value
        val enabledAndHealthy = slots.filter { it.isEnabled && it.isHealthy && it.apiKey.isNotBlank() }
        if (enabledAndHealthy.isEmpty()) return null

        // Try after the current slot
        val next = enabledAndHealthy.firstOrNull { it.slotNumber > afterSlotNumber }
        return next ?: enabledAndHealthy.first()
    }

    fun markSlotFailure(slotNumber: Int, error: String) {
        val current = _slotsState.value.find { it.slotNumber == slotNumber } ?: return
        val updated = current.copy(
            isHealthy = false,
            lastError = error
        )
        updateSlot(updated)
    }

    fun markSlotSuccess(slotNumber: Int) {
        val current = _slotsState.value.find { it.slotNumber == slotNumber } ?: return
        val updated = current.copy(
            isHealthy = true,
            totalRequests = current.totalRequests + 1,
            lastUsedTimestamp = System.currentTimeMillis(),
            lastError = null
        )
        updateSlot(updated)
    }
}
