package app.opentv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import app.opentv.R
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Only vertical viewport changes and coarse time tiles reach the database. */
@Composable
private fun viewportProgrammes(
    rows: List<ChannelsViewModel.Row>, state: LazyListState,
    window: PhoneGuideTimeline.Window, viewModel: ChannelsViewModel, nowNext: Boolean = false,
): Map<Any, List<Programme>> {
    val version by viewModel.phoneGuideVersion.collectAsState()
    var programmes by remember(rows, window, version, nowNext) { mutableStateOf(emptyMap<Any, List<Programme>>()) }
    LaunchedEffect(rows, state, window, version, nowNext) {
        snapshotFlow {
            val first = state.firstVisibleItemIndex / 8 * 8
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first + 8
            first to (((last / 8 + 2) * 8).coerceAtMost(first + 40))
        }.distinctUntilChanged().collectLatest { (first, end) ->
            programmes = viewModel.phoneProgrammes(rows.subList(first.coerceAtMost(rows.size), end.coerceAtMost(rows.size)), window, nowNext)
        }
    }
    return programmes
}

/** List now/next stays anchored to the live clock, never to the touch guide's browsed day. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PhoneGuideChannelList(
    rows: List<ChannelsViewModel.Row>, now: Long, viewModel: ChannelsViewModel,
    onPlay: (ChannelsViewModel.Row) -> Unit, onDetails: (ChannelsViewModel.Row) -> Unit,
    onFavourite: (ChannelsViewModel.Row) -> Unit,
) {
    val state = rememberLazyListState()
    val liveStart = now / 60_000 * 60_000
    val programmes = viewportProgrammes(rows, state, PhoneGuideTimeline.Window(liveStart, liveStart + 6 * PhoneGuideTimeline.HOUR), viewModel, nowNext = true)
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { row ->
            val list = programmes[row.key].orEmpty()
            val current = row.copy(now = list.firstOrNull { it.isLiveAt(now) },
                next = list.firstOrNull { it.startUtcMillis > now }, programmes = list)
            ListItem(
                headlineContent = { Text(row.primary.shownName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Column {
                    Text(current.now?.title ?: stringResource(R.string.guide_no_info), maxLines = 1)
                    current.next?.let { Text(it.title, maxLines = 1) }
                } },
                leadingContent = {
                    coil.compose.AsyncImage(row.primary.logoUrl, contentDescription = null, modifier = Modifier.size(40.dp))
                },
                trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onFavourite(current) }) {
                        Icon(if (row.primary.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = stringResource(R.string.guide_favourites))
                    }
                    TextButton(onClick = { onDetails(current) }) { Text(stringResource(R.string.phone_guide_details)) }
                } },
                modifier = Modifier.combinedClickable(onClick = { onPlay(current) }, onLongClick = { onDetails(current) }),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneTouchGuide(
    rows: List<ChannelsViewModel.Row>, now: Long, viewModel: ChannelsViewModel,
    onPlay: (ChannelsViewModel.Row) -> Unit,
    onProgramme: (ChannelsViewModel.Row, Programme) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = PhoneGuideTimeline.date(now, zone)
    var date by remember { mutableStateOf(today) }
    val version by viewModel.phoneGuideVersion.collectAsState()
    var firstDate by remember { mutableStateOf(today) }
    var lastDate by remember { mutableStateOf(today) }
    LaunchedEffect(version, today, zone) {
        val horizon = viewModel.phoneGuideHorizon()
        firstDate = horizon.start?.let { PhoneGuideTimeline.date(it, zone) } ?: today
        lastDate = horizon.end?.let { PhoneGuideTimeline.date(it - 1, zone) } ?: today
        date = date.coerceIn(minOf(firstDate, today), maxOf(lastDate, today))
    }
    val day = remember(date, zone) { PhoneGuideTimeline.day(date, zone) }
    val density = LocalDensity.current
    val baseScale = 240f // dp per elapsed hour, never a minimum programme width
    var offset by remember(date) { mutableFloatStateOf(
        if (date == today) PhoneGuideTimeline.position(now, day.start, baseScale).coerceAtLeast(0f) else 0f,
    ) }
    var detailRow by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }
    val state = rememberLazyListState()
    fun time(millis: Long) = PhoneGuideTimeline.timeLabel(millis, zone)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = date > firstDate, onClick = { date = date.minusDays(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.phone_guide_previous_day))
            }
            Text(date.format(DateTimeFormatter.ofPattern("EEE d MMM")), Modifier.weight(1f))
            IconButton(enabled = date < lastDate, onClick = { date = date.plusDays(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.phone_guide_next_day))
            }
            TextButton(onClick = { date = today; offset = PhoneGuideTimeline.position(now, PhoneGuideTimeline.day(today, zone).start, baseScale) }) {
                Text(stringResource(R.string.phone_guide_now))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val channelWidth = 112.dp
            val viewportWidth = (maxWidth - channelWidth).value.coerceAtLeast(1f)
            // At most three elapsed hours visible, so a six-hour prefetch always covers it.
            val scale = maxOf(baseScale, viewportWidth / 3f)
            val totalWidth = PhoneGuideTimeline.position(day.end, day.start, scale)
            val maxOffset = (totalWidth - viewportWidth).coerceAtLeast(0f)
            LaunchedEffect(maxOffset) { offset = offset.coerceIn(0f, maxOffset) }
            val left = offset.coerceIn(0f, maxOffset)
            val visible = PhoneGuideTimeline.Window(
                day.start + (left / scale * PhoneGuideTimeline.HOUR).toLong(),
                minOf(day.end, day.start + ((left + viewportWidth) / scale * PhoneGuideTimeline.HOUR).toLong()),
            )
            val window = PhoneGuideTimeline.prefetch(visible.start)
            val programmes = viewportProgrammes(rows, state, window, viewModel)
            val liveStart = now / 60_000 * 60_000
            val liveProgrammes = viewportProgrammes(rows, state,
                PhoneGuideTimeline.Window(liveStart, liveStart + 6 * PhoneGuideTimeline.HOUR), viewModel, nowNext = true)
            val horizontal = rememberScrollableState { delta ->
                val old = offset.coerceIn(0f, maxOffset)
                offset = (old - with(density) { delta.toDp().value }).coerceIn(0f, maxOffset)
                with(density) { (old - offset).dp.toPx() }
            }
            Column {
                Row(Modifier.height(40.dp)) {
                    Spacer(Modifier.width(channelWidth))
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().scrollable(horizontal, Orientation.Horizontal)) {
                        var tick = Math.floorDiv(visible.start, PhoneGuideTimeline.HALF_HOUR) * PhoneGuideTimeline.HALF_HOUR
                        while (tick < visible.end) {
                            val instant = tick
                            Text(time(instant), maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.offset(x = PhoneGuideTimeline.position(instant, visible.start, scale).dp)
                                    .width((scale / 2).dp))
                            tick += PhoneGuideTimeline.HALF_HOUR
                        }
                    }
                }
                LazyColumn(state = state, modifier = Modifier.weight(1f)) {
                    items(rows, key = { it.key }) { row ->
                        val list = programmes[row.key].orEmpty()
                        val live = liveProgrammes[row.key].orEmpty()
                        val current = row.copy(programmes = list,
                            now = live.firstOrNull { it.isLiveAt(now) },
                            next = live.firstOrNull { it.startUtcMillis > now })
                        Row(Modifier.fillMaxWidth().height(76.dp)) {
                            Text(row.primary.shownName, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(channelWidth).fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable { detailRow = current }.padding(8.dp))
                            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().scrollable(horizontal, Orientation.Horizontal)) {
                                list.forEach { programme ->
                                    PhoneGuideTimeline.clip(programme.startUtcMillis, programme.endUtcMillis, visible)?.let { clipped ->
                                        // Exact UTC geometry; tiny shows remain available in the channel detail sheet.
                                        val label = "${programme.title}, ${time(programme.startUtcMillis)}–${time(programme.endUtcMillis)}"
                                        Box(Modifier.offset(x = PhoneGuideTimeline.position(clipped.start, visible.start, scale).dp)
                                            .width(PhoneGuideTimeline.position(clipped.end, clipped.start, scale).dp)
                                             .fillMaxHeight().clipToBounds().background(MaterialTheme.colorScheme.secondaryContainer)
                                             .border(1.dp, Color.Black)
                                            .semantics { contentDescription = label }
                                            .clickable { onProgramme(current, programme) }) {
                                            Text(programme.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(4.dp))
                                        }
                                    }
                                }
                                if (now in visible.start until visible.end) {
                                    Box(Modifier.offset(x = PhoneGuideTimeline.position(now, visible.start, scale).dp)
                                        .width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    detailRow?.let { row ->
        ModalBottomSheet(onDismissRequest = { detailRow = null }) {
            Text(row.primary.shownName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            TextButton(onClick = { detailRow = null; onPlay(row) }) { Text(stringResource(R.string.guide_watch_channel)) }
            if (row.programmes.isEmpty()) {
                Text(stringResource(R.string.guide_no_info_channel), modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).navigationBarsPadding()) {
                items(row.programmes, key = { it.id }) { programme ->
                    ListItem(headlineContent = { Text(programme.title) },
                        supportingContent = { Text("${time(programme.startUtcMillis)}–${time(programme.endUtcMillis)}") },
                        modifier = Modifier.clickable { detailRow = null; onProgramme(row, programme) })
                }
            }
        }
    }
}
