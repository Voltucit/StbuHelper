package com.hualala.linyu.utils

/** Compatibility sink: upstream verbose protocol logging is intentionally not persisted. */
@Suppress("UNUSED_PARAMETER")
object AppLogger {
    fun i(message: String) = Unit
    fun w(message: String) = Unit
    fun e(message: String, error: Throwable? = null) = Unit
}
