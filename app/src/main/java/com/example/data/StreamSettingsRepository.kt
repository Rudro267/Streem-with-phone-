package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AudioMixerConfig
import com.example.model.StreamQualityProfile
import com.example.model.TransportPreference
import com.example.model.VideoResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore: DataStore<Preferences> by preferencesDataStore(name = "tallylink_studio_prefs")

data class StudioSavedSettings(
    val qualityProfile: StreamQualityProfile = StreamQualityProfile(),
    val audioMixer: AudioMixerConfig = AudioMixerConfig(),
    val transportPreference: TransportPreference = TransportPreference.AUTO,
    val manualPeerIp: String = "",
    val quickCues: List<String> = DEFAULT_QUICK_CUES
) {
    companion object {
        val DEFAULT_QUICK_CUES = listOf(
            "Read Superchat",
            "Mic Clipping!",
            "Check Frame Drops",
            "3-2-1 Go Live",
            "Zoom / Focus"
        )
    }
}

class StreamSettingsRepository(private val context: Context) {

    private object Keys {
        val RESOLUTION = stringPreferencesKey("video_resolution")
        val TARGET_FPS = intPreferencesKey("target_fps")
        val BITRATE_MBPS = intPreferencesKey("bitrate_mbps")
        val LOW_LATENCY = booleanPreferencesKey("low_latency")
        val INTERNAL_GAIN = floatPreferencesKey("internal_gain")
        val MIC_GAIN = floatPreferencesKey("mic_gain")
        val INTERNAL_MUTED = booleanPreferencesKey("internal_muted")
        val MIC_MUTED = booleanPreferencesKey("mic_muted")
        val TRANSPORT_PREF = stringPreferencesKey("transport_pref")
        val MANUAL_PEER_IP = stringPreferencesKey("manual_peer_ip")
        val QUICK_CUES = stringPreferencesKey("quick_cues_csv")
    }

    val settingsFlow: Flow<StudioSavedSettings> = context.studioDataStore.data.map { prefs ->
        val resName = prefs[Keys.RESOLUTION] ?: VideoResolution.FHD_1080P.name
        val resolution = VideoResolution.entries.firstOrNull { it.name == resName } ?: VideoResolution.FHD_1080P
        val transportName = prefs[Keys.TRANSPORT_PREF] ?: TransportPreference.AUTO.name
        val transport = TransportPreference.entries.firstOrNull { it.name == transportName } ?: TransportPreference.AUTO
        val cuesRaw = prefs[Keys.QUICK_CUES]
        val cues = if (cuesRaw.isNullOrBlank()) {
            StudioSavedSettings.DEFAULT_QUICK_CUES
        } else {
            cuesRaw.split("||").filter { it.isNotBlank() }
        }
        StudioSavedSettings(
            qualityProfile = StreamQualityProfile(
                resolution = resolution,
                targetFps = prefs[Keys.TARGET_FPS] ?: 60,
                bitrateMbps = prefs[Keys.BITRATE_MBPS] ?: 12,
                lowLatencyEnabled = prefs[Keys.LOW_LATENCY] ?: true
            ),
            audioMixer = AudioMixerConfig(
                internalGain = prefs[Keys.INTERNAL_GAIN] ?: 0.85f,
                micGain = prefs[Keys.MIC_GAIN] ?: 1.0f,
                isInternalMuted = prefs[Keys.INTERNAL_MUTED] ?: false,
                isMicMuted = prefs[Keys.MIC_MUTED] ?: false
            ),
            transportPreference = transport,
            manualPeerIp = prefs[Keys.MANUAL_PEER_IP] ?: "",
            quickCues = cues
        )
    }

    suspend fun updateQualityProfile(profile: StreamQualityProfile) {
        context.studioDataStore.edit { prefs ->
            prefs[Keys.RESOLUTION] = profile.resolution.name
            prefs[Keys.TARGET_FPS] = profile.targetFps
            prefs[Keys.BITRATE_MBPS] = profile.bitrateMbps
            prefs[Keys.LOW_LATENCY] = profile.lowLatencyEnabled
        }
    }

    suspend fun updateAudioMixer(mixer: AudioMixerConfig) {
        context.studioDataStore.edit { prefs ->
            prefs[Keys.INTERNAL_GAIN] = mixer.internalGain
            prefs[Keys.MIC_GAIN] = mixer.micGain
            prefs[Keys.INTERNAL_MUTED] = mixer.isInternalMuted
            prefs[Keys.MIC_MUTED] = mixer.isMicMuted
        }
    }

    suspend fun updateTransportPreference(preference: TransportPreference) {
        context.studioDataStore.edit { prefs ->
            prefs[Keys.TRANSPORT_PREF] = preference.name
        }
    }

    suspend fun updateManualPeerIp(ip: String) {
        context.studioDataStore.edit { prefs ->
            prefs[Keys.MANUAL_PEER_IP] = ip.trim()
        }
    }

    suspend fun updateQuickCues(cues: List<String>) {
        context.studioDataStore.edit { prefs ->
            prefs[Keys.QUICK_CUES] = cues.joinToString("||")
        }
    }
}
