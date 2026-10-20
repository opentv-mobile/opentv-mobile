package app.opentv.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import androidx.room.InvalidationTracker
import app.opentv.R
import app.opentv.core.LocaleUtils
import app.opentv.core.ServiceLocator
import coil.imageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class RecentChannelsWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in setOf(AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, Intent.ACTION_LOCALE_CHANGED)) return
        val pending = goAsync()
        RecentWidgetUpdates.scope?.launch {
            try {
                withTimeout(8_000) { RecentWidgetUpdates.refresh(context) }
            } finally {
                pending.finish()
            }
        } ?: pending.finish()
    }
}

/** One observer per application, not per launcher instance. No EPG/programmes invalidations. */
object RecentWidgetUpdates {
    internal var scope: CoroutineScope? = null
        private set
    private val refreshMutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start(context: Context, appScope: CoroutineScope) {
        if (scope != null) return
        scope = appScope
        appScope.launch(Dispatchers.IO) {
            val graph = ServiceLocator.get(context)
            val changes = callbackFlow {
                val observer = object : InvalidationTracker.Observer("channels", "sources", "categories") {
                    override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
                }
                graph.database.invalidationTracker.addObserver(observer)
                trySend(Unit)
                awaitClose { graph.database.invalidationTracker.removeObserver(observer) }
            }
            combine(changes, graph.settings.recentChannels, graph.settings.hiddenCategories,
                graph.settings.liveEnabled, graph.settings.languageTag) { _, _, _, _, _ -> Unit }
                .debounce(250)
                .collect { refresh(context) }
        }
    }

    suspend fun refresh(context: Context) = refreshMutex.withLock {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, RecentChannelsWidget::class.java))
        if (ids.isEmpty()) return@withLock
        try {
            val graph = ServiceLocator.get(context)
            val channels = RecentChannelsRepository(graph.database.channels()).recent(
                graph.settings.recentChannels.value, graph.settings.hiddenCategories.value, graph.settings.liveEnabled.value,
            )
            ids.forEach { id ->
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 280)
                manager.updateAppWidget(id, render(LocaleUtils.wrap(context), channels, entryCount(height)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fail closed: never leave old protected names/logos behind after a failed read.
            ids.forEach { manager.updateAppWidget(it, render(LocaleUtils.wrap(context), emptyList(), 4)) }
        }
    }

    internal fun entryCount(heightDp: Int): Int = ((heightDp - 52) / 48).coerceIn(4, 6)

    private fun render(context: Context, channels: List<RecentChannelCandidate>, count: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_recent_channels).apply {
            setTextViewText(R.id.widget_title, context.getString(R.string.recent_widget_title))
            setTextViewText(R.id.widget_empty, context.getString(R.string.recent_widget_empty))
            setOnClickPendingIntent(R.id.widget_title, RecentWidgetIntents.pending(context))
            setOnClickPendingIntent(R.id.widget_empty, RecentWidgetIntents.pending(context))
            setViewVisibility(R.id.widget_empty, if (channels.isEmpty()) View.VISIBLE else View.GONE)
            removeAllViews(R.id.widget_channels)
            channels.take(count).forEach { channel ->
                val row = RemoteViews(context.packageName, R.layout.widget_recent_channel)
                row.setTextViewText(R.id.widget_channel_name, channel.name)
                row.setContentDescription(R.id.widget_channel_row, channel.name)
                val logo = cachedLogo(context, channel.logoUrl)
                if (logo == null) row.setImageViewResource(R.id.widget_channel_logo, R.drawable.ic_widget_channel)
                else row.setImageViewBitmap(R.id.widget_channel_logo, logo)
                row.setOnClickPendingIntent(R.id.widget_channel_row, RecentWidgetIntents.pending(context, channel.id))
                addView(R.id.widget_channels, row)
            }
        }

    /** Direct Coil disk-cache read: no ImageRequest, fetcher, content URI or network fallback. */
    private fun cachedLogo(context: Context, key: String?): Bitmap? {
        if (key.isNullOrBlank()) return null
        return try {
            context.imageLoader.diskCache?.openSnapshot(key)?.use { snapshot ->
                val file = snapshot.data.toFile()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 144) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) { null }
    }
}
