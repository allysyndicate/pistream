package com.pistream.companion.domain

object ReasonCodes {

    fun toPlainEnglish(code: String): String {
        suffixMatch(code, "_speaker_unassigned")?.let { id ->
            return "${id.systemLabel()} speaker is not assigned to a Bluetooth device."
        }
        suffixMatch(code, "_speaker_disconnected")?.let { id ->
            return "${id.systemLabel()} speaker is disconnected from the Pi."
        }
        prefixMatch(code, "spotify_", "_unhealthy")?.let { endpoint ->
            return "Spotify endpoint \"${endpoint.systemLabel()}\" is not running."
        }
        return when (code) {
            "pipewire_down" -> "PipeWire audio service is not running on the Pi."
            "wireplumber_down" -> "WirePlumber session manager is not running on the Pi."
            "whole_house_sink_missing" -> "Whole-house combined sink is not present."
            "watchdog_inactive" -> "Bluetooth watchdog timer is not running."
            "speaker_unassigned" -> "Not assigned to a Bluetooth device."
            "speaker_disconnected" -> "Bluetooth speaker is disconnected."
            "sink_missing" -> "Audio sink is not present on the Pi."
            "service_inactive" -> "Pi service is not active."
            "route_not_ready" -> "Audio route is not ready."
            "speaker_system_status_missing" -> "Pi did not report status for this speaker system."
            else -> code.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    fun toPlainEnglishList(codes: List<String>): List<String> = codes.map(::toPlainEnglish)

    private fun suffixMatch(code: String, suffix: String): String? {
        if (!code.endsWith(suffix)) return null
        val head = code.removeSuffix(suffix)
        return head.takeIf { it.isNotEmpty() }
    }

    private fun prefixMatch(code: String, prefix: String, suffix: String): String? {
        if (!code.startsWith(prefix) || !code.endsWith(suffix)) return null
        val middle = code.removePrefix(prefix).removeSuffix(suffix)
        return middle.takeIf { it.isNotEmpty() }
    }

    private fun String.systemLabel(): String = when (this) {
        "indoor" -> "Indoor"
        "outdoor" -> "Outdoor"
        "both" -> "Whole house"
        else -> replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

enum class SpeakerLiveState { CONNECTED, DISCONNECTED, UNASSIGNED, UNKNOWN }

fun ComponentRow.liveState(): SpeakerLiveState = when {
    "speaker_unassigned" in reasonCodes -> SpeakerLiveState.UNASSIGNED
    state == "healthy" || state == "connected" -> SpeakerLiveState.CONNECTED
    state == "degraded" || state == "disconnected" || state == "failed" -> SpeakerLiveState.DISCONNECTED
    else -> SpeakerLiveState.UNKNOWN
}

fun ComponentRow.plainEnglishStatus(): String = when (liveState()) {
    SpeakerLiveState.CONNECTED -> "Connected"
    SpeakerLiveState.DISCONNECTED -> "Disconnected"
    SpeakerLiveState.UNASSIGNED -> "Not assigned"
    SpeakerLiveState.UNKNOWN -> "Status not reported"
}

enum class SystemReadiness { READY, NOT_READY, UNASSIGNED, UNREPORTED, UNKNOWN }

fun SpeakerSystemRow.systemReadiness(): SystemReadiness = when {
    !statusReported -> SystemReadiness.UNREPORTED
    "speaker_unassigned" in reasonCodes -> SystemReadiness.UNASSIGNED
    readiness == "ready" -> SystemReadiness.READY
    readiness == "not_ready" -> SystemReadiness.NOT_READY
    else -> SystemReadiness.UNKNOWN
}

fun SpeakerSystemRow.plainEnglishStatus(): String = when (systemReadiness()) {
    SystemReadiness.READY -> if (active) "Playing" else "Ready"
    SystemReadiness.NOT_READY -> "Not ready"
    SystemReadiness.UNASSIGNED -> "Not assigned"
    SystemReadiness.UNREPORTED -> "Status not reported"
    SystemReadiness.UNKNOWN -> "Status unknown"
}
