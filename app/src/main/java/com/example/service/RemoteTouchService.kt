package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.model.RemoteTouchAction
import com.example.model.RemoteTouchEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteTouchService : AccessibilityService() {

    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally unused: this service only dispatches remote gestures during active USB sessions
    }

    override fun onInterrupt() {
        activeStroke = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        _isServiceConnected.value = false
    }

    fun performRemoteEvent(event: RemoteTouchEvent) {
        when (event.action) {
            RemoteTouchAction.GLOBAL_BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            RemoteTouchAction.GLOBAL_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            RemoteTouchAction.GLOBAL_RECENTS -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                return
            }
            else -> Unit
        }

        val (screenW, screenH) = getDisplayBounds()
        val px = (event.normX.coerceIn(0.001f, 0.999f) * screenW)
        val py = (event.normY.coerceIn(0.001f, 0.999f) * screenH)

        try {
            when (event.action) {
                RemoteTouchAction.DOWN -> {
                    lastX = px
                    lastY = py
                    val path = Path().apply { moveTo(px, py) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val stroke = GestureDescription.StrokeDescription(path, 0L, 40L, true)
                        activeStroke = stroke
                        val gesture = GestureDescription.Builder().addStroke(stroke).build()
                        dispatchGesture(gesture, null, null)
                    } else {
                        val gesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
                            .build()
                        dispatchGesture(gesture, null, null)
                    }
                }
                RemoteTouchAction.MOVE -> {
                    val prev = activeStroke
                    val path = Path().apply {
                        moveTo(lastX, lastY)
                        lineTo(px, py)
                    }
                    lastX = px
                    lastY = py
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && prev != null) {
                        val continued = prev.continueStroke(path, 0L, 32L, true)
                        activeStroke = continued
                        val gesture = GestureDescription.Builder().addStroke(continued).build()
                        dispatchGesture(gesture, null, null)
                    }
                }
                RemoteTouchAction.UP, RemoteTouchAction.CANCEL -> {
                    val prev = activeStroke
                    val path = Path().apply {
                        moveTo(lastX, lastY)
                        lineTo(px, py)
                    }
                    activeStroke = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && prev != null) {
                        val finalStroke = prev.continueStroke(path, 0L, 25L, false)
                        val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
                        dispatchGesture(gesture, null, null)
                    } else {
                        val tapPath = Path().apply { moveTo(px, py) }
                        val gesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, 45L))
                            .build()
                        dispatchGesture(gesture, null, null)
                    }
                }
                else -> Unit
            }
        } catch (_: Exception) {
            activeStroke = null
        }
    }

    private fun getDisplayBounds(): Pair<Float, Float> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width().toFloat().coerceAtLeast(1f) to bounds.height().toFloat().coerceAtLeast(1f)
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels.toFloat().coerceAtLeast(1f) to metrics.heightPixels.toFloat().coerceAtLeast(1f)
        }
    }

    companion object {
        @Volatile
        private var instance: RemoteTouchService? = null

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        fun dispatchRemoteTouch(event: RemoteTouchEvent): Boolean {
            val svc = instance ?: return false
            svc.performRemoteEvent(event)
            return true
        }

        fun isEnabledInSettings(context: Context): Boolean {
            if (_isServiceConnected.value) return true
            val expected = ComponentName(context, RemoteTouchService::class.java).flattenToString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
