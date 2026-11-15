package com.amurayada.music.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.amurayada.music.MainActivity
import com.amurayada.music.service.MusicPlaybackService

private fun safeDispatchServiceAction(context: Context, serviceAction: String) {
    val intent = Intent(context, MusicPlaybackService::class.java).apply {
        action = serviceAction
    }
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (_: Exception) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(launchIntent)
    }
}

class PlayPauseActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        safeDispatchServiceAction(context, "ACTION_PLAY_PAUSE")
    }
}

class SkipNextActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        safeDispatchServiceAction(context, "ACTION_SKIP_NEXT")
    }
}

class OpenAppActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

class ToggleFavoriteActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        safeDispatchServiceAction(context, "ACTION_TOGGLE_FAVORITE")
    }
}
