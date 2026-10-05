package com.example.local_music_player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 音源管理页（我的 → 音源管理）：导入插件文件 / 链接 / 订阅 JSON，启停、测活、删除。
 * 在线页的站点（站点 chips）与本页启用的音源一一对应。
 */
@Composable
fun SourceManagerScreen(
    sources: List<MusicFreeSource>,
    status: String?,
    pendingImports: List<MusicFreeSource>,
    selectionMode: Boolean,
    selection: Set<String>,
    onImportFiles: (List<Uri>) -> Unit,
    onImportUrl: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onRemoveMany: (Set<String>) -> Unit,
    onConfirmImport: (Set<String>) -> Unit,
    onCancelImport: () -> Unit,
    onToggleSelectionMode: (Boolean) -> Unit,
    onToggleSelection: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    sourceTestStatuses: Map<String, StreamSourceTestStatus>,
    testingSourceIds: Set<String>,
    onTestSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var urlDialogOpen by rememberSaveable { mutableStateOf(false) }
    var urlInput by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MusicFreeSource?>(null) }
    var pendingBatchDelete by remember { mutableStateOf(false) }
    var menuSourceId by remember { mutableStateOf<String?>(null) }
    var importMenuOpen by remember { mutableStateOf(false) }
    var pendingSelection by rememberSaveable { mutableStateOf(setOf<String>()) }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onImportFiles(uris) }

    LaunchedEffect(pendingImports) {
        if (pendingImports.isNotEmpty()) {
            pendingSelection = pendingImports.map { it.id }.toSet()
        }
    }

    Column(modifier.fillMaxSize()) {
        SourceManagerStatus(status)
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            IconButton(
                onClick = { importMenuOpen = true },
                modifier = Modifier.align(Alignment.CenterEnd).sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
            DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                DropdownMenuItem(
                    text = { Text("导入文件") },
                    onClick = {
                        importMenuOpen = false
                        fileLauncher.launch(arrayOf("application/json", "application/javascript", "text/javascript", "text/plain", "application/octet-stream"))
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
                DropdownMenuItem(
                    text = { Text("链接导入") },
                    onClick = { importMenuOpen = false; urlDialogOpen = true },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        }
        if (selectionMode) {
            SelectionBar(
                count = selection.size,
                total = sources.size,
                onToggleSelectAll = onToggleSelectAll,
                onDelete = { if (selection.isNotEmpty()) pendingBatchDelete = true },
                onDone = { onToggleSelectionMode(false) },
            )
        }
        if (sources.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("尚未导入音源，点击上方按钮导入插件或订阅。", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    SourceRow(
                        source = source,
                        selectionMode = selectionMode,
                        selected = source.id in selection,
                        menuOpen = menuSourceId == source.id,
                        onOpenMenu = { menuSourceId = source.id },
                        onMenuDismiss = { menuSourceId = null },
                        onToggle = { onToggle(source.id, it) },
                        onToggleSelection = { onToggleSelection(source.id) },
                        onDelete = { pendingDelete = source },
                        testStatus = sourceTestStatuses[source.id],
                        testing = source.id in testingSourceIds,
                        onTest = { onTestSource(source.id) },
                        onSelectMode = {
                            onToggleSelectionMode(true)
                            onToggleSelection(source.id)
                        },
                    )
                }
            }
        }
    }
    if (urlDialogOpen) {
        UrlImportDialog(
            input = urlInput,
            onInputChange = { urlInput = it },
            onConfirm = {
                val url = urlInput.trim()
                if (url.isNotEmpty()) {
                    onImportUrl(url)
                    urlInput = ""
                }
                urlDialogOpen = false
            },
            onDismiss = { urlDialogOpen = false },
        )
    }
    if (pendingImports.isNotEmpty()) {
        PendingSourceImportDialog(
            sources = pendingImports,
            selected = pendingSelection,
            onToggle = { id ->
                pendingSelection = if (id in pendingSelection) pendingSelection - id else pendingSelection + id
            },
            onToggleSelectAll = {
                pendingSelection = if (pendingSelection.size == pendingImports.size) {
                    emptySet()
                } else {
                    pendingImports.map { it.id }.toSet()
                }
            },
            onConfirm = { onConfirmImport(pendingSelection) },
            onCancel = onCancelImport,
        )
    }
    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除音源") },
            text = { Text("确定删除音源“${source.name}”吗？删除后需要重新导入才能使用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(source.id)
                        pendingDelete = null
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
            },
        )
    }
    if (pendingBatchDelete) {
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text("删除音源") },
            text = { Text("确定删除选中的 ${selection.size} 个音源吗？删除后需要重新导入才能使用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveMany(selection)
                        pendingBatchDelete = false
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = false }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
            },
        )
    }
}

/** 状态行（读屏 liveRegion 播报导入/删除结果）。 */
@Composable
private fun SourceManagerStatus(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(
            message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 选择模式工具栏：全选、删除选中、完成。 */
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onToggleSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "已选择 $count 个",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = onToggleSelectAll,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) { Text(if (count == total && total > 0) "取消全选" else "全选") }
        TextButton(
            onClick = onDelete,
            enabled = count > 0,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) { Text("删除选中") }
        TextButton(
            onClick = onDone,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) { Text("完成") }
    }
}

/** 导入确认对话框：勾选要导入的音源，确认后仅导入选中的。 */
@Composable
private fun PendingSourceImportDialog(
    sources: List<MusicFreeSource>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("选择要导入的音源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "共 ${sources.size} 个可导入",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = onToggleSelectAll,
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) { Text(if (selected.size == sources.size && sources.isNotEmpty()) "取消全选" else "全选") }
                }
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(sources, key = { it.id }) { source ->
                        PendingSourceRow(
                            source = source,
                            checked = source.id in selected,
                            onToggle = { onToggle(source.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selected.isNotEmpty(),
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
    )
}

@Composable
private fun PendingSourceRow(
    source: MusicFreeSource,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = source.name
                stateDescription = if (checked) "已选择" else "未选择"
            }
            .sizeIn(minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                source.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                source.version.ifBlank { "版本未知" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 源行：一个无障碍焦点。点击切换启用/选择；长按弹出菜单（选择/测活/删除）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(
    source: MusicFreeSource,
    selectionMode: Boolean,
    selected: Boolean,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onMenuDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
    testStatus: StreamSourceTestStatus?,
    testing: Boolean,
    onTest: () -> Unit,
    onSelectMode: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selectionMode) onToggleSelection()
                        else onToggle(!source.enabled)
                    },
                    onLongClick = if (selectionMode) null else ({ onOpenMenu() }),
                    onLongClickLabel = "更多操作",
                )
                .clearAndSetSemantics {
                    contentDescription = source.name
                    stateDescription = if (source.enabled) "已启用" else "已停用"
                    onClick(label = "切换开关") { onToggle(!source.enabled); true }
                    if (!selectionMode) {
                        customActions = buildList {
                            add(CustomAccessibilityAction("选择") { onSelectMode(); true })
                            if (source.enabled) {
                                add(CustomAccessibilityAction("测试音源") { onTest(); true })
                            }
                            add(CustomAccessibilityAction("删除") { onDelete(); true })
                        }
                    }
                }
                .sizeIn(minHeight = 56.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                testStatus?.let { status ->
                    Text(
                        status.message(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    buildString {
                        append("MusicFree 音源")
                        if (source.version.isNotBlank()) append("，版本").append(source.version)
                        append(if (source.enabled) "，已启用" else "，已停用")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onMenuDismiss,
        ) {
            DropdownMenuItem(
                text = { Text("选择") },
                onClick = {
                    onMenuDismiss()
                    onSelectMode()
                },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
            DropdownMenuItem(
                text = { Text(if (testing) "正在测试音源" else "测试音源") },
                onClick = {
                    onMenuDismiss()
                    onTest()
                },
                enabled = source.enabled && !testing,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    onMenuDismiss()
                    onDelete()
                },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
        }
    }
}

@Composable
private fun UrlImportDialog(
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("链接导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("支持插件直连地址或订阅 JSON 链接，例如 https://example.com/plugin/index.js")
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text("https://example.com/plugin/index.js") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) onConfirm() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = input.isNotBlank(),
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
    )
}
