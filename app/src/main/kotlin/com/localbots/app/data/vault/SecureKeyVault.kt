package com.localbots.app.data.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
    private var prefs: SharedPreferences = initializePreferences()

    private fun initializePreferences(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to standard prefs: ${t.message}")
            try {
                context.deleteSharedPreferences(PREFS_NAME)
            } catch (ignored: Throwable) {}
            context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun switchToFallback(): SharedPreferences {
        Log.w(TAG, "Switching to standard fallback preferences after crypto error")
        val fallback = context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
        prefs = fallback
        return fallback
    }

    private val _slotsState = MutableStateFlow<List<ApiKeyConfig>>(emptyList())
    val slotsState: StateFlow<List<ApiKeyConfig>> = _slotsState.asStateFlow()

    init {
        try {
            loadAllSlots()
        } catch (t: Throwable) {
            Log.e(TAG, "Init loadAllSlots encountered error", t)
            _slotsState.value = DefaultConnectors.defaultSlots
        }
    }

    fun loadAllSlots(): List<ApiKeyConfig> {
        val list = mutableListOf<ApiKeyConfig>()
        val defaults = DefaultConnectors.defaultSlots

        for (i in 1..12) {
            var jsonStr: String? = null
            try {
                jsonStr = prefs.getString("slot_$i", null)
            } catch (secEx: Throwable) {
                Log.w(TAG, "Crypto failure reading slot_$i, reverting to fallback: ${secEx.message}")
                switchToFallback()
                try {
                    jsonStr = prefs.getString("slot_$i", null)
                } catch (ignored: Throwable) {}
            }

            if (jsonStr != null) {
                try {
                    val config = json.decodeFromString<ApiKeyConfig>(jsonStr)
                    list.add(config)
                } catch (e: Throwable) {
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
        try {
            val serialized = json.encodeToString(slot)
            prefs.edit().putString("slot_${slot.slotNumber}", serialized).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Error writing slot ${slot.slotNumber}, using fallback", t)
            try {
                switchToFallback()
                val serialized = json.encodeToString(slot)
                prefs.edit().putString("slot_${slot.slotNumber}", serialized).apply()
            } catch (ignored: Throwable) {}
        }
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

    companion object {
        private const val TAG = "SecureKeyVault"
        private const val PREFS_NAME = "localbots_secure_vault"
        private const val FALLBACK_NAME = "localbots_vault_fallback"
    }
}
