package com.localbots.app.util

import android.os.Build

object ArchUtils {

    fun getPrimaryAbi(): String {
        return if (Build.SUPPORTED_ABIS.isNotEmpty()) {
            Build.SUPPORTED_ABIS[0]
        } else {
            "arm64-v8a"
        }
    }

    fun getDeviceArch(): String {
        val abi = getPrimaryAbi()
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "aarch64 (ARM 64-bit)"
            abi.contains("x86_64") -> "x86_64 (Intel/AMD 64-bit)"
            abi.contains("armeabi") -> "armv7l (ARM 32-bit)"
            abi.contains("x86") -> "x86 (Intel 32-bit)"
            else -> abi
        }
    }

    val isArm64: Boolean
        get() = getPrimaryAbi().contains("arm64") || getPrimaryAbi().contains("aarch64")

    val isX86_64: Boolean
        get() = getPrimaryAbi().contains("x86_64")
}
