package dev.imaizentarou.autotube.phone.ui

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.imaizentarou.autotube.R
import dev.imaizentarou.autotube.player.PlayerEngine
import dev.imaizentarou.autotube.queue.QueueStore
import dev.imaizentarou.autotube.youtube.SearchCursor
import dev.imaizentarou.autotube.youtube.SearchResult
import dev.imaizentarou.autotube.youtube.YouTubeExtractor
import dev.imaizentarou.autotube.youtube.YouTubeSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * アプリ内の YouTube 検索。結果をタップでキューの末尾へ、メニューから「次に再生」「今すぐ再生」。
 * 入力欄に URL（動画・再生リスト）を貼ったときは、検索せずにそのまま追加できる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAddUrl: (String) -> Unit,
    onMessage: (text: String, actionLabel: String?, action: (() -> Unit)?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    /** 結果を出している検索語。入力が変わったら候補表示に戻る。 */
    var searchedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<SearchResult>() }
    var cursor by remember { mutableStateOf<SearchCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val items by QueueStore.items.collectAsStateWithLifecycle()
    val queuedIds = remember(items) { items.map { it.videoId }.toSet() }

    val pastedUrl = remember(query) {
        YouTubeExtractor.canonicalUrl(query) ?: YouTubeExtractor.playlistUrl(query)
    }

    fun search(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        query = trimmed
        searchedQuery = trimmed
        keyboard?.hide()
        results.clear()
        cursor = null
        error = null
        loading = true
        scope.launch {
            try {
                val page = YouTubeSearch.search(trimmed)
                results.addAll(page.results)
                cursor = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e is java.io.IOException) context.getString(R.string.search_error_offline) else context.getString(R.string.search_error_failed)
            } finally {
                loading = false
            }
        }
    }

    fun add(result: SearchResult, mode: AddMode) {
        val current = PlayerEngine.state.value.current
        val item = QueueStore.addMeta(result.meta, afterId = current?.id, playNext = mode != AddMode.End)
        if (item == null) {
            onMessage(context.getString(R.string.search_already_queued), null, null)
            return
        }
        when (mode) {
            AddMode.End -> onMessage(context.getString(R.string.search_added), context.getString(R.string.search_undo)) { QueueStore.remove(item.id) }
            AddMode.Next -> onMessage(context.getString(R.string.search_will_play_next), context.getString(R.string.search_undo)) { QueueStore.remove(item.id) }
            AddMode.Now -> PlayerEngine.play(item)
        }
    }

    LaunchedEffect(Unit) { if (searchedQuery == null) focus.requestFocus() }
    // 回転などで作り直されたら、同じ語で検索し直す（結果は保存していない）。
    LaunchedEffect(Unit) { searchedQuery?.let { if (results.isEmpty()) search(it) } }
    LaunchedEffect(query, searchedQuery) {
        if (query == searchedQuery || query.isBlank() || pastedUrl != null) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = YouTubeSearch.suggestions(query)
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, cursor) {
        val next = cursor ?: return@LaunchedEffect
        if (!nearEnd || loadingMore || loading) return@LaunchedEffect
        loadingMore = true
        try {
            val page = YouTubeSearch.more(next)
            val known = results.map { it.meta.videoId }.toSet()
            results.addAll(page.results.filter { it.meta.videoId !in known })
            cursor = page.next
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cursor = null
        } finally {
            loadingMore = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.settings_back)) } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (pastedUrl != null) onAddUrl(pastedUrl) else search(query) }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = {
                                    query = ""
                                    focus.requestFocus()
                                }) { Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear)) }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                pastedUrl != null -> item(key = "url") { PastedUrlCard(pastedUrl) { onAddUrl(pastedUrl) } }
                query != searchedQuery && suggestions.isNotEmpty() -> items(suggestions, key = { "s:$it" }) { suggestion ->
                    Row(
                        Modifier.fillMaxWidth().clickable { search(suggestion) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(suggestion, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
                loading -> item(key = "loading") { CenteredProgress() }
                error != null -> item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { searchedQuery?.let(::search) }) { Text(stringResource(R.string.search_try_again)) }
                    }
                }
                searchedQuery != null && results.isEmpty() -> item(key = "none") {
                    Text(stringResource(R.string.search_no_results), Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    items(results, key = { it.meta.videoId }) { result ->
                        ResultRow(result, queued = result.meta.videoId in queuedIds, onAdd = { add(result, it) })
                    }
                    if (loadingMore) item(key = "more") { CenteredProgress() }
                }
            }
        }
    }
}

private enum class AddMode { End, Next, Now }

@Composable
private fun ResultRow(result: SearchResult, queued: Boolean, onAdd: (AddMode) -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val meta = result.meta
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !queued) { onAdd(AddMode.End) }
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(meta.thumbnailUrl, Modifier.width(144.dp).aspectRatio(16f / 9f)) {
            val duration = dev.imaizentarou.autotube.queue.QueueItem.formatSeconds(meta.durationSec)
            if (duration.isNotEmpty()) DurationBadge(duration, Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(meta.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val detail = listOfNotNull(formatViews(context, result.viewCount), result.uploadedText).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (queued) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.search_in_queue),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            IconButton(onClick = { onAdd(AddMode.End) }) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.search_add_to_queue)) }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.search_menu)) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_play_now)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_play), null) },
                    enabled = !queued,
                    onClick = { onAdd(AddMode.Now); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_play_next)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_play_next), null) },
                    enabled = !queued,
                    onClick = { onAdd(AddMode.Next); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_add_to_end)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_add), null) },
                    enabled = !queued,
                    onClick = { onAdd(AddMode.End); menuOpen = false },
                )
            }
        }
    }
}

@Composable
private fun PastedUrlCard(url: String, onAdd: () -> Unit) {
    val videoId = YouTubeExtractor.canonicalUrl(url)?.let(YouTubeExtractor::videoId)
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (videoId != null) Thumbnail(thumbnailUrlOf(videoId), Modifier.width(112.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (videoId != null) stringResource(R.string.search_add_this_video) else stringResource(R.string.search_add_this_playlist), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.search_add)) }
            }
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp))
    }
}

/** View count as a short label (e.g. 1.2K views, 3.4M views). null if unavailable. */
private fun formatViews(context: Context, count: Long): String? = when {
    count < 0 -> null
    else -> context.getString(R.string.search_views, compactNumber(count))
}

private fun compactNumber(n: Long): String = when {
    n < 1_000 -> "%,d".format(n)
    n < 1_000_000 -> "%sK".format(trimDecimal(n / 1_000.0))
    n < 1_000_000_000 -> "%sM".format(trimDecimal(n / 1_000_000.0))
    else -> "%sB".format(trimDecimal(n / 1_000_000_000.0))
}

private fun trimDecimal(value: Double): String = if (value >= 10) value.toLong().toString() else "%.1f".format(value).removeSuffix(".0")
