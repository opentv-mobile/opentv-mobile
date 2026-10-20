package app.opentv.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.opentv.MainActivity

internal object RecentWidgetIntents {
    fun intent(context: Context, channelId: Long? = null): Intent {
        require(channelId == null || channelId > 0)
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // PendingIntent identity excludes extras. Data makes every channel unique without secrets.
            data = Uri.Builder().scheme("opentv-widget").authority("recent")
                .appendPath(channelId?.toString() ?: "home").build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (channelId != null) putExtra(MainActivity.EXTRA_PLAY_CHANNEL, channelId)
        }
    }

    fun pending(context: Context, channelId: Long? = null): PendingIntent = PendingIntent.getActivity(
        context, 0, intent(context, channelId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
