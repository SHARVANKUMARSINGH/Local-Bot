package com.localbots.app.domain.nativebridge

import androidx.annotation.Keep

@Keep
data class NativeCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationSeconds: Double
) {
    val isSuccess: Boolean get() = exitCode == 0
}
