package com.kzbs.kzbstube.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PreviewNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            SearchResultsActivity.ACTION_PREVIEW_PLAY_PAUSE -> PreviewController.onPlayPause?.invoke()
            SearchResultsActivity.ACTION_PREVIEW_REWIND -> PreviewController.onRewind?.invoke()
            SearchResultsActivity.ACTION_PREVIEW_FORWARD -> PreviewController.onForward?.invoke()
            SearchResultsActivity.ACTION_PREVIEW_CLOSE -> PreviewController.onClose?.invoke()
        }
    }
}

object PreviewController {
    var onPlayPause: (() -> Unit)? = null
    var onRewind: (() -> Unit)? = null
    var onForward: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
}
