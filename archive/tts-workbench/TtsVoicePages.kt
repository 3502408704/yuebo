package com.example.local_music_player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * AI TTS 工作台子页（由 TtsWorkbench.kt 宿主分派；页面 key 与窗口标题由 MusicApp 托管）：
 * - [TtsVoiceEditPage] 角色语音：「编辑语音」的目标页，该角色完整的语音合成能力——
 *   台词卡（该角色轨道文本）、情绪标签卡（AI 情感控制：逐行情绪标签=合成参数）、
 *   音色来源/引擎下拉、音色列表子页入口、导演面板（AI 导演置顶最显眼）、合成参数入口；
 * - [TtsVoicePickerPage] 音色列表：按语言分类，每个分类专属入口；分类内发音人点按选择、
 *   长按菜单或读屏 customActions 试听；
 * - [TtsParamsPage] 合成参数：独立入口的动态参数页，随引擎变化异步加载（先出加载帧再挂面板）。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，不参与编译。
 */

// ==== 角色语音页（编辑语音） ====

@Composable
internal fun TtsVoiceEditPage(
    role: String,
    models: List<TtsModelInfo>,
    aiClient: TtsAiClient,
    config: TtsRoleConfig,
    referenceName: String,
    trackText: String,
    emotionTags: List<String>?,
    status: String,
    running: Boolean,
    onConfigChange: (TtsRoleConfig) -> Unit,
    onEmotionTagsChange: (List<String>?) -> Unit,
    onPickReference: () -> Unit,
    onSynthesize: () -> Unit,
    onOpenVoices: () -> Unit,
    onOpenParams: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val aiDirector = remember { TtsAiUiState() }
    val aiVoice = remember { TtsAiUiState() }
    val aiEmotion = remember { TtsAiUiState() }
    var showDirector by rememberSaveable { mutableStateOf(false) }
    var showVoiceBuilder by rememberSaveable { mutableStateOf(false) }
    var showAiDirector by rememberSaveable { mutableStateOf(false) }
    var showAiVoice by rememberSaveable { mutableStateOf(false) }
    var showEmotionAi by rememberSaveable { mutableStateOf(false) }
    val directorValues = remember { mutableStateMapOf<String, String>() }
    val builderValues = remember { mutableStateMapOf<String, String>() }

    // 音色来源/引擎下拉都按目录动态生成（某来源没有引擎就不出现；换来源/引擎时参数与音色归位）
    val availableModes = ttsModeOptions(models)
    val effectiveMode = availableModes.firstOrNull { it.mode == config.mode }?.mode
        ?: availableModes.firstOrNull()?.mode.orEmpty()
    val modeModels = models.filter { it.mode == effectiveMode }
    val selectedModel = modeModels.firstOrNull { it.id == config.modelId } ?: modeModels.firstOrNull()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppBar(title = "角色语音·$role", onBack = onBack, onDevices = null)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // —— 台词：该角色轨道的文本（编辑文本可改写；AI 导演与按台词分析以此为上下文） ——
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            if (trackText.isBlank()) "台词" else "台词（${trackText.lines().size} 行）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (trackText.isBlank()) "该角色还没有台词，回主页在脚本里补充"
                            else trackText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            // —— 情绪标签：AI 情感控制的落点（情感=合成参数，不下发进合成文本；脚本内（情绪）标注兜底） ——
            if (trackText.isNotBlank()) {
                val originalLines = trackText.lines().filter { it.isNotBlank() }
                val aligned = emotionTags?.takeIf { it.size == originalLines.size }
                // 「风格映射」引擎（微软等）：情感走 style 参数，AI 只从可映射词表选词。
                // 该引擎且当前音色没有风格清单时 AI 情感不可用（没有可下发的 style）——
                // 停用入口而不是让合成时静默丢风格（2026-09-29 修复：风格控制不可用但入口仍亮着）。
                val styleFlavor = selectedModel?.supportsStyle == true && selectedModel?.supportsInstructions != true
                val currentVoice = selectedModel?.voices?.firstOrNull { it.id == config.voiceId }
                val styleSupported = !styleFlavor || currentVoice?.styles?.isNotEmpty() == true
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("情绪标签", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    !styleSupported ->
                                        "当前音色（${currentVoice?.label ?: "所选音色"}）不支持情感风格，AI 情感控制不可用；换多风格音色（如晓晓/云健）后可用。"
                                    aligned == null && styleFlavor ->
                                        "未标注。AI 从「${currentVoice?.label ?: "当前音色"}」的真实情感风格词表逐行生成情绪标签，脚本行内已有的（情绪）标注优先；其余行才映射为 style 参数下发。"
                                    aligned == null ->
                                        "未标注。AI 逐行生成情绪标签后，脚本行内已有的（情绪）标注优先；其余行才作为情感参数下发。"
                                    styleFlavor ->
                                        "已标注 ${aligned.count { it.isNotBlank() }}/${aligned.size} 行；脚本行内情绪优先，其余行映射为微软情感风格（style）。"
                                    else ->
                                        "已标注 ${aligned.count { it.isNotBlank() }}/${aligned.size} 行；脚本行内情绪优先，其余行作为情感参数下发。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        aiEmotion.reset()
                                        showEmotionAi = true
                                    },
                                    enabled = !running && !aiEmotion.running && styleSupported,
                                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                                ) {
                                    Icon(Icons.Rounded.TheaterComedy, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (styleSupported) "AI 情感控制" else "AI 情感控制（该音色不可用）")
                                }
                                if (aligned != null) {
                                    OutlinedButton(
                                        onClick = { onEmotionTagsChange(null) },
                                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                                    ) { Text("清除标签") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                TtsModeDropdown(
                    options = availableModes,
                    selectedMode = effectiveMode,
                    onSelect = { mode ->
                        val first = models.firstOrNull { it.mode == mode }
                        onConfigChange(config.copy(
                            mode = mode,
                            modelId = first?.id.orEmpty(),
                            voiceId = ttsDefaultVoiceId(first),
                            format = ttsDefaultFormatFor(first),
                            params = roleParamsFor(first, emptyMap()),
                        ))
                    },
                )
            }
            item {
                TtsEngineDropdown(
                    models = modeModels,
                    selected = selectedModel,
                    onSelect = { model ->
                        val keepVoice = model.voices.firstOrNull { it.id == config.voiceId }?.id
                        onConfigChange(config.copy(
                            modelId = model.id,
                            voiceId = keepVoice ?: ttsDefaultVoiceId(model),
                            format = ttsDefaultFormatFor(model),
                            params = roleParamsFor(model, config.params),
                        ))
                    },
                )
            }
            // —— 输出格式下拉：选项=引擎原生支持集（服务端透传不转码），同轨道全程一致保证可拼接 ——
            item {
                TtsFormatDropdown(
                    options = (selectedModel?.formats?.ifEmpty { listOf("wav") } ?: listOf("wav"))
                        .map { TtsToolOption(it, ttsFormatLabel(it)) },
                    selectedFormat = config.format.ifBlank { ttsDefaultFormatFor(selectedModel) },
                    onSelect = { value -> onConfigChange(config.copy(format = value)) },
                )
            }
            when (effectiveMode) {
                "design" -> {
                    if (config.voiceDescription.isNotBlank()) {
                        item {
                            Text(
                                "音色描述：${config.voiceDescription}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showVoiceBuilder = true },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (config.voiceDescription.isBlank()) "填写音色描述" else "修改音色描述") }
                            OutlinedButton(
                                onClick = { aiVoice.reset(); showAiVoice = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("AI 设计") }
                        }
                    }
                    item {
                        Text(
                            "生成时会先合成一句校准音频，再以克隆保持全程同音。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                "clone" -> {
                    item {
                        Text(
                            "参考音频：${referenceName.ifBlank { "未选择（建议 8~10 秒）" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item {
                        OutlinedButton(onClick = onPickReference, modifier = Modifier.fillMaxWidth()) {
                            Text("选择参考音频")
                        }
                    }
                }
                else -> {
                    item {
                        val voice = selectedModel?.voices?.firstOrNull { it.id == config.voiceId }
                        OutlinedButton(onClick = onOpenVoices, modifier = Modifier.fillMaxWidth()) {
                            Text("选择音色：${voice?.label ?: "未选择"}")
                        }
                    }
                }
            }
            // —— 表演与参数：导演面板只对支持 instructions 的引擎出现（MiMo）；微软走情感风格参数 ——
            if (selectedModel?.supportsInstructions == true) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                aiDirector.reset()
                                showAiDirector = true
                            },
                            enabled = !running && trackText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("AI 导演")
                        }
                        if (config.baseStyle.isNotBlank()) {
                            Text("表演指导：${config.baseStyle}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showDirector = true }, modifier = Modifier.weight(1f)) {
                                Text("导演面板")
                            }
                            if (config.baseStyle.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { onConfigChange(config.copy(baseStyle = "")) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("清除指导") }
                            }
                        }
                    }
                }
            }
            // —— 合成参数：独立入口（参数随引擎变化，子页内异步加载） ——
            item {
                val paramCount = selectedModel?.paramsSchema?.size ?: 0
                OutlinedButton(onClick = onOpenParams, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (paramCount == 0) "合成参数（当前引擎没有可调项）"
                        else "合成参数（${selectedModel?.engine.orEmpty()}·$paramCount 项）"
                    )
                }
            }
            item {
                Button(
                    onClick = onSynthesize,
                    enabled = !running && trackText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 52.dp),
                ) { Text(if (running) "正在合成…" else "合成「$role」轨道") }
            }
            item {
                Text(
                    status,
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showDirector) {
        TtsPromptDialog(
            title = "导演面板",
            hint = "表演指导会作为控制指令发给合成引擎，不会被朗读出来。",
            fields = TTS_DIRECTOR_FIELDS,
            initial = directorValues,
            onApply = { values ->
                directorValues.clear()
                directorValues.putAll(values)
                onConfigChange(config.copy(baseStyle = buildTtsPrompt(TTS_DIRECTOR_FIELDS, values)))
                showDirector = false
            },
            onDismiss = { showDirector = false },
        )
    }
    if (showAiDirector) {
        TtsAiParamsDialog(
            title = "AI 导演",
            intro = "AI 会朗读该角色台词设计表演指导（角色、场景、情绪、停顿、重音等），确认后应用到导演面板；也可先在导演面板手动微调。",
            fields = TTS_DIRECTOR_FIELDS,
            showSpeed = true,
            inputLabel = null,
            inputInitial = "",
            state = aiDirector,
            onStart = { launchTtsAiRequest(scope, aiClient, TTS_AI_MODE_DIRECT, trackText, aiDirector) },
            onApply = { values, speed ->
                directorValues.clear()
                directorValues.putAll(values)
                var next = config.copy(baseStyle = buildTtsPrompt(TTS_DIRECTOR_FIELDS, values))
                // 语速建议落到当前引擎的语速参数（schema 里有 speed 位才生效，如微软）
                if (speed != null) {
                    val model = selectedModel
                    if (model != null && model.paramsSchema.any { it.name == "speed" }) {
                        next = next.copy(params = next.params + ("speed" to "%.2f".format(speed)))
                    }
                }
                onConfigChange(next)
                showAiDirector = false
            },
            onDismiss = { showAiDirector = false },
        )
    }
    if (showVoiceBuilder) {
        TtsPromptDialog(
            title = "音色工作台",
            hint = "用一句话或多个维度描述角色声音，引擎会按描述合成。",
            fields = TTS_VOICE_DESCRIPTION_FIELDS,
            initial = builderValues,
            onApply = { values ->
                builderValues.clear()
                builderValues.putAll(values)
                onConfigChange(config.copy(voiceDescription = buildTtsPrompt(TTS_VOICE_DESCRIPTION_FIELDS, values)))
                showVoiceBuilder = false
            },
            onDismiss = { showVoiceBuilder = false },
        )
    }
    if (showAiVoice) {
        TtsAiParamsDialog(
            title = "AI 音色设计",
            intro = "两种工作模式：优化你的一句话描述，或让 AI 朗读该角色台词、按内容自动设计合适的音色；确认后应用到音色描述。",
            fields = TTS_VOICE_DESCRIPTION_FIELDS,
            showSpeed = false,
            inputLabel = "一句话描述角色声音",
            inputInitial = config.voiceDescription,
            state = aiVoice,
            designModes = true,
            trackText = trackText,
            onStart = { prompt -> launchTtsAiRequest(scope, aiClient, TTS_AI_MODE_VOICE, prompt, aiVoice) },
            onApply = { values, _ ->
                builderValues.clear()
                builderValues.putAll(values)
                onConfigChange(config.copy(voiceDescription = buildTtsPrompt(TTS_VOICE_DESCRIPTION_FIELDS, values)))
                showAiVoice = false
            },
            onDismiss = { showAiVoice = false },
        )
    }
    if (showEmotionAi) {
        // 输入带上角色前缀（与 AI 情感控制「角色（情绪）：台词」契约对齐）；产物只取情绪标签，
        // 台词文字逐行校验一致才应用——情感是合成参数，脚本与台词永不改写。
        // 「风格映射」引擎（微软）下发可映射词表，AI 选词必中真实 style（引擎感知，防复用错通道）
        val emotionLines = trackText.lines().filter { it.isNotBlank() }
        val styleFlavor = selectedModel?.supportsStyle == true && selectedModel?.supportsInstructions != true
        TtsAiScriptDialog(
            title = "AI 情感控制",
            intro = if (styleFlavor) {
                "AI 逐行理解「$role」的 ${emotionLines.size} 行台词，从「${selectedModel?.voices?.firstOrNull { it.id == config.voiceId }?.label ?: "当前音色"}」的真实情感风格词表中选出最贴切的情绪；标签在合成时映射为 style 参数下发给引擎，不改动台词与脚本。生成后可预览再应用。"
            } else {
                "AI 逐行理解「$role」的 ${emotionLines.size} 行台词，生成对应的情绪标签；标签在合成时作为情感参数下发给引擎，不改动台词与脚本。生成后可预览再应用。"
            },
            state = aiEmotion,
            onStart = {
                launchTtsAiRequest(
                    scope, aiClient, TTS_AI_MODE_EMOTION,
                    emotionLines.joinToString("\n") { "$role：$it" }, aiEmotion,
                    emotionVocab = ttsEmotionVocabFor(
                        selectedModel,
                        selectedModel?.voices?.firstOrNull { it.id == config.voiceId },
                    ))
            },
            onApply = { generated ->
                val tags = parseEmotionTags(emotionLines, generated)
                if (tags == null) {
                    aiEmotion.scriptPreview = null
                    aiEmotion.message = "AI 输出与台词不对应，已放弃本次标注，请重试"
                } else {
                    onEmotionTagsChange(tags)
                    showEmotionAi = false
                }
            },
            onDismiss = { showEmotionAi = false },
        )
    }
}

// ==== 音色列表页（语言分类 → 分类内音色，支持试听；顶部可随时切换引擎） ====

@Composable
internal fun TtsVoicePickerPage(
    lang: String,
    model: TtsModelInfo?,
    modeModels: List<TtsModelInfo>,
    selectedModel: TtsModelInfo?,
    selectedVoiceId: String,
    status: String,
    onEngineSelect: (TtsModelInfo) -> Unit,
    onLangChange: (String) -> Unit,
    onSelect: (TtsVoice) -> Unit,
    onPreview: (TtsVoice) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppBar(
            title = if (lang.isBlank()) "选择音色" else "音色·$lang",
            onBack = onBack,
            onDevices = null,
        )
        if (modeModels.isNotEmpty()) {
            // 引擎下拉常驻：浏览音色时即可换引擎（换引擎后音色清单与分类随之刷新）
            Column(Modifier.padding(horizontal = 16.dp)) {
                TtsEngineDropdown(models = modeModels, selected = selectedModel, onSelect = onEngineSelect)
            }
        }
        when {
            model == null -> Text(
                "当前音色来源没有可用引擎",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            lang.isBlank() -> {
                val groups = remember(model.id) { groupVoicesByLanguage(model.voices) }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "${model.label} 共 ${model.voices.size} 个音色，按语言分类",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    items(groups, key = { it.label }) { group ->
                        Card(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onLangChange(group.label) },
                                    onClickLabel = "打开分类",
                                )
                                .semantics(mergeDescendants = true) {},
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(group.label, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${group.voices.size} 个音色",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                            }
                        }
                    }
                }
            }
            else -> {
                val groupVoices = remember(model.id, lang) {
                    groupVoicesByLanguage(model.voices).firstOrNull { it.label == lang }?.voices.orEmpty()
                }
                var query by rememberSaveable(lang) { mutableStateOf("") }
                val filtered = groupVoices.filter { voice ->
                    query.isBlank() || voice.label.contains(query, ignoreCase = true) ||
                        voice.id.contains(query, ignoreCase = true)
                }
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        label = { Text("搜索发音人") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        items(filtered, key = { it.id }) { voice ->
                            TtsVoiceRow(
                                voice = voice,
                                selected = voice.id == selectedVoiceId,
                                onSelect = { onSelect(voice) },
                                onPreview = { onPreview(voice) },
                            )
                        }
                        if (filtered.isEmpty()) {
                            item { Text("没有匹配的发音人", Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
        Text(
            status,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 发音人行，格式与微软音色表一致：「名称，性别，语言」；行首单选框标记当前选择。
 * 点按选择；试听走长按菜单与读屏 customActions（行内不放按钮，列表保持干净）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TtsVoiceRow(
    voice: TtsVoice,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val genderLabel = when {
        voice.gender.equals("Female", ignoreCase = true) -> "女"
        voice.gender.equals("Male", ignoreCase = true) -> "男"
        else -> voice.gender
    }
    val rowText = listOf(voice.label, genderLabel, voiceLanguageLabel(voice.lang))
        .filter { it.isNotBlank() }
        .joinToString("，")
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = { menuOpen = true },
                    onClickLabel = "选择",
                    onLongClickLabel = "更多操作",
                )
                .sizeIn(minHeight = 56.dp)
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .semantics(mergeDescendants = true) {
                    customActions = listOf(
                        CustomAccessibilityAction("试听") { onPreview(); true },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rowText, style = MaterialTheme.typography.bodyMedium)
                // 目录元数据（特质/适合内容/风格数）：有才显示；随合并语义一起被读屏朗读。
                val detail = listOf(
                    voice.traits.takeIf { it.isNotBlank() }?.let { "特质：$it" },
                    voice.suitable.takeIf { it.isNotBlank() }?.let { "适合：$it" },
                    voice.styles.takeIf { it.isNotEmpty() }?.let { "风格 ${it.size} 种" },
                ).filterNotNull().joinToString(" ｜ ")
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("试听") },
                onClick = {
                    menuOpen = false
                    onPreview()
                },
            )
            DropdownMenuItem(
                text = { Text(if (selected) "保持选择" else "选择") },
                onClick = {
                    menuOpen = false
                    onSelect()
                },
            )
        }
    }
}

// ==== 合成参数页（独立入口；随引擎变化异步加载） ====

@Composable
internal fun TtsParamsPage(
    model: TtsModelInfo?,
    values: Map<String, String>,
    onChange: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    // 参数面板随引擎切换重挂载：先渲染加载帧（liveRegion 播报）再异步挂面板，避免切换瞬间整页卡顿
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(model?.id) {
        loading = true
        kotlinx.coroutines.yield()
        loading = false
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppBar(title = "合成参数", onBack = onBack, onDevices = null)
        when {
            model == null -> Text(
                "当前音色来源没有可用引擎",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            loading -> Text(
                "正在加载合成参数…",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            model.paramsSchema.isEmpty() -> Text(
                "当前引擎（${model.engine}）没有可调参数。",
                Modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text("${model.label}（${model.engine}）", style = MaterialTheme.typography.titleMedium) }
                item {
                    Text(
                        "参数由服务端目录下发，随引擎自动加载；调整立即生效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item { TtsDynamicParams(model = model, values = values, onChange = onChange) }
            }
        }
    }
}
