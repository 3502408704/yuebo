package com.example.local_music_player

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSubtitleSettingsSheet(
    state: MusicUiState,
    onVisibleChange: (Boolean) -> Unit,
    onPickSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit,
    onSelectTrack: (String?) -> Unit,
    onSelectSecondaryTrack: (String?) -> Unit,
    onLoadAiSubtitle: () -> Unit,
    onToggleTts: () -> Unit,
    onSpeakCurrentSubtitle: () -> Unit,
    onPreviousSubtitle: () -> Unit,
    onReplaySubtitle: () -> Unit,
    onNextSubtitle: () -> Unit,
    onSetOffset: (Long) -> Unit,
    onSetScale: (Float) -> Unit,
    onSelectCue: (String) -> Unit,
    returnFocusRequester: FocusRequester? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val titleFocusRequester = remember { FocusRequester() }
    val transcriptFocusRequester = remember { FocusRequester() }
    val primaryRowFocusRequester = remember { FocusRequester() }
    val secondaryRowFocusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var primaryPickerOpen by rememberSaveable { mutableStateOf(false) }
    var secondaryPickerOpen by rememberSaveable { mutableStateOf(false) }
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var localStatus by rememberSaveable { mutableStateOf<String?>(null) }
    val adjustedPosition = state.videoPositionMs + state.videoSubtitleOffsetMs
    val currentPrimaryIndex = activeVideoSubtitleIndex(state.videoSubtitleCues, adjustedPosition)
    val currentPrimaryText = state.videoSubtitleCues.getOrNull(currentPrimaryIndex)?.text.orEmpty()
    val primaryTrack = state.videoSubtitleTracks.firstOrNull { it.id == state.videoSubtitleSelectedTrackId }
    val secondaryTrack = state.videoSubtitleTracks.firstOrNull { it.id == state.videoSecondarySubtitleTrackId }
    val secondaryCandidates = state.videoSubtitleTracks.filter { it.id != state.videoSubtitleSelectedTrackId }

    VideoSubtitleDialogFocusEffect(
        title = "字幕设置",
        focusRequester = titleFocusRequester,
        returnFocusRequester = returnFocusRequester,
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.semantics { paneTitle = "字幕设置" },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = "字幕设置",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .focusRequester(titleFocusRequester)
                        .semantics {
                            contentDescription = "字幕设置"
                            heading()
                        },
                )
            }
            item {
                VideoSubtitleSwitchRow(
                    label = "显示字幕",
                    checked = state.videoSubtitleVisible,
                    onCheckedChange = onVisibleChange,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                VideoSubtitleListItem(
                    label = "主字幕",
                    valueText = videoSubtitleSelectionLabel(
                        state.videoSubtitleSelectedTrackId,
                        primaryTrack,
                        state.videoSubtitleLoadingTrackId,
                        "关闭",
                        "暂无字幕轨道",
                    ),
                    enabled = state.videoSubtitleTracks.isNotEmpty(),
                    modifier = Modifier.focusRequester(primaryRowFocusRequester),
                    onClick = { primaryPickerOpen = true },
                )
            }
            item {
                VideoSubtitleListItem(
                    label = "副字幕",
                    valueText = when {
                        state.videoSecondarySubtitleLoadingTrackId != null -> "加载中"
                        secondaryTrack != null -> videoSubtitleTrackLabel(secondaryTrack)
                        secondaryCandidates.isNotEmpty() -> "关闭"
                        else -> "暂无其他字幕轨道"
                    },
                    enabled = secondaryCandidates.isNotEmpty(),
                    modifier = Modifier.focusRequester(secondaryRowFocusRequester),
                    onClick = { secondaryPickerOpen = true },
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = "选择外挂字幕",
                    enabled = true,
                    onClick = onPickSubtitle,
                )
            }
            if (state.videoSubtitleName != null) {
                item {
                    VideoSubtitleActionRow(
                        label = "清除当前字幕",
                        enabled = true,
                        onClick = onClearSubtitle,
                    )
                }
            }
            item {
                Text(
                    text = "字幕增强",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 4.dp)
                        .semantics {
                            contentDescription = "字幕增强"
                            heading()
                        },
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = if (state.videoSubtitleAiLoading) "正在加载 AI 定时字幕…" else "加载 AI 定时字幕",
                    enabled = !state.videoSubtitleAiLoading,
                    onClick = onLoadAiSubtitle,
                )
            }
            item {
                VideoSubtitleListItem(
                    label = "字幕稿",
                    valueText = if (state.videoSubtitleCues.isEmpty()) {
                        "暂无已加载字幕"
                    } else {
                        "共 ${state.videoSubtitleCues.size} 句，可搜索"
                    },
                    enabled = state.videoSubtitleCues.isNotEmpty(),
                    modifier = Modifier.focusRequester(transcriptFocusRequester),
                    onClick = { transcriptOpen = true },
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = "导出 WebVTT",
                    enabled = state.videoSubtitleCues.isNotEmpty(),
                    onClick = {
                        localStatus = VideoSubtitleShare.shareWebVtt(
                            context,
                            state.videoSubtitleName ?: "字幕",
                            state.videoSubtitleCues,
                        ).fold(
                            onSuccess = { "字幕 WebVTT 已交给系统分享" },
                            onFailure = { "字幕导出失败：${it.message.orEmpty()}" },
                        )
                    },
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = "导出字幕文本",
                    enabled = state.videoSubtitleCues.isNotEmpty(),
                    onClick = {
                        localStatus = VideoSubtitleShare.sharePlainText(
                            context,
                            state.videoSubtitleName ?: "字幕",
                            state.videoSubtitleCues,
                        ).fold(
                            onSuccess = { "字幕文本已交给系统分享" },
                            onFailure = { "字幕导出失败：${it.message.orEmpty()}" },
                        )
                    },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                VideoSubtitleSliderRow(
                    label = "字幕延迟",
                    valueText = "${state.videoSubtitleOffsetMs}毫秒",
                    value = state.videoSubtitleOffsetMs.toFloat(),
                    valueRange = -30_000f..30_000f,
                    steps = 11,
                    onValueChange = { onSetOffset(it.toLong()) },
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = "延迟归零",
                    enabled = state.videoSubtitleOffsetMs != 0L,
                    onClick = { onSetOffset(0L) },
                )
            }
            item {
                VideoSubtitleSliderRow(
                    label = "字幕大小",
                    valueText = "${(state.videoSubtitleScale * 100).toInt()}%",
                    value = state.videoSubtitleScale,
                    valueRange = 0.75f..1.75f,
                    steps = 9,
                    onValueChange = onSetScale,
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = "字幕大小恢复默认",
                    enabled = state.videoSubtitleScale != 1f,
                    onClick = { onSetScale(1f) },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                VideoSubtitleSwitchRow(
                    label = "朗读字幕",
                    checked = state.videoSubtitleTtsEnabled,
                    onCheckedChange = { onToggleTts() },
                )
            }
            item {
                VideoSubtitleNavigationRow(
                    enabled = state.videoSubtitleCues.isNotEmpty(),
                    onPrevious = onPreviousSubtitle,
                    onReplay = onReplaySubtitle,
                    onNext = onNextSubtitle,
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = "朗读当前字幕",
                    enabled = currentPrimaryText.isNotBlank(),
                    onClick = onSpeakCurrentSubtitle,
                )
            }
            item {
                VideoSubtitleActionRow(
                    label = "复制当前字幕",
                    enabled = currentPrimaryText.isNotBlank(),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(currentPrimaryText))
                        localStatus = "当前字幕已复制"
                    },
                )
            }
            state.videoSubtitleStatus?.takeIf(String::isNotBlank)?.let { status ->
                item {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .semantics {
                                contentDescription = status
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
            }
            localStatus?.let { status ->
                item {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .semantics {
                                contentDescription = status
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
            }
        }
    }

    if (primaryPickerOpen) {
        VideoSubtitleTrackSelectionSheet(
            title = "选择主字幕",
            tracks = state.videoSubtitleTracks,
            selectedTrackId = state.videoSubtitleSelectedTrackId,
            loadingTrackId = state.videoSubtitleLoadingTrackId,
            allowClose = true,
            onSelect = {
                primaryPickerOpen = false
                onSelectTrack(it)
            },
            returnFocusRequester = primaryRowFocusRequester,
            onDismiss = { primaryPickerOpen = false },
        )
    }
    if (secondaryPickerOpen) {
        VideoSubtitleTrackSelectionSheet(
            title = "选择副字幕",
            tracks = secondaryCandidates,
            selectedTrackId = state.videoSecondarySubtitleTrackId,
            loadingTrackId = state.videoSecondarySubtitleLoadingTrackId,
            allowClose = true,
            onSelect = {
                secondaryPickerOpen = false
                onSelectSecondaryTrack(it)
            },
            returnFocusRequester = secondaryRowFocusRequester,
            onDismiss = { secondaryPickerOpen = false },
        )
    }
    if (transcriptOpen) {
        val trackId = state.videoSubtitleSelectedTrackId ?: "subtitle"
        val cueItems = videoSubtitleCueItems(trackId, state.videoSubtitleCues)
        val currentCueId = state.videoSubtitleCues.getOrNull(currentPrimaryIndex)?.let { cue ->
            videoSubtitleCueId(trackId, currentPrimaryIndex, cue)
        }
        VideoSubtitleTranscriptSheet(
            cues = cueItems,
            currentCueId = currentCueId,
            onSelectCue = onSelectCue,
            returnFocusRequester = transcriptFocusRequester,
            onDismiss = { transcriptOpen = false },
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSubtitleTrackSelectionSheet(
    title: String,
    tracks: List<VideoSubtitleTrack>,
    selectedTrackId: String?,
    loadingTrackId: String?,
    allowClose: Boolean,
    onSelect: (String?) -> Unit,
    returnFocusRequester: FocusRequester,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    VideoSubtitleDialogFocusEffect(title, focusRequester, returnFocusRequester)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .semantics {
                            contentDescription = title
                            heading()
                        },
                )
            }
            if (allowClose) {
                item {
                    VideoSubtitleSelectionRow(
                        label = "关闭字幕",
                        selected = selectedTrackId == null,
                        enabled = true,
                        onClick = { onSelect(null) },
                    )
                }
            }
            items(tracks, key = VideoSubtitleTrack::id) { track ->
                VideoSubtitleSelectionRow(
                    label = videoSubtitleTrackLabel(track),
                    selected = track.id == selectedTrackId,
                    enabled = !track.isLocked,
                    stateText = when {
                        track.isLocked -> "已锁定"
                        track.id == loadingTrackId -> "加载中"
                        else -> null
                    },
                    onClick = { onSelect(track.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSubtitleTranscriptSheet(
    cues: List<VideoSubtitleCueItem>,
    currentCueId: String?,
    onSelectCue: (String) -> Unit,
    returnFocusRequester: FocusRequester,
    onDismiss: () -> Unit,
) {
    val title = "字幕稿"
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(cues, query) {
        val normalized = query.trim()
        if (normalized.isBlank()) cues else cues.filter { it.text.contains(normalized, ignoreCase = true) }
    }
    VideoSubtitleDialogFocusEffect(title, focusRequester, returnFocusRequester)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
                    .semantics {
                        contentDescription = title
                        heading()
                    },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索字幕") },
                supportingText = { Text("共 ${results.size} 句") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(results, key = VideoSubtitleCueItem::id) { cue ->
                    val current = cue.id == currentCueId
                    ListItem(
                        headlineContent = {
                            Text(cue.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = { Text(videoSubtitleTimestamp(cue.positionMs)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onSelectCue(cue.id) }
                            .clearAndSetSemantics {
                                contentDescription = "${videoSubtitleTimestamp(cue.positionMs)}，${cue.text}"
                                stateDescription = if (current) "当前字幕" else ""
                                role = Role.Button
                                onClick("跳转到此字幕") {
                                    onSelectCue(cue.id)
                                    true
                                }
                            },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoEpisodeSelectionSheet(
    state: MusicUiState,
    returnFocusRequester: FocusRequester,
    onSelectEpisode: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = "选集"
    val focusRequester = remember { FocusRequester() }
    VideoSubtitleDialogFocusEffect(title, focusRequester, returnFocusRequester)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = "选集",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .semantics {
                            contentDescription = title
                            heading()
                        },
                )
            }
            itemsIndexed(state.videoQueue, key = { _, video -> video.id }) { index, video ->
                val selected = index == state.videoIndex
                ListItem(
                    headlineContent = {
                        Text(
                            text = "${index + 1}. ${video.title}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = if (selected) ({ Text("正在播放") }) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onSelectEpisode(index) }
                        .clearAndSetSemantics {
                            contentDescription = "${index + 1}. ${video.title}"
                            stateDescription = if (selected) "正在播放" else "未选中"
                            role = Role.Button
                            onClick("选择${video.title}") {
                                onSelectEpisode(index)
                                true
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun VideoSubtitleListItem(
    label: String,
    valueText: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(valueText, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = valueText
                role = Role.Button
                if (enabled) onClick { onClick(); true }
            },
    )
}

@Composable
private fun VideoSubtitleSelectionRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    stateText: String? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = stateText ?: if (selected) "已选中" else "未选中"
                role = Role.Button
                if (enabled) onClick { onClick(); true }
            },
    )
}

@Composable
private fun VideoSubtitleActionRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun VideoSubtitleSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = if (checked) "已开启" else "已关闭"
                role = Role.Switch
                onClick {
                    onCheckedChange(!checked)
                    true
                }
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun VideoSubtitleSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    ListItem(
        headlineContent = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label)
                    Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.semantics {
                        contentDescription = label
                        stateDescription = valueText
                    },
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VideoSubtitleNavigationRow(
    enabled: Boolean,
    onPrevious: () -> Unit,
    onReplay: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onPrevious, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("上一句")
        }
        TextButton(onClick = onReplay, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("重读")
        }
        TextButton(onClick = onNext, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("下一句")
        }
    }
}

@Composable
internal fun VideoSubtitleDialogFocusEffect(
    title: String,
    focusRequester: FocusRequester,
    returnFocusRequester: FocusRequester?,
) {
    val view = LocalView.current
    val currentReturnRequester = rememberUpdatedState(returnFocusRequester)
    LaunchedEffect(title) {
        delay(200)
        // Compose 的 requestFocus 在节点尚未挂载或无障碍服务未就绪时会失败，
        // 此时退化为系统播报，保证 TalkBack 至少能听到工作表标题。
        if (runCatching { focusRequester.requestFocus() }.isFailure) {
            announceForAccessibility(view, title)
        }
    }
    DisposableEffect(title) {
        onDispose {
            val requester = currentReturnRequester.value ?: return@onDispose
            view.postDelayed({ runCatching { requester.requestFocus() } }, 220)
        }
    }
}

private fun announceForAccessibility(view: View, text: String) {
    val manager = view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return
    if (!manager.isEnabled) return
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
        className = view.javaClass.name
        packageName = view.context.packageName
        this.text.add(text)
    }
    runCatching { manager.sendAccessibilityEvent(event) }
}

private fun videoSubtitleSelectionLabel(
    selectedId: String?,
    track: VideoSubtitleTrack?,
    loadingId: String?,
    closedLabel: String,
    emptyLabel: String,
): String = when {
    loadingId != null -> "加载中"
    track != null -> videoSubtitleTrackLabel(track)
    selectedId == null -> closedLabel
    else -> emptyLabel
}

private fun videoSubtitleTrackLabel(track: VideoSubtitleTrack): String = buildList {
    add(track.label.ifBlank { track.language.ifBlank { "未命名字幕" } })
    add(track.sourceLabel)
    if (track.isAiGenerated) add("AI")
    if (track.isLocked) add("已锁定")
    track.authorName.takeIf(String::isNotBlank)?.let { add("作者 $it") }
}.joinToString("，")

private fun videoSubtitleTimestamp(positionMs: Long): String {
    val safe = positionMs.coerceAtLeast(0L)
    val totalSeconds = safe / 1_000L
    return "%02d:%02d:%02d".format(
        totalSeconds / 3_600L,
        (totalSeconds % 3_600L) / 60L,
        totalSeconds % 60L,
    )
}

/** 视频倍速选择（B 站同款 8 档，0.5x–3x）：底部工作表列出档位，当前档位带选中态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSpeedSheet(
    speed: Double,
    enabled: Boolean,
    returnFocusRequester: FocusRequester? = null,
    onSelect: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = "倍速"
    val focusRequester = remember { FocusRequester() }
    VideoSubtitleDialogFocusEffect(title, focusRequester, returnFocusRequester)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .semantics {
                            contentDescription = title
                            heading()
                        },
                )
            }
            if (!enabled) {
                item {
                    Text(
                        text = "投送中或兼容解码模式下不支持调节倍速",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
            }
            items(VideoSpeedOptions) { option ->
                VideoSubtitleSelectionRow(
                    label = formatSpeedLabel(option) + if (option == speed) "（当前）" else "",
                    selected = option == speed,
                    enabled = enabled,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/** 视频清晰度选择：列出服务端解析下发的官方档位（1080P/720P/480P…），当前档带选中态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoQualitySheet(
    options: List<VideoQualityOption>,
    selected: String,
    switching: Boolean,
    returnFocusRequester: FocusRequester? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = "清晰度"
    val focusRequester = remember { FocusRequester() }
    VideoSubtitleDialogFocusEffect(title, focusRequester, returnFocusRequester)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .semantics {
                            contentDescription = title
                            heading()
                        },
                )
            }
            if (options.isEmpty()) {
                item {
                    Text(
                        text = if (switching) "正在切换清晰度，请稍候" else "暂无切换的其他码率",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            items(options, key = { it.code }) { option ->
                VideoSubtitleSelectionRow(
                    label = option.label + if (option.code == selected) "（当前）" else "",
                    selected = option.code == selected,
                    enabled = !switching,
                    onClick = { onSelect(option.code) },
                )
            }
        }
    }
}

/** 视频定时播放（睡眠定时）：15/30/45/60 分钟到点暂停；已设置时显示剩余并支持取消。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSleepTimerSheet(
    totalMs: Long,
    remainingMs: Long,
    returnFocusRequester: FocusRequester? = null,
    onSelect: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = "定时播放"
    val focusRequester = remember { FocusRequester() }
    VideoSubtitleDialogFocusEffect(title, focusRequester, returnFocusRequester)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .semantics {
                            contentDescription = title
                            heading()
                        },
                )
            }
            if (totalMs > 0) {
                item {
                    Text(
                        text = "将在约 " + videoSubtitleTimestamp(remainingMs) + " 后暂停播放",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            items(VideoSleepTimerOptions) { minutes ->
                VideoSubtitleSelectionRow(
                    label = "$minutes 分钟",
                    selected = totalMs == minutes * 60_000L,
                    enabled = true,
                    onClick = { onSelect(minutes) },
                )
            }
            if (totalMs > 0) {
                item {
                    VideoSubtitleActionRow(label = "取消定时播放", enabled = true, onClick = onCancel)
                }
            }
        }
    }
}
