# Android View 无障碍工程规范

## 1. 目标

无障碍不是给控件加几句朗读文字。它是为非视觉、低视力、键盘、开关控制、放大和替代输入用户提供一条与视觉操作等价的完成路径。

任何用户可达状态都要回答六个问题：用户在什么位置；当前对象是什么；它属于什么角色；它现在是什么值或状态；用户可以做什么；操作后发生了什么。还要回答第七个问题：用户如何回到刚才的任务，而不是被弹窗、列表刷新或新窗口打断。

本规范以传统 Android View 系统为主，适用于 XML、Activity、Fragment、RecyclerView、Dialog、PopupWindow、WindowManager Overlay、自定义 View、WebView 和媒体控件。Compose 有相同目标，但 API 不同，不能把本文件代码直接照搬。

## 2. Android 的无障碍数据流

Android 无障碍不是“应用直接对 TalkBack 说话”。应用提供 View 层级和节点信息；辅助功能服务读取 `AccessibilityNodeInfo`；用户在 TalkBack 中点击、滑动或打开操作菜单后，系统把请求回调到 View；应用处理后更新节点和必要事件。

最简路径如下。

视觉 View：负责可见内容和普通点击。

无障碍节点：由系统或 `AccessibilityDelegate` 填充名称、角色、状态、范围、集合位置、可执行 Action 和提示。

辅助功能服务：例如 TalkBack，根据节点决定朗读和可用操作。

操作回调：系统调用 `performAccessibilityAction` 或已有点击行为。

状态更新：应用更新 View、节点语义，并在必要时发送内容变化或主动播报。

这解释了三个常见误解。第一，`OnTouchListener` 不是无障碍点击路径；TalkBack 双击通常到达 `performClick()`。第二，只改 TextView 或图标不等于节点语义同步。第三，调用 `announceForAccessibility` 不是替代正确节点信息，它只是一条短暂消息。

## 3. 语义最小模型

### 名称

名称回答“这是什么”。对文本按钮，系统通常能从文本得到名称；对图标按钮、合并卡片和自定义 View，显式设置 `contentDescription` 或在节点中设置文本/描述。

名称应包含完成任务所需的上下文。推荐“清除搜索”“快进 5 秒”“删除选中的 3 项”“音乐，周杰伦，正在播放”。不推荐“按钮”“更多”“图标”“图片”这类没有任务信息的词。

`contentDescription` 不应机械覆盖有价值的可见文本。对可读文本控件，优先保留系统文本语义；只有需要补充上下文、合并多个子项或替换图标含义时才设置描述。

### 角色

角色回答“它是什么类型的控件”。原生 Button、EditText、Switch、CheckBox、SeekBar 通常已有角色。把普通 TextView 做成点击行、把 LinearLayout 做成开关、把容器做成播放器时，需要补角色。

对开关，节点应表现为 `android.widget.Switch`，并且可勾选。对可选择条目，应表现为可选/可勾选。对滑块，应带范围。对可展开区域，应带展开或收起 Action。不要用自定义 `roleDescription` 替代原生角色；优先原生 className，角色说明只用于原生角色不足时补充。

### 状态和值

状态回答“现在怎样”，例如已选中、已展开、已暂停、已开启。值回答“当前数值是多少”，例如 1.2 倍、60%、01:35/03:20。

稳定状态使用 `stateDescription`；数值范围使用 `RangeInfoCompat`；可选对象额外设置 `isCheckable` 和 `isChecked`。不要把状态永久拼进可复用行的名称，否则 RecyclerView 复用会遗留旧状态，且高版本可能重复朗读。

### Action

Action 回答“现在能做什么”。标准点击、长按、滚动和编辑应保持系统原生含义。附加动作必须有简短动词标签，如“试听”“查看详情”“删除”“展开菜单”“移至左上”“调高音量”。

### 反馈

反馈回答“刚刚发生了什么”。用户操作后的状态变化可以主动播报；后台进度、结果、失败、空数据和不可逆操作也应有非视觉反馈。但每一次 UI 重绘、每个列表 bind、每一帧播放进度都不应该播报。

## 4. XML 与 View 树

### 可访问性树不是视觉树

视觉元素不等于独立焦点。封面、分隔线、背景、重复标题、装饰箭头、徽标和不可独立操作的子文字，通常使用 `importantForAccessibility="no"`。父项必须同时拥有完整名称和所有被合并信息。

不要仅设 `clickable=false` 或 `focusable=false` 来隐藏装饰；可访问性服务仍可能发现它。反过来，不能为了减少焦点把真正操作隐藏掉。若父项合并了子按钮的功能，必须通过 Action 提供等价入口。

### 一个任务一个焦点

一个卡片通常应是一个焦点：名称包含标题和必要说明；内部封面、标题、说明、箭头不再单独停留。若卡内有彼此独立的任务，例如“播放”和“更多”，可选择两个焦点，或让主行保留一个焦点并提供两个 Action。选择依据是操作频率、版本兼容、发现成本和能否清楚命名，不是单纯追求最少焦点。

### 标题、分组和阅读顺序

页面和弹窗标题使用 `android:accessibilityHeading="true"` 或 `ViewCompat.setAccessibilityHeading`。长页的分区标题也应这样标记，方便 TalkBack 的“按标题”导航。

默认阅读顺序来自 View 树和布局顺序。优先让 XML 的结构自然表达顺序：返回、标题或上下文、主操作、内容、次要操作。只有自然顺序无法表达真实工作流时，再使用 traversal 或 nextFocus 属性；显式顺序必须同时验证触摸探索、键盘 Tab、旋转和动态显示隐藏后的行为。

`labelFor` 用于视觉标签与输入框是不同 View 的场景。若 Material `TextInputLayout` 已正确提供输入框语义，不要再叠加冲突的 labelFor 或重复描述。

## 5. 事件、播报和焦点

### 三种不同机制

普通输入焦点由 `requestFocus()` 管理，主要影响键盘、输入法和 D-pad。

无障碍焦点由 `ACTION_ACCESSIBILITY_FOCUS` 管理，决定 TalkBack 正在朗读哪一个节点。

活动播报由 `announceForAccessibility()` 管理，只发送一条临时消息，不保证移动焦点，也不替代状态更新。

因此，打开搜索框通常需要输入焦点和显示键盘；如果还要让 TalkBack 立即进入输入框，需要在控件可见且完成布局后请求无障碍焦点。关闭面板时，不能只隐藏 View；还应把焦点返还给触发该面板的按钮。

### 何时播报

应播报：用户切换开关、选择项目、展开收起、开始或完成扫描、保存成功或失败、删除完成、进入编辑模式、加载失败、上下文模式改变。

不应播报：Adapter 每次 `onBindViewHolder`、图片加载、动画每一帧、滚动过程每个位置、仅因样式刷新而产生的重复状态。

动态内容区域在需要持续宣布时，可使用合适的 live region 或有节制的 `announceForAccessibility`。不要把长文本、频繁进度或多个并发任务全部塞入播报队列。

### 弹窗、面板和覆盖层

打开 Dialog、底部面板或全屏 Overlay 的标准流程是：显示界面；赋予标题或 `setAccessibilityPaneTitle`；使背景内容不可访问；在布局稳定后将无障碍焦点移动到标题或首个主控件。

关闭流程是：清除 pane title；恢复背景内容；把无障碍焦点交回触发控件；不要把焦点留在已移除的节点。

背景隔离通常使用 `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`，而不是仅把背景透明或不可点击。Overlay 出现时不能由于 WindowManager 的窗口事件意外抢走用户正在浏览的列表焦点；必要时先临时屏蔽新 Overlay 的无障碍树，待窗口事件完成后再开放。

## 6. 自定义无障碍操作：完整实现模型

自定义操作用于普通点击不足以覆盖的任务，例如列表项的试听、查看详情、下一首、编辑、删除；折叠面板的展开收起；悬浮窗的移动；播放器的音量和亮度；长按菜单的替代入口。

完整实现必须有四部分。

第一，定义稳定的 Action ID。项目级 ID 放在 `res/values/ids.xml`，避免在多个文件中生成碰撞或无法追踪的数字。标准 Action 如点击、长按、滚动优先复用系统常量。

第二，在节点初始化时注册动作。可使用 `ViewCompat.addAccessibilityAction` 处理简单闭包；复杂或状态依赖行为使用 `ViewCompat.setAccessibilityDelegate`，在 `onInitializeAccessibilityNodeInfo` 调用 `info.addAction(...)`。

第三，在 `performAccessibilityAction` 分发并执行。处理自定义 ID 后返回 `true`；不认识的动作必须交给 `super.performAccessibilityAction`。Action 执行的业务逻辑应复用普通点击、长按或命令方法，而不是维护一套内容不同的无障碍分支。

第四，执行后更新名称、状态、可用 Action 和反馈。比如“展开”执行后应变为“收起”，stateDescription 改为已展开，内容区域显示，并播报已展开；“试听”执行后动作应变为暂停或加载中。

示例：一个合并歌曲行的操作模型。

```kotlin
ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            R.id.accessibilityActionPreview,
            if (isPlaying) "暂停" else "试听"
        ))
        info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            R.id.accessibilityActionOpenMenu,
            "更多操作"
        ))
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        return when (action) {
            R.id.accessibilityActionPreview -> {
                togglePreview()
                true
            }
            R.id.accessibilityActionOpenMenu -> {
                showMenu()
                true
            }
            else -> super.performAccessibilityAction(host, action, args)
        }
    }
})
```

简单 Action 可以写成：

```kotlin
ViewCompat.addAccessibilityAction(view, "切换时间戳显示") { _, _ ->
    toggleTimestamp()
    view.announceForAccessibility("显示时间戳，已开启")
    true
}
```

但简单闭包不适合需要动态删除、替换、复杂状态同步或多版本语义的控件。此时使用 delegate 更可审计。

自定义 Action 不会自动让 TalkBack 的普通双击完成该动作。双击仍应保持节点的默认点击语义；Action 位于 TalkBack 操作菜单。若默认点击也需要改变，覆盖或补齐 `performClick()`，不能只依赖 `OnTouchListener`。这是自定义播放器 frame 和 Overlay 常见的遗漏点。

Android 版本较低时，操作菜单对自定义 Action 的可用性和发现性可能不足。若把右侧按钮隐藏后，用户没有可靠路径访问二级操作，就必须保留独立可聚焦按钮。无障碍“焦点合并”不能以牺牲旧系统可达性为代价。

## 7. RecyclerView 和可复用节点

RecyclerView 的风险不在于列表本身，而在于复用、局部状态和异步更新。

位置对用户有意义时，在节点中设置 `CollectionItemInfoCompat`，使服务能读出第几项和总数。分组、网格和标题项应根据真实结构提供对应 collection 信息，不应盲目把每一项都当同一种行。

选择模式下，行应是 checkable，设置 checked 和 stateDescription，并把默认点击 Action 改名为选择或取消选择。退出选择模式后必须清理状态；`onViewRecycled` 也要清理，避免旧 holder 朗读前一项的选中状态。

高亮、正在播放、下载进度、收藏状态等局部变化使用 payload 更新。`notifyDataSetChanged()` 可能让当前节点失效、焦点跳到列表开头或重建 delegate。只有数据集结构真正整体改变时才考虑全量刷新，并在后续恢复合理焦点。

Adapter 在 bind 时设置状态可以，但不要 bind 时主动播报。播报只发生在用户触发选择、播放、删除等操作后或重要异步结果到达后。

## 8. 原生与自定义控件

### 开关和单选

原生 Switch、CheckBox、RadioButton 优先保留。用普通容器模拟开关时，节点必须设置 Switch 类名、checkable、checked、名称和状态描述。选择组需要说明当前项；高版本状态和名称分开，低版本可以在必要时将状态并入名称作为降级，但要避免重复。

### 范围控件

SeekBar、音量、进度、速度、亮度和数值选择器必须有当前值、最小值、最大值和可操作方式。对自定义 SeekBar delegate 设置 `RangeInfoCompat`；对媒体进度给出时间、总时长和百分比；处理 `ACTION_SCROLL_FORWARD`、`ACTION_SCROLL_BACKWARD` 或适当的 set-progress 动作后播报新值。

不要把纯文字加减号当作滑块的唯一操作路径。若提供加减按钮，它们也需要明确标签、最小触摸面积和更新后的反馈。

### 文本、图片和图表

有信息价值的图片需要描述；纯装饰图片应隐藏。复杂图表、封面或二维码的描述要说明其用途，不要只说“图片”。如果图像承载复杂数据，应提供等价文本摘要或可浏览数据列表。

### 自定义 View

自定义绘制 View 若没有可访问子节点，至少把自身做成正确角色、名称、状态和 Action 的单一节点。若画布内存在多个独立对象，考虑 `AccessibilityNodeProvider` 建立虚拟节点；不要把多个独立任务硬塞进一个无法操作的描述。虚拟节点需要稳定 ID、bounds、父子关系、文本、可点击状态、Action 和焦点处理。

## 9. 输入、键盘、手势和替代操作

所有触摸操作都要有无障碍等价路径。长按、双指、拖动、边缘滑动、摇一摇、音量键、耳机键和手势控制不能成为唯一入口。

编辑框必须有清晰标签、输入类型、错误提示和提交结果。清空、搜索、取消和返回改变上下文时，标签应同步改变。键盘显示或隐藏后，确保焦点没有停在不可见节点。

`onKeyDown` 或音量键快捷方式必须尊重系统无障碍服务和用户设置。不要拦截会被屏幕阅读器使用的按键组合；提供关闭或冲突提示，并保留屏幕内可操作入口。

## 10. 媒体、TTS、WebView 和动态内容

媒体播放器应提供播放状态、当前媒体、位置、时长、速度、音量和可达控制。VideoView 通常不应独自承载语义，真正响应点击与控制的外层 View 才应是主节点。

TTS 与屏幕阅读器必须区分。若产品支持“跟随屏幕阅读器”，同一文本不能同时由应用 TTS 和 `announceForAccessibility` 朗读。音频焦点和 usage 需要验证读屏闪避、暂停和恢复，不可把 TalkBack 或系统 TTS 包误识别为业务媒体。

WebView 需要可聚焦，允许触摸模式焦点，并随系统字体缩放调节 text zoom。网页内容必须有正确 HTML 结构：标题、链接名称、表单 label、按钮文本和图片 alt；原生容器可访问不代表网页本身可访问。

加载、空状态、错误、下载、扫描、保存和删除都应具备可感知文本。不要只显示 Spinner、颜色、Snackbar 或 Toast；必要时给出主动播报，但保留屏幕上可操作的恢复路径。

## 11. 触摸目标、文本和视觉协同

可点击目标应有足够尺寸，常用最低目标为约 48dp；相邻目标需要避免误触。不要用极小图标作为唯一操作入口。

支持系统字体缩放、屏幕放大、横竖屏和窄宽屏。文本不能被固定高度裁剪；操作标签不能因字体放大而溢出或遮挡；图标不能成为唯一信息载体。

颜色、动画、震动和音效可以辅助，但不能作为唯一状态通道。选中、错误、加载、播放、成功和禁用必须同时有文本、状态语义或可访问反馈。

## 12. 测试与审计

静态审计检查 XML 与代码中的：名称、重要性、heading、stateDescription、delegate、custom action、焦点请求、Overlay 背景隔离、列表刷新方法、回收清理、点击与长按路径、键盘路径、字体缩放和错误状态。

TalkBack 手工测试至少覆盖：线性滑动；按标题导航；默认双击；操作菜单；状态切换；编辑和多选；滚动与列表更新；页面旋转或重建；进入和关闭对话框；进入和关闭 Overlay；搜索和键盘；大字体；媒体控制；网络失败与空状态。

还要用键盘/D-pad、开关控制或等效替代输入确认焦点顺序和操作可达性。测试不应只验证“能朗读”，还要验证朗读顺序、不会重复、不会抢焦点、不会丢焦点、操作后有结果。

自动化测试适合验证纯策略：标签拼接、状态映射、Action 政策、默认值、音频模式分流。Robolectric 或 UI 测试可验证节点属性和 Action；真实 TalkBack 行为、厂商系统差异、WebView 内容和 Overlay 窗口焦点仍需真机回归。

## 13. 常见失败模式

只加 contentDescription：用户能听到名字，却不知道状态、不能执行隐藏功能、操作后没有反馈。

把全部子 View 设为可访问：焦点碎片化，用户要听大量重复文字才能完成一个任务。

把状态塞进名称：高版本重复朗读，RecyclerView 复用残留，状态与内容混淆。

bind 时 announce：滚动或局部刷新产生播报风暴。

只写 OnTouchListener：TalkBack 双击到 `performClick` 后无反应。

只隐藏视觉背景：TalkBack 仍能滑到被覆盖的内容。

只在新窗口 `requestFocus`：键盘焦点改变而无障碍焦点没有进入新面板。

只给长按和手势提供功能：屏幕阅读器、键盘和开关用户没有入口。

只在新 Android 测试 Action 合并：旧系统的右侧按钮被隐藏后，功能不可达。

只做视觉高亮全量刷新：列表焦点失效。

只靠 Toast、颜色或震动反馈：非视觉用户无法稳定获得结果。

## 14. 可复制的实现配方

### 图标按钮

```xml
<ImageButton
    android:id="@+id/clearButton"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:contentDescription="清除搜索"
    android:background="?attr/selectableItemBackgroundBorderless" />
```

图标本身不需要额外焦点。不要把 `contentDescription` 写成“关闭图标”或“叉号”；它必须说明动作。若按钮在不同状态下执行不同任务，每次状态变化都更新名称，例如“播放”“暂停”“重新播放”。

### 合并卡片

```xml
<LinearLayout
    android:id="@+id/songRow"
    android:focusable="true"
    android:clickable="true"
    android:importantForAccessibility="yes">

    <ImageView
        android:id="@+id/cover"
        android:importantForAccessibility="no" />

    <TextView
        android:id="@+id/title"
        android:importantForAccessibility="no" />
</LinearLayout>
```

```kotlin
row.contentDescription = buildString {
    append(song.title)
    append("，")
    append(song.artist)
    if (song.isFavorite) append("，已收藏")
}
```

只在父项能完整表达歌曲信息、默认点击行为和附加操作时才这样合并。若视觉上存在独立的“更多”按钮，必须决定它保留独立焦点，还是转为父项的自定义 Action；两种策略不能同时让用户听到重复任务。

### 自定义开关

```kotlin
fun configureSwitchRow(row: View, enabled: Boolean, label: String) {
    row.contentDescription = label
    ViewCompat.setStateDescription(row, if (enabled) "已开启" else "已关闭")
    ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = "android.widget.Switch"
            info.isCheckable = true
            info.isChecked = enabled
        }
    })
}
```

`enabled` 必须来自当前状态源，而不是首次创建时的旧变量。若状态会变化，重新配置节点或让 delegate 从 View tag、ViewModel 或当前 state 读取。用户点击后先更新真实状态，再更新语义，最后播报“某功能，已开启/已关闭”。

### 受控焦点转移

```kotlin
fun focusForScreenReader(view: View) {
    view.post {
        view.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )
    }
}
```

只在任务边界使用，例如打开搜索、显示对话框、显示错误恢复区、打开底部面板。普通颜色变化、列表局部刷新和后台播放器状态变化不应移动无障碍焦点。

### Overlay 打开和关闭

```kotlin
fun openPanel(trigger: View, panel: View, background: ViewGroup) {
    panel.visibility = View.VISIBLE
    ViewCompat.setAccessibilityPaneTitle(panel, "筛选条件")
    for (index in 0 until background.childCount) {
        val child = background.getChildAt(index)
        if (child !== panel) {
            child.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
    panel.post { focusForScreenReader(panel) }
}

fun closePanel(trigger: View, panel: View, background: ViewGroup) {
    ViewCompat.setAccessibilityPaneTitle(panel, null)
    panel.visibility = View.GONE
    for (index in 0 until background.childCount) {
        background.getChildAt(index).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    focusForScreenReader(trigger)
}
```

真实工程应只恢复自己在打开时改变的节点，避免把其他模块已有的 `importantForAccessibility` 设置误改为 AUTO。面板内必须有关闭路径；返回键、点击遮罩、关闭按钮和 Action 的行为要一致。

### RecyclerView 选中态和回收

```kotlin
override fun onBindViewHolder(holder: Holder, position: Int) {
    val selected = selectedIds.contains(items[position].id)
    holder.itemView.contentDescription = items[position].title
    ViewCompat.setStateDescription(
        holder.itemView,
        if (selectionMode) if (selected) "已选中" else "未选中" else null
    )
}

override fun onViewRecycled(holder: Holder) {
    ViewCompat.setStateDescription(holder.itemView, null)
    super.onViewRecycled(holder)
}
```

用户点选时使用 `notifyItemChanged(position, PAYLOAD_SELECTION)`，而不是全量刷新。payload 分支必须同步视觉、checked、状态描述和默认点击 Action，但不能主动重复播报列表名称。

### 范围控件

```kotlin
ViewCompat.setAccessibilityDelegate(seekBar, object : AccessibilityDelegateCompat() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        val bar = host as SeekBar
        info.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
            AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT,
            0f,
            bar.max.toFloat(),
            bar.progress.toFloat()
        )
        info.contentDescription = "播放进度，${format(bar.progress)}"
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        val bar = host as SeekBar
        return when (action) {
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> {
                seekBy(5_000)
                bar.announceForAccessibility("快进 5 秒，当前 ${format(bar.progress)}")
                true
            }
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> {
                seekBy(-5_000)
                bar.announceForAccessibility("快退 5 秒，当前 ${format(bar.progress)}")
                true
            }
            else -> super.performAccessibilityAction(host, action, args)
        }
    }
})
```

先决定单位，再决定步长。歌曲时间使用秒或毫秒时，节点文字应转为人能听懂的时间；音量、速度、百分比等则应保留单位。自定义 Action 与触摸拖动必须改同一份状态。

### 自定义绘制的虚拟节点

当一个 Canvas View 画出多个独立对象，例如棋盘格、时间轴、波形标记或可移动控制点，单个 description 不够。此时实现 `AccessibilityNodeProvider` 并为每个逻辑对象建立虚拟节点。每个虚拟节点至少需要稳定 virtual ID、屏幕 bounds、文本或描述、可点击或可调节属性、Action、父子关系和 accessibility focus 处理。虚拟节点的顺序必须稳定，滚动或缩放后 bounds 与可见性必须同步。若无法完整维护这些语义，宁可提供一个单节点摘要加替代列表操作，也不要暴露错误的虚拟节点。

## 15. 可审计的测试矩阵

页面级：启动页、返回、标题、主操作、空状态、加载、成功、失败、权限拒绝、旋转和进程重建。

列表级：首项、中间项、末项、滚动、局部播放高亮、多选、全选、删除、插入、分页、回收后状态、Action 菜单和旧版本独立按钮。

输入级：获取焦点、软键盘显示、搜索、清空、提交、错误、返回关闭输入、输入法 action、外接键盘、放大字体。

窗口级：Dialog、PopupWindow、底部面板、全屏遮罩、WindowManager Overlay、遮罩点击、返回键、关闭按钮、焦点进入、焦点返还、背景隔离和多窗口连续打开。

媒体级：播放、暂停、结束、错误、进度、速度、音量、音频焦点、耳机拔出、通知控制、读屏同时运行、TTS 失败和网络中断。

辅助技术级：TalkBack 线性浏览、按标题浏览、操作菜单、键盘/D-pad、开关控制、系统字体放大、显示大小放大、屏幕旋转和高对比或深色主题。

每个用例记录起始焦点、执行手势或按键、预期朗读、预期状态、预期下一焦点和失败后的可恢复路径。只记录“可用”没有审计价值。

## 16. 梦仔伴唱已核对映射

本规范是通用规范，不以任何单一应用为前提。梦仔伴唱中已核对的实例可用于理解，但不是规范唯一实现：`BaseActivity.kt` 的状态与播报拆分；`SongAdapter.kt`、`LocalMusicFragment.kt`、`PlaylistDetailActivity.kt` 的集合项和 Action；`LyricDetailActivity.kt` 的 pane title 与背景隔离；`SearchPlaybackBar.kt` 的新窗口不抢焦点；`FloatingBallView.kt` 的 Overlay Action 与 role；`MvPlayActivity.kt` 的 RangeInfo 与滚动 Action；`LyricPlayer.kt` 的读屏模式和 TTS 保护；`OnboardingActivity.kt` 的 screen-reader focusable；`ids.xml` 的项目 Action ID 集中定义。

对梦仔伴唱进行改动时，同时阅读现有项目审计报告 `docs/reports/2026-07-19-accessibility-design-audit.md` 和本通用规范。前者说明既有行为与历史原因，后者约束新的工程实现。