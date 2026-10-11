package com.losslesstransfer

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object LogBus {

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private const val MAX_LINES = 500

    fun append(text: String) {
        val stamped = "[${LocalTime.now().format(timeFormatter)}] $text"
        _logs.update { current ->
            if (current.size >= MAX_LINES) {
                current.drop(current.size - MAX_LINES + 1) + stamped
            } else {
                current + stamped
            }
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
