package com.localbots.app.domain.agent

import android.util.Log
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resilient DNS resolver for Google Cloud, OAuth, and Gemini APIs on Android.
 *
 * Eliminates "Unable to resolve host: No address associated with hostname" errors
 * caused by Android background network restrictions, cellular IPv6 resolution latency,
 * or mobile operator DNS drops.
 *
 * Three-tier resolution strategy:
 * 1. Standard Android Bionic system DNS.
 * 2. Direct DNS-over-HTTPS query to Google Public DNS (https://8.8.8.8/resolve) which
 *    bypasses local carrier DNS completely and requires zero hostname resolution.
 * 3. Static Google Front-End (GFE) Anycast IP addresses for Google OAuth and API hosts.
 */
object GoogleResilientDns : Dns {
    private const val TAG = "GoogleResilientDns"

    // In-memory cache for resolved addresses (hostname -> list of InetAddress)
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    // Known global Google Anycast IPs
    private val GOOGLE_ANYCAST_IPS = listOf(
        "192.178.211.95",
        "142.250.190.42",
        "142.250.190.74",
        "172.217.168.202",
        "142.250.192.202",
        "192.178.211.84",
        "172.217.119.4"
    )

    // Direct IP DoH client (does not use DNS to resolve its target)
    private val dohClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val hostLower = hostname.lowercase().trim()

        // 0. Check cache
        cache[hostLower]?.let { (timestamp, addresses) ->
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS && addresses.isNotEmpty()) {
                return addresses
            }
        }

        // 1. Try standard Android System DNS
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostLower)
            if (systemAddresses.isNotEmpty()) {
                cache[hostLower] = Pair(System.currentTimeMillis(), systemAddresses)
                return systemAddresses
            }
        } catch (e: Exception) {
            Log.w(TAG, "System DNS lookup failed for $hostLower: ${e.message}. Attempting DoH fallback...")
        }

        // 2. Query Google Public DNS over HTTPS (8.8.8.8 directly, no DNS required)
        val dohAddresses = queryDoh(hostLower)
        if (dohAddresses.isNotEmpty()) {
            cache[hostLower] = Pair(System.currentTimeMillis(), dohAddresses)
            Log.i(TAG, "Successfully resolved $hostLower via Google DoH (8.8.8.8): ${dohAddresses.map { it.hostAddress }}")
            return dohAddresses
        }

        // 3. Static Anycast IP fallback for Google domains
        if (hostLower.contains("google.com") || hostLower.contains("googleapis.com")) {
            val fallback = GOOGLE_ANYCAST_IPS.mapNotNull { ipStr ->
                runCatching {
                    val parts = ipStr.split(".").map { it.toInt().toByte() }
                    val rawBytes = byteArrayOf(parts[0], parts[1], parts[2], parts[3])
                    InetAddress.getByAddress(hostLower, rawBytes)
                }.getOrNull()
            }
            if (fallback.isNotEmpty()) {
                cache[hostLower] = Pair(System.currentTimeMillis(), fallback)
                Log.i(TAG, "Successfully resolved $hostLower via static Anycast fallback: ${fallback.map { it.hostAddress }}")
                return fallback
            }
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\": No address associated with hostname")
    }

    private fun queryDoh(hostname: String): List<InetAddress> {
        return runCatching {
            val request = Request.Builder()
                .url("https://8.8.8.8/resolve?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .build()

            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: return emptyList()
                val result = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val item = answers.optJSONObject(i) ?: continue
                    if (item.optInt("type") == 1) { // Type 1 = A record (IPv4)
                        val ip = item.optString("data")
                        if (ip.isNotBlank() && ip.contains(".")) {
                            runCatching {
                                val parts = ip.split(".").map { it.toInt().toByte() }
                                val rawBytes = byteArrayOf(parts[0], parts[1], parts[2], parts[3])
                                result.add(InetAddress.getByAddress(hostname, rawBytes))
                            }
                        }
                    }
                }
                result
            }
        }.getOrDefault(emptyList())
    }
}
