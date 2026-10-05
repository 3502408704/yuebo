package com.example.local_music_player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

/**
 * 在线页（源驱动）：下拉选择一个启用音源；搜索类型（歌曲/专辑/歌单/歌手）随
 * 插件能力显隐；歌曲点按整列入队播放，尾部按钮下载（音质弹窗选档）；专辑/歌单/歌手
 * 钻取到合集详情页；当前音源榜单直接显示在本页。
 */

/** 主导航底栏：媒体库 / 在线 / 我的。 */
@Composable
internal fun MainNavigation(selectedTab: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                modifier = Modifier.sizeIn(minHeight = GlassTheme.MinTouchTarget),
            )
        }
    }
}

@Composable
internal fun OnlineSearchScreen(
    state: MusicUiState,
    miniPlayerInset: Dp,
    onSearch: (String) -> Unit,
    onKindChange: (OnlineSearchKind) -> Unit,
    onSelectSource: (String?) -> Unit,
    onLoadTopLists: (String?) -> Unit,
    onOpenCollection: (OnlineCollection) -> Unit,
    onOpenArtist: (StreamArtist) -> Unit,
    onPlay: (List<StreamTrack>, Int) -> Unit,
    onDownload: (StreamTrack) -> Unit,
) {
    var queryInput by rememberSaveable { mutableStateOf(state.onlineSearchQuery) }
    var sourceMenuOpen by rememberSaveable { mutableStateOf(false) }
    val selectedSource = state.musicFreeSources.firstOrNull { it.id == state.onlineActiveSourceId }
        ?: state.musicFreeSources.firstOrNull()
    LaunchedEffect(state.musicFreeSources, selectedSource?.id) {
        selectedSource?.let { onLoadTopLists(it.id) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = miniPlayerInset),
    ) {
        AppBar(
            title = "在线",
            onBack = null,
            onDevices = null,
            onSearch = null,
            trailingContent = {
                Box {
                    TextButton(
                        onClick = { sourceMenuOpen = true },
                        enabled = selectedSource != null,
                        modifier = Modifier
                            .widthIn(max = 168.dp)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = selectedSource?.name ?: "选择音源"
                                onClick { sourceMenuOpen = true; true }
                                customActions = state.musicFreeSources.map { source ->
                                    CustomAccessibilityAction(source.name) {
                                        sourceMenuOpen = false
                                        onSelectSource(source.id)
                                        true
                                    }
                                }
                            },
                    ) {
                        Text(
                            selectedSource?.name ?: "选择音源",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = sourceMenuOpen,
                        onDismissRequest = { sourceMenuOpen = false },
                    ) {
                        state.musicFreeSources.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.name) },
                                onClick = { sourceMenuOpen = false; onSelectSource(source.id) },
                                modifier = Modifier.sizeIn(minHeight = 48.dp),
                            )
                        }
                    }
                }
            },
        )
        if (state.musicFreeSources.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "尚未导入音源\n请到「我的 → 音源管理」导入 MusicFree 插件后使用在线搜索",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            return
        }
        OutlinedTextField(
            value = queryInput,
            onValueChange = { queryInput = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("搜索${state.onlineSearchKind.label}") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (queryInput.isNotBlank()) onSearch(queryInput.trim()) }),
            trailingIcon = {
                IconButton(
                    onClick = { if (queryInput.isNotBlank()) onSearch(queryInput.trim()) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Icon(Icons.Default.Search, contentDescription = "搜索") }
            },
        )
        // 搜索类型行（歌曲/专辑/歌单/歌手）。
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnlineSearchKind.entries.forEach { kind ->
                androidx.compose.material3.FilterChip(
                    selected = state.onlineSearchKind == kind,
                    onClick = {
                        onKindChange(kind)
                        if (queryInput.isNotBlank()) onSearch(queryInput.trim())
                    },
                    label = { Text(kind.label) },
                )
            }
        }
        val searching = state.onlineSearching
        when {
            searching -> Text(
                "正在搜索「${state.onlineSearchQuery}」…",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.onlineSearchQuery.isBlank() -> {
                when {
                    state.onlineTopListLoading && state.onlineTopListGroups.isEmpty() -> Text(
                        "正在加载榜单…",
                        Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.onlineTopListGroups.isNotEmpty() -> OnlineTopListGroups(
                        state = state,
                        onOpenCollection = onOpenCollection,
                    )
                    else -> Text(
                        "该音源暂无榜单，输入关键词开始搜索",
                        Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> when (state.onlineSearchKind) {
                OnlineSearchKind.Music -> {
                    if (state.onlineSearchTracks.isEmpty()) {
                        OnlineEmptyHint(state.onlineSearchTracks.isEmpty() && !searching)
                    } else {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            itemsIndexed(
                                state.onlineSearchTracks,
                                key = { _, track -> track.pluginId + ":" + track.key },
                            ) { index, track ->
                                StreamTrackRow(
                                    track = track,
                                    playing = false,
                                    onClick = { onPlay(state.onlineSearchTracks, index) },
                                    onDownload = { onDownload(track) },
                                )
                            }
                        }
                    }
                }
                OnlineSearchKind.Album, OnlineSearchKind.Sheet -> {
                    val collections = state.onlineSearchCollections.filter { collection ->
                        val expected = if (state.onlineSearchKind == OnlineSearchKind.Album) {
                            StreamCollectionKind.Album
                        } else {
                            StreamCollectionKind.Sheet
                        }
                        collection.kind == expected
                    }
                    if (collections.isEmpty()) {
                        OnlineEmptyHint(true)
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            items(collections, key = { it.key }) { collection ->
                                OnlineCollectionRow(
                                    name = collection.name,
                                    subtitle = listOf(collection.artist, collection.sourceName)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    artworkUrl = collection.artworkUrl,
                                    onClick = { onOpenCollection(collection) },
                                )
                            }
                        }
                    }
                }
                OnlineSearchKind.Artist -> {
                    if (state.onlineSearchArtists.isEmpty()) {
                        OnlineEmptyHint(true)
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            items(state.onlineSearchArtists, key = { it.pluginId + ":" + it.key }) { artist ->
                                OnlineCollectionRow(
                                    name = artist.name,
                                    subtitle = listOf("歌手", artistSourceName(state, artist.pluginId))
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    artworkUrl = artist.avatar,
                                    onClick = { onOpenArtist(artist) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineEmptyHint(empty: Boolean) {
    if (empty) {
        Text(
            "没有找到相关内容",
            Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun artistSourceName(state: MusicUiState, pluginId: String): String =
    state.musicFreeSources.firstOrNull { it.id == pluginId }?.name ?: pluginId

/**
 * 合集/歌手详情页：专辑/歌单/榜单详情与歌手作品共用；详情态提供「播放全部/下载全部」，
 * 歌手段只提供「播放全部」。列表滚动接近末尾时自动翻页。
 */
@Composable
internal fun OnlineCollectionDetailScreen(
    state: MusicUiState,
    miniPlayerInset: Dp,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onPlayAll: (List<StreamTrack>) -> Unit,
    onPlayTrack: (List<StreamTrack>, Int) -> Unit,
    onDownloadTrack: (StreamTrack) -> Unit,
    onDownloadAll: () -> Unit,
) {
    val collection = state.onlineCollectionDetail
    val artist = state.onlineArtistDetail
    val tracks = state.onlineCollectionTracks
    val title = collection?.name ?: artist?.name ?: "合集详情"
    val subtitle = when {
        collection != null -> listOfNotNull(
            collection.artist.takeIf { it.isNotBlank() },
            collection.worksNum?.takeIf { it.isNotBlank() },
            collection.sourceName.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        artist != null -> listOf("歌手作品", artistSourceName(state, artist.pluginId))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        else -> ""
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = miniPlayerInset),
    ) {
        AppBar(title = title, onBack = onBack, onDevices = null)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { onPlayAll(tracks) }, enabled = tracks.isNotEmpty()) { Text("播放全部") }
            if (collection != null) {
                OutlinedButton(onClick = onDownloadAll, enabled = tracks.isNotEmpty()) { Text("下载全部") }
            }
        }
        when {
            state.onlineCollectionLoading && tracks.isEmpty() -> Text(
                "正在加载…",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            tracks.isEmpty() -> Text(
                state.status ?: "暂无曲目",
                Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                val listState = rememberLazyListState()
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(
                        tracks,
                        key = { _, track -> track.pluginId + ":" + track.key },
                    ) { index, track ->
                        StreamTrackRow(
                            track = track,
                            playing = false,
                            onClick = { onPlayTrack(tracks, index) },
                            onDownload = { onDownloadTrack(track) },
                        )
                    }
                    if (state.onlineCollectionLoading) {
                        item {
                            Text(
                                "正在加载更多…",
                                Modifier.fillMaxWidth().padding(12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                // 接近末尾时自动翻页（合集/歌手作品分页；end 或在途时不触发）。
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                LaunchedEffect(lastVisible, tracks.size, state.onlineCollectionLoading, state.onlineCollectionEnd) {
                    if (tracks.isNotEmpty() && !state.onlineCollectionLoading && !state.onlineCollectionEnd &&
                        lastVisible >= tracks.size - 4
                    ) {
                        onLoadMore()
                    }
                }
            }
        }
    }
}

/** 榜单目录页：各源 getTopLists 的分组列表；条目点按进合集详情。 */
@Composable
internal fun OnlineTopListsScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCollection: (OnlineCollection) -> Unit,
) {
    LaunchedEffect(Unit) {
        if (state.onlineTopListGroups.isEmpty() && !state.onlineTopListLoading) onRefresh()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppBar(title = "榜单", onBack = onBack, onDevices = null)
        when {
            state.onlineTopListLoading && state.onlineTopListGroups.isEmpty() -> Text(
                "正在加载榜单…",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.onlineTopListGroups.isEmpty() -> Row(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("没有可用榜单", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRefresh) { Text("重试") }
            }
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                state.onlineTopListGroups.forEach { group ->
                    item(key = "group:${group.title}") {
                        Text(
                            group.title,
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(group.sheets, key = { it.pluginId + ":" + it.key }) { sheet ->
                        val converted = sheet.toUiCollection(state.musicFreeSources)
                        OnlineCollectionRow(
                            name = converted.name,
                            subtitle = listOf(converted.artist, converted.sourceName)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            artworkUrl = converted.artworkUrl,
                            onClick = { onOpenCollection(converted) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineTopListGroups(
    state: MusicUiState,
    onOpenCollection: (OnlineCollection) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        state.onlineTopListGroups.forEach { group ->
            item(key = "group:${group.title}") {
                Text(
                    group.title,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(group.sheets, key = { it.pluginId + ":" + it.key }) { sheet ->
                val converted = sheet.toUiCollection(state.musicFreeSources)
                OnlineCollectionRow(
                    name = converted.name,
                    subtitle = converted.artist,
                    artworkUrl = converted.artworkUrl,
                    onClick = { onOpenCollection(converted) },
                )
            }
        }
    }
}

/** StreamCollection → OnlineCollection（榜单目录条目转换；源名从启用音源表回查）。 */
private fun StreamCollection.toUiCollection(sources: List<MusicFreeSource>): OnlineCollection = OnlineCollection(
    pluginId = pluginId,
    sourceName = sources.firstOrNull { it.id == pluginId }?.name ?: pluginId,
    kind = kind,
    collectionId = key,
    name = name,
    artist = artist,
    artworkUrl = artwork,
    description = description,
    worksNum = worksNum,
)

/** 在线歌曲行：封面 + 标题/歌手·专辑 + 时长；点按播放，尾部下载按钮；读屏整行合并 + customActions。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamTrackRow(
    track: StreamTrack,
    playing: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onClickLabel = "播放",
            )
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(track.name)
                    if (track.artist.isNotBlank()) append("，").append(track.artist)
                    if (track.album.isNotBlank()) append("，专辑 ").append(track.album)
                    if (playing) append("，正在播放")
                }
                customActions = listOf(
                    CustomAccessibilityAction("播放") { onClick(); true },
                    CustomAccessibilityAction("下载") { onDownload(); true },
                )
            }
            .sizeIn(minHeight = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnlineArtwork(
            url = track.artwork,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            placeholderSeed = track.name,
            placeholderKind = BrandArtworkKind.Music,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artist, track.album, track.sourceName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.durationMs > 0) {
            Text(
                formatSeconds(track.durationMs / 1000f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // 下载按钮只服务触控：读屏走整行 customActions（避免重复可聚焦入口）。
        IconButton(
            onClick = onDownload,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clearAndSetSemantics { },
        ) { Icon(Icons.Rounded.Download, contentDescription = null) }
    }
}

/** 秒 → m:ss（本文件私有，避免与 MusicApp 内部同名助手耦合）。 */
private fun formatSeconds(sec: Float): String {
    val seconds = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** 专辑简介折叠区：默认收起只显示两行；展开/收起按钮 48dp 且停留在原处保持焦点。 */
@Composable
internal fun AlbumIntroSection(intro: String, albumKey: String) {
    var expanded by rememberSaveable(albumKey) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text(
            intro,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                stateDescription = if (expanded) "已展开" else "已收起"
            },
        ) { Text(if (expanded) "收起简介" else "展开简介") }
    }
}

/**
 * 专辑曲目/分集的显示顺序：descending=false 原样，true 反转。**纯函数**；
 * `null` 透传表示「尚未加载」。只做原样/反转，不按标题数字重排（各站格式不一，猜数字更危险）。
 */
internal fun <T> orderedAlbumTracks(tracks: List<T>?, descending: Boolean): List<T>? =
    tracks?.let { if (descending) it.reversed() else it }

/**
 * 专辑曲目/分集的一键正序/倒序按钮：点一下切换；读屏只播报当前顺序「正序/倒序」。
 */
@Composable
internal fun TrackOrderToggle(
    descending: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val label = if (descending) "倒序" else "正序"
    val nextLabel = if (descending) "正序" else "倒序"
    TextButton(
        onClick = { onToggle(!descending) },
        modifier = Modifier.padding(horizontal = 16.dp).sizeIn(minHeight = 48.dp).clearAndSetSemantics {
            contentDescription = label
            onClick { onToggle(!descending); true }
            customActions = listOf(CustomAccessibilityAction("切换为$nextLabel") { onToggle(!descending); true })
        },
    ) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** 专辑/歌单/歌手行：方形封面 + 名称/副标题；点按钻取。 */
@Composable
private fun OnlineCollectionRow(
    name: String,
    subtitle: String,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "打开") { onClick() }
            .sizeIn(minHeight = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnlineArtwork(
            url = artworkUrl,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
            placeholderSeed = name,
            placeholderKind = BrandArtworkKind.Generic,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
