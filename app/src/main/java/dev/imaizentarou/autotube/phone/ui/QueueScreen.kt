package dev.imaizentarou.autotube.phone.ui

import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.imaizentarou.autotube.R
import dev.imaizentarou.autotube.player.PlayerEngine
import dev.imaizentarou.autotube.player.PlayerState
import dev.imaizentarou.autotube.queue.QueueItem
import dev.imaizentarou.autotube.queue.QueueStore
import dev.imaizentarou.autotube.youtube.YouTubeExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** コピーした URL。動画なら videoId が入る（サムネイルを出す）。再生リストなら null。 */
data class ClipboardCandidate(val url: String, val videoId: String?)

/**
 * メイン画面。上に再生中のパネル（車に繋いでいるときはサムネイルと「車で再生中」、そうでなければ映像）、
 * 下にキュー。未視聴は並べ替え・スワイプで操作でき、視聴済みは畳んだ別枠にまとめる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    playerView: PlayerView,
    snackbar: SnackbarHostState,
    clipboard: ClipboardCandidate?,
    onAddClipboard: (ClipboardCandidate, playNext: Boolean) -> Unit,
    onDismissClipboard: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInYouTube: (QueueItem) -> Unit,
    onMessage: (text: String, actionLabel: String?, action: (() -> Unit)?) -> Unit,
) {
    val state by PlayerEngine.state.collectAsStateWithLifecycle()
    val items by QueueStore.items.collectAsStateWithLifecycle()
    val current = state.current
    val upcoming = items.filter { !it.watched || it.id == current?.id }
    val watched = items.filter { it.watched && it.id != current?.id }
    var watchedExpanded by rememberSaveable { mutableStateOf(false) }

    // 再生位置は StateFlow に載っていないので 0.5 秒ごとに読む。
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(current?.id) {
        while (true) {
            val player = PlayerEngine.player
            positionMs = player.currentPosition
            durationMs = if (player.duration > 0) player.duration else (current?.durationSec ?: 0) * 1000
            delay(500)
        }
    }

    fun remove(item: QueueItem) {
        val index = QueueStore.items.value.indexOfFirst { it.id == item.id }
        if (item.id == current?.id) PlayerEngine.stop()
        QueueStore.remove(item.id)
        onMessage("削除しました", "元に戻す") { QueueStore.insert(item, index) }
    }

    fun toggleWatched(item: QueueItem) {
        QueueStore.update(item.id) { it.copy(watched = !it.watched, positionMs = 0) }
        onMessage(if (item.watched) "未視聴に戻しました" else "視聴済みにしました", "元に戻す") {
            QueueStore.update(item.id) { item }
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? String ?: return@rememberReorderableLazyListState
        val toId = to.key as? String ?: return@rememberReorderableLazyListState
        if (upcoming.none { it.id == fromId } || upcoming.none { it.id == toId }) return@rememberReorderableLazyListState
        QueueStore.move(fromId, toId, after = to.index > from.index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoTube", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSearch) { Icon(painterResource(R.drawable.ic_search), contentDescription = "動画を探す") }
                    IconButton(onClick = onOpenSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (current != null) {
                NowPlayingPanel(state, current, playerView, positionMs, durationMs)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (clipboard != null) {
                    item(key = "clipboard") {
                        ClipboardCard(clipboard, onAdd = { onAddClipboard(clipboard, it) }, onDismiss = onDismissClipboard)
                    }
                }
                if (items.isEmpty()) {
                    item(key = "empty") { EmptyQueue(onOpenSearch) }
                } else {
                    item(key = "header-upcoming") {
                        val remainingSec = upcoming.sumOf { item ->
                            val watchedMs = if (item.id == current?.id) positionMs else item.positionMs
                            (item.durationSec - watchedMs / 1000).coerceAtLeast(0)
                        }
                        SectionHeader(
                            title = "キュー",
                            detail = "${upcoming.size} 本" + QueueItem.formatSeconds(remainingSec).let { if (it.isEmpty()) "" else " · 残り $it" },
                        )
                    }
                    items(upcoming, key = { it.id }) { item ->
                        ReorderableItem(reorderState, key = item.id) { dragging ->
                            SwipeableRow(
                                item = item,
                                onSwipeRight = { toggleWatched(item) },
                                onSwipeLeft = { remove(item) },
                            ) {
                                QueueRow(
                                    item = item,
                                    isCurrent = item.id == current?.id,
                                    paused = item.id == current?.id && !state.playWhenReady,
                                    dragging = dragging,
                                    dragHandle = Modifier.draggableHandle(),
                                    onClick = { if (item.id == current?.id) PlayerEngine.togglePlayPause() else PlayerEngine.play(item) },
                                    menu = { dismiss ->
                                        ItemMenuEntries(item, current, dismiss, ::toggleWatched, ::remove, onOpenInYouTube)
                                    },
                                )
                            }
                        }
                    }
                    if (watched.isNotEmpty()) {
                        item(key = "header-watched") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { watchedExpanded = !watchedExpanded }
                                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painterResource(if (watchedExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("視聴済み ${watched.size} 本", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    val snapshot = QueueStore.items.value
                                    QueueStore.removeWatched()
                                    onMessage("視聴済み ${watched.size} 本を削除しました", "元に戻す") { QueueStore.replaceAll(snapshot) }
                                }) { Text("すべて削除") }
                            }
                        }
                        if (watchedExpanded) {
                            items(watched, key = { it.id }) { item ->
                                SwipeableRow(item = item, onSwipeRight = { toggleWatched(item) }, onSwipeLeft = { remove(item) }) {
                                    QueueRow(
                                        item = item,
                                        isCurrent = false,
                                        paused = false,
                                        dragging = false,
                                        dragHandle = null,
                                        onClick = { PlayerEngine.play(item) },
                                        menu = { dismiss ->
                                            ItemMenuEntries(item, current, dismiss, ::toggleWatched, ::remove, onOpenInYouTube)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(state: PlayerState, current: QueueItem, playerView: PlayerView, positionMs: Long, durationMs: Long) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (state.carAttached) {
            // 映像は車に出ているので、スマホでは黒い四角ではなく何を流しているかを見せる。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(current.thumbnailUrl, Modifier.width(128.dp).aspectRatio(16f / 9f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_car), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("車で再生中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(current.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { playerView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
                // 映像が出るまで（取得中・準備前）はサムネイルを敷いておく。
                if (state.isLoading || PlayerEngine.player.playbackState == Player.STATE_IDLE) {
                    Thumbnail(current.thumbnailUrl, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(current.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        StatusLine(state)
        SeekBar(positionMs, durationMs)
        Controls(state)
    }
}

/** 状態を一言で。技術的な中身（エラーの詳細）はログ画面に任せ、ここでは出さない。 */
@Composable
private fun StatusLine(state: PlayerState) {
    val error = state.error
    val (text, icon) = when {
        error != null -> "再生できませんでした。自動でやり直します" to R.drawable.ic_close
        state.offline && (state.isLoading || state.isBuffering) -> "回線待ち（戻ったら続きから）" to R.drawable.ic_wifi_off
        state.isLoading -> "動画を取得中…" to null
        state.playWhenReady && state.isBuffering -> "読み込み中…" to null
        else -> return
    }
    Column(Modifier.padding(top = 8.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(painterResource(icon), null, Modifier.size(16.dp))
                } else {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (error != null) {
            Row {
                TextButton(onClick = { PlayerEngine.restartCurrent() }) { Text("今すぐやり直す") }
                if (PlayerEngine.hasNext()) TextButton(onClick = { PlayerEngine.playNext() }) { Text("次の動画へ") }
            }
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1).toFloat()
    val value = dragging ?: positionMs.coerceIn(0, durationMs.coerceAtLeast(0)).toFloat()
    Column {
        Slider(
            value = value.coerceIn(0f, max),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { PlayerEngine.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..max,
            enabled = durationMs > 0,
            modifier = Modifier.height(32.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(QueueItem.formatSeconds(value.toLong() / 1000).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(QueueItem.formatSeconds(durationMs / 1000), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Controls(state: PlayerState) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(R.drawable.ic_skip_previous, "前へ") { PlayerEngine.playPrevious() }
        ControlButton(R.drawable.ic_fast_rewind, "10 秒戻る") { PlayerEngine.seekBy(-10_000) }
        FilledIconButton(
            onClick = { PlayerEngine.togglePlayPause() },
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(
                painterResource(if (state.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (state.playWhenReady) "一時停止" else "再生",
                modifier = Modifier.size(32.dp),
            )
        }
        ControlButton(R.drawable.ic_fast_forward, "30 秒進む") { PlayerEngine.seekBy(30_000) }
        ControlButton(R.drawable.ic_skip_next, "次へ") { PlayerEngine.playNext() }
    }
}

@Composable
private fun ControlButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 右へスワイプで視聴済みの切り替え、左へで削除。どちらも取り消せる（スナックバー）。 */
@Composable
private fun SwipeableRow(item: QueueItem, onSwipeRight: () -> Unit, onSwipeLeft: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onSwipeRight()
                SwipeToDismissBoxValue.EndToStart -> onSwipeLeft()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // 項目は一覧から消えるか別枠へ移る。同じ key で戻ってきたとき（取り消し）に寄ったままにならないよう戻しておく。
            scope.launch { swipe.reset() }
        },
        backgroundContent = {
            val toRight = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color by animateColorAsState(
                when (swipe.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiaryContainer
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                },
                label = "swipe",
            )
            Row(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End,
            ) {
                if (swipe.dismissDirection != SwipeToDismissBoxValue.Settled) {
                    val (icon, label) = when {
                        !toRight -> R.drawable.ic_delete to "削除"
                        item.watched -> R.drawable.ic_undo to "未視聴に戻す"
                        else -> R.drawable.ic_check to "視聴済み"
                    }
                    Icon(painterResource(icon), null)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        content()
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    paused: Boolean,
    dragging: Boolean,
    dragHandle: Modifier?,
    onClick: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val background = when {
        dragging -> MaterialTheme.colorScheme.surfaceContainerHigh
        isCurrent -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(color = background, shadowElevation = if (dragging) 6.dp else 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                .alpha(if ((item.watched || item.unavailable) && !isCurrent) 0.6f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(item.thumbnailUrl, Modifier.width(120.dp).aspectRatio(16f / 9f)) {
                if (item.durationLabel.isNotEmpty()) DurationBadge(item.durationLabel, Modifier.align(Alignment.BottomEnd))
                val progress = if (item.durationSec > 0) item.positionMs / (item.durationSec * 1000f) else 0f
                if (progress > 0.01f && !item.watched) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                        trackColor = Color.Black.copy(alpha = 0.4f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (isCurrent) {
                    Text(
                        if (paused) "一時停止中" else "再生中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(
                    item.uploader.takeIf { it.isNotBlank() },
                    when {
                        item.unavailable -> "再生できません（削除・非公開など）"
                        item.watched -> "視聴済み"
                        item.resumePositionMs > 0 && !isCurrent -> "${QueueItem.formatSeconds(item.resumePositionMs / 1000)} から"
                        else -> null
                    },
                ).joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more), contentDescription = "メニュー") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
            }
            if (dragHandle != null) {
                Icon(
                    painterResource(R.drawable.ic_drag),
                    contentDescription = "並べ替え",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandle.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ItemMenuEntries(
    item: QueueItem,
    current: QueueItem?,
    dismiss: () -> Unit,
    onToggleWatched: (QueueItem) -> Unit,
    onRemove: (QueueItem) -> Unit,
    onOpenInYouTube: (QueueItem) -> Unit,
) {
    if (item.id != current?.id) {
        MenuEntry(R.drawable.ic_play_next, "次に再生") { QueueStore.moveAfter(item.id, current?.id); dismiss() }
    }
    MenuEntry(R.drawable.ic_to_top, "先頭へ移動") { QueueStore.moveToTop(item.id); dismiss() }
    MenuEntry(if (item.watched) R.drawable.ic_undo else R.drawable.ic_check, if (item.watched) "未視聴に戻す" else "視聴済みにする") {
        onToggleWatched(item); dismiss()
    }
    MenuEntry(R.drawable.ic_open_in_new, "YouTube で開く") { onOpenInYouTube(item); dismiss() }
    MenuEntry(R.drawable.ic_delete, "削除") { onRemove(item); dismiss() }
}

@Composable
private fun MenuEntry(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(painterResource(icon), null) }, onClick = onClick)
}

@Composable
fun DurationBadge(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun ClipboardCard(candidate: ClipboardCandidate, onAdd: (playNext: Boolean) -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (candidate.videoId != null) {
                Thumbnail(thumbnailUrlOf(candidate.videoId), Modifier.width(96.dp).aspectRatio(16f / 9f))
            } else {
                Icon(painterResource(R.drawable.ic_paste), null, Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (candidate.videoId != null) "コピーした動画を追加" else "コピーした再生リストを追加",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(candidate.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), contentDescription = "閉じる") }
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAdd(false) }) { Text("追加") }
            if (candidate.videoId != null) OutlinedButton(onClick = { onAdd(true) }) { Text("次に再生") }
        }
    }
}

@Composable
private fun EmptyQueue(onOpenSearch: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_car), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("キューは空です", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "検索して追加できます。YouTube アプリの「共有」で AutoTube を選んでも、" +
                "URL をコピーしてから開いても追加できます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSearch) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("動画を探す")
        }
    }
}

/** URL テキストからクリップボード候補を作る。キューに入っているものは出さない。 */
fun clipboardCandidateOf(text: String): ClipboardCandidate? {
    YouTubeExtractor.canonicalUrl(text)?.let { url ->
        val id = YouTubeExtractor.videoId(url)
        return if (QueueStore.contains(id)) null else ClipboardCandidate(url, id)
    }
    return YouTubeExtractor.playlistUrl(text)?.let { ClipboardCandidate(it, null) }
}
