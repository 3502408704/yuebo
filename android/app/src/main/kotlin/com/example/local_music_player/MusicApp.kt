package com.example.local_music_player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.CookieManager
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import androidx.media3.ui.compose.PlayerSurface
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 页面栈：Library 承载三个主标签；其余为独立子页。 */
internal enum class Screen { Library, OnlineCollectionDetail, Album, VideoPlayer, NowPlaying, Lyrics, Devices, Playlists }

internal fun screenAfterNowPlayingReturn(origin: Screen, hasOnlineDetail: Boolean): Screen =
    if (origin == Screen.OnlineCollectionDetail && !hasOnlineDetail) Screen.Library else origin

/** 主导航三标签：媒体库 / 在线 / 我的。 */
internal enum class MainTab(val label: String, val icon: ImageVector) {
    Library("媒体库", Icons.Filled.LibraryMusic),
    Online("在线", Icons.Filled.Public),
    Mine("我的", Icons.Filled.Person),
}

private enum class NowPlayingMorePanel { Root, SleepTimer, Speed, QueueMode, AbLoop, LyricTts, SkipIntroOutro }

/** 我的页子区：首页 / 音源管理 / 播放列表 / 下载管理 / 设置。 */
private enum class MineSection(val label: String) {
    Home("我的"),
    Sources("音源管理"),
    Playlists("歌单"),
    History("历史"),
    Queue("播放队列"),
    Downloads("下载管理"),
    Settings("设置"),
}

/** 媒体库只负责本地曲库与网盘浏览；歌单和历史归入「我的」。 */
internal enum class LibrarySection(val label: String) { Media("媒体库") }

/** 媒体库子页的浏览方式：本地媒体 / 百度网盘 / 夸克网盘（收藏/历史不入此下拉）。 */
internal enum class LibraryBrowseSource(val label: String) { Local("本地媒体"), BaiduPan("百度网盘"), QuarkPan("夸克网盘") }

/** 全屏播放/次级播放控制页不叠加迷你播放器；其余任何页面只要有当前播放会话都显示。 */
internal fun isPlayerOverlayScreen(screen: Screen): Boolean =
    screen == Screen.NowPlaying ||
        screen == Screen.Lyrics ||
        screen == Screen.Devices ||
        screen == Screen.Playlists ||
        screen == Screen.VideoPlayer

/** 迷你播放器显示条件：有当前会话（音频或视频）且未手动关闭，且当前不在全屏播放控制页上。 */
internal fun shouldShowMiniPlayer(screen: Screen, hasActiveSession: Boolean, dismissed: Boolean = false): Boolean =
    hasActiveSession && !dismissed && !isPlayerOverlayScreen(screen)

/** 设置子页标识（子页状态提升到外层，供页面 key 与窗口标题使用——子页各自是独立窗口）。 */
private const val SETTINGS_SUBPAGE_SILENCE = "silence"

/** 当前窗口/屏幕的 pane 标题：Compose 通过 paneTitle 语义让系统在窗口切换时播报（等同 setAccessibilityPaneTitle）。 */
private fun screenPaneTitle(
    screen: Screen,
    selectedTab: MainTab,
    mineSection: MineSection,
    librarySection: LibrarySection,
    onlineSearchKind: OnlineSearchKind,
    onlineCollectionName: String?,
    settingsSubpage: String? = null,
): String = when (screen) {
    Screen.Library -> when (selectedTab) {
        MainTab.Library -> when (librarySection) {
            LibrarySection.Media -> "媒体库"
        }
        MainTab.Online -> "在线 · ${onlineSearchKind.label}"
        MainTab.Mine -> when (mineSection) {
            MineSection.Home -> "我的"
            MineSection.Sources -> "音源管理"
                MineSection.Playlists -> "歌单"
                MineSection.History -> "历史"
                MineSection.Queue -> "播放队列"
                MineSection.Downloads -> "下载管理"
                // 设置的子页是独立窗口：标题随子页变（否则读屏听到的还是「设置」，无法定位页面）。
                MineSection.Settings -> when (settingsSubpage) {
                SETTINGS_SUBPAGE_SILENCE -> "跳过静音设置"
                else -> "设置"
            }
        }
    }
    Screen.OnlineCollectionDetail -> onlineCollectionName ?: "合集详情"
    Screen.Album -> "专辑详情"
    Screen.VideoPlayer -> "视频播放"
    Screen.NowPlaying -> "正在播放"
    Screen.Lyrics -> "歌词"
    Screen.Devices -> "播放设备"
    Screen.Playlists -> "播放列表"
}

/**
 * 页面身份：Screen 编码成**字符串**（`SaveableStateHolder`/`AnimatedContent`
 * 都要求 key 能进 Bundle，data class 会直接抛 IllegalArgumentException）。
 */
internal fun pageKeyOf(screen: Screen): String = screen.name

/**
 * 主标签并入页面 key（仅 `Screen.Library` 使用）：`\u0001` 后缀让媒体库/在线/我的
 * 成为三个不同的 AnimatedContent key——互切有页面过渡，且各自保存滚动状态。
 * [screenOfPageKey] 解析时会剥掉该后缀，页面分派不受影响。
 */
internal fun libraryTabKey(base: String, tab: MainTab, detail: String = ""): String =
    "${base}\u0001${tab.name}\u0002$detail"

/** 页面 key 串 → 页面类型（取 `Screen` 名部分；层级与主标签后缀只是让 key **不同**，不影响分派）。 */
internal fun screenOfPageKey(pageKey: String): Screen {
    val base = pageKey.substringBefore('\u0001')
    return Screen.entries.firstOrNull { base == it.name } ?: Screen.Library
}

/** 迷你播放器悬浮在列表底部时，列表需预留的滚动空间，避免最后一行被遮挡。 */
private val MiniPlayerBottomInset = 72.dp

/** 音量档位：按系统音量层级分为 15 档（0~15，0 为静音），本机与投送（DLNA/Chromecast）共用。 */
private const val VOLUME_MAX_LEVEL = 15

@Composable
fun MusicApp(viewModel: NativeMusicViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accessibilityView = LocalView.current
    val storagePermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null
    val bluetoothPermissionGranted = bluetoothPermission == null ||
        ContextCompat.checkSelfPermission(context, bluetoothPermission) == PackageManager.PERMISSION_GRANTED
    val allFilesAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    val storagePermissionGranted = storagePermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val libraryAccessGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        allFilesAccessGranted || storagePermissionGranted
    } else {
        storagePermissionGranted
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.refresh(result.values.all { it })
    }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    }
    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesLauncher.launch(android.content.Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ))
        }
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshAudioOutputs()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.completeDelete(result.resultCode == Activity.RESULT_OK) }
    var artworkTargetAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var artworkTargetIsFront by rememberSaveable { mutableStateOf(true) }
    val albumArtworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val albumId = artworkTargetAlbumId
        if (uri != null && albumId != null) {
            viewModel.updateAlbumArtwork(albumId, artworkTargetIsFront, uri)
        }
        artworkTargetAlbumId = null
    }
    val fileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFiles(uris) }
    val folderImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::importFolder) }
    val videoSubtitleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::loadVideoSubtitle) }
    var screen by rememberSaveable { mutableStateOf(Screen.Library) }
    var nowPlayingOrigin by rememberSaveable { mutableStateOf(Screen.Library) }
    var videoPlayerOrigin by rememberSaveable { mutableStateOf(Screen.Library) }
    // 设备页的返回去向：从播放页进入设备页时返回播放页，否则回媒体库。
    var devicesOrigin by rememberSaveable { mutableStateOf(Screen.Library) }
    // 媒体库网盘浏览的登录浮层去向（null=不显示登录页）。
    var cloudAuthDisk by rememberSaveable { mutableStateOf<CloudDisk?>(null) }
    // 用户主动退出视频全屏页后为 true：即使 currentVideo 变化（如迷你条切下一集）也不再被强制拉回全屏页。
    var videoPlayerBackgrounded by rememberSaveable { mutableStateOf(false) }
    var selectedAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var albumRestoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var editAlbumOnOpenId by rememberSaveable { mutableStateOf<String?>(null) }
    var fromHistoryAlbum by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Library) }
    // 媒体库子页的浏览方式：本地媒体 / 百度网盘 / 夸克网盘（收藏/历史不入此下拉）。
    var libraryBrowseSource by rememberSaveable { mutableStateOf(LibraryBrowseSource.Local) }
    var librarySection by rememberSaveable { mutableStateOf(LibrarySection.Media) }
    var activeCloudDisk by rememberSaveable { mutableStateOf<CloudDisk?>(null) }
    var folderPath by rememberSaveable { mutableStateOf("") }
    var mineSection by rememberSaveable { mutableStateOf(MineSection.Home) }
    // 设置子页提升到这一层，页面 key 与窗口标题才能跟随。
    var settingsSubpage by rememberSaveable { mutableStateOf<String?>(null) }
    // 我的子页返回后要恢复焦点的入口：同为外层状态（各 section 现在是独立页面 key）。
    var mineReturnFocus by rememberSaveable { mutableStateOf<MineSection?>(null) }
    // 在线页：榜单目录页。
    var onlineTopListsOpened by rememberSaveable { mutableStateOf(false) }
    val albumListState = rememberLazyGridState()
    val libraryListState = rememberLazyGridState()
    val videoListState = rememberLazyGridState()
    // 每个 Screen 各自保存可保存状态（列表滚动位置等）：AnimatedContent 会销毁离开的页面，
    // 没有它时「播放后返回」总是回到列表顶部。按 Screen 分桶，返回同一页即恢复上次位置。
    val screenStateHolder = rememberSaveableStateHolder()
    // 切换浏览模式或进出子文件夹时回到列表顶部；播放视频/歌曲返回时保持原滚动位置。
    LaunchedEffect(state.libraryBrowse, folderPath) {
        // 仅当主网格（歌曲/艺术家/文件夹）正在显示时复位，避免对未挂载的列表调用。
        if (state.libraryBrowse != LibraryBrowse.Albums && state.libraryBrowse != LibraryBrowse.Videos) {
            libraryListState.scrollToItem(0)
        }
    }
    LaunchedEffect(state.libraryBrowse) {
        if (state.libraryBrowse == LibraryBrowse.Videos) {
            videoListState.scrollToItem(0)
        }
    }
    // 外部打开视频（如 QQ/微信“用其他应用打开”）直接进入全屏视频播放页；应用内点击已由回调切屏，此处理为幂等。
    // 用户主动退出过视频页（videoPlayerBackgrounded）后不再强制弹回，迷你条切集等后台操作保持停留在当前页。
    LaunchedEffect(state.currentVideo?.id) {
        if (state.currentVideo != null && screen != Screen.VideoPlayer && !videoPlayerBackgrounded) {
            videoPlayerOrigin = screen
            screen = Screen.VideoPlayer
        }
    }
    var importDialogOpen by rememberSaveable { mutableStateOf(false) }
    // 用户协议：首启门页要求同意后才能使用应用；同意版本持久化，正文改版后重新征求。
    val appConsents = remember { context.getSharedPreferences("app_consents", Context.MODE_PRIVATE) }
    var agreementAcceptedVersion by rememberSaveable {
        mutableStateOf(appConsents.getInt("user_agreement_accepted_version", 0))
    }
    // 原生权限引导只在冷启动后首次进入曲库时各执行一次，拒绝不纠缠（曲库页保留手动入口）。
    var mediaPermissionPrompted by rememberSaveable { mutableStateOf(false) }
    var allFilesPrompted by rememberSaveable { mutableStateOf(false) }
    var announcedTarget by remember { mutableStateOf<String?>(null) }
    var importProgressDismissed by remember { mutableStateOf(false) }
    val libraryAlbums = remember(
        state.tracks,
        state.importedAlbums,
        state.trackMetadataOverrides,
        state.hiddenAlbumKeys,
    ) {
        buildLibraryAlbums(
            libraryTracks = state.tracks,
            importedAlbums = state.importedAlbums,
            overrides = state.trackMetadataOverrides,
            hiddenKeys = state.hiddenAlbumKeys,
        )
    }
    fun returnToLibrary() {
        if (screen == Screen.Album) albumRestoreId = selectedAlbumId
        screen = Screen.Library
    }

    /** 从播放页返回：回到进入播放页之前的页面，否则回列表。 */
    fun returnFromNowPlaying() {
        val origin = nowPlayingOrigin
        nowPlayingOrigin = Screen.Library
        screen = screenAfterNowPlayingReturn(
            origin = origin,
            hasOnlineDetail = state.onlineCollectionTracks.isNotEmpty() &&
                (state.onlineCollectionDetail != null || state.onlineArtistDetail != null),
        )
    }
    fun returnFromVideoPlayer() {
        viewModel.pauseVideoForBackground()
        videoPlayerBackgrounded = true
        val origin = videoPlayerOrigin
        videoPlayerOrigin = Screen.Library
        screen = when (origin) {
            Screen.NowPlaying, Screen.Lyrics, Screen.Playlists, Screen.VideoPlayer -> Screen.Library
            else -> origin
        }
    }

    /**
     * 音频播放统一入口：任意页面开始播放音频后直接进入全屏播放器（与视频行为对齐）。
     *
     * [userInitiated] 表示用户主动打开（点迷你播放器等）——不受「播放时进入播放页」开关限制；
     * 起播自动跳转在开关关闭时留在当前页面，播放控制走迷你播放器。
     */
    fun openNowPlayingForAudio(userInitiated: Boolean = false) {
        if (!userInitiated && !state.autoOpenPlayingPage) return
        videoPlayerBackgrounded = false
        nowPlayingOrigin = screen
        screen = Screen.NowPlaying
    }

    /** 打开视频全屏播放页（迷你条视频态等使用），并允许外部拉起幂等逻辑再次接管。 */
    fun openVideoPlayerNow() {
        videoPlayerBackgrounded = false
        videoPlayerOrigin = screen
        screen = Screen.VideoPlayer
    }
    fun goBack() {
        when {
            screen == Screen.Lyrics -> screen = Screen.NowPlaying
            screen == Screen.Playlists -> screen = Screen.NowPlaying
            screen == Screen.VideoPlayer -> returnFromVideoPlayer()
            screen == Screen.NowPlaying -> returnFromNowPlaying()
            screen == Screen.Devices -> screen = Screen.Library
            screen == Screen.OnlineCollectionDetail -> {
                viewModel.closeOnlineCollection()
                viewModel.closeOnlineArtist()
                screen = Screen.Library
            }
            screen != Screen.Library -> returnToLibrary()
            librarySection != LibrarySection.Media -> librarySection = LibrarySection.Media
            libraryBrowseSource != LibraryBrowseSource.Local -> libraryBrowseSource = LibraryBrowseSource.Local
            selectedTab == MainTab.Library && folderPath.isNotBlank() -> {
                folderPath = folderPath.substringBeforeLast('/', "")
            }
        }
    }
    // 在线页公共回调：搜索页/榜单页/合集详情页共用。
    fun requestDownloadNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    /** 播放插件搜索/钻取结果：统一走音频内核并进入正在播放页。 */
    fun playStreamTracks(tracks: List<StreamTrack>, index: Int) {
        viewModel.playStreamTracks(tracks, index)
        openNowPlayingForAudio()
    }
    val downloadStreamTrack: (StreamTrack) -> Unit = { track ->
        viewModel.requestDownloadOnline(track)
        requestDownloadNotifications()
    }
    // 在线合集（专辑/歌单/榜单）钻取：整张点按进详情页（内部整张播放/下载）。
    val openOnlineCollection: (OnlineCollection) -> Unit = { collection ->
        viewModel.openOnlineCollection(collection)
        screen = Screen.OnlineCollectionDetail
    }
    // 历史专辑续播：视频专辑重开视频播放页；在线合集恢复不打开详情（数据在后台重拉）；
    // 本地/其它回本地专辑页。
    val openHistoryAlbum: (AlbumPlaybackHistoryEntry) -> Unit = { entry ->
        fromHistoryAlbum = true
        viewModel.playAlbumHistory(entry)
        when {
            referenceIsVideo(entry.trackReference) -> openVideoPlayerNow()
            else -> openNowPlayingForAudio()
        }
    }
    // 历史单曲续播：音频进音频正在播放页，视频进视频播放页。
    val playHistorySingle: (SinglePlaybackHistoryEntry, List<SinglePlaybackHistoryEntry>) -> Unit = { entry, siblings ->
        viewModel.playSingleHistory(entry, siblings)
        if (referenceIsVideo(entry.reference)) {
            openVideoPlayerNow()
        } else {
            openNowPlayingForAudio()
        }
    }

    /** 收藏项播放统一入口：整张合集/专辑收藏项被打开时跳到对应页面；
     *  普通单曲收藏项仍按原行为起播并进入正在播放页。 */
    fun playFavorite(reference: MediaReference, folderId: String?) {
        val isAlbum = onlineCollectionForFavorite(reference) != null || localAlbumKeyForFavorite(reference) != null
        viewModel.playFavoriteItem(
            reference = reference,
            folderId = folderId,
            onOpenOnlineCollection = { collection ->
                fromHistoryAlbum = false
                openOnlineCollection(collection)
            },
            onOpenLocalAlbum = { albumKey ->
                selectedAlbumId = albumKey
                screen = Screen.Album
            },
        )
        if (!isAlbum) openNowPlayingForAudio()
    }

    LaunchedEffect(libraryAccessGranted) {
        viewModel.refresh(libraryAccessGranted)
    }
    // 原生权限引导：同意协议后首次进入曲库——先弹媒体读取权限，授予后（Android 11+）跳一次「所有文件访问」设置页。
    LaunchedEffect(
        agreementAcceptedVersion, screen, storagePermissionGranted, allFilesAccessGranted,
    ) {
        if (agreementAcceptedVersion < USER_AGREEMENT_VERSION || screen != Screen.Library) return@LaunchedEffect
        when {
            !storagePermissionGranted -> if (!mediaPermissionPrompted) {
                mediaPermissionPrompted = true
                permissionLauncher.launch(storagePermissions)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesAccessGranted -> if (!allFilesPrompted) {
                allFilesPrompted = true
                accessibilityView.announceForAccessibility("正在打开系统所有文件访问设置")
                requestAllFilesAccess()
            }
        }
    }
    LaunchedEffect(state.importing) {
        if (state.importing) importProgressDismissed = false
    }
    LaunchedEffect(state.deleteRequest) {
        state.deleteRequest?.let { sender ->
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }
    LaunchedEffect(state.lyricsScreenOpen) {
        if (state.lyricsScreenOpen) {
            screen = Screen.Lyrics
            viewModel.clearLyricsScreenOpen()
        }
    }
    LaunchedEffect(state.currentTrack?.id) {
        state.currentTrack?.let { track ->
            accessibilityView.announceForAccessibility("正在播放 ${track.title}")
        }
    }
    LaunchedEffect(state.targetName, state.isCasting) {
        val target = "${state.isCasting}:${state.targetName}"
        if (announcedTarget != null && announcedTarget != target) {
            val message = if (state.isCasting) "已连接 ${state.targetName}" else "已切换至 ${state.targetName}"
            accessibilityView.announceForAccessibility(message)
        }
        announcedTarget = target
    }
    LaunchedEffect(state.logExportAnnouncement) {
        state.logExportAnnouncement?.let {
            accessibilityView.announceForAccessibility(it)
            viewModel.clearLogExportAnnouncement()
        }
    }
    LaunchedEffect(state.logClearAnnouncement) {
        state.logClearAnnouncement?.let {
            accessibilityView.announceForAccessibility(it)
            viewModel.clearLogClearAnnouncement()
        }
    }
    LaunchedEffect(state.downloadAnnouncement) {
        state.downloadAnnouncement?.let {
            playDownloadCompleteTone(context)
            viewModel.clearDownloadAnnouncement()
        }
    }
    LaunchedEffect(state.downloadAddAnnouncement) {
        state.downloadAddAnnouncement?.let {
            accessibilityView.announceForAccessibility(it)
            viewModel.clearDownloadAddAnnouncement()
        }
    }
    LaunchedEffect(state.importResult) {
        state.importResult?.let {
            accessibilityView.announceForAccessibility(
                "导入完成：成功 ${it.imported} 首，跳过 ${it.skipped} 个，失败 ${it.failed} 个。",
            )
        }
    }
    BackHandler(
        enabled = screen != Screen.Library ||
            (selectedTab == MainTab.Library && folderPath.isNotBlank()) ||
            (selectedTab == MainTab.Library && librarySection != LibrarySection.Media) ||
            (selectedTab == MainTab.Library && libraryBrowseSource != LibraryBrowseSource.Local),
    ) {
        goBack()
    }
    val darkTheme = when (state.themeMode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
    DisposableEffect(context, darkTheme) {
        val window = (context as? Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
        onDispose { }
    }
    // 深浅色统一使用 GlassTheme 设计令牌（暖橙强调 + 玻璃质感），避免浅色模式退化为通用 Material 白导致与深色主题风格割裂。
    // 静态配色可用 remember(darkTheme) 缓存；如需跟随壁纸动态取色，再切换为 dynamicLight/DarkColorScheme。
    val colors = remember(darkTheme) {
        if (darkTheme) GlassTheme.colorScheme() else GlassTheme.lightColorScheme()
    }
    // 迷你播放器统一承载当前会话：视频优先（视频退出全屏后在列表页仍可回到视频页），否则显示音频轨道。
    // 全屏播放控制页（NowPlaying/Lyrics/Devices/Playlists/VideoPlayer）自身不叠加迷你条。
    val miniVideo = state.currentVideo
    val miniAudio = state.currentTrack
    val miniIsVideo = miniVideo != null
    val miniPlaybackTrack: NativeTrack? = if (miniIsVideo) miniVideo else miniAudio
    val miniPlaying = if (miniIsVideo) state.videoPlaying else state.playing
    val miniPlayerVisible = shouldShowMiniPlayer(
        screen = screen,
        hasActiveSession = miniPlaybackTrack != null,
        dismissed = state.miniPlayerDismissed,
    )
    val miniPlayerInset = if (miniPlayerVisible) MiniPlayerBottomInset else 0.dp
    val onMiniOpen = if (miniIsVideo) {
        {
            videoPlayerBackgrounded = false
            videoPlayerOrigin = screen
            screen = Screen.VideoPlayer
        }
    } else {
        { openNowPlayingForAudio(userInitiated = true) }
    }
    val onMiniToggle = if (miniIsVideo) viewModel::videoToggle else viewModel::togglePlayback
    val onMiniPrevious = if (miniIsVideo) viewModel::skipPreviousVideo else viewModel::skipPrevious
    val onMiniNext = if (miniIsVideo) viewModel::skipNextVideo else viewModel::skipNext
    val miniCanSkipPrevious = skipStepAvailable(
        step = -1,
        isVideo = miniIsVideo,
        audioIndex = state.queueIndex,
        audioSize = state.queue.size,
        videoIndex = state.videoIndex,
        videoSize = state.videoQueue.size,
        audioMode = state.queueMode,
    )
    val miniCanSkipNext = skipStepAvailable(
        step = 1,
        isVideo = miniIsVideo,
        audioIndex = state.queueIndex,
        audioSize = state.queue.size,
        videoIndex = state.videoIndex,
        videoSize = state.videoQueue.size,
        audioMode = state.queueMode,
    )
    val miniPreviousLabel = skipStepLabel(-1, miniIsVideo, isLive = false)
    val miniNextLabel = skipStepLabel(1, miniIsVideo, isLive = false)

    val motionEnabled = rememberMotionEnabled()
    // 页面身份：每个 Screen 都算一页（窗口切换/读屏播报/滚动位置各自独立）。
    val pageKey = pageKeyOf(screen)
    // 主页把主标签并入 key：媒体库/在线/我的互切也有页面过渡（读屏 paneTitle 不变，照常播报）。
    // 细节段（库收藏历史段/我的子页/设置子页）一并并入：这些「同屏换页」
    // 也必须是真正的页面切换，否则读屏收不到窗口变化、标题不播报。
    val animatedPageKey = if (screen == Screen.Library) {
        libraryTabKey(
            pageKey,
            selectedTab,
            detail = listOf(
                librarySection.name,
                libraryBrowseSource.name,
                if (selectedTab == MainTab.Library) activeCloudDisk?.name ?: "null" else "null",
                mineSection.name,
                settingsSubpage,
            ).joinToString("|"),
        )
    } else {
        pageKey
    }
    MaterialTheme(colorScheme = colors, typography = GlassTheme.typography(), shapes = GlassTheme.shapes()) {
        androidx.compose.material3.Scaffold(
            // 页面自身统一消费 safeDrawing，避免 Scaffold 默认 systemBars 与页面 inset 叠加。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (screen == Screen.Library) {
                    // 主页（媒体库/在线/我的）：全局底栏；媒体库页内部仅提供本地/网盘浏览方式下拉。
                    // 播放器浮层页不显示底栏。
                    MainNavigation(selectedTab) { tab ->
                        selectedTab = tab
                        if (tab == MainTab.Mine) mineSection = MineSection.Home
                    }
                }
            },
        ) { contentPadding ->
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).semantics {
                            paneTitle = screenPaneTitle(
                                screen,
                                selectedTab,
                                mineSection,
                                librarySection,
                                state.onlineSearchKind,
                                state.onlineCollectionDetail?.name,
                                settingsSubpage,
                            )
                        },
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = animatedPageKey,
                            transitionSpec = {                                if (motionEnabled) {
                                    // 全局页面过渡：新页轻微上滑淡入、旧页淡出（系统关动画时立即切换）。
                                    (
                                        fadeIn(tween(GlassTheme.MotionMedium, easing = GlassTheme.EasingEmphasized)) +
                                            slideInVertically(tween(GlassTheme.MotionMedium, easing = GlassTheme.EasingEmphasized)) { it / 24 }
                                        ) togetherWith (
                                        fadeOut(tween(GlassTheme.MotionShort)) +
                                            slideOutVertically(tween(GlassTheme.MotionShort)) { -it / 24 }
                                        )
                                } else {
                                    EnterTransition.None togetherWith ExitTransition.None
                                }
                            },
                            label = "screenTransition",
                        ) { pageKeyState ->
                            // pageKey 是「Screen[\u0001标签…]」编码串；页面内容仍按 Screen 分派。
                            val page = screenOfPageKey(pageKeyState)
                            screenStateHolder.SaveableStateProvider(pageKeyState) {
                            when (page) {
                Screen.Library -> when (selectedTab) {
                    // ├─ 媒体库标签：媒体库（本地/网盘下拉）
                    MainTab.Library -> when (librarySection) {
                    LibrarySection.Media -> Column(Modifier.fillMaxSize()) {
                        // 浏览方式分段：本地媒体 / 百度网盘 / 夸克网盘（收藏/历史不入此下拉）。
                        LibraryBrowseSourceMenu(
                            selected = libraryBrowseSource,
                            onSelect = { source ->
                                libraryBrowseSource = source
                                activeCloudDisk = when (source) {
                                    LibraryBrowseSource.BaiduPan -> CloudDisk.BAIDU
                                    LibraryBrowseSource.QuarkPan -> CloudDisk.QUARK
                                    LibraryBrowseSource.Local -> null
                                }
                            },
                        )
                        when (libraryBrowseSource) {
                            LibraryBrowseSource.Local -> LibraryScreen(
                                state = state,
                                gridDensity = state.gridDensity,
                                miniPlayerInset = miniPlayerInset,
                                albums = libraryAlbums,
                                folderPath = folderPath,
                                albumListState = albumListState,
                                libraryListState = libraryListState,
                                videoListState = videoListState,
                                restoreAlbumId = albumRestoreId,
                                onFolderPathChange = { folderPath = it },
                                onRequestPermission = { permissionLauncher.launch(storagePermissions) },
                                onRequestAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ::requestAllFilesAccess else null,
                                onPlay = { tracks, index ->
                                    viewModel.playFromQueue(tracks, index)
                                    openNowPlayingForAudio()
                                },
                                onPlayVideo = { tracks, index ->
                                    viewModel.playVideos(tracks, index)
                                    openVideoPlayerNow()
                                },
                                onViewLyrics = viewModel::openLyricsFromSong,
                                onShare = viewModel::share,
                                onCopy = viewModel::copy,
                                onCut = viewModel::cut,
                                onDelete = viewModel::requestDelete,
                                onPaste = viewModel::paste,
                                onCopyFolder = viewModel::copyFolder,
                                onCutFolder = viewModel::cutFolder,
                                onDeleteFolder = viewModel::deleteFolder,
                                onSort = viewModel::setLibrarySort,
                                onBrowse = viewModel::setLibraryBrowse,
                                onSearch = viewModel::setSearchQuery,
                                onImport = { importDialogOpen = true },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onToggleAlbumFavorite = viewModel::toggleLibraryAlbumFavorite,
                                isAlbumFavorite = viewModel::isLibraryAlbumFavorite,
                                onOpenAlbum = { album ->
                                    selectedAlbumId = album.key
                                    screen = Screen.Album
                                },
                                onEditAlbum = { album ->
                                    selectedAlbumId = album.key
                                    editAlbumOnOpenId = album.key
                                    screen = Screen.Album
                                },
                                onRemoveAlbum = viewModel::removeLibraryAlbum,
                                onAlbumRestoreHandled = { albumRestoreId = null },
                                onUpdateTrackMetadata = viewModel::updateTrackMetadata,
                            )
                            LibraryBrowseSource.BaiduPan, LibraryBrowseSource.QuarkPan -> {
                                // 网盘浏览：未登录点「去登录」时显示授权浮层（授权码模式优先），
                                // 登录成功（账号集合出现新 id）后自动关闭回到目录浏览。
                                var cloudAuthWeb by rememberSaveable { mutableStateOf(false) }
                                val initialPanIds = remember { state.panAccounts.map { it.id }.toSet() }
                                val initialQuarkIds = remember { state.quarkAccounts.map { it.id }.toSet() }
                                LaunchedEffect(state.panAccounts) {
                                    if (state.panAccounts.any { it.id !in initialPanIds }) {
                                        viewModel.panCancelQrLogin()
                                        cloudAuthDisk = null
                                        cloudAuthWeb = false
                                    }
                                }
                                LaunchedEffect(state.quarkAccounts) {
                                    if (state.quarkAccounts.any { it.id !in initialQuarkIds }) {
                                        viewModel.quarkCancelQrLogin()
                                        cloudAuthDisk = null
                                        cloudAuthWeb = false
                                    }
                                }
                                when {
                                    cloudAuthDisk == CloudDisk.BAIDU && cloudAuthWeb -> {
                                        val oauthUrl = viewModel.panAuthorizePageUrl()
                                        BaiduPanWebAuthScreen(
                                            url = oauthUrl
                                                ?: state.panDeviceCode?.let { baiduPanVerificationUrlWithCode(it) }.orEmpty(),
                                            onBack = {
                                                cloudAuthWeb = false
                                                cloudAuthDisk = null
                                            },
                                            redirectUri = viewModel.panOAuthRedirectUri,
                                            onRedirectCode = viewModel::panCompleteCodeLogin,
                                            errorText = state.panError,
                                        )
                                    }
                                    cloudAuthDisk == CloudDisk.BAIDU -> BaiduPanAuthorizeScreen(
                                        state = state,
                                        onStart = viewModel::panStartQrLogin,
                                        onCancel = {
                                            viewModel.panCancelQrLogin()
                                            cloudAuthDisk = null
                                        },
                                        onEnterWebAuth = {
                                            viewModel.panCancelQrLogin()
                                            cloudAuthWeb = true
                                        },
                                    )
                                    cloudAuthDisk == CloudDisk.QUARK && cloudAuthWeb -> QuarkPanWebAuthScreen(
                                        state = state,
                                        viewModel = viewModel,
                                        onBack = {
                                            cloudAuthWeb = false
                                            cloudAuthDisk = null
                                        },
                                    )
                                    cloudAuthDisk == CloudDisk.QUARK -> QuarkPanAuthorizeScreen(
                                        state = state,
                                        onStart = viewModel::quarkStartQrLogin,
                                        onCancel = {
                                            viewModel.quarkCancelQrLogin()
                                            cloudAuthDisk = null
                                        },
                                        onEnterWebAuth = {
                                            viewModel.quarkCancelQrLogin()
                                            cloudAuthWeb = true
                                        },
                                    )
                                    else -> CloudDriveScreen(
                                        state = state,
                                        gridDensity = state.gridDensity,
                                        miniPlayerInset = miniPlayerInset,
                                        activeCloudDisk = if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) CloudDisk.QUARK else CloudDisk.BAIDU,
                                        onOpenDisk = { },
                                        onBackToDiskList = { libraryBrowseSource = LibraryBrowseSource.Local },
                                        onOpenRoot = {
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkListDir("0")
                                            else viewModel.panListDir("/")
                                        },
                                        onGoUp = {
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkGoUp()
                                            else viewModel.panListDir(state.panPath.substringBeforeLast('/', "/").ifBlank { "/" })
                                        },
                                        onOpenDir = { file ->
                                            if (file.provider == CloudDisk.QUARK) viewModel.quarkOpenDir(file.toQuarkPanFile())
                                            else viewModel.panListDir(file.path)
                                        },
                                        onLoadMore = {
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) {
                                                if (state.quarkSearchActive) viewModel.quarkSearchLoadMore()
                                                else viewModel.quarkListDir("0", loadMore = true)
                                            } else {
                                                if (state.panSearchActive) viewModel.panSearchLoadMore()
                                                else viewModel.panListDir(state.panPath, loadMore = true)
                                            }
                                        },
                                        onSearch = { query ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkSearch(query)
                                            else viewModel.panSearch(query)
                                        },
                                        onExitSearch = {
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkExitSearch()
                                            else viewModel.panExitSearch()
                                        },
                                        onPlay = { files, index ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) {
                                                viewModel.playQuarkPanFiles(files.map { it.toQuarkPanFile() }, index)
                                            } else {
                                                viewModel.playBaiduPanFiles(files.map { it.toBaiduPanFile() }, index)
                                            }
                                            openNowPlayingForAudio()
                                        },
                                        onPlayVideo = { files, index ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) {
                                                viewModel.playQuarkPanVideos(files.map { it.toQuarkPanFile() }, index)
                                            } else {
                                                viewModel.playBaiduPanVideos(files.map { it.toBaiduPanFile() }, index)
                                            }
                                            openVideoPlayerNow()
                                        },
                                        onDownload = { file ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkStartDownload(file.toQuarkPanFile())
                                            else viewModel.panStartDownload(file.toBaiduPanFile())
                                            requestDownloadNotifications()
                                        },
                                        onDownloadFolder = { file ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkStartDownloadFolder(file.toQuarkPanFile())
                                            else viewModel.panStartDownloadFolder(file.toBaiduPanFile())
                                            requestDownloadNotifications()
                                        },
                                        onToggleFavorite = viewModel::toggleCloudFileFavorite,
                                        isCloudFileFavorite = viewModel::isCloudFileFavorite,
                                        onDelete = { file ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkDeleteFile(file.toQuarkPanFile())
                                            else viewModel.panDeleteFile(file.toBaiduPanFile())
                                        },
                                        onCopy = { file ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkCopyFile(file.toQuarkPanFile())
                                            else viewModel.panCopyFile(file.toBaiduPanFile())
                                        },
                                        onCut = { file ->
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkCutFile(file.toQuarkPanFile())
                                            else viewModel.panCutFile(file.toBaiduPanFile())
                                        },
                                        onPaste = {
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.quarkPaste()
                                            else viewModel.panPaste()
                                        },
                                        onAnnouncementHandled = {
                                            if (libraryBrowseSource == LibraryBrowseSource.QuarkPan) viewModel.clearQuarkAnnouncement()
                                            else viewModel.clearPanAnnouncement()
                                        },
                                        onGoLogin = { disk ->
                                            if (disk == CloudDisk.QUARK) {
                                                cloudAuthDisk = disk
                                                cloudAuthWeb = false
                                            } else {
                                                cloudAuthDisk = disk
                                                cloudAuthWeb = viewModel.panAuthorizePageUrl() != null
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    }
                    // ├─ 在线标签：插件音源搜索页（站点 chips + 歌曲/专辑/歌单/歌手）。
                    MainTab.Online -> OnlineSearchScreen(
                        state = state,
                        miniPlayerInset = miniPlayerInset,
                        onSearch = viewModel::searchOnline,
                        onKindChange = viewModel::setOnlineSearchKind,
                        onSelectSource = viewModel::selectOnlineSource,
                        onLoadTopLists = viewModel::loadOnlineTopLists,
                        onOpenCollection = openOnlineCollection,
                        onOpenArtist = { artist ->
                            viewModel.openOnlineArtist(artist)
                            screen = Screen.OnlineCollectionDetail
                        },
                        onPlay = { tracks, index ->
                            viewModel.playStreamTracks(tracks, index)
                            openNowPlayingForAudio()
                        },
                        onDownload = downloadStreamTrack,
                    )
                    // ├─ 我的标签：首页 / 音源管理 / 播放列表 / 下载管理 / 设置。
                    MainTab.Mine -> MineScreen(
                        state = state,
                        section = mineSection,
                        onSectionChange = { mineSection = it },
                        viewModel = viewModel,
                        settingsSubpage = settingsSubpage,
                        onSettingsSubpageChange = { settingsSubpage = it },
                        mineReturnFocus = mineReturnFocus,
                        onMineReturnFocusChange = { mineReturnFocus = it },
                        onPlayFavorite = ::playFavorite,
                        onOpenNowPlaying = ::openNowPlayingForAudio,
                        onOpenHistoryAlbum = openHistoryAlbum,
                        onPlayHistorySingle = playHistorySingle,
                    )
            }
            Screen.Album -> {
                val album = libraryAlbums.firstOrNull { it.key == selectedAlbumId }
                if (album == null) {
                    Column(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        AppBar(title = "专辑详情", onBack = ::returnToLibrary,
                            onDevices = { devicesOrigin = Screen.NowPlaying; screen = Screen.Devices })
                        Text("未找到该专辑", modifier = Modifier.padding(24.dp))
                    }
                } else {
                    AlbumDetailScreen(
                        album = album,
                        gridDensity = state.gridDensity,
                        miniPlayerInset = miniPlayerInset,
                        status = state.status,
                        onBack = ::returnToLibrary,
                        onUpdateAlbum = viewModel::updateAlbum,
                        onSelectArtwork = { front ->
                            album.importedAlbum?.let { imported ->
                                artworkTargetAlbumId = imported.id
                                artworkTargetIsFront = front
                                albumArtworkLauncher.launch(arrayOf("image/*"))
                            }
                        },
                        onPlay = { tracks, index ->
                            viewModel.playLibraryAlbumTracks(album.key, album.name, album.artist, tracks, index)
                            openNowPlayingForAudio()
                        },
                        onViewLyrics = viewModel::openLyricsFromSong,
                        onDelete = viewModel::requestDelete,
                        onUpdateTrackMetadata = viewModel::updateTrackMetadata,
                        editOnOpen = editAlbumOnOpenId == album.key,
                        onEditOnOpenHandled = { editAlbumOnOpenId = null },
                    )
                }
            }
            // 在线合集（专辑/歌单/榜单）详情：按详情/歌手两种形态渲染；返回时清理对应详情状态。
            Screen.OnlineCollectionDetail -> OnlineCollectionDetailScreen(
                state = state,
                miniPlayerInset = miniPlayerInset,
                onBack = {
                    if (state.onlineArtistDetail != null) viewModel.closeOnlineArtist()
                    if (state.onlineCollectionDetail != null) viewModel.closeOnlineCollection()
                    screen = Screen.Library
                },
                onLoadMore = viewModel::loadMoreOnlineCollectionPage,
                onPlayAll = { tracks ->
                    val collection = state.onlineCollectionDetail
                    if (collection != null) {
                        viewModel.playStreamCollectionTracks(collection, tracks, 0)
                    } else {
                        viewModel.playStreamTracks(tracks, 0)
                    }
                    openNowPlayingForAudio()
                },
                onPlayTrack = { tracks, index ->
                    val collection = state.onlineCollectionDetail
                    if (collection != null) {
                        viewModel.playStreamCollectionTracks(collection, tracks, index)
                    } else {
                        viewModel.playStreamTracks(tracks, index)
                    }
                    openNowPlayingForAudio()
                },
                onDownloadTrack = downloadStreamTrack,
                onDownloadAll = {
                    state.onlineCollectionDetail?.let(viewModel::requestDownloadOnlineCollection)
                },
            )
            Screen.VideoPlayer -> VideoPlayerScreen(
                state = state,
                onBack = ::returnFromVideoPlayer,
                onToggle = viewModel::videoToggle,
                onSeek = viewModel::videoSeekTo,
                onPrevious = viewModel::skipPreviousVideo,
                onNext = viewModel::skipNextVideo,
                onCastVolume = viewModel::setVideoVolume,
                onSelectEpisode = { index -> viewModel.playVideos(state.videoQueue, index) },
                onSpeed = viewModel::setVideoSpeed,
                onStartHoldSpeed = viewModel::startVideoHoldSpeed,
                onEndHoldSpeed = viewModel::endVideoHoldSpeed,
                onStartSleepTimer = viewModel::startVideoSleepTimer,
                onCancelSleepTimer = viewModel::cancelVideoSleepTimer,
                onEnterBackground = viewModel::enterVideoBackground,
                onResumeFromBackground = viewModel::resumeVideoFromBackground,
                onAudioOnly = viewModel::setVideoAudioOnly,
                onPickSubtitle = { videoSubtitleLauncher.launch(arrayOf("text/*", "application/x-subrip", "application/ttml+xml")) },
                onClearSubtitle = viewModel::clearVideoSubtitle,
                onToggleSubtitleTts = viewModel::toggleVideoSubtitleTts,
                onSetSubtitleVisible = viewModel::setVideoSubtitleVisible,
                onSelectSubtitleTrack = viewModel::selectVideoSubtitleTrack,
                onSelectSecondarySubtitleTrack = viewModel::selectVideoSecondarySubtitleTrack,
                onLoadAiSubtitle = viewModel::loadAiVideoSubtitle,
                onSpeakCurrentSubtitle = viewModel::speakCurrentVideoSubtitle,
                onPreviousSubtitle = viewModel::seekPreviousVideoSubtitle,
                onReplaySubtitle = viewModel::replayCurrentVideoSubtitle,
                onNextSubtitle = viewModel::seekNextVideoSubtitle,
                onSetSubtitleOffset = viewModel::setVideoSubtitleOffset,
                onSetSubtitleScale = viewModel::setVideoSubtitleScale,
                onSelectSubtitleCue = viewModel::selectVideoSubtitleCue,
                onScanCast = viewModel::scanDlnaDevices,
                onSelectDlna = viewModel::videoStartDlna,
                onReturnToLocal = viewModel::videoReturnToLocal,
            )
            Screen.NowPlaying -> NowPlayingScreen(
                state = state,
                onBack = ::returnFromNowPlaying,
                onToggle = viewModel::togglePlayback,
                onSeek = viewModel::seek,
                onPrevious = viewModel::skipPrevious,
                onNext = viewModel::skipNext,
                onVolume = viewModel::setVolume,
                onSpeed = viewModel::setSpeed,
                onQueueMode = viewModel::setQueueMode,
                onOpenDevices = {
                    devicesOrigin = Screen.NowPlaying
                    screen = Screen.Devices
                },
                onSaveSkipConfig = viewModel::saveSkipConfig,
                onGetSkipConfig = viewModel::skipConfigFor,
                onToggleSkipIntroOutro = viewModel::setSkipIntroOutroEnabled,
                onStartSleepTimer = viewModel::startSleepTimer,
                onStartTrackSleepTimer = viewModel::startTrackSleepTimer,
                onCancelSleepTimer = viewModel::cancelSleepTimer,
                onSetAbStart = viewModel::setAbStart,
                onSetAbEnd = viewModel::setAbEnd,
                onClearAbLoop = viewModel::clearAbLoop,
                onOpenLyrics = { screen = Screen.Lyrics },
                onOpenPlaylist = { screen = Screen.Playlists },
                onToggleLyricTts = viewModel::toggleLyricTts,
                onSetLyricTtsEngine = viewModel::setLyricTtsEngine,
                onSetLyricTtsOffset = viewModel::setLyricTtsOffset,
                onSetLyricTtsChannel = viewModel::setLyricTtsChannel,
            )
            Screen.Lyrics -> LyricsScreen(
                state = state,
                onBack = { screen = Screen.NowPlaying },
                onSeek = viewModel::seek,
                onReload = viewModel::requestLyricsForCurrentTrack,
            )
            Screen.Devices -> DeviceScreen(
                state = state,
                onBack = { screen = devicesOrigin },
                onScanDevices = viewModel::scanDlnaDevices,
                onSelectHistory = viewModel::startHistory,
                onRefreshAudioOutputs = viewModel::refreshAudioOutputs,
                onSelectAudioOutput = viewModel::selectAudioOutput,
                bluetoothPermissionGranted = bluetoothPermissionGranted,
                onRequestBluetoothPermission = { bluetoothPermission?.let(bluetoothPermissionLauncher::launch) },
                onSelectDlna = viewModel::startDlna,
                chromecastDevices = state.chromecastDevices,
                onSelectChromecast = viewModel::startChromecast,
                onCastVolumeChange = viewModel::setVolume,
            )
            Screen.Playlists -> PlaylistScreen(
                state = state,
                onPlayQueueItem = { viewModel.playFromQueue(state.queue, it) },
                onPlayHistoryTrack = { track -> viewModel.playFromQueue(state.tracks, state.tracks.indexOf(track)) },
                onMoveQueueItem = viewModel::moveQueueItem,
                onRemoveQueueItem = viewModel::removeQueueItem,
                onViewLyrics = { index -> viewModel.openLyricsFromSong(state.queue[index]) },
                onSaveQueueAsPlaylist = viewModel::saveQueueAsPlaylist,
                onPlayPlaylist = viewModel::playPlaylist,
                onDeletePlaylist = viewModel::deletePlaylist,
                onClearPlaybackHistory = viewModel::clearPlaybackHistory,
            )
                            }
                            }
                            }
                        val miniMotionEnabled = rememberMotionEnabled()
                        androidx.compose.animation.AnimatedVisibility(
                            visible = miniPlayerVisible && miniPlaybackTrack != null,
                            enter = if (miniMotionEnabled) {
                                slideInVertically(tween(GlassTheme.MotionMedium, easing = GlassTheme.EasingEmphasized)) { it } +
                                    fadeIn(tween(GlassTheme.MotionMedium, easing = GlassTheme.EasingEmphasized))
                            } else EnterTransition.None,
                            exit = if (miniMotionEnabled) {
                                slideOutVertically(tween(GlassTheme.MotionShort)) { it } + fadeOut(tween(GlassTheme.MotionShort))
                            } else ExitTransition.None,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            miniPlaybackTrack?.let { miniTrack ->
                                MiniPlayer(
                                    track = miniTrack,
                                    playing = miniPlaying,
                                    isVideo = miniIsVideo,
                                    onToggle = onMiniToggle,
                                    onPrevious = onMiniPrevious,
                                    onNext = onMiniNext,
                                    canSkipPrevious = miniCanSkipPrevious,
                                    canSkipNext = miniCanSkipNext,
                                    previousLabel = miniPreviousLabel,
                                    nextLabel = miniNextLabel,
                                    resolvingBackward = state.onlineResolving && !state.onlineResolvingForward,
                                    resolvingForward = state.onlineResolving && state.onlineResolvingForward,
                                    onDismiss = viewModel::dismissMiniPlayer,
                                    onOpenNowPlaying = onMiniOpen,
                                )
                            }
                        }
                    }
                }
                if (state.importing && !importProgressDismissed) {
                    ImportProgressDialog(
                        progress = state.importProgress,
                        importingArchive = state.importingArchive,
                        onDismiss = { importProgressDismissed = true },
                    )
                }
                state.passwordRequest?.let { request ->
                    PasswordDialog(
                        request = request,
                        onSubmit = viewModel::submitPassword,
                        onDismiss = viewModel::cancelImportPassword,
                    )
                }
                state.importResult?.let { result ->
                    ImportResultDialog(
                        result = result,
                        onDismiss = viewModel::dismissImportResult,
                    )
                }
                if (importDialogOpen) {
                    ImportDialog(
                        onImportFiles = {
                            importDialogOpen = false
                            fileImportLauncher.launch(arrayOf("audio/*"))
                        },
                        onImportFolder = {
                            importDialogOpen = false
                            folderImportLauncher.launch(null)
                        },
                        onDismiss = { importDialogOpen = false },
                    )
                }
                state.downloadQualityPrompt?.let { prompt ->
                    DownloadQualityDialog(
                        prompt = prompt,
                        defaultQuality = state.downloadQuality,
                        defaultQualityValue = state.downloadQualityValue,
                        qualityOptions = state.sourceQualityOptions[
                            prompt.track?.pluginId ?: prompt.collection?.pluginId
                        ].orEmpty(),
                        onConfirm = viewModel::confirmDownloadQuality,
                        onDismiss = viewModel::cancelDownloadQualityPrompt,
                    )
                }
                if (agreementAcceptedVersion < USER_AGREEMENT_VERSION) {
                    UserAgreementGate(
                        onAccept = {
                            appConsents.edit()
                                .putInt("user_agreement_accepted_version", USER_AGREEMENT_VERSION).apply()
                            agreementAcceptedVersion = USER_AGREEMENT_VERSION
                            accessibilityView.announceForAccessibility("已同意用户协议与免责声明")
                        },
                    )
                }
                // 非会员触达会员内容的气泡已随账号体系移除（最终版无会员概念）。
            }
        }
    }
}

@Composable
private fun DownloadQualityDialog(
    prompt: DownloadQualityPrompt,
    defaultQuality: StreamQuality,
    defaultQualityValue: String?,
    qualityOptions: List<StreamQualityOption>,
    onConfirm: (StreamQualityOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = streamQualityEntries(qualityOptions)
    var selected by remember(prompt, entries, defaultQualityValue) {
        mutableStateOf(
            entries.firstOrNull { option ->
                option.value == defaultQualityValue && option.tier == defaultQuality
            } ?: entries.firstOrNull { it.tier == defaultQuality } ?: entries.last(),
        )
    }
    val subject = prompt.track?.name ?: prompt.collection?.name.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下载音质") },
        text = {
            Column {
                if (subject.isNotBlank()) {
                    Text(subject, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                entries.forEach { option ->
                    TextButton(
                        onClick = { selected = option },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) {
                        Text(if (option == selected) "${option.displayLabel()}（已选择）" else option.displayLabel())
                    }
                }
                Text(
                    "音源不支持所选档位时会自动降档",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ImportDialog(
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导入的文件和文件夹将在原位置播放，不会复制到应用内。")
                TextButton(
                    onClick = onImportFiles,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) { Text("导入文件") }
                TextButton(
                    onClick = onImportFolder,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) { Text("导入文件夹") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
    )
}
@Composable
private fun UserAgreementGate(onAccept: () -> Unit) {
    val context = LocalContext.current
    Surface(
        Modifier.fillMaxSize().semantics { paneTitle = "用户协议与免责声明" },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("用户协议与免责声明", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "请阅读协议后选择是否同意。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                Text(
                    USER_AGREEMENT_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { (context as? Activity)?.finishAffinity() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("不同意并退出") }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("同意并继续") }
            }
        }
    }
}

/** 只读查看用户协议（关于页入口）；关闭后由调用方恢复焦点。 */
@Composable
private fun UserAgreementDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用户协议与免责声明", modifier = Modifier.semantics { heading() }) },
        text = {
            Text(
                USER_AGREEMENT_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("关闭") }
        },
    )
}

@Composable
private fun MineScreen(
    state: MusicUiState,
    section: MineSection,
    onSectionChange: (MineSection) -> Unit,
    viewModel: NativeMusicViewModel,
    settingsSubpage: String?,
    onSettingsSubpageChange: (String?) -> Unit,
    mineReturnFocus: MineSection?,
    onMineReturnFocusChange: (MineSection?) -> Unit,
    onPlayFavorite: (MediaReference, String?) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenHistoryAlbum: (AlbumPlaybackHistoryEntry) -> Unit,
    onPlayHistorySingle: (SinglePlaybackHistoryEntry, List<SinglePlaybackHistoryEntry>) -> Unit,
) {
    val downloadFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val sourcesFocusRequester = remember { FocusRequester() }
    val playlistsFocusRequester = remember { FocusRequester() }
    val historyFocusRequester = remember { FocusRequester() }
    val queueFocusRequester = remember { FocusRequester() }
    val returnFocus = mineReturnFocus
    fun returnHome() {
        onSectionChange(MineSection.Home)
    }
    BackHandler(enabled = section != MineSection.Home) {
        onMineReturnFocusChange(section)
        returnHome()
    }
    LaunchedEffect(section, returnFocus) {
        if (section == MineSection.Home) {
            when (returnFocus) {
                MineSection.Downloads -> downloadFocusRequester.requestFocus()
                MineSection.Settings -> settingsFocusRequester.requestFocus()
                MineSection.Sources -> sourcesFocusRequester.requestFocus()
                MineSection.Playlists -> playlistsFocusRequester.requestFocus()
                MineSection.History -> historyFocusRequester.requestFocus()
                MineSection.Queue -> queueFocusRequester.requestFocus()
                else -> Unit
            }
            onMineReturnFocusChange(null)
        }
    }
    when (section) {
        MineSection.Home -> {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
                AppBar(title = "我的", onBack = null, onDevices = null)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    item {
                        MineEntryRow(
                            icon = Icons.Default.Public,
                            title = "音源管理",
                            subtitle = state.installedMusicFreeSources.takeIf { it.isNotEmpty() }
                                ?.let { sources -> "已启用 ${sources.count { source -> source.enabled }}/${sources.size} 个音源" },
                            modifier = Modifier.focusRequester(sourcesFocusRequester),
                            onClick = { onSectionChange(MineSection.Sources) },
                        )
                    }
                    item { HorizontalDivider() }
                    item {
                        MineEntryRow(
                            icon = Icons.Default.QueueMusic,
                            title = "歌单",
                            modifier = Modifier.focusRequester(playlistsFocusRequester),
                            onClick = { onSectionChange(MineSection.Playlists) },
                        )
                    }
                    item { HorizontalDivider() }
                    item {
                        MineEntryRow(
                            icon = Icons.Default.History,
                            title = "历史",
                            subtitle = "最近播放记录",
                            modifier = Modifier.focusRequester(historyFocusRequester),
                            onClick = { onSectionChange(MineSection.History) },
                        )
                    }
                    item { HorizontalDivider() }
                    item {
                        MineEntryRow(
                            icon = Icons.Default.QueueMusic,
                            title = "播放队列",
                            subtitle = "当前队列和已保存队列",
                            modifier = Modifier.focusRequester(queueFocusRequester),
                            onClick = { onSectionChange(MineSection.Queue) },
                        )
                    }
                    item { HorizontalDivider() }
                    item {
                        MineEntryRow(
                            icon = Icons.Default.Download,
                            title = "下载管理",
                            subtitle = mineDownloadSummary(state.downloadTasks.map { DownloadTaskSnapshot(it.id, it.status, it.downloadedBytes) }),
                            modifier = Modifier.focusRequester(downloadFocusRequester),
                            onClick = { onSectionChange(MineSection.Downloads) },
                        )
                    }
                    item { HorizontalDivider() }
                    item {
                        MineEntryRow(
                            icon = Icons.Default.Settings,
                            title = "设置",
                            modifier = Modifier.focusRequester(settingsFocusRequester),
                            onClick = { onSectionChange(MineSection.Settings) },
                        )
                    }
                }
            }
        }
        MineSection.Sources -> {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                AppBar(
                    title = "音源管理",
                    onBack = {
                        onMineReturnFocusChange(MineSection.Sources)
                        returnHome()
                    },
                    onDevices = null,
                )
                SourceManagerScreen(
                    sources = state.installedMusicFreeSources,
                    status = state.sourceManagerStatus,
                    pendingImports = state.pendingSourceImports,
                    selectionMode = state.sourceSelectionMode,
                    selection = state.sourceSelection,
                    onImportFiles = viewModel::importSourceFiles,
                    onImportUrl = viewModel::importSourceUrl,
                    onToggle = viewModel::toggleSource,
                    onRemove = viewModel::removeSource,
                    onRemoveMany = viewModel::removeSources,
                    onConfirmImport = viewModel::confirmSourceImport,
                    onCancelImport = viewModel::cancelSourceImport,
                    onToggleSelectionMode = viewModel::setSourceSelectionMode,
                    onToggleSelection = viewModel::toggleSourceSelection,
                    onToggleSelectAll = viewModel::toggleSourceSelectAll,
                    sourceTestStatuses = state.sourceTestStatuses,
                    testingSourceIds = state.testingSourceIds,
                    onTestSource = viewModel::testSource,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        MineSection.Playlists -> PlaylistHubScreen(
            state = state,
            sources = state.musicFreeSources,
            onCreateFolder = { scope, name -> viewModel.createFavoriteFolder(scope, name) },
            onPlay = { folderId, reference -> onPlayFavorite(reference, folderId) },
            onPlayFolder = { folderId ->
                viewModel.playFavoriteFolder(folderId)
                onOpenNowPlaying()
            },
            onRemove = viewModel::removeFavoriteItems,
            onDeleteFolder = viewModel::deleteFavoriteFolder,
            onImportOnline = viewModel::importOnlinePlaylist,
            onBack = {
                onMineReturnFocusChange(MineSection.Playlists)
                returnHome()
            },
        )
        MineSection.History -> PlaybackHistoryScreen(
            state = state,
            scope = MediaScope.Local,
            onBack = {
                onMineReturnFocusChange(MineSection.History)
                returnHome()
            },
            onPlayAlbum = onOpenHistoryAlbum,
            onPlaySingle = onPlayHistorySingle,
            onClear = viewModel::clearPlaybackHistory,
            onRemoveAlbums = viewModel::removeAlbumPlaybackHistory,
            onRemoveSingles = viewModel::removeSinglePlaybackHistory,
        )
        MineSection.Queue -> PlaylistScreen(
            state = state,
            onPlayQueueItem = { viewModel.playFromQueue(state.queue, it) },
            onPlayHistoryTrack = { track -> viewModel.playFromQueue(state.tracks, state.tracks.indexOf(track)) },
            onMoveQueueItem = viewModel::moveQueueItem,
            onRemoveQueueItem = viewModel::removeQueueItem,
            onViewLyrics = { index -> viewModel.openLyricsFromSong(state.queue[index]) },
            onSaveQueueAsPlaylist = viewModel::saveQueueAsPlaylist,
            onPlayPlaylist = viewModel::playPlaylist,
            onDeletePlaylist = viewModel::deletePlaylist,
            onClearPlaybackHistory = viewModel::clearPlaybackHistory,
        )
        MineSection.Downloads -> DownloadManagerScreen(
            tasks = state.downloadTasks,
            panTasks = state.panDownloads,
            quarkTasks = state.quarkDownloads,
            speedEta = state.downloadSpeedEta,
            wifiOnlyDownload = state.wifiOnlyDownload,
            onBack = {
                onMineReturnFocusChange(MineSection.Downloads)
                returnHome()
            },
            onWifiOnlyDownload = viewModel::setWifiOnlyDownload,
            onPauseAll = viewModel::pauseAllDownloads,
            onResumeAll = viewModel::resumeAllDownloads,
            onPause = viewModel::pauseDownload,
            onResume = viewModel::resumeDownload,
            onRetry = viewModel::retryDownload,
            onCancel = viewModel::cancelDownload,
            onRemoveRecord = viewModel::removeDownloadRecord,
            onPanPause = viewModel::panPauseDownload,
            onPanResume = viewModel::panResumeDownload,
            onPanCancel = viewModel::panCancelDownload,
            onPanRemove = viewModel::panRemoveDownload,
            onQuarkPause = viewModel::quarkPauseDownload,
            onQuarkResume = viewModel::quarkResumeDownload,
            onQuarkCancel = viewModel::quarkCancelDownload,
            onQuarkRemove = viewModel::quarkRemoveDownload,
        )
        MineSection.Settings -> SettingsScreen(
            onBack = {
                onMineReturnFocusChange(MineSection.Settings)
                returnHome()
            },
            state = state,
            subpage = settingsSubpage,
            onSubpageChange = onSettingsSubpageChange,
            onThemeMode = viewModel::setThemeMode,
            onGridDensity = viewModel::setGridDensity,
            onFadeTransitions = viewModel::setFadeTransitions,
            onAutoMatchLocalLyrics = viewModel::setAutoMatchLocalLyrics,
            onAutoOpenPlayingPage = viewModel::setAutoOpenPlayingPage,
            onSkipSilenceEnabled = viewModel::setSkipSilenceEnabled,
            onSilenceSkipMode = viewModel::setSilenceSkipMode,
            onSilenceSkipThresholdMs = viewModel::setSilenceSkipThresholdMs,
            onPlaybackQuality = viewModel::setPlaybackQuality,
            onDownloadQuality = viewModel::setDownloadQuality,
            onSpeed = viewModel::setSpeed,
            onQueueMode = viewModel::setQueueMode,
            onExportLogs = viewModel::exportLogs,
            onClearLogs = viewModel::clearLogs,
            onToggleLogExportClearBefore = viewModel::toggleLogExportClearBefore,
        )
    }
}

@Composable
private fun MineEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().sizeIn(minHeight = 64.dp).clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = title
                onClick(label = "进入") { onClick(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(start = 16.dp, end = 20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PlaylistHubScreen(
    state: MusicUiState,
    sources: List<MusicFreeSource>,
    onBack: () -> Unit,
    onCreateFolder: (MediaScope, String) -> Unit,
    onPlay: (String, MediaReference) -> Unit,
    onPlayFolder: (String) -> Unit,
    onRemove: (String, Set<String>) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onImportOnline: (String, String, String) -> Unit,
) {
    var scope by rememberSaveable { mutableStateOf(MediaScope.Local) }
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var importUrl by rememberSaveable { mutableStateOf("") }
    var importName by rememberSaveable { mutableStateOf("") }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sources) {
        if (sourceId !in sources.map { it.id }) sourceId = sources.firstOrNull()?.id.orEmpty()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        AppBar(title = "歌单", onBack = onBack, onDevices = null)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = scope == MediaScope.Local,
                onClick = { scope = MediaScope.Local },
                label = { Text("本地歌单") },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
            FilterChip(
                selected = scope == MediaScope.Music,
                onClick = { scope = MediaScope.Music },
                label = { Text("在线歌单") },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
            if (scope == MediaScope.Music) {
                OutlinedButton(
                    onClick = { importOpen = true },
                    enabled = sources.isNotEmpty(),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("从平台导入") }
            }
        }
        if (scope == MediaScope.Music && sources.isEmpty()) {
            Text(
                "请先到音源管理导入支持歌单导入的 MusicFree 插件。",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(Modifier.weight(1f)) {
            FavoritesScreen(
                state = state,
                scope = scope,
                embedded = true,
                onCreateFolder = { onCreateFolder(scope, it) },
                onPlay = onPlay,
                onPlayFolder = onPlayFolder,
                onRemove = onRemove,
                onDeleteFolder = onDeleteFolder,
            )
        }
    }
    if (importOpen) {
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("导入在线歌单", modifier = Modifier.semantics { heading() }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        OutlinedButton(
                            onClick = { sourceMenuOpen = true },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                        ) { Text("音源：${sources.firstOrNull { it.id == sourceId }?.name ?: "请选择"}") }
                        DropdownMenu(
                            expanded = sourceMenuOpen,
                            onDismissRequest = { sourceMenuOpen = false },
                        ) {
                            sources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.name) },
                                    onClick = { sourceId = source.id; sourceMenuOpen = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text("歌单名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        label = { Text("平台链接或 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("取消") } },
            confirmButton = {
                Button(
                    onClick = {
                        onImportOnline(sourceId, importUrl, importName)
                        importUrl = ""
                        importName = ""
                        importOpen = false
                    },
                    enabled = sourceId.isNotBlank() && importUrl.isNotBlank() && importName.isNotBlank(),
                ) { Text("导入") }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FavoritesScreen(
    state: MusicUiState,
    scope: MediaScope,
    embedded: Boolean = false,
    onBack: (() -> Unit)? = null,
    onCreateFolder: (String) -> Unit,
    onPlay: (String, MediaReference) -> Unit,
    onPlayFolder: (String) -> Unit,
    onRemove: (String, Set<String>) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {    // 收藏夹按内容域隔离：本模块只展示本域的收藏夹（旧全局混合夹已在存储层拆分迁移）。
    val scopedFolders = state.favoriteFolders.filter { it.scope == scope }
    val defaultFolderId = favoriteDefaultFolderId(scope)
    var selectedFolderId by rememberSaveable(scopedFolders) { mutableStateOf(scopedFolders.firstOrNull()?.id ?: defaultFolderId) }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val folders = scopedFolders.ifEmpty { listOf(FavoriteFolder(defaultFolderId, "默认歌单", 0L, scope = scope)) }
    val folder = folders.firstOrNull { it.id == selectedFolderId } ?: folders.first()
    fun keyOf(reference: MediaReference) = reference.source + ":" + reference.key
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
        if (!embedded) {
            AppBar(title = "歌单", onBack = onBack, onDevices = null)
        }
        StatusMessage(state.status)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            folders.forEach { item ->
                FilterChip(
                    selected = item.id == folder.id,
                    onClick = { selectedFolderId = item.id; selecting = false; selectedKeys = emptySet() },
                    label = { Text(item.name) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { createOpen = true }, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) { Text("新建歌单") }
            if (!isDefaultFavoriteFolderId(folder.id)) {
                OutlinedButton(onClick = { onDeleteFolder(folder.id) }, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) { Text("删除歌单") }
            }
        }
        Button(
            onClick = { onPlayFolder(folder.id) },
            enabled = folder.items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).sizeIn(minHeight = 48.dp),
        ) { Text("播放全部") }
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onRemove(folder.id, selectedKeys); selecting = false; selectedKeys = emptySet() },
                    enabled = selectedKeys.isNotEmpty(),
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                ) { Text("删除所选") }
                OutlinedButton(onClick = { selecting = false; selectedKeys = emptySet() }, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) { Text("取消选择") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (folder.items.isEmpty()) {
                item { Text("这个歌单还没有曲目。") }
            } else {
                items(folder.items, key = { keyOf(it) }) { reference ->
                    val key = keyOf(reference)
                    val selected = key in selectedKeys
                    // 「正在播放」标记：与当前播放条目的引用键同口径（收藏/历史条目共享同一套 key）。
                    val playing = state.playingReferenceKey == key
                    FavoriteReferenceRow(
                        reference = reference,
                        playing = playing,
                        selected = selected,
                        selecting = selecting,
                        onPlay = { onPlay(folder.id, reference) },
                        onSelect = {
                            selecting = true
                            selectedKeys = if (selected) selectedKeys - key else selectedKeys + key
                        },
                        onDelete = { onRemove(folder.id, setOf(key)) },
                    )
                }
            }
        }
    }
    if (createOpen) {
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
            },
            confirmButton = {
                Button(onClick = { onCreateFolder(folderName); folderName = ""; createOpen = false }, enabled = folderName.isNotBlank()) { Text("新建") }
            },
            dismissButton = { TextButton(onClick = { createOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FavoriteReferenceRow(
    reference: MediaReference,
    playing: Boolean = false,
    selected: Boolean,
    selecting: Boolean,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selecting) onSelect() else onPlay() },
            onLongClick = { menuOpen = true },
        ).clearAndSetSemantics {
            contentDescription = referenceLabel(reference) + if (playing) "，正在播放" else "" +
                if (selected) "，已选择" else ""
            stateDescription = when {
                playing -> "正在播放"
                selected -> "已选择"
                else -> "未选择"
            }
            onClick(label = if (selecting) "切换选择" else "播放") { if (selecting) onSelect() else onPlay(); true }
            customActions = listOf(
                CustomAccessibilityAction(if (selected) "取消选择" else "选择") { onSelect(); true },
                CustomAccessibilityAction("删除") { onDelete(); true },
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (playing) {
                // 状态不只用文字表达：左侧暖橙竖线 + 描述文字双通道。
                Box(Modifier.width(3.dp).height(48.dp).background(GlassTheme.Accent))
            }
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(reference.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(referenceSubLabel(reference), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(text = { Text(if (selected) "取消选择" else "选择") }, onClick = { menuOpen = false; onSelect() }, leadingIcon = { Icon(Icons.Default.Done, null) })
        DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
    }
}

@Composable
internal fun PlaybackHistoryScreen(
    state: MusicUiState,
    scope: MediaScope,
    embedded: Boolean = false,
    onBack: (() -> Unit)? = null,
    onPlayAlbum: (AlbumPlaybackHistoryEntry) -> Unit,
    /** [siblings] 为当前标签下可见的全部单曲条目（顺序与界面一致）：用于把整条列表建成播放队列。 */
    onPlaySingle: (SinglePlaybackHistoryEntry, List<SinglePlaybackHistoryEntry>) -> Unit,
    onClear: () -> Unit,
    onRemoveAlbums: (Set<String>) -> Unit,
    onRemoveSingles: (Set<String>) -> Unit,
) {
    // 子标签为空时两者都列；否则首个标签只列单曲、次个只列专辑（最终版无视频/剧集域）。
    val subTabLabels = when (scope) {
        MediaScope.Music -> listOf("单曲", "专辑")
        else -> emptyList()
    }
    var subTabIndex by rememberSaveable(scope) { mutableStateOf(0) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedAlbumKeys by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var selectedSingleKeys by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var confirmClearTab by rememberSaveable { mutableStateOf(false) }
    val albumsInScope = state.albumPlaybackHistory.filter { mediaScopeForAlbum(it) == scope }
    val singlesInScope = state.singlePlaybackHistory.filter { mediaScopeForSingle(it.reference) == scope }
    // 子标签为空时两者都列；否则首个标签只列单曲/视频、次个只列专辑/剧集。
    val showingSingles = subTabLabels.isEmpty() || subTabIndex == 0
    val albumsVisible = if (showingSingles && subTabLabels.isNotEmpty()) emptyList() else albumsInScope
    val singlesVisible = if (!showingSingles) emptyList() else singlesInScope
    val tabHasEntries = albumsVisible.isNotEmpty() || singlesVisible.isNotEmpty()
    val accessibilityView = LocalView.current
    if (confirmClearTab) {
        AlertDialog(
            onDismissRequest = { confirmClearTab = false },
            title = { Text("清空${scope.label}播放历史") },
            text = { Text("将删除「${scope.label}」下的全部播放记录（含播放进度），此操作不可撤销。") },
            dismissButton = { TextButton(onClick = { confirmClearTab = false }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") } },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveAlbums(albumsInScope.map { it.albumKey }.toSet())
                        onRemoveSingles(singlesInScope.map { it.reference.source + ":" + it.reference.key }.toSet())
                        confirmClearTab = false
                        selecting = false
                        selectedAlbumKeys = emptySet()
                        selectedSingleKeys = emptySet()
                        accessibilityView.announceForAccessibility("已清空${scope.label}播放历史")
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("清空") }
            },
        )
    }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
        if (!embedded) {
            AppBar(
                title = "播放历史",
                onBack = onBack,
                onDevices = null,
                trailingContent = {
                    if (tabHasEntries) {
                        TextButton(onClick = { confirmClearTab = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text("清空")
                        }
                    }
                },
            )
        }
        StatusMessage(state.status)
        if (subTabLabels.isNotEmpty()) {
            TabRow(selectedTabIndex = subTabIndex) {
                subTabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = subTabIndex == index,
                        onClick = { subTabIndex = index; selecting = false; selectedAlbumKeys = emptySet(); selectedSingleKeys = emptySet() },
                        text = { Text(label) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    )
                }
            }
        }
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onRemoveAlbums(selectedAlbumKeys)
                        onRemoveSingles(selectedSingleKeys)
                        selecting = false
                        selectedAlbumKeys = emptySet()
                        selectedSingleKeys = emptySet()
                    },
                    enabled = selectedAlbumKeys.isNotEmpty() || selectedSingleKeys.isNotEmpty(),
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                ) { Text("删除所选") }
                OutlinedButton(onClick = { selecting = false; selectedAlbumKeys = emptySet(); selectedSingleKeys = emptySet() }, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) { Text("取消选择") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (albumsVisible.isEmpty() && singlesVisible.isEmpty()) {
                item { Text("还没有${scope.label}播放记录。") }
            }
            items(albumsVisible, key = { "album:" + it.albumKey }) { entry ->
                val selected = entry.albumKey in selectedAlbumKeys
                HistoryAlbumRow(
                    entry = entry,
                    playing = state.playingAlbumKey == entry.albumKey,
                    selected = selected,
                    selecting = selecting,
                    onPlay = { onPlayAlbum(entry) },
                    onSelect = {
                        selecting = true
                        selectedAlbumKeys = if (selected) selectedAlbumKeys - entry.albumKey else selectedAlbumKeys + entry.albumKey
                    },
                    onDelete = { onRemoveAlbums(setOf(entry.albumKey)) },
                )
            }
            items(singlesVisible, key = { "single:" + it.reference.source + ":" + it.reference.key }) { entry ->
                val key = entry.reference.source + ":" + entry.reference.key
                val selected = key in selectedSingleKeys
                HistorySingleRow(
                    entry = entry,
                    playing = state.playingReferenceKey == key,
                    selected = selected,
                    selecting = selecting,
                    onPlay = { onPlaySingle(entry, singlesVisible) },
                    onSelect = {
                        selecting = true
                        selectedSingleKeys = if (selected) selectedSingleKeys - key else selectedSingleKeys + key
                    },
                    onDelete = { onRemoveSingles(setOf(key)) },
                )
            }
            if (tabHasEntries) {
                item {
                    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("清除${scope.label}播放历史") }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HistoryAlbumRow(entry: AlbumPlaybackHistoryEntry, playing: Boolean, selected: Boolean, selecting: Boolean, onPlay: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onSelect() else onPlay() }, onLongClick = { menuOpen = true })
            .clearAndSetSemantics {
                contentDescription = entry.albumTitle + "，" + entry.artist + "，第 " + (entry.trackIndex + 1) + " 首，进度 " + formatTime(entry.positionMs) +
                    if (playing) "，正在播放" else "" + if (selected) "，已选择" else ""
                stateDescription = when {
                    playing -> "正在播放"
                    selected -> "已选择"
                    else -> "未选择"
                }
                onClick(label = if (selecting) "切换选择" else "继续播放") { if (selecting) onSelect() else onPlay(); true }
                customActions = listOf(
                    CustomAccessibilityAction(if (selected) "取消选择" else "选择") { onSelect(); true },
                    CustomAccessibilityAction("删除") { onDelete(); true },
                )
            },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlayingMarker(playing)
                Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.albumTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("第 ${entry.trackIndex + 1} 首：${entry.trackReference.title} · ${formatTime(entry.positionMs)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(if (selected) "取消选择" else "选择") }, onClick = { menuOpen = false; onSelect() }, leadingIcon = { Icon(Icons.Default.Done, null) })
            DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}

/** 「正在播放」左侧暖橙竖线：颜色之外的第二个视觉通道（状态不只靠颜色表达）。 */
@Composable
private fun PlayingMarker(playing: Boolean) {
    if (!playing) return
    Box(Modifier.width(3.dp).height(48.dp).background(GlassTheme.Accent))
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HistorySingleRow(entry: SinglePlaybackHistoryEntry, playing: Boolean, selected: Boolean, selecting: Boolean, onPlay: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) onSelect() else onPlay() }, onLongClick = { menuOpen = true })
            .clearAndSetSemantics {
                contentDescription = referenceLabel(entry.reference) + "，进度 " + formatTime(entry.positionMs) + "，播放 " + entry.playCount + " 次" +
                    if (playing) "，正在播放" else "" + if (selected) "，已选择" else ""
                stateDescription = when {
                    playing -> "正在播放"
                    selected -> "已选择"
                    else -> "未选择"
                }
                onClick(label = if (selecting) "切换选择" else "继续播放") { if (selecting) onSelect() else onPlay(); true }
                customActions = listOf(
                    CustomAccessibilityAction(if (selected) "取消选择" else "选择") { onSelect(); true },
                    CustomAccessibilityAction("删除") { onDelete(); true },
                )
            },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlayingMarker(playing)
                Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.reference.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(referenceSubLabel(entry.reference) + " · " + formatTime(entry.positionMs) + " · " + entry.playCount + " 次", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(if (selected) "取消选择" else "选择") }, onClick = { menuOpen = false; onSelect() }, leadingIcon = { Icon(Icons.Default.Done, null) })
            DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}

private fun referenceLabel(reference: MediaReference): String = buildList {
    add(reference.title)
    reference.artist.takeIf(String::isNotBlank)?.let(::add)
    add(sourceLabel(reference.source))
}.joinToString("，")

private fun referenceSubLabel(reference: MediaReference): String = buildList {
    add(sourceLabel(reference.source))
    reference.artist.takeIf(String::isNotBlank)?.let(::add)
    reference.album.takeIf(String::isNotBlank)?.let(::add)
    reference.durationMs.takeIf { it > 0 }?.let { add(formatTime(it)) }
}.joinToString(" · ")

private fun sourceLabel(source: String): String = when (source) {
    "local" -> "本地媒体"
    "online" -> "在线媒体"
    "baidupan" -> "百度网盘"
    "quarkpan" -> "夸克网盘"
    "video" -> "视频"
    else -> "音频"
}

internal fun mineDownloadSummary(tasks: List<DownloadTaskSnapshot>): String {
    if (tasks.isEmpty()) return "暂无任务"
    val active = tasks.count { it.status in setOf(DownloadTaskStatus.QUEUED, DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.WAITING_NETWORK) }
    return when {
        active > 0 -> "$active 项进行中"
        tasks.all { it.status == DownloadTaskStatus.COMPLETED } -> "${tasks.size} 项已完成"
        else -> "${tasks.size} 项待处理"
    }
}

@Composable
private fun QuarkPanAuthorizeScreen(
    state: MusicUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onEnterWebAuth: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onStart() }
    val qrImage = remember(state.quarkQrImage) {
        state.quarkQrImage?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "夸克网盘登录", onBack = onCancel, onDevices = null)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "请使用夸克 App 扫描下方二维码登录，扫码后会自动完成登录。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                Modifier.size(240.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (qrImage != null) {
                    Image(
                        bitmap = qrImage,
                        contentDescription = "夸克网盘登录二维码",
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            if (state.quarkAuthInProgress) "正在生成二维码…" else "二维码加载失败，请点击刷新",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (state.quarkAuthInProgress) {
                Text(
                    "等待扫码授权…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.quarkError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val bytes = state.quarkQrImage
                        if (bytes != null) {
                            val saved = saveQuarkPanQr(context, bytes)
                            Toast.makeText(
                                context,
                                if (saved) "二维码已保存到相册" else "二维码保存失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = state.quarkQrImage != null,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                ) {
                    Text("保存二维码")
                }
                OutlinedButton(
                    onClick = onStart,
                    enabled = !state.quarkAuthInProgress,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                ) {
                    Text("刷新二维码")
                }
            }
        }
        TextButton(
            onClick = onEnterWebAuth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .sizeIn(minHeight = 48.dp),
        ) {
            Text("无法扫码，进入官方网页登录")
        }
    }
}

/** 保存夸克网盘登录二维码 PNG 到系统相册（Pictures/月播）。 */
private fun saveQuarkPanQr(context: Context, bytes: ByteArray): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "夸克网盘登录二维码_${System.currentTimeMillis()}.png",
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/月播",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val written = resolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
            true
        } ?: false
        if (written && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        if (!written) {
            resolver.delete(uri, null, null)
        }
        written
    }.getOrDefault(false)
}


/** 夸克官方网页登录：内嵌 WebView 打开官方登录页，登录后自动抓取 Cookie；未自动完成时可手动点击。 */
@Composable
private fun QuarkPanWebAuthScreen(
    state: MusicUiState,
    viewModel: NativeMusicViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var canGoBack by remember { mutableStateOf(false) }
    var autoAttempted by remember { mutableStateOf(false) }
    // 桌面版 pan.quark.cn 的登录表单是隐藏弹窗，需注入脚本强制显示并滚动到可见。
    val loginRevealJs = remember {
        "(function(){" +
            "function reveal(el){var c=el;while(c&&c.nodeType===1){" +
            "if(c.style){if(c.style.display==='none')c.style.display='block';" +
            "if(c.style.visibility==='hidden')c.style.visibility='visible';" +
            "if(c.style.opacity==='0')c.style.opacity='1';}" +
            "c=c.parentNode;}}" +
            "var all=document.querySelectorAll('*');" +
            "var target=null;" +
            "for(var i=0;i<all.length;i++){" +
            "var t=(all[i].textContent||'').trim();" +
            "if(t==='手机登录'||t==='微信登录'||t==='扫码登录'||t.indexOf('其他登录方式')>=0){" +
            "target=all[i];break;}}" +
            "if(target){reveal(target);target.scrollIntoView({block:'center'});return 'revealed';}" +
            "for(var i=0;i<all.length;i++){" +
            "var el=all[i];" +
            "if(el.children.length===0&&(el.textContent||'').trim()==='登录'){el.click();return 'clicked';}}" +
            "return 'none';" +
            "})();"
    }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.userAgentString = QUARK_WEBVIEW_UA
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    canGoBack = view?.canGoBack() == true
                    // 官方页顶部是营销区，登录表单在下方；加载完成后滚动到登录区，方便直接使用。
                    // SPA 登录表单可能延迟渲染，多次重试。
                    listOf(900L, 2400L, 4000L).forEach { delayMs ->
                        view?.postDelayed({
                            runCatching { view.evaluateJavascript(loginRevealJs, null) }
                        }, delayMs)
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    // 自动完成：出现会话 Cookie 后抓取一次；失败则保留手动按钮重试。
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            if (!autoAttempted && state.quarkAccount == null && viewModel.quarkWebSessionReady()) {
                autoAttempted = true
                viewModel.quarkCompleteWebLogin()
                break
            }
        }
    }
    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            onBack()
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "夸克网页登录", onBack = onBack, onDevices = null)
        AndroidView(
            factory = {
                // 清除 WebView 旧登录态（Cookie），确保“添加账号”打开的是新的登录页而非已登录状态。
                // removeAllCookies 完成后才加载登录页，避免旧会话被带入。
                CookieManager.getInstance().removeAllCookies {
                    webView.loadUrl(QUARK_WEB_LOGIN_URL)
                }
                webView
            },
            modifier = Modifier.weight(1f),
            update = { view ->
                canGoBack = view.canGoBack()
            },
        )
        state.quarkError?.takeIf { state.quarkAccount == null }?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun PanDownloadRow(
    name: String,
    size: Long,
    downloadedBytes: Long,
    status: DownloadTaskStatus,
    error: String?,
    providerName: String,
    speedEta: DownloadSpeedEta?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actions = buildList {
        when (status) {
            DownloadTaskStatus.QUEUED,
            DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.FINALIZING -> {
                add(Triple("暂停下载", Icons.Default.Pause) { onPause() })
                add(Triple("取消下载", Icons.Default.Close) { onCancel() })
            }
            DownloadTaskStatus.PAUSED,
            DownloadTaskStatus.FAILED -> {
                add(Triple("继续下载", Icons.Default.PlayArrow) { onResume() })
                add(Triple("取消下载", Icons.Default.Close) { onCancel() })
            }
            DownloadTaskStatus.COMPLETED -> {
                add(Triple("移除下载记录", Icons.Default.Delete) { onRemove() })
            }
            DownloadTaskStatus.WAITING_NETWORK -> Unit
        }
    }
    val progress = size.takeIf { it > 0 }?.let { downloadedBytes.toFloat() / it }
    val statusLabel = "$providerName · ${panDownloadStatusLabel(status)}"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .pointerInput(name) { detectTapGestures(onLongPress = { menuOpen = true }) }
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(name)
                    append("，")
                    append(statusLabel)
                    append("，")
                    append(formatFileSize(downloadedBytes))
                    size.takeIf { it > 0 }?.let { sizeValue ->
                        append(" / ")
                        append(formatFileSize(sizeValue))
                        progress?.let {
                            append("，")
                            append((it * 100).toInt())
                            append("%")
                        }
                    }
                    error?.takeIf { status == DownloadTaskStatus.FAILED }?.let {
                        append("，")
                        append(it)
                    }
                }
                customActions = actions.map { (label, _, run) ->
                    CustomAccessibilityAction(label) { run(); true }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(statusLabel)
                    append("，")
                    append(formatFileSize(downloadedBytes))
                    size.takeIf { it > 0 }?.let { append(" / ${formatFileSize(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (status == DownloadTaskStatus.DOWNLOADING) {
                speedEta?.let { eta ->
                    Text(
                        buildString {
                            append(formatDownloadSpeed(eta.speedBps))
                            if (eta.etaMs > 0) append(" · ${formatRemainingTime(eta.etaMs)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            error?.takeIf { status == DownloadTaskStatus.FAILED }?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            actions.forEach { (label, icon, run) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { menuOpen = false; run() },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                )
            }
        }
    }
}


private fun panDownloadStatusLabel(status: DownloadTaskStatus): String = when (status) {
    DownloadTaskStatus.QUEUED -> "等待下载"
    DownloadTaskStatus.DOWNLOADING -> "下载中"
    DownloadTaskStatus.PAUSED -> "已暂停"
    DownloadTaskStatus.FAILED -> "下载失败"
    DownloadTaskStatus.COMPLETED -> "已完成"
    DownloadTaskStatus.WAITING_NETWORK -> "等待网络"
    DownloadTaskStatus.FINALIZING -> "合成中"
}

@Composable
private fun BaiduPanAuthorizeScreen(
    state: MusicUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onEnterWebAuth: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onStart() }
    val qrImage = remember(state.panQrImage) {
        state.panQrImage?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "百度网盘登录", onBack = onCancel, onDevices = null)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "请使用百度网盘 App 扫描下方二维码授权登录，扫码后会自动完成登录。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                Modifier.size(240.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (qrImage != null) {
                    Image(
                        bitmap = qrImage,
                        contentDescription = "百度网盘登录二维码",
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            if (state.panAuthInProgress) "正在生成二维码…" else "二维码加载失败，请点击刷新",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            // 设备码（用户码）：部分授权路径会落到要求手填设备码的页面，必须让用户拿得到码。
            state.panDeviceCode?.let { code ->
                val clipboard = LocalClipboardManager.current
                Text(
                    "设备码：${code.userCode}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "设备码约 5 分钟内有效；如授权页面要求输入设备码，请输入或粘贴上方设备码，过期请点“刷新二维码”。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code.userCode))
                        Toast.makeText(context, "设备码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) {
                    Text("复制设备码")
                }
            }
            if (state.panAuthInProgress) {
                Text(
                    "等待扫码授权…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.panError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val bytes = state.panQrImage
                        if (bytes != null) {
                            val saved = saveBaiduPanQr(context, bytes)
                            Toast.makeText(
                                context,
                                if (saved) "二维码已保存到相册" else "二维码保存失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = state.panQrImage != null,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                ) {
                    Text("保存二维码")
                }
                OutlinedButton(
                    onClick = onStart,
                    enabled = !state.panAuthInProgress,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                ) {
                    Text("刷新二维码")
                }
            }
        }
        TextButton(
            onClick = onEnterWebAuth,
            enabled = state.panDeviceCode != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .sizeIn(minHeight = 48.dp),
        ) {
            Text("我不能扫码，进入网页授权")
        }
    }
}

/** 保存百度网盘登录二维码 PNG 到系统相册（Pictures/月播）。 */
private fun saveBaiduPanQr(context: Context, bytes: ByteArray): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "百度网盘登录二维码_${System.currentTimeMillis()}.png",
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/月播",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val written = resolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
            true
        } ?: false
        if (written && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        if (!written) {
            resolver.delete(uri, null, null)
        }
        written
    }.getOrDefault(false)
}

/** 应用内网页授权页：自动携带用户码，登录并确认授权后由设备码轮询完成登录。 */
@Composable
private fun BaiduPanWebAuthScreen(
    url: String,
    onBack: () -> Unit,
    /** 授权码模式回调地址：非空时拦截百度跳转并把 code 交给 onRedirectCode 换 token。 */
    redirectUri: String? = null,
    onRedirectCode: ((String) -> Unit)? = null,
    errorText: String? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var canGoBack by remember { mutableStateOf(false) }
    val initialUrl = remember { url }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    val redirect = redirectUri
                    val onCode = onRedirectCode
                    if (redirect != null && onCode != null &&
                        isBaiduPanOAuthRedirect(target, redirect)
                    ) {
                        val code = baiduPanQueryParameter(target, "code").orEmpty()
                        if (code.isNotBlank()) onCode(code)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    canGoBack = view?.canGoBack() == true
                }
            }
            webChromeClient = WebChromeClient()
        }
    }
    // 进入网页授权即复制设备码：授权页若落到手填设备码的形态，用户可直接粘贴。
    LaunchedEffect(Unit) {
        val userCode = Uri.parse(initialUrl).getQueryParameter("code").orEmpty()
        if (userCode.isNotBlank()) {
            clipboard.setText(AnnotatedString(userCode))
            Toast.makeText(
                context,
                "设备码 $userCode 已复制，如页面要求输入设备码可直接粘贴",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    // 设备码过期后 ViewModel 会自动换新码：url 变化时重载授权页并复制新码，避免旧页面卡住流程。
    LaunchedEffect(url) {
        if (url.isNotBlank() && url != initialUrl) {
            webView.loadUrl(url)
            val userCode = Uri.parse(url).getQueryParameter("code").orEmpty()
            if (userCode.isNotBlank()) clipboard.setText(AnnotatedString(userCode))
            Toast.makeText(context, "设备码已刷新，请重新确认授权", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            onBack()
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "网页授权登录", onBack = onBack, onDevices = null)
        errorText?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        AndroidView(
            factory = {
                // 清除 WebView 旧登录态（Cookie），确保“添加账号”打开的是新的登录页而非已登录状态。
                CookieManager.getInstance().removeAllCookies {
                    webView.loadUrl(url)
                }
                webView
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                canGoBack = view.canGoBack()
            },
        )
    }
}

@Composable
private fun CloudDriveScreen(
    state: MusicUiState,
    miniPlayerInset: Dp = 0.dp,
    gridDensity: GridDensity,
    activeCloudDisk: CloudDisk?,
    onOpenDisk: (CloudDisk) -> Unit,
    onBackToDiskList: () -> Unit,
    onOpenRoot: () -> Unit,
    onGoUp: () -> Unit,
    onOpenDir: (CloudFile) -> Unit,
    onLoadMore: () -> Unit,
    onSearch: (String) -> Unit,
    onExitSearch: () -> Unit,
    onPlay: (List<CloudFile>, Int) -> Unit,
    onPlayVideo: (List<CloudFile>, Int) -> Unit,
    onDownload: (CloudFile) -> Unit,
    onDownloadFolder: (CloudFile) -> Unit,
    /** 一键收藏/取消收藏网盘音频文件。 */
    onToggleFavorite: (CloudFile) -> Unit,
    isCloudFileFavorite: (CloudFile) -> Boolean,
    onDelete: (CloudFile) -> Unit,
    onCopy: (CloudFile) -> Unit,
    onCut: (CloudFile) -> Unit,
    onPaste: () -> Unit,
    onAnnouncementHandled: () -> Unit,
    onGoLogin: (CloudDisk) -> Unit,
    onBackToHub: (() -> Unit)? = null,
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Name) }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    val cloudGridState = rememberLazyGridState()
    val accessibilityView = LocalView.current
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val disk = activeCloudDisk
    LaunchedEffect(disk) {
        searchVisible = false
        searchQuery = ""
    }
    if (disk == null) {
        CloudDiskLandingScreen(
            state = state,
            onOpenDisk = onOpenDisk,
            onGoLogin = onGoLogin,
            onBack = onBackToHub,
        )
        return
    }
    val accountName = when (disk) {
        CloudDisk.BAIDU -> state.panAccount?.let { it.netdiskName.ifBlank { it.name } }
        CloudDisk.QUARK -> state.quarkAccount?.name
    }
    val cloudFiles = remember(state, disk) {
        when (disk) {
            CloudDisk.BAIDU -> state.panFiles.map { it.toCloudFile() }
            CloudDisk.QUARK -> state.quarkFiles.map { it.toCloudFile() }
        }
    }
    val cloudPath = when (disk) {
        CloudDisk.BAIDU -> state.panPath
        CloudDisk.QUARK -> state.quarkPath
    }
    val cloudIsRoot = when (disk) {
        CloudDisk.BAIDU -> state.panPath == "/"
        CloudDisk.QUARK -> state.quarkPath.isEmpty()
    }
    val cloudLoading = when (disk) {
        CloudDisk.BAIDU -> state.panLoading
        CloudDisk.QUARK -> state.quarkLoading
    }
    val cloudError = when (disk) {
        CloudDisk.BAIDU -> state.panError
        CloudDisk.QUARK -> state.quarkError
    }
    val cloudHasMore = when (disk) {
        CloudDisk.BAIDU -> state.panHasMore
        CloudDisk.QUARK -> state.quarkHasMore
    }
    val cloudSearchActive = when (disk) {
        CloudDisk.BAIDU -> state.panSearchActive
        CloudDisk.QUARK -> state.quarkSearchActive
    }
    val cloudAnnouncement = when (disk) {
        CloudDisk.BAIDU -> state.panAnnouncement
        CloudDisk.QUARK -> state.quarkAnnouncement
    }
    val hasClipboard = when (disk) {
        CloudDisk.BAIDU -> state.hasClipboardPan
        CloudDisk.QUARK -> state.hasClipboardQuark
    }
    LaunchedEffect(cloudAnnouncement) {
        cloudAnnouncement?.let {
            accessibilityView.announceForAccessibility(it)
            onAnnouncementHandled()
        }
    }
    LaunchedEffect(searchVisible) {
        if (searchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            Modifier.fillMaxWidth(),
        ) {
            if (searchVisible) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索全部云端文件") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                                onExitSearch()
                                searchFocusRequester.requestFocus()
                                keyboardController?.show()
                            },
                            enabled = searchQuery.isNotEmpty(),
                        ) { Icon(Icons.Default.Close, contentDescription = "清除搜索") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                        onSearch(searchQuery)
                    }),
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .sizeIn(minHeight = 48.dp)
                        .focusRequester(searchFocusRequester),
                )
            } else {
                AppBar(
                    title = if (cloudIsRoot) disk.displayName else cloudPath,
                    onBack = if (cloudIsRoot) { { onBackToDiskList() } } else { { onGoUp() } },
                    onDevices = null,
                    onSearch = if (accountName != null) { { searchVisible = true } } else null,
                    onPaste = if (hasClipboard) { { onPaste() } } else null,
                )            }
            LibrarySortMenu(sort, sortAscending) { option ->
                if (option == sort) {
                    sortAscending = !sortAscending
                } else {
                    sort = option
                    sortAscending = true
                }
            }
        }
        BackHandler(enabled = searchVisible) {
            searchVisible = false
            searchQuery = ""
            onExitSearch()
            keyboardController?.hide()
        }
        BackHandler(enabled = !cloudIsRoot && !searchVisible) {
            onGoUp()
        }
        BackHandler(enabled = cloudIsRoot && !searchVisible) {
            onBackToDiskList()
        }
        LaunchedEffect(disk, accountName) {
            val account = accountName ?: return@LaunchedEffect
            if (cloudFiles.isEmpty() && !cloudLoading && !cloudSearchActive) {
                onOpenRoot()
            }
        }
        if (accountName == null) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("登录${disk.displayName}账号后即可浏览和播放云端文件。")
                Button(
                    onClick = { onGoLogin(disk) },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) {
                    Text("去账号中心登录")
                }
            }
            return
        }
        if (cloudLoading && cloudFiles.isEmpty()) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "正在加载…",
                        modifier = Modifier.padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            return
        }
        cloudError?.takeIf { cloudFiles.isEmpty() }?.let { error ->
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(error)
                Button(
                    onClick = onOpenRoot,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) {
                    Text("重试")
                }
            }
            return
        }
        val visibleFiles = remember(cloudFiles, cloudSearchActive, searchQuery, sort, sortAscending) {
            val query = searchQuery.trim()
            sortCloudFiles(
                when {
                    cloudSearchActive -> cloudFiles
                    query.isBlank() -> cloudFiles
                    else -> cloudFiles.filter { it.name.contains(query, ignoreCase = true) }
                },
                sort,
                sortAscending,
            )
        }
        val audioQueue = remember(visibleFiles) {
            visibleFiles.filter { !it.isDir && isPanAudioFileName(it.name) }
        }
        val audioIndexById = remember(audioQueue) {
            audioQueue.mapIndexed { index, item -> item.id to index }.toMap()
        }
        val videoQueue = remember(visibleFiles) {
            visibleFiles.filter { !it.isDir && isPanVideoFileName(it.name) }
        }
        val videoIndexById = remember(videoQueue) {
            videoQueue.mapIndexed { index, item -> item.id to index }.toMap()
        }
        // 滚动到底自动翻页（无限滚动），与百度网盘一致列出全部文件，无需手动加载更多。
        LaunchedEffect(visibleFiles.size, cloudHasMore, cloudLoading) {
            snapshotFlow { cloudGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisible ->
                    if (
                        cloudHasMore && !cloudLoading &&
                        lastVisible != null && lastVisible >= visibleFiles.size - 4
                    ) {
                        onLoadMore()
                    }
                }
        }
        if (visibleFiles.isEmpty()) {
            Text(
                if (searchQuery.isBlank()) "未找到文件。" else "未找到匹配的文件。",
                modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            LazyVerticalGrid(
                columns = gridCells(gridDensity),
                state = cloudGridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + miniPlayerInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                gridItems(visibleFiles, key = { it.id }) { file ->
                    CloudFileCard(
                        file = file,
                        onOpenDir = onOpenDir,
                        onPlay = { audioIndexById[file.id]?.let { index -> onPlay(audioQueue, index) } },
                        onPlayVideo = { videoIndexById[file.id]?.let { index -> onPlayVideo(videoQueue, index) } },
                        onDownload = onDownload,
                        onDownloadFolder = onDownloadFolder,
                        onToggleFavorite = { onToggleFavorite(file) },
                        favorited = isCloudFileFavorite(file),
                        onDelete = onDelete,
                        onCopy = onCopy,
                        onCut = onCut,
                    )
                }
            }
        }
        cloudError?.takeIf { cloudFiles.isNotEmpty() }?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun CloudDiskLandingScreen(
    state: MusicUiState,
    onOpenDisk: (CloudDisk) -> Unit,
    onGoLogin: (CloudDisk) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var loginPromptDisk by rememberSaveable { mutableStateOf<CloudDisk?>(null) }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "月播云", onBack = onBack, onDevices = null)
        Text(
            "选择要浏览的网盘",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        CloudDiskRow(
            disk = CloudDisk.BAIDU,
            loggedIn = state.panAccount != null,
            accountName = state.panAccount?.let { it.netdiskName.ifBlank { it.name } },
            onOpen = { onOpenDisk(CloudDisk.BAIDU) },
            onLogin = { loginPromptDisk = CloudDisk.BAIDU },
        )
        CloudDiskRow(
            disk = CloudDisk.QUARK,
            loggedIn = state.quarkAccount != null,
            accountName = state.quarkAccount?.name,
            onOpen = { onOpenDisk(CloudDisk.QUARK) },
            onLogin = { loginPromptDisk = CloudDisk.QUARK },
        )
        loginPromptDisk?.let { disk ->
            AlertDialog(
                onDismissRequest = { loginPromptDisk = null },
                title = { Text("尚未登录${disk.displayName}") },
                text = { Text("请先到账号中心登录${disk.displayName}，登录后即可在此浏览、播放和下载云端文件。") },
                confirmButton = {
                    Button(
                        onClick = { loginPromptDisk = null; onGoLogin(disk) },
                        modifier = Modifier.sizeIn(minHeight = 48.dp),
                    ) {
                        Text("去账号中心登录")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { loginPromptDisk = null }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

@Composable
private fun CloudDiskRow(
    disk: CloudDisk,
    loggedIn: Boolean,
    accountName: String?,
    onOpen: () -> Unit,
    onLogin: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = if (loggedIn) onOpen else onLogin)
            .clearAndSetSemantics {
                contentDescription = if (loggedIn) "${disk.displayName}，已登录" else "${disk.displayName}，未登录"
                onClick(label = if (loggedIn) "进入" else "去账号中心登录") {
                    if (loggedIn) onOpen() else onLogin()
                    true
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(disk.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (loggedIn) "已登录${accountName?.let { "：$it" }.orEmpty()}" else "未登录",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}


@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CloudFileCard(
    file: CloudFile,
    onOpenDir: (CloudFile) -> Unit,
    onPlay: (CloudFile) -> Unit,
    onPlayVideo: (CloudFile) -> Unit,
    onDownload: (CloudFile) -> Unit,
    onDownloadFolder: (CloudFile) -> Unit,
    /** 一键收藏/取消收藏（音频文件落入月播云默认收藏夹）。 */
    onToggleFavorite: (() -> Unit)? = null,
    favorited: Boolean = false,
    onDelete: (CloudFile) -> Unit,
    onCopy: (CloudFile) -> Unit,
    onCut: (CloudFile) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var restoreRowFocus by remember { mutableStateOf(false) }
    val rowFocusRequester = remember { FocusRequester() }
    val isAudio = !file.isDir && isPanAudioFileName(file.name)
    val isVideo = !file.isDir && isPanVideoFileName(file.name)
    val favoriteLabel = if (favorited) "取消收藏" else "收藏"
    val label = "${file.name}，${
        if (file.isDir) "文件夹"
        else if (isVideo) "视频文件，${formatFileSize(file.size)}"
        else formatFileSize(file.size)
    }${if (favorited) "，已收藏" else ""}"
    LaunchedEffect(deleteConfirmOpen, detailsOpen, restoreRowFocus) {
        if (!deleteConfirmOpen && !detailsOpen && restoreRowFocus) {
            rowFocusRequester.requestFocus()
            restoreRowFocus = false
        }
    }
    Box {
        Card(
            Modifier.fillMaxWidth().heightIn(min = 104.dp)
                .combinedClickable(
                    onClick = {
                        when {
                            file.isDir -> onOpenDir(file)
                            isVideo -> onPlayVideo(file)
                            isAudio -> onPlay(file)
                        }
                    },
                    onLongClick = { menuOpen = true },
                )
                .focusRequester(rowFocusRequester)
                .clearAndSetSemantics {
                    contentDescription = label
                    when {
                        file.isDir -> {
                            onClick(label = "打开文件夹") {
                                onOpenDir(file)
                                true
                            }
                        }
                        isVideo -> {
                            onClick(label = "播放视频") {
                                onPlayVideo(file)
                                true
                            }
                        }
                        isAudio -> {
                            onClick(label = "播放") {
                                onPlay(file)
                                true
                            }
                        }
                    }
                    customActions = buildList {
                        if (file.isDir) {
                            add(CustomAccessibilityAction("下载文件夹") { onDownloadFolder(file); true })
                        }
                        if (!file.isDir) {
                            add(CustomAccessibilityAction("下载到本地") { onDownload(file); true })
                        }
                        if (isAudio && onToggleFavorite != null) {
                            add(CustomAccessibilityAction(favoriteLabel) { onToggleFavorite(); true })
                        }
                        add(CustomAccessibilityAction("复制") { onCopy(file); true })
                        add(CustomAccessibilityAction("剪切") { onCut(file); true })
                        add(CustomAccessibilityAction("删除") { deleteConfirmOpen = true; true })
                        add(CustomAccessibilityAction("查看详情") { detailsOpen = true; true })
                    }
                },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when {
                        file.isDir -> Icons.Default.Folder
                        isVideo -> Icons.Default.Movie
                        isAudio -> Icons.Default.MusicNote
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                )
                Text(
                    file.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (file.isDir) "文件夹" else formatFileSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = { menuOpen = false; onCopy(file) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("剪切") },
                onClick = { menuOpen = false; onCut(file) },
                leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
            )
            if (file.isDir) {
                DropdownMenuItem(
                    text = { Text("下载文件夹") },
                    onClick = { menuOpen = false; onDownloadFolder(file) },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                )
            }
            if (!file.isDir) {
                DropdownMenuItem(
                    text = { Text("下载到本地") },
                    onClick = { menuOpen = false; onDownload(file) },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                )
            }
            if (isAudio && onToggleFavorite != null) {
                DropdownMenuItem(
                    text = { Text(favoriteLabel) },
                    onClick = { menuOpen = false; onToggleFavorite() },
                    leadingIcon = {
                        Icon(
                            if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                        )
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = { menuOpen = false; deleteConfirmOpen = true },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("详情") },
                onClick = { menuOpen = false; detailsOpen = true },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            )
        }
    }
    if (deleteConfirmOpen) {
        CloudDeleteConfirmDialog(
            file = file,
            onDismiss = { deleteConfirmOpen = false; restoreRowFocus = true },
            onConfirm = { deleteConfirmOpen = false; onDelete(file) },
        )
    }
    if (detailsOpen) {
        CloudFileDetailsDialog(
            file = file,
            onDismiss = { detailsOpen = false; restoreRowFocus = true },
        )
    }
}

@Composable
private fun CloudDeleteConfirmDialog(
    file: CloudFile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (file.isDir) "删除云端文件夹？" else "删除云端文件？") },
        text = { Text("“${file.name}”将从${file.provider.displayName}中永久删除，此操作无法撤销。") },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("删除") }
        },
    )
}

@Composable
private fun CloudFileDetailsDialog(
    file: CloudFile,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (file.isDir) "云端文件夹详情" else "云端文件详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("网盘：${file.provider.displayName}")
                Text("名称：${file.name}")
                if (file.provider == CloudDisk.BAIDU) {
                    Text("路径：${file.path}")
                }
                Text(if (file.isDir) "类型：文件夹" else "类型：文件")
                if (!file.isDir) {
                    Text("大小：${formatFileSize(file.size)}")
                }
                Text("修改时间：${formatCloudMtime(file.mtime / 1000)}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("关闭") }
        },
    )
}


private fun formatCloudMtime(seconds: Long): String =
    if (seconds <= 0) "未知"
    else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(seconds * 1000))

@Composable
private fun DownloadManagerScreen(
    tasks: List<DownloadTask>,
    panTasks: List<BaiduPanDownload>,
    quarkTasks: List<QuarkPanDownload>,
    speedEta: Map<String, DownloadSpeedEta>,
    wifiOnlyDownload: Boolean,
    onBack: () -> Unit,
    onWifiOnlyDownload: (Boolean) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemoveRecord: (String) -> Unit,
    onPanPause: (Long) -> Unit,
    onPanResume: (Long) -> Unit,
    onPanCancel: (Long) -> Unit,
    onPanRemove: (Long) -> Unit,
    onQuarkPause: (String) -> Unit,
    onQuarkResume: (String) -> Unit,
    onQuarkCancel: (String) -> Unit,
    onQuarkRemove: (String) -> Unit,
) {
    // 三个标签页的桶：下载中（含排队/暂停/合成中）、已下载、下载失败。
    val downloadingStatuses = setOf(
        DownloadTaskStatus.QUEUED,
        DownloadTaskStatus.DOWNLOADING,
        DownloadTaskStatus.WAITING_NETWORK,
        DownloadTaskStatus.FINALIZING,
        DownloadTaskStatus.PAUSED,
    )
    val downloading = tasks.filter { it.status in downloadingStatuses }
    val failed = tasks.filter { it.status == DownloadTaskStatus.FAILED }
    val completed = tasks.filter { it.status == DownloadTaskStatus.COMPLETED }
    val panDownloading = panTasks.filter { it.status in downloadingStatuses }
    val panFailed = panTasks.filter { it.status == DownloadTaskStatus.FAILED }
    val panCompleted = panTasks.filter { it.status == DownloadTaskStatus.COMPLETED }
    val quarkDownloading = quarkTasks.filter { it.status in downloadingStatuses }
    val quarkFailed = quarkTasks.filter { it.status == DownloadTaskStatus.FAILED }
    val quarkCompleted = quarkTasks.filter { it.status == DownloadTaskStatus.COMPLETED }
    var selectedTab by remember { mutableStateOf(0) }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "下载管理", onBack = onBack, onDevices = null)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).sizeIn(minHeight = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("仅 WiFi 下载", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = wifiOnlyDownload,
                onCheckedChange = onWifiOnlyDownload,
                modifier = Modifier.semantics {
                    contentDescription = "仅 WiFi 下载"
                    stateDescription = if (wifiOnlyDownload) "已开启" else "已关闭"
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onPauseAll,
                enabled = downloading.isNotEmpty() || panDownloading.isNotEmpty() ||
                    quarkDownloading.isNotEmpty(),
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
            ) { Text("全部暂停") }
            OutlinedButton(
                onClick = onResumeAll,
                enabled = failed.isNotEmpty() || panFailed.isNotEmpty() || quarkFailed.isNotEmpty() ||
                    downloading.any { it.status == DownloadTaskStatus.PAUSED } ||
                    panDownloading.any { it.status == DownloadTaskStatus.PAUSED } ||
                    quarkDownloading.any { it.status == DownloadTaskStatus.PAUSED },
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
            ) { Text("全部继续") }
        }
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            listOf("下载中", "已下载", "下载失败").forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    // 不以「标签页」角色播报：读屏按普通按钮朗读（含选中态）。
                    modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Button },
                    text = { Text(label) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fun androidx.compose.foundation.lazy.LazyListScope.emptyHintIf(show: Boolean, message: String) {
                if (show) item { Text(message, modifier = Modifier.padding(vertical = 24.dp)) }
            }

            fun androidx.compose.foundation.lazy.LazyListScope.onlineRows(list: List<DownloadTask>) {
                items(list, key = DownloadTask::id) { task ->
                    DownloadTaskRow(task, speedEta["online:${task.id}"], onPause, onResume, onRetry, onCancel, onRemoveRecord)
                }
            }

            fun androidx.compose.foundation.lazy.LazyListScope.panRows(list: List<BaiduPanDownload>) {
                list.forEach { task ->
                    item(key = "pan-${task.fsId}") {
                        PanDownloadRow(
                            name = task.name,
                            size = task.size,
                            downloadedBytes = task.downloadedBytes,
                            status = task.status,
                            error = task.error,
                            providerName = "百度网盘",
                            speedEta = speedEta["pan:${task.fsId}"],
                            onPause = { onPanPause(task.fsId) },
                            onResume = { onPanResume(task.fsId) },
                            onCancel = { onPanCancel(task.fsId) },
                            onRemove = { onPanRemove(task.fsId) },
                        )
                    }
                }
            }

            fun androidx.compose.foundation.lazy.LazyListScope.quarkRows(list: List<QuarkPanDownload>) {
                list.forEach { task ->
                    item(key = "quark-${task.fid}") {
                        PanDownloadRow(
                            name = task.name,
                            size = task.size,
                            downloadedBytes = task.downloadedBytes,
                            status = task.status,
                            error = task.error,
                            providerName = "夸克网盘",
                            speedEta = speedEta["quark:${task.fid}"],
                            onPause = { onQuarkPause(task.fid) },
                            onResume = { onQuarkResume(task.fid) },
                            onCancel = { onQuarkCancel(task.fid) },
                            onRemove = { onQuarkRemove(task.fid) },
                        )
                    }
                }
            }

            when (selectedTab) {
                1 -> {
                    emptyHintIf(
                        completed.isEmpty() && panCompleted.isEmpty() && quarkCompleted.isEmpty(),
                        "暂无已下载的任务",
                    )
                    onlineRows(completed)
                    panRows(panCompleted)
                    quarkRows(quarkCompleted)
                }
                2 -> {
                    emptyHintIf(
                        failed.isEmpty() && panFailed.isEmpty() && quarkFailed.isEmpty(),
                        "暂无下载失败的任务",
                    )
                    onlineRows(failed)
                    panRows(panFailed)
                    quarkRows(quarkFailed)
                }
                else -> {
                    emptyHintIf(
                        downloading.isEmpty() && panDownloading.isEmpty() && quarkDownloading.isEmpty(),
                        "暂无下载中的任务",
                    )
                    onlineRows(downloading)
                    panRows(panDownloading)
                    quarkRows(quarkDownloading)
                }
            }
        }
    }
}

/** 桶里是否存在可继续的暂停任务（「全部继续」按钮使能判定）。 */
private fun pausedIn(list: List<DownloadTask>): Boolean = list.any { it.status == DownloadTaskStatus.PAUSED }


@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    speedEta: DownloadSpeedEta?,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemoveRecord: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actions = buildList {
        when (task.status) {
            DownloadTaskStatus.QUEUED,
            DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.WAITING_NETWORK,
            DownloadTaskStatus.FINALIZING -> {
                add(Triple("暂停下载", Icons.Default.Pause) { onPause(task.id) })
                add(Triple("取消下载", Icons.Default.Close) { onCancel(task.id) })
            }
            DownloadTaskStatus.PAUSED -> {
                add(Triple("继续下载", Icons.Default.PlayArrow) { onResume(task.id) })
                add(Triple("取消下载", Icons.Default.Close) { onCancel(task.id) })
            }
            DownloadTaskStatus.FAILED -> {
                add(Triple("重试下载", Icons.Default.Refresh) { onRetry(task.id) })
                add(Triple("取消下载", Icons.Default.Close) { onCancel(task.id) })
            }
            DownloadTaskStatus.COMPLETED -> {
                add(Triple("移除下载记录", Icons.Default.Delete) { onRemoveRecord(task.id) })
            }
        }
    }
    val progress = task.totalBytes.takeIf { it > 0 }?.let { task.downloadedBytes.toFloat() / it }
    val status = downloadTaskStatusLabel(task.status)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .pointerInput(task.id) { detectTapGestures(onLongPress = { menuOpen = true }) }
            .clearAndSetSemantics {
                contentDescription = buildString {
                    append(task.track.title)
                    append("，")
                    append(task.track.artist.ifBlank { "未知艺术家" })
                    append("，")
                    append(status)
                    append("，")
                    if (task.totalBytes > 0) {
                        append(formatFileSize(task.downloadedBytes))
                        append(" / ")
                        append(formatFileSize(task.totalBytes))
                        progress?.let {
                            append("，")
                            append((it * 100).toInt())
                            append("%")
                        }
                    } else {
                        append("已下载 ")
                        append(formatFileSize(task.downloadedBytes))
                    }
                    task.error?.takeIf {
                        task.status == DownloadTaskStatus.FAILED || task.status == DownloadTaskStatus.WAITING_NETWORK
                    }?.let {
                        append("，")
                        append(it)
                    }
                }
                customActions = actions.map { (label, _, run) ->
                    CustomAccessibilityAction(label) { run(); true }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.track.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(task.track.artist.ifBlank { "未知艺术家" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (task.totalBytes > 0) "$status，${formatFileSize(task.downloadedBytes)} / ${formatFileSize(task.totalBytes)}"
                else "$status，已下载 ${formatFileSize(task.downloadedBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            progress?.let {
                LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            if (task.status == DownloadTaskStatus.DOWNLOADING) {
                speedEta?.let { eta ->
                    Text(
                        buildString {
                            append(formatDownloadSpeed(eta.speedBps))
                            if (eta.etaMs > 0) append(" · ${formatRemainingTime(eta.etaMs)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            task.error?.takeIf { task.status == DownloadTaskStatus.FAILED || task.status == DownloadTaskStatus.WAITING_NETWORK }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            actions.forEach { (label, icon, run) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { menuOpen = false; run() },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                )
            }
        }
    }
}

private fun downloadTaskStatusLabel(status: DownloadTaskStatus): String = when (status) {
    DownloadTaskStatus.QUEUED -> "等待下载"
    DownloadTaskStatus.DOWNLOADING -> "下载中"
    DownloadTaskStatus.WAITING_NETWORK -> "等待网络"
    DownloadTaskStatus.PAUSED -> "已暂停"
    DownloadTaskStatus.FAILED -> "下载失败"
    DownloadTaskStatus.COMPLETED -> "已完成"
    DownloadTaskStatus.FINALIZING -> "合成中"
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PlaylistScreen(
    state: MusicUiState,
    onPlayQueueItem: (Int) -> Unit,
    onPlayHistoryTrack: (NativeTrack) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onViewLyrics: (Int) -> Unit,
    onSaveQueueAsPlaylist: (String) -> Unit,
    onPlayPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onClearPlaybackHistory: () -> Unit,
) {
    var createDialogOpen by rememberSaveable { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var queueMenuIndex by remember { mutableStateOf<Int?>(null) }
    val tracksById = remember(state.tracks) { state.tracks.associateBy { it.id } }
    val recentTracks = remember(state.playbackHistory, tracksById) {
        state.playbackHistory.mapNotNull { entry -> tracksById[entry.trackId]?.let { entry to it } }
    }
    val frequentTracks = remember(recentTracks) { recentTracks.sortedByDescending { it.first.playCount }.take(10) }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "播放列表", onBack = null, onDevices = null)
        StatusMessage(state.status)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeading("当前队列（${state.queue.size} 首）") }
            item {
                OutlinedButton(
                    onClick = { createDialogOpen = true },
                    enabled = state.queue.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) { Text("将当前队列保存为播放列表") }
            }
            if (state.queue.isEmpty()) {
                item { Text("当前队列为空。") }
            } else {
                items(state.queue.withIndex().toList(), key = { "queue:${it.index}:${it.value.id}" }) { item ->
                    val isCurrent = item.index == state.queueIndex
                    Box {
                        Card(
                            Modifier.fillMaxWidth().combinedClickable(
                                onClick = { onPlayQueueItem(item.index) },
                                onLongClick = { queueMenuIndex = item.index },
                            ),
                        ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { onPlayQueueItem(item.index) },
                                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).clearAndSetSemantics {
                                    contentDescription = "${item.index + 1}. ${item.value.title}${if (isCurrent) "，正在播放" else ""}"
                                    onClick(label = "播放") { onPlayQueueItem(item.index); true }
                                    customActions = buildList {
                                        add(CustomAccessibilityAction("查看歌词") { onViewLyrics(item.index); true })
                                        if (item.index > 0) add(CustomAccessibilityAction("上移") { onMoveQueueItem(item.index, -1); true })
                                        if (item.index < state.queue.lastIndex) add(CustomAccessibilityAction("下移") { onMoveQueueItem(item.index, 1); true })
                                        if (!isCurrent) add(CustomAccessibilityAction("移除") { onRemoveQueueItem(item.index); true })
                                    }
                                },
                            ) {
                                Text(
                                    "${item.index + 1}. ${item.value.title}${if (isCurrent) "（正在播放）" else ""}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { onMoveQueueItem(item.index, -1) },
                                enabled = item.index > 0,
                                modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() },
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null)
                            }
                            IconButton(
                                onClick = { onMoveQueueItem(item.index, 1) },
                                enabled = item.index < state.queue.lastIndex,
                                modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() },
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null)
                            }
                            IconButton(
                                onClick = { onRemoveQueueItem(item.index) },
                                enabled = !isCurrent,
                                modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = queueMenuIndex == item.index,
                        onDismissRequest = { queueMenuIndex = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text("查看歌词") },
                            onClick = { queueMenuIndex = null; onViewLyrics(item.index) },
                            leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        )
                    }
                    }
                }
            }

            item { SectionHeading("已保存的播放列表") }
            if (state.playlists.isEmpty()) {
                item { Text("保存当前队列后会显示在这里。") }
            } else {
                items(state.playlists, key = { it.id }) { playlist ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { onPlayPlaylist(playlist.id) }, modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) {
                                Text("${playlist.name}（${playlist.trackIds.size + playlist.externalUris.size} 首）", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onDeletePlaylist(playlist.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除 ${playlist.name}")
                            }
                        }
                    }
                }
            }

            item { SectionHeading("最近播放") }
            if (recentTracks.isEmpty()) item { Text("开始播放歌曲后会记录在这里。") }
            else items(recentTracks.take(20), key = { "recent:${it.second.id}" }) { (_, track) ->
                TextButton(onClick = { onPlayHistoryTrack(track) }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text("${track.title} · ${track.artist}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item { SectionHeading("最常播放") }
            if (frequentTracks.isEmpty()) item { Text("开始播放歌曲后会统计在这里。") }
            else items(frequentTracks, key = { "frequent:${it.second.id}" }) { (entry, track) ->
                TextButton(onClick = { onPlayHistoryTrack(track) }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text("${track.title} · 已播放 ${entry.playCount} 次", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.playbackHistory.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = onClearPlaybackHistory, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                        Text("清除播放历史")
                    }
                }
            }
        }
    }
    if (createDialogOpen) {
        AlertDialog(
            onDismissRequest = { createDialogOpen = false },
            title = { Text("保存播放列表") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("播放列表名称") },
                    singleLine = true,
                )
            },
            dismissButton = { TextButton(onClick = { createDialogOpen = false }) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    onSaveQueueAsPlaylist(playlistName)
                    playlistName = ""
                    createDialogOpen = false
                }, enabled = playlistName.isNotBlank()) { Text("保存") }
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onThemeMode: (AppThemeMode) -> Unit,
    onGridDensity: (GridDensity) -> Unit,
    onFadeTransitions: (Boolean) -> Unit,
    onAutoMatchLocalLyrics: (Boolean) -> Unit,
    onAutoOpenPlayingPage: (Boolean) -> Unit,
    onSkipSilenceEnabled: (Boolean) -> Unit,
    onSilenceSkipMode: (SilenceSkipMode) -> Unit,
    onSilenceSkipThresholdMs: (Long) -> Unit,
    onPlaybackQuality: (StreamQualityOption) -> Unit,
    onDownloadQuality: (StreamQualityOption) -> Unit,
    onSpeed: (Double) -> Unit,
    onQueueMode: (NativeQueueMode) -> Unit,
    onExportLogs: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onToggleLogExportClearBefore: () -> Unit,
    /** 当前打开的设置子页：提升到外层，
     *  让页面 key 与窗口标题（screenPaneTitle）都能感知——子页各自是独立窗口、读屏播报页名。 */
    subpage: String?,
    onSubpageChange: (String?) -> Unit,
) {
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var agreementOpen by rememberSaveable { mutableStateOf(false) }
    val agreementEntryFocus = remember { FocusRequester() }
    val accessibilityView = LocalView.current
    if (agreementOpen) {
        UserAgreementDialog(onDismiss = {
            agreementOpen = false
            // 关闭协议后焦点回到关于页入口；请求失败时降级为系统播报。
            runCatching { agreementEntryFocus.requestFocus() }
                .onFailure { accessibilityView.announceForAccessibility("已关闭用户协议与免责声明") }
        })
    }
    if (subpage == SETTINGS_SUBPAGE_SILENCE) {
        SilenceSkipSettingsScreen(
            state = state,
            onBack = { onSubpageChange(null) },
            onEnabled = onSkipSilenceEnabled,
            onMode = onSilenceSkipMode,
            onThresholdMs = onSilenceSkipThresholdMs,
        )
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "设置", onBack = onBack, onDevices = null)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        item { SectionHeading("外观") }
        item { ThemeModeMenu(state.themeMode, onThemeMode) }
        item { GridDensityMenu(state.gridDensity, onGridDensity) }
        item { SectionHeading("播放") }
        item { PlaybackSpeedMenu(state.playbackSpeed, onSpeed, "播放速度（当前会话）") }
        item { QueueModeMenu(state.queueMode, onQueueMode, "默认播放模式") }
        item {
            OnlineQualitySettingsMenu(
                state = state,
                onPlaybackQuality = onPlaybackQuality,
                onDownloadQuality = onDownloadQuality,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .clickable { onFadeTransitions(!state.fadeTransitions) }
                    .clearAndSetSemantics {
                        contentDescription = "曲目切换淡入淡出"
                        stateDescription = if (state.fadeTransitions) "已开启" else "已关闭"
                        onClick { onFadeTransitions(!state.fadeTransitions); true }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("曲目切换淡入淡出", style = MaterialTheme.typography.titleSmall)
                    Text("切歌时平滑过渡", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.fadeTransitions,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .clickable { onAutoMatchLocalLyrics(!state.autoMatchLocalLyrics) }
                    .clearAndSetSemantics {
                        contentDescription = "自动匹配歌词"
                        stateDescription = if (state.autoMatchLocalLyrics) "已开启" else "已关闭"
                        onClick { onAutoMatchLocalLyrics(!state.autoMatchLocalLyrics); true }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动在线匹配本地歌词", style = MaterialTheme.typography.titleSmall)
                    Text("联网获取并保存歌词", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.autoMatchLocalLyrics,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .clickable { onAutoOpenPlayingPage(!state.autoOpenPlayingPage) }
                    .clearAndSetSemantics {
                        contentDescription = "播放后打开播放页"
                        stateDescription = if (state.autoOpenPlayingPage) "已开启" else "已关闭"
                        onClick { onAutoOpenPlayingPage(!state.autoOpenPlayingPage); true }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("播放时进入播放页", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.autoOpenPlayingPage) "播放后打开播放页"
                        else "播放后留在当前页",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.autoOpenPlayingPage,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .clickable { onSubpageChange(SETTINGS_SUBPAGE_SILENCE) }
                    .clearAndSetSemantics {
                        contentDescription = "跳过静音"
                        stateDescription = if (state.skipSilenceEnabled) "已开启" else "已关闭"
                        onClick(label = "打开设置") { onSubpageChange(SETTINGS_SUBPAGE_SILENCE); true }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("跳过静音", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.skipSilenceEnabled) "${silenceSkipModeLabel(state.silenceSkipMode)} ${state.silenceSkipThresholdMs} ms"
                        else "已关闭",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { SectionHeading("关于") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .clickable { aboutOpen = true }
                    .clearAndSetSemantics {
                        contentDescription = "关于月播"
                        onClick { aboutOpen = true; true }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("关于月播", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    }
    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text("月播", modifier = Modifier.semantics { heading() }) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text("版本 " + BuildConfig.VERSION_NAME)
                    OutlinedButton(
                        onClick = { agreementOpen = true },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).focusRequester(agreementEntryFocus),
                    ) { Text("用户协议与免责声明") }
                    OutlinedButton(
                        onClick = { onExportLogs(state.logExportClearBefore) },
                        enabled = !state.logExporting,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) { Text(if (state.logExporting) "正在导出日志" else "导出日志") }
                    OutlinedButton(
                        onClick = onClearLogs,
                        enabled = !state.logClearing && !state.logExporting,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) { Text(if (state.logClearing) "正在清空日志" else "清空日志") }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = state.logExportClearBefore,
                            onCheckedChange = { onToggleLogExportClearBefore() },
                            enabled = !state.logExporting,
                        )
                        Text("导出前清空旧日志", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("应用和系统日志用于问题诊断。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun VideoPlayerScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCastVolume: (Float) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onSpeed: (Double) -> Unit,
    onStartHoldSpeed: () -> Unit,
    onEndHoldSpeed: () -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onEnterBackground: () -> Unit,
    onResumeFromBackground: () -> Unit,
    onAudioOnly: (Boolean) -> Unit,
    onPickSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit,
    onToggleSubtitleTts: () -> Unit,
    onSetSubtitleVisible: (Boolean) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onSelectSecondarySubtitleTrack: (String?) -> Unit,
    onLoadAiSubtitle: () -> Unit,
    onSpeakCurrentSubtitle: () -> Unit,
    onPreviousSubtitle: () -> Unit,
    onReplaySubtitle: () -> Unit,
    onNextSubtitle: () -> Unit,
    onSetSubtitleOffset: (Long) -> Unit,
    onSetSubtitleScale: (Float) -> Unit,
    onSelectSubtitleCue: (String) -> Unit,
    onScanCast: () -> Unit,
    onSelectDlna: (DlnaDevice) -> Unit,
    onReturnToLocal: () -> Unit,
) {
    val context = LocalContext.current
    // 播放器页的触感锚点：顶栏集群（投送/只播放声音/全屏/更多）与选集按钮统一短促轻点。
    val hapticView = LocalView.current
    val app = context.applicationContext as MusicApplication
    val player = app.videoPlayer.player
    val ffmpegPlayer = app.ffmpegVideoPlayer
    val systemAccessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager
    val talkBackEnabled = systemAccessibilityManager?.isEnabled == true
    var controlsVisible by remember { mutableStateOf(true) }
    // 全屏 = 设备真实处于横屏：按钮只负责锁定方向，物理旋转同样进入/退出沉浸式布局。
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var castDialogOpen by remember { mutableStateOf(false) }
    var episodePanelOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var subtitleSettingsOpen by remember { mutableStateOf(false) }
    var speedSheetOpen by remember { mutableStateOf(false) }
    var sleepTimerSheetOpen by remember { mutableStateOf(false) }
    var qualitySheetOpen by remember { mutableStateOf(false) }
    // 手势层反馈：中央指示文字与滑动快进的预览进度（松手才真正 seek）。
    var gestureIndicator by remember { mutableStateOf<String?>(null) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    val activity = context as? Activity
    val exitFullscreen: () -> Unit = {
        episodePanelOpen = false
        // 退出全屏先锁竖屏，方向稳定后由下方 LaunchedEffect 交还系统传感器。
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    val clearGestureFeedback: () -> Unit = {
        gestureIndicator = null
        seekPreviewMs = null
    }
    // 倍速仅 ExoPlayer 引擎支持：FFmpeg 兜底与投送中禁用入口。
    val speedAdjustable = !state.videoUsingFfmpeg && !state.videoCasting
    val currentVideoUri = state.currentVideo?.uri
    // 直播：B 站直播链接，或直播站（电视直播）解析出的直播流（state.videoLive）
    val isLive = isLiveVideoUri(currentVideoUri) || state.videoLive
    val progressAvailable = !isLive && videoHasSeekableDuration(currentVideoUri, state.videoDurationMs)
    val gestureSeekAllowed = progressAvailable && !state.videoCasting && !isLive
    val gestureVerticalAllowed = isLandscape && !state.videoCasting
    val gestureHoldSpeedAllowed = speedAdjustable && !isLive
    val hasPreviousItem = skipStepAvailable(
        step = -1,
        isVideo = true,
        audioIndex = state.queueIndex,
        audioSize = state.queue.size,
        videoIndex = state.videoIndex,
        videoSize = state.videoQueue.size,
    )
    val hasNextEpisode = skipStepAvailable(
        step = 1,
        isVideo = true,
        audioIndex = state.queueIndex,
        audioSize = state.queue.size,
        videoIndex = state.videoIndex,
        videoSize = state.videoQueue.size,
    )
    val previousItemLabel = skipStepLabel(-1, isVideo = true, isLive = false)
    val nextItemLabel = skipStepLabel(1, isVideo = true, isLive = false)
    val showEpisodes = shouldShowVideoEpisodes(state.videoQueue.size)
    val playbackStatus = when {
        state.videoError != null -> "播放失败：${state.videoError}"
        state.videoCasting -> "正在投送到 ${state.videoCastTarget}"
        state.videoLoading -> "正在加载"
        state.videoBuffering -> "正在缓冲"
        isLive && state.videoPlaying -> "直播 · 正在播放"
        isLive -> "直播 · 已暂停"
        state.videoPlaying && !progressAvailable -> "正在播放 · 时长未知"
        state.videoPlaying -> "正在播放"
        !progressAvailable -> "已暂停 · 时长未知"
        else -> "已暂停"
    }
    val episodeTriggerFocus = remember { FocusRequester() }
    val moreMenuFocusRequester = remember { FocusRequester() }
    val closeEpisodePanel: () -> Unit = {
        episodePanelOpen = false
        runCatching { episodeTriggerFocus.requestFocus() }
    }
    val chromeColor = if (isLandscape) Color.White else MaterialTheme.colorScheme.onSurface
    DisposableEffect(isLandscape) {
        val window = activity?.window
        if (window != null && isLandscape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 画面延伸进刘海区，避免横屏顶栏与画面之间留出黑条。
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            // 沉浸式全屏：隐藏状态栏与导航栏，支持从边缘轻扫临时显示。
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                }
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // 视频播放期间屏幕常亮；暂停或投送到远端设备时交还系统熄屏。
    DisposableEffect(activity, state.videoPlaying, state.videoCasting) {
        val window = activity?.window
        if (window != null && state.videoPlaying && !state.videoCasting) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // 离开播放页时解除方向锁定。
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // 退出全屏锁定竖屏后，方向一旦稳定即交还系统控制。
    LaunchedEffect(isLandscape) {
        if (!isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler(enabled = isLandscape, onBack = exitFullscreen)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, onEnterBackground, onResumeFromBackground) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> onEnterBackground()
                Lifecycle.Event.ON_RESUME -> onResumeFromBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controlsVisible, talkBackEnabled, state.videoPlaying, isLandscape, episodePanelOpen) {
        if (isLandscape && controlsVisible && !talkBackEnabled && state.videoPlaying && !episodePanelOpen) {
            delay(3_000)
            controlsVisible = false
        }
    }
    // 手势指示文字随最后一次更新 1.5 秒后自动消失（对齐参考实现的临时状态提示）。
    LaunchedEffect(gestureIndicator) {
        if (gestureIndicator != null) {
            delay(1_500)
            gestureIndicator = null
        }
    }
    // 手势调过亮度则离开播放页时交还系统默认亮度。
    var brightnessAdjusted by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        onDispose {
            if (brightnessAdjusted && activity != null) {
                activity.window.attributes = activity.window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
    // 剧集点击即播：分集尚未就绪时显示加载占位（分集清单由外部入队，最终版无服务端剧集解析）。
    if (state.currentVideo == null) {
        Box(
            Modifier.fillMaxSize()
                .background(if (isLandscape) Color.Black else MaterialTheme.colorScheme.background)
                .semantics { paneTitle = "视频播放" },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            ) {
                Text("视频播放", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.sizeIn(minWidth = 96.dp, minHeight = 48.dp),
                ) {
                    Text("返回")
                }
            }
        }
        return
    }
    val subtitleAccessibilityText = if (state.videoSubtitleVisible) videoSubtitleAccessibilityText(state) else ""
    val videoSurfaceModifier = Modifier
        .then(
            rememberVideoPlayerGestures(
                activity = activity,
                seekAllowed = gestureSeekAllowed,
                verticalAllowed = gestureVerticalAllowed,
                holdSpeedAllowed = gestureHoldSpeedAllowed,
                startPositionMs = { state.videoPositionMs },
                durationMs = { state.videoDurationMs },
                onToggleControls = {
                    controlsVisible = !controlsVisible
                    clearGestureFeedback()
                },
                onSeekPreview = { target -> seekPreviewMs = target },
                onSeekCommit = { target ->
                    onSeek(target)
                    seekPreviewMs = null
                },
                onIndicator = { text -> gestureIndicator = text },
                onIndicatorEnd = { gestureIndicator = null },
                onBrightnessAdjusted = { brightnessAdjusted = true },
                onStartHoldSpeed = onStartHoldSpeed,
                onEndHoldSpeed = onEndHoldSpeed,
            ),
        )
        .clearAndSetSemantics {
            contentDescription = "播放器"
            stateDescription = if (subtitleAccessibilityText.isBlank()) {
                playbackStatus
            } else {
                "$playbackStatus，$subtitleAccessibilityText"
            }
            onClick(label = if (controlsVisible) "隐藏媒体控制" else "显示媒体控制") {
                controlsVisible = !controlsVisible
                clearGestureFeedback()
                true
            }
            // 极简媒体控制（仅暂停+下一集按钮）的无障碍等价操作：TalkBack 可直达全部传输功能。
            customActions = buildList {
                add(CustomAccessibilityAction(if (state.videoPlaying || state.videoBuffering) "暂停" else "播放") { onToggle(); true })
                if (gestureSeekAllowed) {
                    add(CustomAccessibilityAction("快退 10 秒") { onSeek((state.videoPositionMs - 10_000).coerceAtLeast(0)); true })
                    add(CustomAccessibilityAction("快进 10 秒") { onSeek(state.videoPositionMs + 10_000); true })
                }
                if (hasPreviousItem) {
                    add(CustomAccessibilityAction(previousItemLabel) { onPrevious(); true })
                }
                if (hasNextEpisode) {
                    add(CustomAccessibilityAction(nextItemLabel) { onNext(); true })
                }
            }
        }
    if (isLandscape) {
        // 横屏：画面铺满全屏，控制层为顶部/底部渐变叠层（B 站式布局：右上角操作集群 + 极简媒体控制）。
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .semantics { paneTitle = "视频播放（全屏）" },
        ) {
            Box(Modifier.fillMaxSize().then(videoSurfaceModifier)) {
                VideoSurfaceStack(state, player, ffmpegPlayer, Modifier.fillMaxSize(), subtitleAccessibilityText)
            }
            VideoGestureIndicator(gestureIndicator, Modifier.align(Alignment.Center))
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Surface(color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                            .heightIn(min = 56.dp).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 标签直接放在可点击节点上：个别厂商读屏不朗读 IconButton 内层 Icon 的合并语义。
                        IconButton(
                            onClick = exitFullscreen,
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "退出全屏" },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                state.currentVideo?.title ?: "视频播放",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                playbackStatus,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.76f),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        // 右上角操作集群：投送 / 只播放声音 / 全屏 / 更多（触感统一短促轻点）。
                        IconButton(onClick = { MediaHaptics.tick(hapticView); castDialogOpen = true }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.Cast,
                                contentDescription = if (state.videoCasting) "正在投送到 ${state.videoCastTarget}" else "投送到设备",
                                tint = if (state.videoCasting) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
                        IconButton(
                            onClick = { MediaHaptics.tick(hapticView); onAudioOnly(!state.videoAudioOnly) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Headset,
                                contentDescription = if (state.videoAudioOnly) "恢复视频画面" else "只播放声音",
                                tint = if (state.videoAudioOnly) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
                        IconButton(onClick = { MediaHaptics.tick(hapticView); exitFullscreen() }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏")
                        }
                        VideoOptionsMenu(
                            state = state,
                            expanded = optionsOpen,
                            onExpandedChange = { optionsOpen = it },
                            onAudioOnly = onAudioOnly,
                            onPickSubtitle = onPickSubtitle,
                            onClearSubtitle = onClearSubtitle,
                            onToggleSubtitleTts = onToggleSubtitleTts,
                            onOpenSubtitleSettings = { subtitleSettingsOpen = true },
                            onOpenSpeed = { speedSheetOpen = true },
                            onOpenSleepTimer = { sleepTimerSheetOpen = true },
                            onOpenQuality = { qualitySheetOpen = true },
                            triggerFocusRequester = moreMenuFocusRequester,
                            contentColor = Color.White,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Surface(color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White) {
                    Column(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // 带时间戳的进度条：滑动快进预览时时间与滑条同步显示目标位置。
                        if (progressAvailable) {
                            VideoSeekSlider(
                                positionMs = seekPreviewMs ?: state.videoPositionMs,
                                durationMs = state.videoDurationMs,
                                onSeek = onSeek,
                                contentColor = Color.White,
                            )
                        }
                        if (state.videoCasting) {
                            VideoCastVolumeRow(volume = state.volume, onCommit = onCastVolume, contentColor = Color.White)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TransportIconButton(
                                icon = if (state.videoPlaying || state.videoBuffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                                label = if (state.videoPlaying || state.videoBuffering) "暂停" else "播放",
                                onClick = onToggle,
                                enabled = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            TransportIconButton(
                                icon = Icons.Default.SkipPrevious,
                                label = previousItemLabel,
                                onClick = onPrevious,
                                enabled = hasPreviousItem,
                            )
                            Spacer(Modifier.width(8.dp))
                            TransportIconButton(
                                icon = Icons.Default.SkipNext,
                                label = nextItemLabel,
                                onClick = onNext,
                                enabled = hasNextEpisode,
                            )
                            Spacer(Modifier.weight(1f))
                            if (showEpisodes) {
                                TextButton(
                                    onClick = { MediaHaptics.tick(hapticView); episodePanelOpen = true },
                                    enabled = true,
                                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(episodeTriggerFocus),
                                ) {
                                    Text("选集 ${state.videoQueue.size}", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = episodePanelOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "关闭选集",
                        ) { closeEpisodePanel() },
                )
            }
            AnimatedVisibility(
                visible = episodePanelOpen,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
                modifier = Modifier.matchParentSize(),
            ) {
                VideoEpisodePanel(
                    state = state,
                    onSelectEpisode = { index ->
                        onSelectEpisode(index)
                        closeEpisodePanel()
                    },
                    onClose = closeEpisodePanel,
                )
            }
        }
    } else {
        // 竖屏：画面在上（16:9，带手势层与右上角操作集群），下方常显控制面板（TalkBack 可随时浏览全部控制）。
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .semantics { paneTitle = "视频播放" },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                // 手势与“播放器”语义只挂在画面层，控制层作为同级节点保留各自语义，
                // 否则 clearAndSetSemantics 会吞掉返回、投送、更多、全屏等控件。
                Box(Modifier.fillMaxSize().then(videoSurfaceModifier)) {
                    VideoSurfaceStack(state, player, ffmpegPlayer, Modifier.fillMaxSize(), subtitleAccessibilityText)
                }
                VideoGestureIndicator(gestureIndicator, Modifier.align(Alignment.Center))
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(160)),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Black.copy(alpha = 0.64f),
                        contentColor = Color.White,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 标签直接放在可点击节点上：个别厂商读屏不朗读 IconButton 内层 Icon 的合并语义。
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { contentDescription = "返回" },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                            Text(
                                state.currentVideo?.title ?: "视频播放",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).clearAndSetSemantics { },
                            )
                            // 右上角操作集群：投送 / 只播放声音 / 全屏 / 更多（触感统一短促轻点）。
                            IconButton(onClick = { MediaHaptics.tick(hapticView); castDialogOpen = true }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Default.Cast,
                                    contentDescription = if (state.videoCasting) "正在投送到 ${state.videoCastTarget}" else "投送到设备",
                                    tint = if (state.videoCasting) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            }
                            IconButton(
                                onClick = { MediaHaptics.tick(hapticView); onAudioOnly(!state.videoAudioOnly) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.Headset,
                                    contentDescription = if (state.videoAudioOnly) "恢复视频画面" else "只播放声音",
                                    tint = if (state.videoAudioOnly) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            }
                            IconButton(
                                onClick = {
                                    MediaHaptics.tick(hapticView)
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "进入全屏")
                            }
                            VideoOptionsMenu(
                                state = state,
                                expanded = optionsOpen,
                                onExpandedChange = { optionsOpen = it },
                                onAudioOnly = onAudioOnly,
                                onPickSubtitle = onPickSubtitle,
                                onClearSubtitle = onClearSubtitle,
                                onToggleSubtitleTts = onToggleSubtitleTts,
                                onOpenSubtitleSettings = { subtitleSettingsOpen = true },
                                onOpenSpeed = { speedSheetOpen = true },
                                onOpenSleepTimer = { sleepTimerSheetOpen = true },
                                onOpenQuality = { qualitySheetOpen = true },
                                triggerFocusRequester = moreMenuFocusRequester,
                                contentColor = Color.White,
                            )
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            state.currentVideo?.title ?: "视频播放",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            playbackStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surface, contentColor = chromeColor) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (progressAvailable) {
                                VideoSeekSlider(
                                    positionMs = seekPreviewMs ?: state.videoPositionMs,
                                    durationMs = state.videoDurationMs,
                                    onSeek = onSeek,
                                    contentColor = chromeColor,
                                )
                            }
                            if (state.videoCasting) {
                                VideoCastVolumeRow(volume = state.volume, onCommit = onCastVolume, contentColor = chromeColor)
                            }
                            // 极简媒体控制：只保留播放/暂停与下一项（上一项与快进快退在播放器语义的
                            // 自定义操作里，对 TalkBack 等价可达）。
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TransportIconButton(
                                    icon = if (state.videoPlaying || state.videoBuffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    label = if (state.videoPlaying || state.videoBuffering) "暂停" else "播放",
                                    onClick = onToggle,
                                    enabled = true,
                                )
                                Spacer(Modifier.width(16.dp))
                                TransportIconButton(
                                    icon = Icons.Default.SkipNext,
                                    label = nextItemLabel,
                                    onClick = onNext,
                                    enabled = hasNextEpisode,
                                )
                            }
                        }
                    }
                }
                if (showEpisodes) {
                    item {
                        TextButton(
                            onClick = { MediaHaptics.tick(hapticView); episodePanelOpen = true },
                            enabled = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .heightIn(min = 48.dp)
                                .focusRequester(episodeTriggerFocus),
                        ) {
                            Text("选集（共 ${state.videoQueue.size} 集）")
                        }
                    }
                }
            }
        }
    }
    if (!isLandscape && episodePanelOpen) {
        VideoEpisodeSelectionSheet(
            state = state,
            returnFocusRequester = episodeTriggerFocus,
            onSelectEpisode = { index ->
                episodePanelOpen = false
                onSelectEpisode(index)
            },
            onDismiss = closeEpisodePanel,
        )
    }
    if (castDialogOpen) {
        VideoCastDialog(
            state = state,
            onScan = onScanCast,
            onSelectDlna = onSelectDlna,
            onReturnToLocal = onReturnToLocal,
            onDismiss = { castDialogOpen = false },
        )
    }
    if (subtitleSettingsOpen) {
        VideoSubtitleSettingsSheet(
            state = state,
            onVisibleChange = onSetSubtitleVisible,
            onPickSubtitle = {
                subtitleSettingsOpen = false
                onPickSubtitle()
            },
            onClearSubtitle = onClearSubtitle,
            onSelectTrack = onSelectSubtitleTrack,
            onSelectSecondaryTrack = onSelectSecondarySubtitleTrack,
            onLoadAiSubtitle = onLoadAiSubtitle,
            onToggleTts = onToggleSubtitleTts,
            onSpeakCurrentSubtitle = onSpeakCurrentSubtitle,
            onPreviousSubtitle = onPreviousSubtitle,
            onReplaySubtitle = onReplaySubtitle,
            onNextSubtitle = onNextSubtitle,
            onSetOffset = onSetSubtitleOffset,
            onSetScale = onSetSubtitleScale,
            onSelectCue = onSelectSubtitleCue,
            returnFocusRequester = moreMenuFocusRequester,
            onDismiss = { subtitleSettingsOpen = false },
        )
    }
    if (speedSheetOpen) {
        VideoSpeedSheet(
            speed = state.videoSpeed,
            enabled = speedAdjustable,
            returnFocusRequester = moreMenuFocusRequester,
            onSelect = { option ->
                speedSheetOpen = false
                onSpeed(option)
            },
            onDismiss = {
                speedSheetOpen = false
                runCatching { moreMenuFocusRequester.requestFocus() }
            },
        )
    }
    if (sleepTimerSheetOpen) {
        VideoSleepTimerSheet(
            totalMs = state.videoSleepTimerTotalMs,
            remainingMs = state.videoSleepTimerRemainingMs,
            returnFocusRequester = moreMenuFocusRequester,
            onSelect = { minutes ->
                sleepTimerSheetOpen = false
                onStartSleepTimer(minutes)
            },
            onCancel = {
                sleepTimerSheetOpen = false
                onCancelSleepTimer()
            },
            onDismiss = {
                sleepTimerSheetOpen = false
                runCatching { moreMenuFocusRequester.requestFocus() }
            },
        )
    }
    if (qualitySheetOpen) {
        // 最终版无服务端多码率解析，清晰度列表恒为空：保留弹窗壳以提示「暂无其他码率」。
        VideoQualitySheet(
            options = state.videoQualityOptions,
            selected = state.videoQualityCode,
            switching = state.videoQualitySwitching,
            returnFocusRequester = moreMenuFocusRequester,
            onSelect = { },
            onDismiss = {
                qualitySheetOpen = false
                runCatching { moreMenuFocusRequester.requestFocus() }
            },
        )
    }
}

/** 当前生效的主/副字幕，合并成一句给画面层的 TalkBack 节点朗读。 */
private fun videoSubtitleAccessibilityText(state: MusicUiState): String {
    val subtitlePosition = state.videoPositionMs + state.videoSubtitleOffsetMs
    val primarySubtitle = state.videoSubtitleCues.getOrNull(
        activeVideoSubtitleIndex(state.videoSubtitleCues, subtitlePosition),
    )
    val secondarySubtitle = state.videoSecondarySubtitleCues.getOrNull(
        activeVideoSubtitleIndex(state.videoSecondarySubtitleCues, subtitlePosition),
    )
    return buildList {
        primarySubtitle?.text?.takeIf(String::isNotBlank)?.let { add("字幕：$it") }
        secondarySubtitle?.text?.takeIf(String::isNotBlank)?.let { add("副字幕：$it") }
    }.joinToString("；")
}

/** 视频画面叠层：渲染 Surface、加载指示、投送与错误提示（播放控制由调用方排布）。 */
@Composable
private fun VideoSurfaceStack(
    state: MusicUiState,
    player: Player,
    ffmpegPlayer: FfmpegFallbackPlayer,
    modifier: Modifier = Modifier,
    subtitleAccessibilityText: String = "",
) {
    val subtitlePosition = state.videoPositionMs + state.videoSubtitleOffsetMs
    val primarySubtitle = state.videoSubtitleCues.getOrNull(
        activeVideoSubtitleIndex(state.videoSubtitleCues, subtitlePosition),
    )
    val secondarySubtitle = state.videoSecondarySubtitleCues.getOrNull(
        activeVideoSubtitleIndex(state.videoSecondarySubtitleCues, subtitlePosition),
    )
    Box(modifier) {
        if (state.videoUsingFfmpeg) {
            AndroidView(
                factory = { ctx ->
                    val surfaceView = SurfaceView(ctx)
                    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            ffmpegPlayer.setSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            ffmpegPlayer.setSurface(null)
                        }
                    })
                    surfaceView
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state.videoLoading || state.videoBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).semantics {
                    contentDescription = if (state.videoBuffering) "正在缓冲视频" else "正在加载视频"
                },
            )
        }
        if (state.videoCasting) {
            Text(
                if (state.videoCastLoading) "正在返回本机…" else "正在投送到 ${state.videoCastTarget}",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state.videoSubtitleVisible && subtitleAccessibilityText.isNotBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clearAndSetSemantics {
                        contentDescription = subtitleAccessibilityText
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                secondarySubtitle?.text?.takeIf(String::isNotBlank)?.let { text ->
                    Text(
                        text = text,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = (14f * state.videoSubtitleScale).sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                primarySubtitle?.text?.takeIf(String::isNotBlank)?.let { text ->
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = (18f * state.videoSubtitleScale).sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        state.videoError?.let { error ->
            Text(
                error,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

@Composable
private fun VideoOptionsMenu(
    state: MusicUiState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAudioOnly: (Boolean) -> Unit,
    onPickSubtitle: () -> Unit,
    onClearSubtitle: () -> Unit,
    onToggleSubtitleTts: () -> Unit,
    onOpenSubtitleSettings: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenQuality: () -> Unit,
    onDownloadEpisode: (() -> Unit)? = null,
    triggerFocusRequester: FocusRequester,
    contentColor: Color = Color.White,
) {
    val hapticView = LocalView.current
    Box {
        IconButton(
            onClick = { MediaHaptics.tick(hapticView); onExpandedChange(true) },
            modifier = Modifier.size(48.dp).focusRequester(triggerFocusRequester),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = contentColor)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (onDownloadEpisode != null && state.currentVideo?.uri?.scheme == "onlinevideo") {
                DropdownMenuItem(
                    text = { Text("下载本集") },
                    onClick = {
                        onExpandedChange(false)
                        MediaHaptics.tick(hapticView)
                        onDownloadEpisode.invoke()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (state.videoAudioOnly) "恢复视频画面" else "只播放声音") },
                onClick = {
                    onAudioOnly(!state.videoAudioOnly)
                    onExpandedChange(false)
                },
            )
            DropdownMenuItem(
                text = { Text("倍速 · ${formatSpeedLabel(state.videoSpeed)}") },
                onClick = {
                    onExpandedChange(false)
                    onOpenSpeed()
                },
                enabled = !state.videoUsingFfmpeg && !state.videoCasting,
            )
            if (state.videoQualityEntryVisible) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "清晰度 · " + (state.videoQualityOptions.firstOrNull { it.code == state.videoQualityCode }?.label
                                ?: "自动")
                        )
                    },
                    onClick = {
                        onExpandedChange(false)
                        onOpenQuality()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        if (state.videoSleepTimerTotalMs > 0) {
                            "定时播放 · 剩余 ${formatTime(state.videoSleepTimerRemainingMs)}"
                        } else {
                            "定时播放"
                        },
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onOpenSleepTimer()
                },
            )
            DropdownMenuItem(
                text = { Text("选择外挂字幕") },
                onClick = {
                    onPickSubtitle()
                    onExpandedChange(false)
                },
            )
            if (state.videoSubtitleName != null) {
                DropdownMenuItem(
                    text = { Text("清除外挂字幕") },
                    onClick = {
                        onClearSubtitle()
                        onExpandedChange(false)
                    },
                )
            }
            if (state.videoSubtitleCues.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (state.videoSubtitleTtsEnabled) "关闭字幕朗读" else "朗读字幕") },
                    onClick = {
                        onToggleSubtitleTts()
                        onExpandedChange(false)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("字幕设置") },
                onClick = {
                    onExpandedChange(false)
                    onOpenSubtitleSettings()
                },
            )
        }
    }
}

/** 进度条 + 当前/总时长（竖屏面板与横屏底栏共用）。 */
@Composable
private fun VideoSeekSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    contentColor: Color,
) {
    val progressMax = durationMs.coerceAtLeast(1L)
    val current = positionMs.coerceIn(0L, progressMax)
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "视频进度"
                stateDescription = "${formatTime(positionMs)}，共 ${formatTime(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = current.toFloat(),
                    range = 0f..progressMax.toFloat(),
                    steps = 19,
                )
                setProgress { target ->
                    onSeek(target.toLong().coerceIn(0L, progressMax))
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction("跳到开头") {
                        onSeek(0L)
                        true
                    },
                    CustomAccessibilityAction("跳到四分之一") {
                        onSeek(durationMs / 4)
                        true
                    },
                    CustomAccessibilityAction("跳到一半") {
                        onSeek(durationMs / 2)
                        true
                    },
                    CustomAccessibilityAction("跳到四分之三") {
                        onSeek(durationMs * 3 / 4)
                        true
                    },
                    CustomAccessibilityAction("跳到结尾") {
                        onSeek((durationMs - 1_000L).coerceAtLeast(0L))
                        true
                    },
                )
            },
    ) {
        val hapticView = LocalView.current
        Slider(
            value = current.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            onValueChangeFinished = { MediaHaptics.tick(hapticView) },
            valueRange = 0f..progressMax.toFloat(),
            steps = 19,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatTime(positionMs),
                color = contentColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatTime(durationMs),
                color = contentColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * 投送音量条：拖动本地草稿、松手才提交（避免逐帧 SOAP 请求刷爆设备），
 * 与音频「播放设备」面板的投送音量条同一交互；15 档口径与全局音量一致。
 */
@Composable
private fun VideoCastVolumeRow(
    volume: Float,
    onCommit: (Float) -> Unit,
    contentColor: Color,
) {
    var volumeDraft by remember { mutableStateOf(volume) }
    LaunchedEffect(volume) { volumeDraft = volume }
    val hapticView = LocalView.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "电视音量",
            color = contentColor.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = volumeDraft,
            onValueChange = { volumeDraft = it },
            onValueChangeFinished = {
                MediaHaptics.tick(hapticView)
                onCommit(volumeDraft)
            },
            valueRange = 0f..1f,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "投送音量"
                    stateDescription = "音量 ${(volumeDraft * VOLUME_MAX_LEVEL).roundToInt().coerceIn(0, VOLUME_MAX_LEVEL)} / $VOLUME_MAX_LEVEL"
                },
        )
        Text(
            "${(volumeDraft * VOLUME_MAX_LEVEL).roundToInt().coerceIn(0, VOLUME_MAX_LEVEL)}",
            color = contentColor.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(24.dp),
        )
    }
}

/** 手势反馈指示：黑底白字居中气泡（快进/快退目标时间戳、亮度、音量、长按倍速）。 */
@Composable
private fun VideoGestureIndicator(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.62f),
            contentColor = Color.White,
        ) {
            Text(
                text.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

private enum class VideoGestureMode { Undecided, None, Seek, Brightness, Volume, HoldSpeed }

/** seek 手势触发阈值系数：系统 touchSlop × 0.5（对齐参考实现）。 */
private const val VIDEO_GESTURE_SEEK_SLOP_MULTIPLIER = 0.5f

/** 手势方向判定：|dx| ≥ 1.05×|dy| 视为横向（seek），否则竖向（亮度/音量）。 */
private const val VIDEO_GESTURE_DOMINANCE = 1.05f

/** 手势 seek 预览节流步长（毫秒）：目标位置变化超过该值才刷新显示。 */
private const val VIDEO_GESTURE_SEEK_PREVIEW_STEP_MS = 250L

/** 亮度手势满滑程变化量：全屏高度滑一遍 = 0.8（范围 0.01–1.0，对齐参考实现）。 */
private const val VIDEO_BRIGHTNESS_FULL_SWIPE = 0.8f

/** 亮度手势下限：避免画面全黑导致用户以为黑屏死机。 */
private const val VIDEO_MIN_BRIGHTNESS = 0.01f

/** 视频播放器手势层（对齐 bilibili-player-android 的交互）：
 *  单击切换控制层；水平滑动快进/快退（10s 起步、120s 上限的平方根曲线，滑动只预览、松手才提交）；
 *  全屏下左半屏竖直滑动调亮度、右半屏调系统媒体音量；长按临时 2x 倍速（松开恢复）。
 *  TalkBack 用户不依赖本手势层：全部功能由播放器语义的 onClick/customActions 等价提供。 */
@Composable
private fun rememberVideoPlayerGestures(
    activity: Activity?,
    seekAllowed: Boolean,
    verticalAllowed: Boolean,
    holdSpeedAllowed: Boolean,
    startPositionMs: () -> Long,
    durationMs: () -> Long,
    onToggleControls: () -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onIndicator: (String) -> Unit,
    onIndicatorEnd: () -> Unit,
    onBrightnessAdjusted: () -> Unit,
    onStartHoldSpeed: () -> Unit,
    onEndHoldSpeed: () -> Unit,
): Modifier {
    val currentPosition by rememberUpdatedState(startPositionMs)
    val currentDuration by rememberUpdatedState(durationMs)
    val currentSeekPreview by rememberUpdatedState(onSeekPreview)
    val currentSeekCommit by rememberUpdatedState(onSeekCommit)
    val currentIndicator by rememberUpdatedState(onIndicator)
    val currentIndicatorEnd by rememberUpdatedState(onIndicatorEnd)
    val currentToggleControls by rememberUpdatedState(onToggleControls)
    val currentStartHold by rememberUpdatedState(onStartHoldSpeed)
    val currentEndHold by rememberUpdatedState(onEndHoldSpeed)
    val currentBrightnessAdjusted by rememberUpdatedState(onBrightnessAdjusted)
    // 长按倍速的触感锚点：进入倍速、松开恢复各一次短促轻点（MediaHaptics）。
    val hapticView = LocalView.current
    val audioManager = remember(activity) {
        activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    return Modifier.pointerInput(seekAllowed, verticalAllowed, holdSpeedAllowed) {
        val seekSlop = viewConfiguration.touchSlop * VIDEO_GESTURE_SEEK_SLOP_MULTIPLIER
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var mode = VideoGestureMode.Undecided
            var seekTargetMs = 0L
            var lastPreviewMs = Long.MIN_VALUE
            var holdActive = false
            var startBrightness = 0f
            var startVolumeStep = 0
            var maxVolumeStep = 0
            // 决策阶段：长按超时 → 临时倍速；移动越过阈值 → 按方向与左右半屏判定手势；抬起 → 单击。
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    if (!change.pressed) return@withTimeoutOrNull
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (abs(dx) > seekSlop || abs(dy) > seekSlop) {
                        mode = when {
                            abs(dx) >= abs(dy) * VIDEO_GESTURE_DOMINANCE ->
                                if (seekAllowed) VideoGestureMode.Seek else VideoGestureMode.None
                            verticalAllowed -> {
                                startBrightness = resolveScreenBrightness(activity)
                                if (audioManager != null) {
                                    maxVolumeStep = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    startVolumeStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                }
                                if (down.position.x < size.width / 2f) {
                                    VideoGestureMode.Brightness
                                } else {
                                    VideoGestureMode.Volume
                                }
                            }
                            else -> VideoGestureMode.None
                        }
                        return@withTimeoutOrNull
                    }
                }
            } ?: run {
                // 长按超时仍未移动：临时倍速（不可用时退化为无操作，不吞掉后续抬起）。
                if (holdSpeedAllowed) {
                    mode = VideoGestureMode.HoldSpeed
                    holdActive = true
                    currentStartHold()
                    MediaHaptics.tick(hapticView)
                    currentIndicator("${formatSpeedLabel(VIDEO_HOLD_SPEED)} 长按倍速播放")
                } else {
                    mode = VideoGestureMode.None
                }
            }
            // 手势确认即轻点一下（快进快退/亮度/音量）；长按倍速在上方自己的分支里振。
            if (mode == VideoGestureMode.Seek || mode == VideoGestureMode.Brightness || mode == VideoGestureMode.Volume) {
                MediaHaptics.tick(hapticView)
            }
            if (mode == VideoGestureMode.Undecided) {
                // 未越过阈值就抬起：按单击处理。
                currentToggleControls()
            } else if (mode != VideoGestureMode.None) {
                val startMs = currentPosition()
                val totalDurationMs = currentDuration()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    when (mode) {
                        VideoGestureMode.Seek -> {
                            val dx = change.position.x - down.position.x
                            seekTargetMs = gestureSeekTargetMs(startMs, dx, seekSlop, totalDurationMs)
                            if (abs(seekTargetMs - lastPreviewMs) >= VIDEO_GESTURE_SEEK_PREVIEW_STEP_MS) {
                                lastPreviewMs = seekTargetMs
                                currentSeekPreview(seekTargetMs)
                                currentIndicator(
                                    (if (dx >= 0f) "快进到 " else "快退到 ") + formatTime(seekTargetMs),
                                )
                            }
                        }
                        VideoGestureMode.Brightness -> {
                            val fraction = (
                                startBrightness -
                                    (change.position.y - down.position.y) / size.height.toFloat() *
                                    VIDEO_BRIGHTNESS_FULL_SWIPE
                                ).coerceIn(VIDEO_MIN_BRIGHTNESS, 1f)
                            activity?.window?.let { window ->
                                window.attributes = window.attributes.apply { screenBrightness = fraction }
                            }
                            currentBrightnessAdjusted()
                            currentIndicator("亮度 ${(fraction * 100).toInt()}%")
                        }
                        VideoGestureMode.Volume -> {
                            if (audioManager != null && maxVolumeStep > 0) {
                                val delta =
                                    -(change.position.y - down.position.y) / size.height.toFloat() * maxVolumeStep
                                val target = (startVolumeStep + delta).toInt().coerceIn(0, maxVolumeStep)
                                if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                                    // 音量每变动一档轻点一下：棘轮手感，档位变化可直接「听」出来。
                                    MediaHaptics.tick(hapticView)
                                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                                }
                                currentIndicator("音量 ${target * 100 / maxVolumeStep}%")
                            }
                        }
                        else -> Unit
                    }
                    if (!change.pressed) break
                }
                when (mode) {
                    VideoGestureMode.Seek -> {
                        // 松手提交：与手势确认对称为第二次轻点。
                        MediaHaptics.tick(hapticView)
                        currentSeekCommit(seekTargetMs)
                        currentIndicatorEnd()
                    }
                    VideoGestureMode.Brightness, VideoGestureMode.Volume -> {
                        MediaHaptics.tick(hapticView)
                        currentIndicatorEnd()
                    }
                    else -> Unit
                }
                if (holdActive) {
                    // 松开恢复原速：与进入倍速对称为第二次短促轻点，手指离开即振、无拖拉。
                    MediaHaptics.tick(hapticView)
                    currentEndHold()
                }
            }
        }
    }
}

/** 读取手势起点的屏幕亮度：窗口未覆写（BRIGHTNESS_OVERRIDE_NONE）时退回系统亮度。 */
private fun resolveScreenBrightness(activity: Activity?): Float {
    val window = activity?.window ?: return 0.5f
    val current = window.attributes.screenBrightness
    if (current in 0f..1f) return current
    return runCatching {
        android.provider.Settings.System.getInt(
            activity.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
        ).toFloat() / 255f
    }.getOrDefault(0.5f)
}

/** 横屏选集侧板：从右缘滑入；面板外点击可关闭，关闭后焦点回到「选集」入口。 */
@Composable
private fun VideoEpisodePanel(
    state: MusicUiState,
    onSelectEpisode: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val hapticView = LocalView.current
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(240.dp)
                // 吞掉面板区域的点击，避免误触遮罩关闭。
                .pointerInput(Unit) { detectTapGestures(onTap = { }) }
                .semantics { paneTitle = "选集" },
            color = GlassTheme.BgBase,
            contentColor = GlassTheme.TextPrimary,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "选集（${state.videoQueue.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭选集")
                    }
                }
                LazyColumn {
                    itemsIndexed(state.videoQueue, key = { _, video -> video.id }) { index, video ->
                        val selected = index == state.videoIndex
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .clickable(onClickLabel = "选择${video.title}") { onSelectEpisode(index) }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clearAndSetSemantics {
                                    contentDescription = video.title
                                    stateDescription = if (selected) "正在播放" else "未选中"
                                    onClick(label = "选择${video.title}") {
                                        onSelectEpisode(index)
                                        true
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = GlassTheme.TextTertiary,
                            )
                            Text(
                                video.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) GlassTheme.Accent else GlassTheme.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCastDialog(
    state: MusicUiState,
    onScan: () -> Unit,
    onSelectDlna: (DlnaDevice) -> Unit,
    onReturnToLocal: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.videoCasting) "正在投送" else "投送到设备") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.videoCasting) {
                    Text("正在投送到 ${state.videoCastTarget}")
                    if (state.videoCastLoading) Text("正在返回本机…")
                } else if (state.videoCastLoading) {
                    Text("正在连接设备…")
                } else {
                    if (state.scanningDevices) {
                        Text("正在搜索设备…")
                    } else if (state.dlnaDevices.isEmpty()) {
                        Text("未找到设备，请确认手机与设备在同一 Wi-Fi 后搜索。")
                    }
                    state.dlnaDevices.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable { onSelectDlna(device) }
                                .semantics { contentDescription = "DLNA 设备：${device.name}" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("DLNA：${device.name}", modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.videoCasting && !state.videoCastLoading -> TextButton(onClick = onReturnToLocal) { Text("返回本机") }
                !state.videoCasting && !state.videoCastLoading -> TextButton(onClick = onScan) { Text("搜索设备") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.videoCasting) "关闭" else "取消") } },
    )
}

@Composable
private fun VideoRow(
    video: NativeTrack,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail = produceState<Bitmap?>(initialValue = null, video.uri) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    context.contentResolver.loadThumbnail(video.uri, android.util.Size(256, 256), null)
                }.getOrNull()
            } else null
        }
    }.value
    Card(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "${video.title.substringBeforeLast('.', video.title)}，${video.format}，时长 ${formatTime(video.durationMs)}，大小 ${formatFileSize(video.sizeBytes)}"
                onClick(label = "播放视频") {
                    onClick()
                    true
                }
            },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${video.format} · ${formatTime(video.durationMs)} · ${formatFileSize(video.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    state: MusicUiState,
    miniPlayerInset: Dp = 0.dp,
    gridDensity: GridDensity,
    albums: List<LibraryAlbum<NativeTrack>>,
    folderPath: String,
    albumListState: LazyGridState,
    libraryListState: LazyGridState,
    videoListState: LazyGridState,
    restoreAlbumId: String?,
    onFolderPathChange: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestAllFiles: (() -> Unit)? = null,
    onPlay: (List<NativeTrack>, Int) -> Unit,
    onPlayVideo: (List<NativeTrack>, Int) -> Unit,
    onViewLyrics: (NativeTrack) -> Unit,
    onShare: (NativeTrack) -> Unit,
    onCopy: (NativeTrack) -> Unit,
    onCut: (NativeTrack) -> Unit,
    onDelete: (NativeTrack) -> Unit,
    onPaste: (String) -> Unit,
    onCopyFolder: (String) -> Unit,
    onCutFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onBrowse: (LibraryBrowse) -> Unit,
    onSearch: (String) -> Unit,
    onImport: () -> Unit,
    onToggleFavorite: (NativeTrack) -> Unit,
    /** 一键收藏/取消收藏整张本地专辑（月播库长按即收藏）。 */
    onToggleAlbumFavorite: (LibraryAlbum<NativeTrack>) -> Unit,
    isAlbumFavorite: (LibraryAlbum<NativeTrack>) -> Boolean,
    onOpenAlbum: (LibraryAlbum<NativeTrack>) -> Unit,
    onEditAlbum: (LibraryAlbum<NativeTrack>) -> Unit,
    onRemoveAlbum: (LibraryAlbum<NativeTrack>) -> Unit,
    onAlbumRestoreHandled: () -> Unit,
    onUpdateTrackMetadata: (NativeTrack, String, String, String) -> Unit,
    onBackToHub: (() -> Unit)? = null,
) {
    val browseMode = state.libraryBrowse
    var collectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var albumAnnouncement by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleAlbums = remember(albums, state.searchQuery) {
        val query = state.searchQuery.trim()
        albums.filter { album ->
            query.isBlank() || album.name.contains(query, ignoreCase = true) ||
                album.artist.contains(query, ignoreCase = true) ||
                album.tracks.any { it.title.contains(query, ignoreCase = true) }
        }
    }
    LaunchedEffect(restoreAlbumId, visibleAlbums, browseMode) {
        val index = restoreAlbumId?.let { id -> visibleAlbums.indexOfFirst { it.key == id } } ?: -1
        if (browseMode == LibraryBrowse.Albums && index >= 0) {
            albumListState.scrollToItem(index)
            onAlbumRestoreHandled()
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        var searchVisible by rememberSaveable { mutableStateOf(false) }
        val searchFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(searchVisible) {
            if (searchVisible) {
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
        BackHandler(enabled = searchVisible) {
            searchVisible = false
            onSearch("")
            keyboardController?.hide()
        }
        BackHandler(enabled = !searchVisible && collectionName != null) {
            collectionName = null
        }
        Column(
            Modifier.fillMaxWidth(),
        ) {
            if (searchVisible) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    label = { Text("搜索歌曲、歌手或专辑") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                onSearch("")
                                searchFocusRequester.requestFocus()
                                keyboardController?.show()
                            },
                            enabled = state.searchQuery.isNotEmpty(),
                        ) { Icon(Icons.Default.Close, contentDescription = "清除搜索") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).sizeIn(minHeight = 48.dp).focusRequester(searchFocusRequester),
                )
            } else {
                AppBar(
                    title = collectionName ?: if (browseMode == LibraryBrowse.Folders) folderPath.ifBlank { "月播库" } else libraryBrowseLabel(browseMode),
                    onBack = when {
                        collectionName != null -> { { collectionName = null } }
                        browseMode == LibraryBrowse.Folders && folderPath.isNotBlank() -> { { onFolderPathChange(folderPath.substringBeforeLast('/', "")) } }
                        // 根目录无上级：返回媒体首页（月播库是从「媒体」进入的模块）。
                        else -> onBackToHub
                    },
                    onDevices = null,
                    onSearch = { searchVisible = true },
                    onImport = onImport,
                    onPaste = if (state.hasClipboardTrack || state.hasClipboardFolder) { { onPaste(folderPath) } } else null,
                )
            }
            StatusMessage(state.status?.takeUnless { it.contains("输出") })
            if (!state.permissionGranted) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "需要音频与视频访问权限才能读取本地音乐和视频。"
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "需要读取音乐或所有文件访问权限才能查看本地音乐。"
                            else -> "未授予音乐读取权限，请允许后查看和播放本地音乐。"
                        },
                    )
                    Button(onClick = onRequestPermission, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                        Text("允许读取音乐")
                    }
                    if (onRequestAllFiles != null) {
                        OutlinedButton(onClick = onRequestAllFiles, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text("授予所有文件访问权限")
                        }
                    }
                }
            } else {
                LibraryBrowseMenu(browseMode) {
                    onBrowse(it)
                    collectionName = null
                }
                albumAnnouncement?.let { announcement ->
                    Text(
                        announcement,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (browseMode != LibraryBrowse.Albums) {
                    LibrarySortMenu(state.librarySort, state.librarySortAscending, onSort)
                }
            }
        }
        if (!state.permissionGranted) return
        val browsingVideos = browseMode == LibraryBrowse.Videos
        val sourceTracks = if (browsingVideos) state.videoTracks else state.tracks
        if (state.loading && sourceTracks.isEmpty() && browseMode != LibraryBrowse.Albums) {
            Text(
                if (browsingVideos) "正在读取本地视频" else "正在读取本地音乐",
                modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            return
        }
        if (state.loading && browseMode != LibraryBrowse.Albums) {
            Text(
                if (browsingVideos) "正在刷新视频列表" else "正在刷新列表",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (sourceTracks.isEmpty() && browseMode != LibraryBrowse.Albums) {
            Text(
                if (browsingVideos) "未找到视频文件。" else "未找到音乐文件。",
                modifier = Modifier.padding(24.dp),
            )
            return
        }
        if (browseMode == LibraryBrowse.Albums) {
            when {
                albums.isEmpty() -> Text(
                    "未找到专辑。",
                    modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                visibleAlbums.isEmpty() -> Text(
                    "未找到匹配的专辑。",
                    modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                else -> LazyVerticalGrid(
                    columns = gridCells(gridDensity),
                    state = albumListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + miniPlayerInset),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(visibleAlbums, key = { it.key }) { album ->
                        LibraryAlbumCard(
                            album = album,
                            onOpen = { onOpenAlbum(album) },
                            onEdit = album.importedAlbum?.let { { onEditAlbum(album) } },
                            onRemove = {
                                albumAnnouncement = "已从专辑列表移除“${album.name}”，源文件和播放列表未改变。"
                                onRemoveAlbum(album)
                            },
                            onToggleFavorite = { onToggleAlbumFavorite(album) },
                            favorited = isAlbumFavorite(album),
                        )
                    }
                }
            }
            return
        }
        if (browseMode == LibraryBrowse.Videos) {
            val visibleVideos = remember(
                state.videoTracks,
                state.searchQuery,
                state.librarySort,
                state.librarySortAscending,
            ) {
                sortTracks(
                    state.videoTracks.filter { it.matches(state.searchQuery) },
                    state.librarySort,
                    state.librarySortAscending,
                )
            }
            if (visibleVideos.isEmpty()) {
                Text("未找到匹配的视频。", modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                LazyVerticalGrid(
                    columns = gridCells(gridDensity),
                    state = videoListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp + miniPlayerInset),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(visibleVideos, key = { it.id }) { video ->
                        VideoRow(
                            video = video,
                            onClick = { onPlayVideo(visibleVideos, visibleVideos.indexOf(video)) },
                        )
                    }
                }
            }
            return
        }
        val visibleTracks = remember(
            state.tracks,
            state.trackMetadataOverrides,
            state.onlyFavorites,
            state.favoriteIds,
            state.searchQuery,
        ) {
            state.tracks.map { track ->
                track.withMetadataOverride(state.trackMetadataOverrides[track.metadataKey()])
            }.filter { track ->
                (!state.onlyFavorites || track.id in state.favoriteIds) && track.matches(state.searchQuery)
            }
        }
        val folderBrowseVideos = remember(state.videoTracks, state.searchQuery) {
            state.videoTracks.filter { it.matches(state.searchQuery) }
        }
        val directTracks = remember(
            visibleTracks,
            collectionName,
            browseMode,
            folderPath,
            state.librarySort,
            state.librarySortAscending,
        ) {
            sortTracks(
                when {
                    collectionName != null && browseMode == LibraryBrowse.Artists -> visibleTracks.filter { it.artist.ifBlank { "未知艺术家" } == collectionName }
                    browseMode == LibraryBrowse.Songs -> visibleTracks
                    browseMode == LibraryBrowse.Folders -> visibleTracks.filter { it.folderPath == folderPath }
                    else -> emptyList()
                },
                state.librarySort,
                state.librarySortAscending,
            )
        }
        val prefix = if (folderPath.isBlank()) "" else "$folderPath/"
        val folders = remember(visibleTracks, folderBrowseVideos, folderPath) {
            (visibleTracks.asSequence().map { it.folderPath } + folderBrowseVideos.asSequence().map { it.folderPath })
                .filter { path -> path.startsWith(prefix) && path != folderPath }
                .map { path -> path.removePrefix(prefix).substringBefore('/') }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()
        }
        val folderVideos = remember(folderBrowseVideos, folderPath) {
            folderBrowseVideos.filter { it.folderPath == folderPath }
        }
        val collections = remember(visibleTracks, browseMode) {
            when (browseMode) {
                LibraryBrowse.Artists -> visibleTracks.groupBy { it.artist.ifBlank { "未知艺术家" } }
                else -> emptyMap()
            }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        }
        if (visibleTracks.isEmpty() && (browseMode != LibraryBrowse.Folders || folderBrowseVideos.isEmpty())) {
            Text(
                if (browseMode == LibraryBrowse.Folders) "未找到匹配的音乐或视频。" else "未找到匹配的音乐。",
                modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            LazyVerticalGrid(
                columns = gridCells(gridDensity),
                state = libraryListState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + miniPlayerInset),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (collectionName == null && collections.isNotEmpty()) {
                    gridItems(collections.entries.toList(), key = { "collection:${it.key}" }) { (name, tracks) ->
                        CollectionCard(name, tracks) { collectionName = name }
                    }
                } else if (browseMode == LibraryBrowse.Folders && collectionName == null) {
                    gridItems(folders, key = { "folder:$it" }) { folder ->
                        val folderFullPath = if (folderPath.isBlank()) folder else "$folderPath/$folder"
                        val folderTracks = remember(folderFullPath, visibleTracks) {
                            visibleTracks.filter { it.folderPath == folderFullPath || it.folderPath.startsWith("$folderFullPath/") }
                        }
                        val folderVideoTracks = remember(folderFullPath, folderBrowseVideos) {
                            folderBrowseVideos.filter { it.folderPath == folderFullPath || it.folderPath.startsWith("$folderFullPath/") }
                        }
                        FolderCard(
                            name = folder,
                            trackCount = folderTracks.size,
                            videoCount = folderVideoTracks.size,
                            totalSizeBytes = folderTracks.sumOf { it.sizeBytes } + folderVideoTracks.sumOf { it.sizeBytes },
                            onClick = { onFolderPathChange(folderFullPath) },
                            onCopy = { onCopyFolder(folderFullPath) },
                            onCut = { onCutFolder(folderFullPath) },
                            onDelete = { onDeleteFolder(folderFullPath) },
                        )
                    }
                }
                gridItems(directTracks, key = { it.id }) { track ->
                    TrackCard(
                        track = track,
                        onClick = { onPlay(directTracks, directTracks.indexOf(track)) },
                        onViewLyrics = { onViewLyrics(track) },
                        onShare = { onShare(track) },
                        onCopy = { onCopy(track) },
                        onCut = { onCut(track) },
                        onDelete = { onDelete(track) },
                        favorite = track.id in state.favoriteIds,
                        onToggleFavorite = { onToggleFavorite(track) },
                        onUpdateTrackMetadata = onUpdateTrackMetadata,
                    )
                }
                gridItems(folderVideos, key = { "video:${it.id}" }) { video ->
                    VideoRow(
                        video = video,
                        onClick = { onPlayVideo(folderVideos, folderVideos.indexOf(video)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SilenceSkipSettingsScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onMode: (SilenceSkipMode) -> Unit,
    onThresholdMs: (Long) -> Unit,
) {
    var thresholdText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.skipSilenceEnabled, state.silenceSkipThresholdMs) {
        thresholdText = if (state.skipSilenceEnabled) state.silenceSkipThresholdMs.toString() else ""
    }
    val threshold = thresholdText.toLongOrNull()
    val thresholdInvalid = state.skipSilenceEnabled && (threshold == null || threshold !in 1L..60_000L)
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "跳过静音", onBack = onBack, onDevices = null)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        .clickable { onEnabled(!state.skipSilenceEnabled) }
                        .clearAndSetSemantics {
                            contentDescription = "跳过静音"
                            stateDescription = if (state.skipSilenceEnabled) "已开启" else "已关闭"
                            onClick { onEnabled(!state.skipSilenceEnabled); true }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用跳过静音", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = state.skipSilenceEnabled, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            item {
                Text(
                    "检测播放中任意位置的连续静音段，包括歌曲内部。关闭后将清除跳过条件。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.skipSilenceEnabled) {
                item { SectionHeading("跳过条件") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.silenceSkipMode == SilenceSkipMode.LongerThan,
                            onClick = { onMode(SilenceSkipMode.LongerThan) },
                            label = { Text("静音时间大于") },
                        )
                        FilterChip(
                            selected = state.silenceSkipMode == SilenceSkipMode.ShorterThan,
                            onClick = { onMode(SilenceSkipMode.ShorterThan) },
                            label = { Text("静音时间小于") },
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit)) {
                                thresholdText = value
                                value.toLongOrNull()?.takeIf { it in 1L..60_000L }?.let(onThresholdMs)
                            }
                        },
                        label = { Text("静音阈值") },
                        suffix = { Text("ms") },
                        singleLine = true,
                        isError = thresholdInvalid,
                        supportingText = {
                            Text(if (thresholdInvalid) "请输入 1 到 60000 的整数。" else "设置会立即保存。")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    )
                }
            }
        }
    }
}

private fun silenceSkipModeLabel(mode: SilenceSkipMode): String = when (mode) {
    SilenceSkipMode.LongerThan -> "静音时间大于"
    SilenceSkipMode.ShorterThan -> "静音时间小于"
}

private enum class QrKind { Wechat, Alipay }

@Composable
private fun LibraryBrowseSourceMenu(selected: LibraryBrowseSource, onSelect: (LibraryBrowseSource) -> Unit) {
    val options = LibraryBrowseSource.entries
    var expanded by remember { mutableStateOf(false) }
    val accessibilityView = LocalView.current
    fun select(option: LibraryBrowseSource) {
        onSelect(option)
        accessibilityView.announceForAccessibility("已切换到${option.label}")
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.sizeIn(minHeight = 48.dp).clearAndSetSemantics {
                role = Role.Button
                contentDescription = selected.label
                onClick(label = "打开浏览方式") { expanded = true; true }
                customActions = options.map { option ->
                    CustomAccessibilityAction(option.label) { select(option); true }
                }
            },
        ) { Text("浏览：${selected.label}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { expanded = false; select(option) },
                )
            }
        }
    }
}

private fun libraryBrowseLabel(mode: LibraryBrowse): String = when (mode) {
    LibraryBrowse.Songs -> "歌曲"
    LibraryBrowse.Albums -> "专辑"
    LibraryBrowse.Artists -> "艺术家"
    LibraryBrowse.Folders -> "文件夹"
    LibraryBrowse.Videos -> "视频"
}

private const val IMPORT_ROOT = "Music/月播"

// 旧版（汪汪播放器时期）导入根目录：改版月播后继续识别老用户已下载/导入的内容。
private const val LEGACY_IMPORT_ROOT = "Music/汪汪播放器"
private val importRoots = listOf(IMPORT_ROOT, LEGACY_IMPORT_ROOT)

// 曲目所属专辑名；null 表示无专辑，不出现在专辑浏览（歌曲/文件夹浏览仍可见）
private fun albumNameOf(track: NativeTrack, folderTrackCounts: Map<String, Int>): String? {
    val album = track.album.trim()
    val folder = track.folderPath.trim('/')
    // 导入根目录是单音频导入的落点，不成专辑
    if (importRoots.any { folder == it }) return null
    // 导入专辑：位于 导入根目录/<X>/ 且 album 为空或等于 X；该文件夹只有一首时视为单曲导入，不成专辑
    for (root in importRoots) {
        if (folder.startsWith("$root/")) {
            val importAlbum = folder.removePrefix("$root/").substringBefore('/')
            if (importAlbum.isNotBlank() && (album.isEmpty() || album == importAlbum)) {
                return if ((folderTrackCounts[folder] ?: 0) >= 2) importAlbum else null
            }
        }
    }
    if (album.isNotEmpty()) {
        // MediaStore 对无标签音频会用父文件夹名填充 ALBUM（如 Music、WeiXin），视为无专辑
        val innermost = folder.substringAfterLast('/')
        if (innermost.isNotEmpty() && album == innermost) return null
        return album
    }
    return null
}

private fun albumCollections(
    tracks: List<NativeTrack>,
    folderTrackCounts: Map<String, Int>,
): Map<String, List<NativeTrack>> =
    tracks.mapNotNull { track ->
        albumNameOf(track, folderTrackCounts)?.let { name -> name to track }
    }.groupBy({ it.first }, { it.second })

@Composable
private fun LibraryBrowseMenu(selected: LibraryBrowse, onSelect: (LibraryBrowse) -> Unit) {
    val options = listOf(
        LibraryBrowse.Artists,
        LibraryBrowse.Albums,
        LibraryBrowse.Songs,
        LibraryBrowse.Folders,
        LibraryBrowse.Videos,
    )
    var expanded by remember { mutableStateOf(false) }
    val accessibilityView = LocalView.current
    fun select(option: LibraryBrowse) {
        onSelect(option)
        accessibilityView.announceForAccessibility("已切换到${libraryBrowseLabel(option)}浏览")
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.sizeIn(minHeight = 48.dp).clearAndSetSemantics {
                role = Role.Button
                contentDescription = libraryBrowseLabel(selected)
                onClick(label = "打开浏览方式") { expanded = true; true }
                customActions = options.map { option ->
                    val label = libraryBrowseLabel(option)
                    CustomAccessibilityAction(label) { select(option); true }
                }
            },
        ) { Text("浏览：${libraryBrowseLabel(selected)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val label = libraryBrowseLabel(option)
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { expanded = false; select(option) },
                )
            }
        }
    }
}

@Composable
private fun LibrarySortMenu(sort: LibrarySort, ascending: Boolean, onSelect: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val accessibilityView = LocalView.current
    fun select(option: LibrarySort) {
        val nextAscending = if (option == sort) !ascending else true
        onSelect(option)
        accessibilityView.announceForAccessibility(
            "已按${librarySortLabel(option)}${if (nextAscending) "升序" else "降序"}排列",
        )
    }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                customActions = LibrarySort.entries.map { option ->
                    CustomAccessibilityAction("按${librarySortLabel(option)}排序") { select(option); true }
                }
            },
        ) { Text("排序：${librarySortLabel(sort)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                val label = librarySortLabel(option)
                DropdownMenuItem(
                    text = {
                        Text(if (option == sort) "$label（当前）" else label)
                    },
                    onClick = { expanded = false; select(option) },
                )
            }
        }
    }
}

private fun sortTracks(tracks: List<NativeTrack>, sort: LibrarySort, ascending: Boolean): List<NativeTrack> {
    val comparator = when (sort) {
        LibrarySort.Name -> compareBy<NativeTrack> { it.title.lowercase() }.thenBy { it.id }
        LibrarySort.Size -> compareBy<NativeTrack> { it.sizeBytes }.thenBy { it.title.lowercase() }
        LibrarySort.Time -> compareBy<NativeTrack> { it.modifiedTimeMs }.thenBy { it.title.lowercase() }
    }
    return tracks.sortedWith(if (ascending) comparator else comparator.reversed())
}

private fun sortCloudFiles(
    files: List<CloudFile>,
    sort: LibrarySort,
    ascending: Boolean,
): List<CloudFile> {
    val keyComparator = when (sort) {
        LibrarySort.Name -> compareBy<CloudFile> { it.name.lowercase() }.thenBy { it.id }
        LibrarySort.Size -> compareBy<CloudFile> { it.size }.thenBy { it.name.lowercase() }
        LibrarySort.Time -> compareBy<CloudFile> { it.mtime }.thenBy { it.name.lowercase() }
    }
    val ordered = if (ascending) keyComparator else keyComparator.reversed()
    // 文件夹始终排在文件前面，与文件管理器习惯一致。
    return files.sortedWith(compareBy<CloudFile> { !it.isDir }.then(ordered))
}

private fun librarySortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.Name -> "文件名"
    LibrarySort.Size -> "文件大小"
    LibrarySort.Time -> "修改时间"
}

private fun NativeTrack.matches(query: String): Boolean {
    val term = query.trim()
    return term.isBlank() || listOf(title, artist, album).any { it.contains(term, ignoreCase = true) }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LibraryAlbumCard(
    album: LibraryAlbum<NativeTrack>,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: () -> Unit,
    /** 一键收藏/取消收藏（整张专辑一条，落入月播库默认收藏夹）。 */
    onToggleFavorite: (() -> Unit)? = null,
    favorited: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var removeConfirmOpen by remember { mutableStateOf(false) }
    val label = buildString {
        append(album.name)
        if (album.artist.isNotBlank()) append("，${album.artist}")
        append("，${album.tracks.size} 首歌曲")
        if (favorited) append("，已收藏")
    }
    Box {
        Card(
            Modifier.fillMaxWidth().heightIn(min = 168.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .clearAndSetSemantics {
                    contentDescription = label
                    onClick(label = "查看专辑详情") { onOpen(); true }
                    customActions = buildList {
                        if (onToggleFavorite != null) {
                            add(CustomAccessibilityAction(if (favorited) "取消收藏" else "收藏") { onToggleFavorite(); true })
                        }
                        onEdit?.let { edit -> add(CustomAccessibilityAction("编辑专辑") { edit(); true }) }
                        add(CustomAccessibilityAction("从专辑列表移除") { removeConfirmOpen = true; true })
                    }
                },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LocalAlbumArtwork(album, Modifier.fillMaxWidth().height(112.dp))
                Text(album.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (album.artist.isNotBlank()) {
                    Text(album.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${album.tracks.size} 首歌曲", style = MaterialTheme.typography.bodySmall)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("查看专辑详情") }, onClick = { menuOpen = false; onOpen() })
            if (onToggleFavorite != null) {
                DropdownMenuItem(
                    text = { Text(if (favorited) "取消收藏" else "收藏") },
                    onClick = { menuOpen = false; onToggleFavorite() },
                    leadingIcon = {
                        Icon(
                            if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                        )
                    },
                )
            }
            onEdit?.let { edit ->
                DropdownMenuItem(text = { Text("编辑专辑") }, onClick = { menuOpen = false; edit() })
            }
            DropdownMenuItem(
                text = { Text("从专辑列表移除") },
                onClick = { menuOpen = false; removeConfirmOpen = true },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            )
        }
    }
    if (removeConfirmOpen) {
        AlertDialog(
            onDismissRequest = { removeConfirmOpen = false },
            title = { Text("从专辑列表移除？") },
            text = { Text("“${album.name}”将不再显示在专辑列表中。源文件和播放列表不会改变。") },
            dismissButton = {
                TextButton(
                    onClick = { removeConfirmOpen = false },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("取消") }
            },
            confirmButton = {
                Button(
                    onClick = { removeConfirmOpen = false; onRemove() },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                ) { Text("移除") }
            },
        )
    }
}

@Composable
private fun AlbumDetailScreen(
    album: LibraryAlbum<NativeTrack>,
    miniPlayerInset: Dp = 0.dp,
    gridDensity: GridDensity,
    status: String?,
    onBack: () -> Unit,
    onUpdateAlbum: (ImportedAlbum) -> Unit,
    onSelectArtwork: (Boolean) -> Unit,
    onPlay: (List<NativeTrack>, Int) -> Unit,
    onViewLyrics: (NativeTrack) -> Unit,
    onDelete: (NativeTrack) -> Unit,
    onUpdateTrackMetadata: (NativeTrack, String, String, String) -> Unit,
    editOnOpen: Boolean,
    onEditOnOpenHandled: () -> Unit,
) {
    val listState = rememberLazyGridState()
    var albumEditorOpen by rememberSaveable { mutableStateOf(false) }
    // 正序/倒序一键切换（与在线专辑详情同一交互）；播放/播放全部按**当前显示顺序**取下标，避免错位。
    var albumDescending by rememberSaveable(album.key) { mutableStateOf(false) }
    val albumTracks = remember(album.tracks, albumDescending) { orderedAlbumTracks(album.tracks, albumDescending)!! }
    var editingTrack by remember { mutableStateOf<NativeTrack?>(null) }
    var restoreTrackEditKey by remember { mutableStateOf<String?>(null) }
    var detailVisible by remember(album.key) { mutableStateOf(false) }
    var saveAnnouncement by rememberSaveable { mutableStateOf<String?>(null) }
    val animationsEnabled = rememberMotionEnabled()
    val coverFocusRequester = remember { FocusRequester() }
    val backCoverFocusRequester = remember { FocusRequester() }
    var restoreCoverFocus by remember { mutableStateOf(false) }
    var restoreBackCoverFocus by remember { mutableStateOf(false) }
    var artworkViewer by remember { mutableStateOf<ArtworkView?>(null) }

    LaunchedEffect(restoreCoverFocus) {
        if (restoreCoverFocus) {
            coverFocusRequester.requestFocus()
            restoreCoverFocus = false
        }
    }
    LaunchedEffect(restoreBackCoverFocus) {
        if (restoreBackCoverFocus) {
            backCoverFocusRequester.requestFocus()
            restoreBackCoverFocus = false
        }
    }
    LaunchedEffect(album.key) { detailVisible = true }
    LaunchedEffect(editOnOpen, album.importedAlbum) {
        if (editOnOpen && album.importedAlbum != null) {
            albumEditorOpen = true
            onEditOnOpenHandled()
        }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(
            title = "专辑详情",
            onBack = onBack,
            onDevices = null,
        )
        saveAnnouncement?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        StatusMessage(status)
        AnimatedVisibility(
            visible = !animationsEnabled || detailVisible,
            enter = if (animationsEnabled) fadeIn() + scaleIn() else EnterTransition.None,
            modifier = Modifier.weight(1f),
        ) {
            LazyVerticalGrid(
                columns = gridCells(gridDensity),
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + miniPlayerInset),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "album-header", span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                                .focusRequester(coverFocusRequester)
                                .clickable(onClickLabel = "查看封面大图") {
                                    artworkViewer = ArtworkView(
                                        label = "封面",
                                        albumName = album.name,
                                        uri = album.importedAlbum?.frontCoverUri,
                                        fallbackTrack = album.tracks.firstOrNull(),
                                    )
                                }
                                .semantics { contentDescription = "封面：${album.name}" },
                        ) {
                            LocalAlbumArtwork(album, Modifier.fillMaxSize(), targetSizePx = 256)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(album.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            if (album.artist.isNotBlank()) Text(album.artist, style = MaterialTheme.typography.bodyLarge)
                            Text("${album.tracks.size} 首歌曲", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                album.importedAlbum?.description?.takeIf(String::isNotBlank)?.let { intro ->
                    item(key = "album-intro", span = { GridItemSpan(maxLineSpan) }) {
                        AlbumIntroSection(intro = intro, albumKey = album.key)
                    }
                }
                item(key = "album-play-all", span = { GridItemSpan(maxLineSpan) }) {
                    Button(
                        onClick = { onPlay(albumTracks, 0) },
                        enabled = albumTracks.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) { Text("全部播放") }
                }
                if (albumTracks.isNotEmpty()) {
                    item(key = "album-order-toggle", span = { GridItemSpan(maxLineSpan) }) {
                        TrackOrderToggle(
                            descending = albumDescending,
                            onToggle = { albumDescending = it },
                        )
                    }
                }
                if (albumTracks.isEmpty()) {
                    item(key = "album-no-tracks", span = { GridItemSpan(maxLineSpan) }) { Text("该专辑没有可播放曲目。") }
                } else {
                    // 曲目行是列表形态（封面+标题+时长），必须跨满整行，否则高网格密度下被压成一字一行。
                    val tracks = albumTracks
                    items(
                        count = tracks.size,
                        key = { index -> tracks[index].metadataKey() },
                        span = { _ -> GridItemSpan(maxLineSpan) },
                    ) { index ->
                        val track = tracks[index]
                        AlbumTrackRow(
                            track = track,
                            // 传入**当前显示顺序**的清单：倒序时点第 N 行播的就是该行，不会错位。
                            onPlay = { onPlay(tracks, index) },
                            onViewLyrics = { onViewLyrics(track) },
                            onEditMetadata = { editingTrack = track },
                            onDelete = onDelete,
                            restoreEditFocus = restoreTrackEditKey == track.metadataKey(),
                            onEditFocusRestored = { restoreTrackEditKey = null },
                        )
                    }
                }
                album.importedAlbum?.backCoverUri?.let { backCoverUri ->
                    item(key = "album-back", span = { GridItemSpan(maxLineSpan) }) {
                        BackCoverSection(
                            uri = backCoverUri,
                            albumName = album.name,
                            focusRequester = backCoverFocusRequester,
                            onExpand = {
                                artworkViewer = ArtworkView(
                                    label = "封底",
                                    albumName = album.name,
                                    uri = backCoverUri,
                                    fallbackTrack = null,
                                    isBack = true,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    fun closeEditor() {
        albumEditorOpen = false
        restoreCoverFocus = true
    }
    if (albumEditorOpen) {
        album.importedAlbum?.let { imported ->
            AlbumEditDialog(
                album = imported,
                onSelectArtwork = onSelectArtwork,
                onDismiss = ::closeEditor,
                onSave = { name, artist, description ->
                    onUpdateAlbum(
                        imported.copy(
                            name = name,
                            artist = artist,
                            description = description,
                            descriptionEdited = true,
                        ),
                    )
                    saveAnnouncement = "已保存专辑信息"
                    closeEditor()
                },
            )
        }
    }
    editingTrack?.let { track ->
        TrackDetailsMetadataDialog(
            track = track,
            onDismiss = {
                editingTrack = null
                restoreTrackEditKey = track.metadataKey()
            },
            onSave = { title, artist, albumName ->
                onUpdateTrackMetadata(track, title, artist, albumName)
                saveAnnouncement = "已保存曲目元数据"
                editingTrack = null
                restoreTrackEditKey = track.metadataKey()
            },
        )
    }
    artworkViewer?.let { view ->
        ArtworkViewerDialog(
            view = view,
            onDismiss = {
                artworkViewer = null
                if (view.isBack) restoreBackCoverFocus = true else restoreCoverFocus = true
            },
        )
    }
}

private data class ArtworkView(
    val label: String,
    val albumName: String,
    val uri: String?,
    val fallbackTrack: NativeTrack?,
    val isBack: Boolean = false,
)

@Composable
private fun ArtworkViewerDialog(view: ArtworkView, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val closeFocusRequester = remember { FocusRequester() }
    val bitmap by produceState<Bitmap?>(initialValue = null, view) {
        value = withContext(Dispatchers.IO) {
            view.uri?.let { loadArtworkUri(context, Uri.parse(it), 1440) }
                ?: view.fallbackTrack?.let { loadAlbumArtwork(context, it, 1440) }
        }
    }
    LaunchedEffect(Unit) { closeFocusRequester.requestFocus() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "${view.albumName}${view.label}大图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "暂无${view.label}图片",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .focusRequester(closeFocusRequester),
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭${view.label}大图")
            }
        }
    }
}

@Composable
private fun BackCoverSection(
    uri: String,
    albumName: String,
    focusRequester: FocusRequester,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { loadArtworkUri(context, Uri.parse(uri), 512) }
    }
    val image = bitmap
    if (image != null) {
        val height = 96.dp
        val width = (height * (image.width.toFloat() / image.height.toFloat())).coerceIn(48.dp, 200.dp)
        Box(
            Modifier.width(width).height(height)
                .clip(RoundedCornerShape(12.dp))
                .focusRequester(focusRequester)
                .clickable(onClickLabel = "查看封底大图", onClick = onExpand)
                .semantics { contentDescription = "封底：${albumName}" },
        ) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AlbumTrackRow(
    track: NativeTrack,
    onPlay: () -> Unit,
    onViewLyrics: () -> Unit,
    onEditMetadata: () -> Unit,
    onDelete: (NativeTrack) -> Unit,
    restoreEditFocus: Boolean,
    onEditFocusRestored: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var restoreRowFocus by remember { mutableStateOf(false) }
    val rowFocusRequester = remember { FocusRequester() }
    LaunchedEffect(restoreEditFocus, restoreRowFocus) {
        if (restoreEditFocus || restoreRowFocus) {
            rowFocusRequester.requestFocus()
            onEditFocusRestored()
            restoreRowFocus = false
        }
    }
    Box {
        Card(
            Modifier.fillMaxWidth().heightIn(min = 88.dp)
                .combinedClickable(onClick = onPlay, onLongClick = { menuOpen = true })
                .focusRequester(rowFocusRequester)
                .clearAndSetSemantics {
                    contentDescription = "${track.title}，${track.artist.ifBlank { "未知艺术家" }}，${formatTime(track.durationMs)}"
                    onClick(label = "播放") { onPlay(); true }
                    customActions = listOf(
                        CustomAccessibilityAction("查看歌词") { onViewLyrics(); true },
                        CustomAccessibilityAction("查看并编辑元数据") { onEditMetadata(); true },
                        CustomAccessibilityAction("删除") { deleteConfirmOpen = true; true },
                    )
                },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SongArtworkThumbnail(track, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${track.artist.ifBlank { "未知艺术家" }} · ${formatTime(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("查看歌词") },
                onClick = { menuOpen = false; onViewLyrics() },
                leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("查看并编辑元数据") },
                onClick = { menuOpen = false; onEditMetadata() },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = { menuOpen = false; deleteConfirmOpen = true },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            )
        }
    }
    if (deleteConfirmOpen) {
        TrackDeleteConfirmDialog(
            track = track,
            onDismiss = { deleteConfirmOpen = false; restoreRowFocus = true },
            onConfirm = { deleteConfirmOpen = false; onDelete(track) },
        )
    }
}

@Composable
private fun AlbumEditDialog(
    album: ImportedAlbum,
    onSelectArtwork: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember(album.id) { mutableStateOf(album.name) }
    var artist by remember(album.id) { mutableStateOf(album.artist) }
    var description by remember(album.id) { mutableStateOf(album.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑专辑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("专辑名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("艺术家") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("简介") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSelectArtwork(true) },
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text("选择封面") }
                    OutlinedButton(
                        onClick = { onSelectArtwork(false) },
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text("选择封底") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), artist.trim(), description.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("保存") }
        },
    )
}

@Composable
private fun TrackDetailsMetadataDialog(
    track: NativeTrack,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var title by remember(track.metadataKey()) { mutableStateOf(track.title) }
    var artist by remember(track.metadataKey()) { mutableStateOf(track.artist) }
    var album by remember(track.metadataKey()) { mutableStateOf(track.album) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("查看并编辑元数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("格式：${track.format}")
                Text("时长：${formatTime(track.durationMs)}")
                Text("大小：${formatFileSize(track.sizeBytes)}")
                Text("位置：${track.uri}", maxLines = 3, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("歌曲名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("艺术家") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("专辑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), artist.trim(), album.trim()) },
                enabled = title.isNotBlank(),
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("保存") }
        },
    )
}

@Composable
private fun TrackDeleteConfirmDialog(
    track: NativeTrack,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除音乐文件？") },
        text = { Text("“${track.title}”将从设备中删除。此操作无法撤销。") },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("删除") }
        },
    )
}

internal fun sortedImportedAlbums(albums: List<ImportedAlbum>): List<ImportedAlbum> =
    albums.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

@Composable
private fun CollectionCard(name: String, tracks: List<NativeTrack>, onClick: () -> Unit) {
    val count = tracks.size
    Card(
        Modifier.fillMaxWidth().heightIn(min = 104.dp).clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$name，$count 首歌曲"
                onClick(label = "查看歌曲") { onClick(); true }
            },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlbumArtworkThumbnail(tracks.first(), Modifier.fillMaxWidth().aspectRatio(1f))
            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$count 首歌曲", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FolderCard(
    name: String,
    trackCount: Int,
    videoCount: Int,
    totalSizeBytes: Long,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
) {
    val countLabel = when {
        trackCount > 0 && videoCount > 0 -> "$trackCount 首歌曲、$videoCount 个视频"
        trackCount > 0 -> "$trackCount 首歌曲"
        else -> "$videoCount 个视频"
    }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(deleteConfirmOpen, detailsOpen, restoreFocus) {
        if (!deleteConfirmOpen && !detailsOpen && restoreFocus) {
            focusRequester.requestFocus()
            restoreFocus = false
        }
    }
    Box {
        Card(
            Modifier.fillMaxWidth().heightIn(min = 104.dp)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .focusRequester(focusRequester)
                .clearAndSetSemantics {
                    contentDescription = "$name，文件夹，$countLabel"
                    onClick(label = "打开文件夹") { onClick(); true }
                    customActions = buildList {
                        add(CustomAccessibilityAction("复制文件夹") { onCopy(); true })
                        add(CustomAccessibilityAction("剪切文件夹") { onCut(); true })
                        add(CustomAccessibilityAction("删除文件夹") { deleteConfirmOpen = true; true })
                        add(CustomAccessibilityAction("查看文件夹详情") { detailsOpen = true; true })
                    }
                },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(countLabel, style = MaterialTheme.typography.bodySmall)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = { menuOpen = false; onCopy() },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("剪切") },
                onClick = { menuOpen = false; onCut() },
                leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = { menuOpen = false; deleteConfirmOpen = true },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("详情") },
                onClick = { menuOpen = false; detailsOpen = true },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            )
        }
    }
    if (deleteConfirmOpen) {
        FolderDeleteConfirmDialog(
            folderName = name,
            trackCount = trackCount,
            videoCount = videoCount,
            onDismiss = { deleteConfirmOpen = false; restoreFocus = true },
            onConfirm = { deleteConfirmOpen = false; onDelete() },
        )
    }
    if (detailsOpen) {
        FolderDetailsDialog(
            folderName = name,
            trackCount = trackCount,
            videoCount = videoCount,
            totalSizeBytes = totalSizeBytes,
            onDismiss = { detailsOpen = false; restoreFocus = true },
        )
    }
}

@Composable
private fun FolderDeleteConfirmDialog(
    folderName: String,
    trackCount: Int,
    videoCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除文件夹？") },
        text = { Text("“$folderName”内的 $trackCount 首歌曲${if (videoCount > 0) "和 $videoCount 个视频" else ""}将从设备中删除，此操作无法撤销。") },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("取消") }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("删除") }
        },
    )
}

@Composable
private fun FolderDetailsDialog(
    folderName: String,
    trackCount: Int,
    videoCount: Int,
    totalSizeBytes: Long,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件夹详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件夹：$folderName")
                Text("歌曲数：$trackCount")
                Text("视频数：$videoCount")
                Text("总大小：${formatFileSize(totalSizeBytes)}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("关闭") }
        },
    )
}

@Composable
private fun NowPlayingScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onVolume: (Float) -> Unit,
    onSpeed: (Double) -> Unit,
    onQueueMode: (NativeQueueMode) -> Unit,
    onOpenDevices: () -> Unit,
    onSaveSkipConfig: (NativeTrack?, Float, Float) -> Unit,
    onGetSkipConfig: (NativeTrack?) -> Pair<Float, Float>,
    onToggleSkipIntroOutro: (Boolean) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onStartTrackSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSetAbStart: () -> Unit,
    onSetAbEnd: () -> Unit,
    onClearAbLoop: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onToggleLyricTts: () -> Unit,
    onSetLyricTtsEngine: (String) -> Unit,
    onSetLyricTtsOffset: (Long) -> Unit,
    onSetLyricTtsChannel: (LyricTtsChannel) -> Unit,
) {
    val track = state.currentTrack
    var coverExpanded by rememberSaveable(track?.id) { mutableStateOf(false) }
    var volumeDraft by remember { mutableStateOf(state.volume) }
    LaunchedEffect(state.volume) { volumeDraft = state.volume }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(
            title = "正在播放",
            onBack = onBack,
            onDevices = null,
            trailingContent = {
                NowPlayingMoreMenu(
                    state = state,
                    onOpenDevices = onOpenDevices,
                    onSaveSkipConfig = onSaveSkipConfig,
                    onGetSkipConfig = onGetSkipConfig,
                    onToggleSkipIntroOutro = onToggleSkipIntroOutro,
                    onStartSleepTimer = onStartSleepTimer,
                    onStartTrackSleepTimer = onStartTrackSleepTimer,
                    onCancelSleepTimer = onCancelSleepTimer,
                    onOpenLyrics = onOpenLyrics,
                    onOpenPlaylist = onOpenPlaylist,
                    onSpeed = onSpeed,
                    onQueueMode = onQueueMode,
                    onSetAbStart = onSetAbStart,
                    onSetAbEnd = onSetAbEnd,
                    onClearAbLoop = onClearAbLoop,
                    onToggleLyricTts = onToggleLyricTts,
                    onSetLyricTtsEngine = onSetLyricTtsEngine,
                    onSetLyricTtsOffset = onSetLyricTtsOffset,
                    onSetLyricTtsChannel = onSetLyricTtsChannel,
                )
            },
        )
        if (track == null) {
            Text("尚未选择歌曲。", modifier = Modifier.padding(24.dp))
            return
        }
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AlbumArt(track, compact = !coverExpanded, onToggle = { coverExpanded = !coverExpanded })
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${track.artist.ifBlank { "未知艺术家" }} · ${track.album.ifBlank { "未知专辑" }}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LyricBar(lyrics = state.lyrics, positionMs = state.positionMs)
            }
        }
        NowPlayingControls(
            track = track,
            state = state,
            volumeDraft = volumeDraft,
            onVolumeDraftChange = { volumeDraft = it },
            onSeek = onSeek,
            onPrevious = onPrevious,
            onToggle = onToggle,
            onNext = onNext,
            onVolume = onVolume,
            canSkipPrevious = skipStepAvailable(
                step = -1,
                isVideo = false,
                audioIndex = state.queueIndex,
                audioSize = state.queue.size,
                videoIndex = state.videoIndex,
                videoSize = state.videoQueue.size,
                audioMode = state.queueMode,
            ),
            canSkipNext = skipStepAvailable(
                step = 1,
                isVideo = false,
                audioIndex = state.queueIndex,
                audioSize = state.queue.size,
                videoIndex = state.videoIndex,
                videoSize = state.videoQueue.size,
                audioMode = state.queueMode,
            ),
            skipLabelsLive = false,
        )
    }
}

@Composable
private fun NowPlayingMoreMenu(
    state: MusicUiState,
    onOpenDevices: () -> Unit,
    onSaveSkipConfig: (NativeTrack?, Float, Float) -> Unit,
    onGetSkipConfig: (NativeTrack?) -> Pair<Float, Float>,
    onToggleSkipIntroOutro: (Boolean) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onStartTrackSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onSpeed: (Double) -> Unit,
    onQueueMode: (NativeQueueMode) -> Unit,
    onSetAbStart: () -> Unit,
    onSetAbEnd: () -> Unit,
    onClearAbLoop: () -> Unit,
    onToggleLyricTts: () -> Unit,
    onSetLyricTtsEngine: (String) -> Unit,
    onSetLyricTtsOffset: (Long) -> Unit,
    onSetLyricTtsChannel: (LyricTtsChannel) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf(NowPlayingMorePanel.Root) }
    var restoreMoreFocus by remember { mutableStateOf(false) }
    val moreFocusRequester = remember { FocusRequester() }
    val abActions = buildList {
        if (!state.isCasting && state.currentTrack != null) {
            add("设定 A 点" to onSetAbStart)
            if (state.abStartMs != null) add("设定 B 点" to onSetAbEnd)
            if (state.abStartMs != null || state.abEndMs != null) add("清除 A-B 循环" to onClearAbLoop)
        }
    }
    val abLabel = when {
        state.abEndMs != null -> "A-B 循环：已开启"
        state.abStartMs != null -> "A-B 循环：等待 B 点"
        else -> "A-B 循环"
    }
    fun dismiss() {
        expanded = false
        panel = NowPlayingMorePanel.Root
        restoreMoreFocus = true
    }
    LaunchedEffect(expanded, restoreMoreFocus) {
        if (!expanded && restoreMoreFocus) {
            moreFocusRequester.requestFocus()
            restoreMoreFocus = false
        }
    }

    Box {
        IconButton(
            onClick = {
                if (expanded) dismiss() else {
                    expanded = true
                    panel = NowPlayingMorePanel.Root
                }
            },
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .focusRequester(moreFocusRequester),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = ::dismiss,
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
        ) {
            when (panel) {
                NowPlayingMorePanel.Root -> {
                    DropdownMenuItem(text = { Text("播放设备") }, onClick = { dismiss(); onOpenDevices() })
                    DropdownMenuItem(
                        text = { Text(if (state.sleepTimerMode != SleepTimerMode.None) "睡眠定时，运行中" else "睡眠定时") },
                        onClick = { panel = NowPlayingMorePanel.SleepTimer },
                    )
                    DropdownMenuItem(text = { Text("歌词") }, onClick = { dismiss(); onOpenLyrics() })
                    DropdownMenuItem(
                        text = { Text(if (state.lyricTtsEnabled) "朗读歌词，已开启" else "朗读歌词") },
                        onClick = { panel = NowPlayingMorePanel.LyricTts },
                    )
                    DropdownMenuItem(text = { Text("播放列表") }, onClick = { dismiss(); onOpenPlaylist() })
                    DropdownMenuItem(
                        text = { Text("播放速度：${state.playbackSpeed} 倍") },
                        onClick = { panel = NowPlayingMorePanel.Speed },
                    )
                    DropdownMenuItem(
                        text = { Text("播放模式：${queueModeLabel(state.queueMode)}") },
                        onClick = { panel = NowPlayingMorePanel.QueueMode },
                    )
                    DropdownMenuItem(
                        text = { Text(abLabel) },
                        enabled = abActions.isNotEmpty(),
                        onClick = { panel = NowPlayingMorePanel.AbLoop },
                    )
                    // 跳过片头片尾总开关：关闭时设置项不显示（无意义入口），开启后才出现。
                    Row(
                        Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (state.skipIntroOutroEnabled) "跳过片头片尾，已开启" else "跳过片头片尾",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(
                            checked = state.skipIntroOutroEnabled,
                            onCheckedChange = onToggleSkipIntroOutro,
                            modifier = Modifier.semantics {
                                contentDescription = "跳过片头片尾"
                                stateDescription = if (state.skipIntroOutroEnabled) "已开启" else "已关闭"
                            },
                        )
                    }
                    if (state.skipIntroOutroEnabled) {
                        DropdownMenuItem(
                            text = { Text("跳过片头片尾设置") },
                            onClick = { panel = NowPlayingMorePanel.SkipIntroOutro },
                        )
                    }
                }

                NowPlayingMorePanel.SleepTimer -> NowPlayingMorePanelContent("睡眠定时", { panel = NowPlayingMorePanel.Root }) {
                    SleepPanel(state, onStartSleepTimer, onStartTrackSleepTimer, onCancelSleepTimer)
                }

                NowPlayingMorePanel.Speed -> NowPlayingMorePanelContent("播放速度", { panel = NowPlayingMorePanel.Root }) {
                    listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).forEach { option ->
                        DropdownMenuItem(
                            text = { Text("${option} 倍${if (option == state.playbackSpeed) "（当前）" else ""}") },
                            onClick = { dismiss(); onSpeed(option) },
                        )
                    }
                }

                NowPlayingMorePanel.QueueMode -> NowPlayingMorePanelContent("播放模式", { panel = NowPlayingMorePanel.Root }) {
                    NativeQueueMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("${queueModeLabel(option)}${if (option == state.queueMode) "（当前）" else ""}") },
                            onClick = { dismiss(); onQueueMode(option) },
                        )
                    }
                }

                NowPlayingMorePanel.AbLoop -> NowPlayingMorePanelContent("A-B 循环", { panel = NowPlayingMorePanel.Root }) {
                    abActions.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { dismiss(); action() })
                    }
                }

                NowPlayingMorePanel.LyricTts -> NowPlayingMorePanelContent("朗读歌词", { panel = NowPlayingMorePanel.Root }) {
                    LyricTtsPanel(
                        state = state,
                        onToggle = onToggleLyricTts,
                        onSetEngine = onSetLyricTtsEngine,
                        onSetOffset = onSetLyricTtsOffset,
                        onSetChannel = onSetLyricTtsChannel,
                    )
                }

                NowPlayingMorePanel.SkipIntroOutro -> NowPlayingMorePanelContent("跳过片头片尾", { panel = NowPlayingMorePanel.Root }) {
                    SkipIntroOutroPanel(
                        state = state,
                        onGetSkipConfig = onGetSkipConfig,
                        onSave = onSaveSkipConfig,
                    )
                }
            }
        }
    }
}

/** 秒数格式化：整数值不带小数（如 5），非整数保留一位（如 4.5）。 */
private fun formatSeconds(sec: Float): String =
    if (sec == sec.toInt().toFloat()) "%.0f".format(sec) else "%.1f".format(sec)

@Composable
private fun SkipIntroOutroPanel(
    state: MusicUiState,
    onGetSkipConfig: (NativeTrack?) -> Pair<Float, Float>,
    onSave: (NativeTrack?, Float, Float) -> Unit,
) {
    val track = state.currentTrack
    val currentSec = state.positionMs / 1_000f
    val durationSec = track?.durationMs?.takeIf { it > 0 }?.div(1_000f)
    val existing = remember(track?.uri) { onGetSkipConfig(track) }
    var headText by rememberSaveable(track?.uri) { mutableStateOf(formatSeconds(existing.first)) }
    var tailText by rememberSaveable(track?.uri) { mutableStateOf(formatSeconds(existing.second)) }
    var saveMessage by rememberSaveable(track?.uri) { mutableStateOf<String?>(null) }
    val headSec = headText.toFloatOrNull()
    val tailSec = tailText.toFloatOrNull()
    val valuesValid = headSec != null && tailSec != null && headSec >= 0f && tailSec >= 0f
    val scope = remember(track?.uri) {
        track?.album?.takeIf { it.isNotBlank() }?.let { "专辑“$it”" }
            ?: track?.folderPath?.takeIf { it.isNotBlank() }?.let { "文件夹“$it”" }
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(scope?.let { "保存范围：${if (track?.album?.isNotBlank() == true) "当前专辑" else "当前文件夹"}" }
            ?: "当前歌曲没有专辑或文件夹，无法保存。", style = MaterialTheme.typography.bodySmall)
        Text("播放位置：${formatSeconds(currentSec)} 秒", style = MaterialTheme.typography.bodySmall)
        SkipDurationEditor(
            title = "片头跳过",
            value = headText,
            onValueChange = { headText = it.filter { char -> char.isDigit() || char == '.' }.take(8); saveMessage = null },
            onDecrease = { headText = formatSeconds(((headSec ?: 0f) - 0.5f).coerceAtLeast(0f)); saveMessage = null },
            onIncrease = { headText = formatSeconds((headSec ?: 0f) + 0.5f); saveMessage = null },
            onSetCurrent = { headText = formatSeconds(currentSec.coerceAtLeast(0f)); saveMessage = null },
            setCurrentLabel = "设为当前位置",
            invalid = headSec == null || headSec < 0f,
        )
        HorizontalDivider()
        SkipDurationEditor(
            title = "片尾跳过",
            value = tailText,
            onValueChange = { tailText = it.filter { char -> char.isDigit() || char == '.' }.take(8); saveMessage = null },
            onDecrease = { tailText = formatSeconds(((tailSec ?: 0f) - 0.5f).coerceAtLeast(0f)); saveMessage = null },
            onIncrease = { tailText = formatSeconds((tailSec ?: 0f) + 0.5f); saveMessage = null },
            onSetCurrent = {
                durationSec?.let { tailText = formatSeconds((it - currentSec).coerceAtLeast(0f)); saveMessage = null }
            },
            setCurrentLabel = "从当前位置计算",
            setCurrentEnabled = durationSec != null,
            invalid = tailSec == null || tailSec < 0f,
        )
        saveMessage?.let { message ->
            Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                onSave(track, headSec ?: 0f, tailSec ?: 0f)
                saveMessage = "已保存到${scope.orEmpty()}"
            },
            enabled = scope != null && valuesValid,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        ) { Text("保存设置") }
    }
}

@Composable
private fun SkipDurationEditor(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onSetCurrent: () -> Unit,
    setCurrentLabel: String,
    setCurrentEnabled: Boolean = true,
    invalid: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("秒数") },
            suffix = { Text("秒") },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) ({ Text("请输入大于或等于 0 的秒数。") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onDecrease, modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 48.dp)) { Text("-0.5s") }
            OutlinedButton(
                onClick = onSetCurrent,
                enabled = setCurrentEnabled,
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = setCurrentLabel },
            ) { Text("设为当前") }
            OutlinedButton(onClick = onIncrease, modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 48.dp)) { Text("+0.5s") }
        }
        if (!setCurrentEnabled) Text("当前歌曲时长未知，无法根据当前位置计算片尾时长。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NowPlayingMorePanelContent(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val focusRequester = remember(title) { FocusRequester() }
    LaunchedEffect(title) { focusRequester.requestFocus() }
    Column(
        Modifier.heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .semantics { paneTitle = title },
    ) {
        DropdownMenuItem(
            text = { Text("返回更多") },
            onClick = onBack,
            modifier = Modifier.focusRequester(focusRequester),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        )
        content()
    }
}

@Composable
private fun NowPlayingControls(
    track: NativeTrack,
    state: MusicUiState,
    volumeDraft: Float,
    onVolumeDraftChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onVolume: (Float) -> Unit,
    canSkipPrevious: Boolean = false,
    canSkipNext: Boolean = false,
    /** 直播频道：按钮文案说「频道」而不是「首」，并在清单只有一条时置灰。 */
    skipLabelsLive: Boolean = false,
) {
    val knownDuration = track.durationMs > 0
    val isLive = state.currentTrackLive
    val previousLabel = skipStepLabel(-1, isVideo = false, isLive = skipLabelsLive)
    val nextLabel = skipStepLabel(1, isVideo = false, isLive = skipLabelsLive)
    val maximum = track.durationMs.coerceAtLeast(1)
    // 音量与播放/暂停是普通 Button/IconButton（不走 TransportIconButton），触感在此单独挂。
    val hapticView = LocalView.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLive) {
                // 直播音频（蜻蜓电台等）：无总时长、不可拖动；可见文本已是完整语义，不再叠加描述。
                Text("直播中")
            } else if (knownDuration) {
                Slider(
                    value = state.positionMs.coerceIn(0, maximum).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    onValueChangeFinished = { MediaHaptics.tick(hapticView) },
                    valueRange = 0f..maximum.toFloat(),
                    // 只保留时间 stateDescription（替代默认百分比播报）；可见时间文本与
                    // rangeInfo 已覆盖进度语义，再挂 contentDescription 会三重播报。
                    modifier = Modifier.semantics {
                        stateDescription = "${formatTime(state.positionMs)}，共 ${formatTime(maximum)}"
                    },
                )
                Text("${formatTime(state.positionMs)} / ${formatTime(maximum)}")
            } else {
                // 时长未知（网盘有声等流媒体起播前拿不到总长，起播后由播放内核回填）：
                // 不渲染退化成 0..1 的进度条，只给出当前时间戳。
                Text("已播放 ${formatTime(state.positionMs)}")
            }
            // 音量：按系统音量层级 15 档，用「降低/提高」两个按钮控制；投送（DLNA/Chromecast）复用同一 onVolume。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val level = (volumeDraft * VOLUME_MAX_LEVEL).roundToInt().coerceIn(0, VOLUME_MAX_LEVEL)
                        val next = (level - 1).coerceAtLeast(0) / VOLUME_MAX_LEVEL.toFloat()
                        MediaHaptics.tick(hapticView)
                        onVolumeDraftChange(next)
                        onVolume(next)
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "降低音量" },
                ) {
                    Icon(Icons.Filled.VolumeDown, contentDescription = null)
                }
                Text(
                    "音量 ${(volumeDraft * VOLUME_MAX_LEVEL).roundToInt().coerceIn(0, VOLUME_MAX_LEVEL)} / $VOLUME_MAX_LEVEL",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    // liveRegion 保留：调音量时按钮聚焦不转移，播报新档位是唯一的读屏反馈；
                    // 但不再挂 contentDescription 复述同一可见文本。
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                IconButton(
                    onClick = {
                        val level = (volumeDraft * VOLUME_MAX_LEVEL).roundToInt().coerceIn(0, VOLUME_MAX_LEVEL)
                        val next = (level + 1).coerceAtMost(VOLUME_MAX_LEVEL) / VOLUME_MAX_LEVEL.toFloat()
                        MediaHaptics.tick(hapticView)
                        onVolumeDraftChange(next)
                        onVolume(next)
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "提高音量" },
                ) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TransportIconButton(
                    icon = Icons.Default.Replay10,
                    label = "快退10秒",
                    onClick = { onSeek((state.positionMs - MEDIA_SEEK_STEP_MS).coerceAtLeast(0)) },
                )
                TransportIconButton(
                    icon = Icons.Default.SkipPrevious,
                    label = previousLabel,
                    onClick = onPrevious,
                    onLongClick = { onSeek((state.positionMs - MEDIA_SEEK_STEP_MS).coerceAtLeast(0)) },
                    enabled = canSkipPrevious,
                    loading = state.onlineResolving && !state.onlineResolvingForward,
                )
                Button(onClick = { MediaHaptics.tap(hapticView); onToggle() }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.playing) "暂停" else "播放")
                }
                TransportIconButton(
                    icon = Icons.Default.SkipNext,
                    label = nextLabel,
                    onClick = onNext,
                    // 时长未知时不做上限钳制（交由 VM seek 兜底），避免钳到 1ms 的假上限。
                    onLongClick = { onSeek(state.positionMs + MEDIA_SEEK_STEP_MS) },
                    enabled = canSkipNext,
                    loading = state.onlineResolving && state.onlineResolvingForward,
                )
                TransportIconButton(
                    icon = Icons.Default.Forward10,
                    label = "快进10秒",
                    onClick = { onSeek(state.positionMs + MEDIA_SEEK_STEP_MS) },
                )
            }
        }
    }
}

@Composable
private fun AbLoopMenu(
    state: MusicUiState,
    onSetStart: () -> Unit,
    onSetEnd: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val actions = buildList {
        if (!state.isCasting && state.currentTrack != null) {
            add("设定 A 点" to onSetStart)
            if (state.abStartMs != null) add("设定 B 点" to onSetEnd)
            if (state.abStartMs != null || state.abEndMs != null) add("清除 A-B 循环" to onClear)
        }
    }
    val label = when {
        state.abEndMs != null -> "A-B 循环：已开启"
        state.abStartMs != null -> "A-B 循环：等待 B 点"
        else -> "A-B 循环"
    }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = actions.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                customActions = actions.map { (actionLabel, action) ->
                    CustomAccessibilityAction(actionLabel) { action(); true }
                }
            },
        ) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (actionLabel, action) ->
                DropdownMenuItem(
                    text = { Text(actionLabel) },
                    onClick = { expanded = false; action() },
                )
            }
        }
    }
}

@Composable
private fun SleepPanel(
    state: MusicUiState,
    onStartSleepTimer: (Int) -> Unit,
    onStartTrackSleepTimer: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
) {
    var selectedMode by rememberSaveable {
        mutableStateOf(if (state.sleepTimerMode == SleepTimerMode.Tracks) SleepTimerMode.Tracks else SleepTimerMode.Minutes)
    }
    LaunchedEffect(state.sleepTimerMode) {
        if (state.sleepTimerMode != SleepTimerMode.None) selectedMode = state.sleepTimerMode
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (selectedMode == SleepTimerMode.Minutes) "按时间" else "按曲目" },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val segments = listOf(
                    SleepTimerMode.Minutes to "按时间",
                    SleepTimerMode.Tracks to "按曲目",
                )
                segments.forEach { (mode, label) ->
                    val selected = selectedMode == mode
                    OutlinedButton(
                        onClick = { selectedMode = mode },
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                        colors = if (selected) {
                            ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) {
                        Text(label)
                    }
                }
            }

            val remainingSeconds = (state.sleepTimerRemainingMs + 999L) / 1_000L
            val minutes = remainingSeconds / 60L
            val seconds = remainingSeconds % 60L
            val timerAnnouncement = when (state.sleepTimerMode) {
                SleepTimerMode.Minutes -> "睡眠定时运行中，剩余 $minutes 分 $seconds 秒"
                SleepTimerMode.Tracks -> "睡眠定时运行中，还剩 ${state.sleepTimerRemainingTracks} 曲"
                SleepTimerMode.None -> state.sleepTimerStatus
            }

            if (state.sleepTimerMode != SleepTimerMode.None) {
                val progress = when (state.sleepTimerMode) {
                    SleepTimerMode.Minutes -> if (state.sleepTimerTotalMs > 0) {
                        state.sleepTimerRemainingMs.toFloat() / state.sleepTimerTotalMs
                    } else 0f
                    SleepTimerMode.Tracks -> if (state.sleepTimerTotalTracks > 0) {
                        state.sleepTimerRemainingTracks.toFloat() / state.sleepTimerTotalTracks
                    } else 0f
                    SleepTimerMode.None -> 0f
                }.coerceIn(0f, 1f)
                val centerText = when (state.sleepTimerMode) {
                    SleepTimerMode.Minutes -> "%d:%02d".format(minutes, seconds)
                    SleepTimerMode.Tracks -> "还剩\n${state.sleepTimerRemainingTracks} 曲"
                    SleepTimerMode.None -> ""
                }
                val transition = rememberInfiniteTransition(label = "sleep_timer")
                val rotation by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
                    label = "sleep_timer_rotation",
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(120.dp).semantics {
                            contentDescription = "睡眠定时进度"
                            stateDescription = timerAnnouncement.orEmpty()
                            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        },
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize().rotate(rotation).clearAndSetSemantics {},
                            strokeWidth = 6.dp,
                        )
                        Text(
                            centerText,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    OutlinedButton(
                        onClick = onCancelSleepTimer,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) { Text("取消定时") }
                }
            }

            timerAnnouncement?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = status
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }

            HorizontalDivider()
            if (selectedMode == SleepTimerMode.Minutes) {
                listOf(
                    5 to "5 分钟", 10 to "10 分钟", 15 to "15 分钟",
                    30 to "30 分钟", 45 to "45 分钟", 60 to "1 小时", 90 to "1.5 小时",
                ).chunked(2).forEach { options ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { (minutesOption, label) ->
                            OutlinedButton(
                                onClick = { onStartSleepTimer(minutesOption) },
                                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                            ) { Text(label) }
                        }
                        if (options.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                if (state.currentTrack == null) {
                    Text(
                        "请先选择一首歌曲。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                listOf(1 to "播完本曲", 3 to "播完 3 曲", 5 to "播完 5 曲", 10 to "播完 10 曲")
                    .chunked(2)
                    .forEach { options ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { (count, label) ->
                                OutlinedButton(
                                    onClick = { onStartTrackSleepTimer(count) },
                                    enabled = state.currentTrack != null,
                                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                                ) { Text(label) }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun LyricTtsPanel(
    state: MusicUiState,
    onToggle: () -> Unit,
    onSetEngine: (String) -> Unit,
    onSetOffset: (Long) -> Unit,
    onSetChannel: (LyricTtsChannel) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .toggleable(
                        value = state.lyricTtsEnabled,
                        onValueChange = { onToggle() },
                        role = Role.Switch,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "跟随播放进度朗读歌词",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.lyricTtsEnabled,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            if (state.lyricTtsEnabled) {
                HorizontalDivider()
                if (state.ttsEngines.isEmpty()) {
                    Text(
                        state.lyricTtsStatus ?: "未找到系统语音引擎，请在系统设置中安装语音。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    var engineExpanded by remember { mutableStateOf(false) }
                    val currentLabel = state.ttsEngines.firstOrNull { it.packageName == state.lyricTtsEngine }?.label
                    Box {
                        OutlinedButton(
                            onClick = { engineExpanded = true },
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                        ) { Text(currentLabel ?: "选择语音引擎") }
                        DropdownMenu(
                            expanded = engineExpanded,
                            onDismissRequest = { engineExpanded = false },
                        ) {
                            state.ttsEngines.forEach { engine ->
                                DropdownMenuItem(
                                    text = { Text(engine.label) },
                                    onClick = { engineExpanded = false; onSetEngine(engine.packageName) },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("输出通道", style = MaterialTheme.typography.bodyMedium)
                var channelExpanded by remember { mutableStateOf(false) }
                val channelLabel = when (state.lyricTtsChannel) {
                    LyricTtsChannel.Media -> "媒体"
                    LyricTtsChannel.Ringtone -> "铃声"
                    LyricTtsChannel.Accessibility -> "无障碍"
                }
                Box {
                    OutlinedButton(
                        onClick = { channelExpanded = true },
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) { Text(channelLabel) }
                    DropdownMenu(
                        expanded = channelExpanded,
                        onDismissRequest = { channelExpanded = false },
                    ) {
                        listOf(
                            LyricTtsChannel.Media to "媒体",
                            LyricTtsChannel.Ringtone to "铃声",
                            LyricTtsChannel.Accessibility to "无障碍",
                        ).forEach { (ch, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { channelExpanded = false; onSetChannel(ch) },
                            )
                        }
                    }
                }
                state.lyricTtsStatus?.takeIf { state.ttsEngines.isNotEmpty() }?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                HorizontalDivider()
                val offsetMs = normalizeLyricOffset(state.lyricTtsOffsetMs)
                val offsetSeconds = (offsetMs / 1_000L).toInt()
                val offsetText = when {
                    offsetSeconds > 0 -> "+${offsetSeconds}s"
                    offsetSeconds < 0 -> "-${-offsetSeconds}s"
                    else -> "0s"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onSetOffset(stepLyricOffset(offsetMs, -1)) },
                        enabled = offsetMs > LYRIC_OFFSET_MIN_MS,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text("-1s") }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .clearAndSetSemantics {
                                contentDescription = "歌词朗读偏移，当前 $offsetText"
                                stateDescription = lyricOffsetAnnouncement(offsetMs)
                                liveRegion = LiveRegionMode.Polite
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    current = offsetSeconds.toFloat(),
                                    range = -2f..2f,
                                    steps = 3,
                                )
                                setProgress { target ->
                                    val next = target.roundToInt().coerceIn(-2, 2) * 1_000L
                                    if (next == offsetMs) false else {
                                        onSetOffset(next)
                                        true
                                    }
                                }
                            },
                    ) {
                        Text(offsetText, style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(
                        onClick = { onSetOffset(stepLyricOffset(offsetMs, 1)) },
                        enabled = offsetMs < LYRIC_OFFSET_MAX_MS,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text("+1s") }
                }
            }
        }
    }
}

@Composable
private fun DeviceScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onScanDevices: () -> Unit,
    onSelectHistory: (CastHistoryEntry) -> Unit,
    onRefreshAudioOutputs: () -> Unit,
    onSelectAudioOutput: (AudioOutput) -> Unit,
    bluetoothPermissionGranted: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    onSelectDlna: (DlnaDevice) -> Unit,
    chromecastDevices: List<String> = emptyList(),
    onSelectChromecast: (String) -> Unit = {},
    onCastVolumeChange: (Float) -> Unit = {},
) {
    LaunchedEffect(bluetoothPermissionGranted) { onRefreshAudioOutputs() }
    val localOutputs = state.audioOutputs.filter { it.protocol == "本机" }
    val bluetoothOutputs = state.audioOutputs.filter { it.protocol == "蓝牙" }
    val historyIds = state.history.map { "${it.protocol}:${it.id}" }.toSet()
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "播放设备", onBack = onBack, onDevices = null)
        StatusMessage(state.status)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("本机输出") }
            gridItems(localOutputs, key = { it.id }) { output ->
                DeviceButton(output.name, state.targetName == output.name, !state.switchingDevice) { onSelectAudioOutput(output) }
            }
            if (bluetoothPermissionGranted && bluetoothOutputs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("蓝牙") }
                gridItems(bluetoothOutputs, key = { it.id }) { output ->
                    DeviceButton(output.name, state.targetName == output.name, output.device != null && !state.switchingDevice) {
                        onSelectAudioOutput(output)
                    }
                }
            } else if (!bluetoothPermissionGranted) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("蓝牙") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = onRequestBluetoothPermission, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                        Text("授权查看已连接蓝牙设备")
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("Wi‑Fi 投送") }
            if (state.castGroupNames.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("同播组（多房间）") }
                gridItems(state.castGroupNames, key = { "group:$it" }) { name ->
                    DeviceButton("$name（同播中）", false, false) {}
                }
            }
            if (state.history.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("历史连接") }
                gridItems(state.history, key = { "history:${it.protocol}:${it.id}" }) { device ->
                    DeviceButton("${device.name}（${device.protocolLabel}）", false, !state.switchingDevice && !state.scanningDevices) { onSelectHistory(device) }
                }
            }
            val newDlna = state.dlnaDevices.filterNot { "dlna:${it.id}" in historyIds }
            if (newDlna.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("新设备") }
                gridItems(newDlna, key = { "dlna:${it.id}" }) { device ->
                    DeviceButton("${device.name}（DLNA）", false, !state.switchingDevice) { onSelectDlna(device) }
                }
            }
            if (state.chromecastDevices.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading("Chromecast") }
                gridItems(state.chromecastDevices, key = { "cast:$it" }) { name ->
                    DeviceButton("$name（Chromecast）", state.targetName == name, !state.switchingDevice) {
                        onSelectChromecast(name)
                    }
                }
            }
            if (state.isCasting) {
                // 投送中面板直接调音量：拖动本地草稿、松手提交，避免逐帧 SOAP 请求刷爆设备
                item(span = { GridItemSpan(maxLineSpan) }) {
                    var volumeDraft by remember { mutableStateOf(state.volume) }
                    LaunchedEffect(state.volume) { volumeDraft = state.volume }
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        SectionHeading("投送音量")
                        Slider(
                            value = volumeDraft,
                            onValueChange = { volumeDraft = it },
                            onValueChangeFinished = { onCastVolumeChange(volumeDraft) },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth().semantics {
                                stateDescription = "${(volumeDraft * VOLUME_MAX_LEVEL).roundToInt()} / $VOLUME_MAX_LEVEL"
                            },
                        )
                    }
                }
            }
        }
        Button(
            onClick = onScanDevices,
            enabled = !state.scanningDevices && !state.switchingDevice,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).sizeIn(minHeight = 48.dp),
        ) { Text(if (state.scanningDevices) "正在搜索设备" else "搜索新设备") }
    }
}

@Composable
private fun DeviceButton(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
        Text("$name${if (selected) "，当前使用" else ""}", maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun AppBar(
    title: String,
    onBack: (() -> Unit)?,
    onDevices: (() -> Unit)?,
    onPaste: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    editModifier: Modifier = Modifier,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(GlassTheme.TopBarHeight)
            .padding(horizontal = GlassTheme.PageHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            // 标签直接放在可点击节点上：个别厂商读屏不朗读 IconButton 内层 Icon 的合并语义。
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .sizeIn(
                        minWidth = GlassTheme.MinTouchTarget,
                        minHeight = GlassTheme.MinTouchTarget,
                    )
                    .semantics { contentDescription = "返回" },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }
        leadingContent?.invoke(this)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onSearch != null) {
            IconButton(
                onClick = onSearch,
                modifier = Modifier.sizeIn(
                    minWidth = GlassTheme.MinTouchTarget,
                    minHeight = GlassTheme.MinTouchTarget,
                ),
            ) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        }
        if (onImport != null) {
            IconButton(
                onClick = onImport,
                modifier = Modifier.sizeIn(
                    minWidth = GlassTheme.MinTouchTarget,
                    minHeight = GlassTheme.MinTouchTarget,
                ),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = "导入")
            }
        }
        if (onEdit != null) {
            TextButton(onClick = onEdit, modifier = editModifier.sizeIn(minHeight = 48.dp)) {
                Text("编辑专辑")
            }
        }
        if (onPaste != null) {
            IconButton(
                onClick = onPaste,
                modifier = Modifier.sizeIn(
                    minWidth = GlassTheme.MinTouchTarget,
                    minHeight = GlassTheme.MinTouchTarget,
                ),
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = "粘贴到当前文件夹")
            }
        }
        if (onDevices != null) {
            IconButton(
                onClick = onDevices,
                modifier = Modifier.sizeIn(
                    minWidth = GlassTheme.MinTouchTarget,
                    minHeight = GlassTheme.MinTouchTarget,
                ),
            ) {
                Icon(Icons.Default.Cast, contentDescription = "播放设备")
            }
        }
        trailingContent?.invoke(this)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TrackCard(
    track: NativeTrack,
    onClick: () -> Unit,
    onViewLyrics: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onUpdateTrackMetadata: (NativeTrack, String, String, String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var metadataEditorOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var restoreCardFocus by remember { mutableStateOf(false) }
    val cardFocusRequester = remember { FocusRequester() }
    val favoriteLabel = if (favorite) "取消收藏" else "收藏"
    val label = "${track.title.substringBeforeLast('.', track.title)}，${track.format}${if (track.isCueTrack) "" else "，${formatFileSize(track.sizeBytes)}"}，${formatTime(track.durationMs)}${if (favorite) "，已收藏" else ""}"
    LaunchedEffect(metadataEditorOpen, restoreCardFocus) {
        if (!metadataEditorOpen && restoreCardFocus) {
            cardFocusRequester.requestFocus()
            restoreCardFocus = false
        }
    }
    Box {
        Card(
            Modifier.fillMaxWidth().heightIn(min = 104.dp)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .focusRequester(cardFocusRequester)
                .clearAndSetSemantics {
                    contentDescription = label
                    onClick(label = "播放") { onClick(); true }
                    customActions = listOf(
                        CustomAccessibilityAction("查看歌词") { onViewLyrics(); true },
                        CustomAccessibilityAction("复制") { onCopy(); true },
                        CustomAccessibilityAction("剪切") { onCut(); true },
                        CustomAccessibilityAction("分享") { onShare(); true },
                        CustomAccessibilityAction("查看并编辑元数据") { metadataEditorOpen = true; true },
                        CustomAccessibilityAction(favoriteLabel) { onToggleFavorite(); true },
                        CustomAccessibilityAction("删除") { deleteConfirmOpen = true; true },
                    )
                },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SongArtworkThumbnail(track, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} · ${track.format}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("查看歌词") },
                onClick = { menuOpen = false; onViewLyrics() },
                leadingIcon = { Icon(Icons.Default.MusicNote, null) },
            )
            DropdownMenuItem(text = { Text("复制") }, onClick = { menuOpen = false; onCopy() }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
            DropdownMenuItem(text = { Text("剪切") }, onClick = { menuOpen = false; onCut() }, leadingIcon = { Icon(Icons.Default.ContentCut, null) })
            DropdownMenuItem(text = { Text("分享") }, onClick = { menuOpen = false; onShare() }, leadingIcon = { Icon(Icons.Default.Share, null) })
            DropdownMenuItem(
                text = { Text("查看并编辑元数据") },
                onClick = { menuOpen = false; metadataEditorOpen = true },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
            )
            DropdownMenuItem(
                text = { Text(favoriteLabel) },
                onClick = { menuOpen = false; onToggleFavorite() },
                leadingIcon = { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
            )
            DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; deleteConfirmOpen = true }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
    if (metadataEditorOpen) {
        TrackDetailsMetadataDialog(
            track = track,
            onDismiss = {
                metadataEditorOpen = false
                restoreCardFocus = true
            },
            onSave = { title, artist, album ->
                onUpdateTrackMetadata(track, title, artist, album)
                metadataEditorOpen = false
                restoreCardFocus = true
            },
        )
    }
    if (deleteConfirmOpen) {
        TrackDeleteConfirmDialog(
            track = track,
            onDismiss = { deleteConfirmOpen = false },
            onConfirm = { deleteConfirmOpen = false; onDelete() },
        )
    }
}

@Composable
private fun AlbumArt(track: NativeTrack, compact: Boolean = false, onToggle: (() -> Unit)? = null) {
    // 封面展开/收起的尺寸过渡；系统关闭动画时直接切换。
    val sizeAnimation = if (rememberMotionEnabled()) {
        Modifier.animateContentSize(tween(GlassTheme.MotionLong, easing = GlassTheme.EasingEmphasized))
    } else {
        Modifier
    }
    val artModifier = if (compact) {
        Modifier.fillMaxWidth().height(120.dp)
    } else {
        Modifier.fillMaxWidth().aspectRatio(1f)
    }.then(sizeAnimation)
    val accessibilityModifier = if (onToggle == null) Modifier else Modifier.semantics {
        contentDescription = "歌曲封面：${track.title}"
        stateDescription = if (compact) "已收起，点击展开" else "已展开，点击收起"
        onClick(label = if (compact) "展开封面" else "收起封面") { onToggle(); true }
    }
    val clickableModifier = if (onToggle == null) Modifier else Modifier.clickable(onClick = onToggle)
    if (track.artworkUrl != null) {
        Card(
            artModifier.then(clickableModifier).then(accessibilityModifier),
        ) {
            OnlineArtwork(
                track.artworkUrl,
                Modifier.fillMaxSize(),
                placeholderSeed = track.title,
                placeholderKind = BrandArtworkKind.Music,
            )
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = "art:${track.id}"
    val bitmap by produceState<Bitmap?>(initialValue = LocalArtworkCache.get(cacheKey), track.id) {
        value = LocalArtworkCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            loadSongArtwork(context, track, 720)?.also { LocalArtworkCache.put(cacheKey, it) }
        }
    }
    val modifier = artModifier.then(clickableModifier).then(accessibilityModifier)
    Card(modifier) {
        ArtworkContent(bitmap, Modifier.fillMaxSize(), placeholderSeed = track.title, placeholderKind = BrandArtworkKind.Music)
    }
}

@Composable
private fun ImportedAlbumArtwork(uri: String?, modifier: Modifier, placeholderSeed: String = "月播") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = uri?.let { artworkUri ->
            withContext(Dispatchers.IO) { loadArtworkUri(context, Uri.parse(artworkUri)) }
        }
    }
    ArtworkContent(bitmap, modifier, placeholderSeed, BrandArtworkKind.Music)
}

@Composable
private fun LocalAlbumArtwork(album: LibraryAlbum<NativeTrack>, modifier: Modifier, targetSizePx: Int = 192) {
    val coverUri = album.importedAlbum?.frontCoverUri
    if (coverUri != null) {
        ImportedAlbumArtwork(coverUri, modifier, placeholderSeed = album.name)
    } else {
        val firstTrack = album.tracks.firstOrNull()
        if (firstTrack != null) {
            AlbumArtworkThumbnail(firstTrack, modifier, targetSizePx)
        } else {
            ArtworkContent(null, modifier, placeholderSeed = album.name, placeholderKind = BrandArtworkKind.Music)
        }
    }
}

@Composable
private fun SongArtworkThumbnail(track: NativeTrack, modifier: Modifier) {
    if (track.artworkUrl != null) {
        OnlineArtwork(
            track.artworkUrl,
            modifier,
            placeholderSeed = track.title,
            placeholderKind = BrandArtworkKind.Music,
        )
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = "${track.id}:192"
    val bitmap by produceState<Bitmap?>(initialValue = LocalArtworkCache.get(cacheKey), track.id) {
        value = LocalArtworkCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            loadSongArtwork(context, track, 192)?.also { LocalArtworkCache.put(cacheKey, it) }
        }
    }
    ArtworkContent(bitmap, modifier, placeholderSeed = track.title, placeholderKind = BrandArtworkKind.Music)
}

@Composable
private fun AlbumArtworkThumbnail(track: NativeTrack, modifier: Modifier, targetSizePx: Int = 192) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = "album:${track.id}:$targetSizePx"
    val bitmap by produceState<Bitmap?>(initialValue = LocalArtworkCache.get(cacheKey), track.id, track.album, targetSizePx) {
        value = LocalArtworkCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            loadAlbumArtwork(context, track, targetSizePx)?.also { LocalArtworkCache.put(cacheKey, it) }
        }
    }
    ArtworkContent(bitmap, modifier, placeholderSeed = track.album.ifBlank { track.title }, placeholderKind = BrandArtworkKind.Music)
}

@Composable
private fun ArtworkContent(
    bitmap: Bitmap?,
    modifier: Modifier,
    placeholderSeed: String = "月播",
    placeholderKind: BrandArtworkKind = BrandArtworkKind.Generic,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            BrandArtworkPlaceholder(placeholderSeed, placeholderKind, Modifier.fillMaxSize())
        }
    }
}

/** 远程封面内存缓存：100 张 / 16MB，LRU 逐出；onTrimMemory 时清空。 */
internal object OnlineArtworkCache {
    private val cache = SimpleLruCache<String, Bitmap>(
        maxEntries = 100,
        maxWeight = 16L * 1024 * 1024,
        weightOf = { it.byteCount.toLong() },
    )
    fun get(url: String): Bitmap? = cache.get(url)
    fun put(url: String, bitmap: Bitmap) = cache.put(url, bitmap)
    fun clear() = cache.clear()
}

/** 本地封面内存缓存：150 张 / 24MB，LRU 逐出；onTrimMemory 时清空。 */
internal object LocalArtworkCache {
    private val cache = SimpleLruCache<String, Bitmap>(
        maxEntries = 150,
        maxWeight = 24L * 1024 * 1024,
        weightOf = { it.byteCount.toLong() },
    )
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) = cache.put(key, bitmap)
    fun clear() = cache.clear()
}

@Composable
internal fun OnlineArtwork(
    url: String?,
    modifier: Modifier,
    targetSizePx: Int = 512,
    placeholderSeed: String = "月播",
    placeholderKind: BrandArtworkKind = BrandArtworkKind.Generic,
) {
    val bitmap by produceState<Bitmap?>(initialValue = url?.let(OnlineArtworkCache::get), url) {
        value = if (url == null) null else withContext(Dispatchers.IO) {
            OnlineArtworkCache.get(url) ?: loadRemoteArtwork(url, targetSizePx)?.also { OnlineArtworkCache.put(url, it) }
        }
    }
    ArtworkContent(bitmap, modifier, placeholderSeed, placeholderKind)
}

private fun loadRemoteArtwork(url: String, targetSizePx: Int = 512): Bitmap? = runCatching {
    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 5_000
        requestMethod = "GET"
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        connection.inputStream.use { input -> decodeSampled(input.readBytes(), targetSizePx) }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private fun loadSongArtwork(context: android.content.Context, track: NativeTrack, targetSizePx: Int = 256): Bitmap? {
    val base = track.title.substringBeforeLast('.', track.title)
    return loadNamedArtwork(
        context,
        track,
        listOf(base, listOf(track.artist, base).filter { it.isNotBlank() }.joinToString(" - ")),
        targetSizePx = targetSizePx,
    ) ?: loadEmbeddedArtwork(context, track, targetSizePx)
}

private fun loadAlbumArtwork(context: android.content.Context, track: NativeTrack, targetSizePx: Int = 256): Bitmap? =
    loadNamedArtwork(context, track, listOf("cover", "folder", "front", "albumart", track.album), allowAlbumArtPrefix = true, targetSizePx = targetSizePx)
        ?: loadEmbeddedArtwork(context, track, targetSizePx)

private fun loadArtworkUri(context: android.content.Context, uri: Uri, targetSizePx: Int = 720): Bitmap? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        decodeSampled(input.readBytes(), targetSizePx)
    }
}.getOrNull()

private fun loadNamedArtwork(
    context: android.content.Context,
    track: NativeTrack,
    names: List<String>,
    allowAlbumArtPrefix: Boolean = false,
    targetSizePx: Int = 256,
): Bitmap? {
    val expectedNames = names.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (expectedNames.isEmpty()) return null
    queryFolderImage(context, track.folderPath, expectedNames, allowAlbumArtPrefix, targetSizePx)?.let { return it }
    queryFolderImage(context, track.folderPath, expectedNames, allowAlbumArtPrefix, targetSizePx, "covers")?.let { return it }
    val canDirectRead = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
    if (!canDirectRead) return null
    return runCatching {
        val baseFolder = java.io.File(Environment.getExternalStorageDirectory(), track.folderPath)
        val artwork = listOf(baseFolder, java.io.File(baseFolder, "covers")).asSequence()
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .firstOrNull {
                it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") &&
                    (expectedNames.any { name -> it.nameWithoutExtension.equals(name, true) } ||
                        (allowAlbumArtPrefix && it.nameWithoutExtension.startsWith("albumart", true)))
            }
        artwork?.let { decodeSampledFile(it.path, targetSizePx) }
    }.getOrNull()
}

private fun queryFolderImage(
    context: android.content.Context,
    folderPath: String,
    names: Set<String>,
    allowAlbumArtPrefix: Boolean,
    targetSizePx: Int = 256,
    subFolder: String? = null,
): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        val resolver = context.contentResolver
        val relative = listOf(folderPath.trim('/'), subFolder.orEmpty()).filter { it.isNotBlank() }.joinToString("/")
        if (relative.isBlank()) return@runCatching null
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND (${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%' OR ${MediaStore.MediaColumns.MIME_TYPE} = 'application/octet-stream')"
        val selectionArgs = arrayOf("$relative/")
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val folderCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol).orEmpty()
                if (!cursor.getString(folderCol).orEmpty().trim('/').equals(relative, true)) continue
                val mime = cursor.getString(mimeCol).orEmpty()
                if (!mime.startsWith("image/") && mime != "application/octet-stream") continue
                val lower = name.lowercase()
                if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") &&
                    !lower.endsWith(".png") && !lower.endsWith(".webp")
                ) continue
                val base = lower.substringBeforeLast('.')
                val match = names.any { it.equals(base, true) } ||
                    (allowAlbumArtPrefix && base.startsWith("albumart", true))
                if (!match) continue
                val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cursor.getLong(id))
                resolver.openInputStream(uri)?.use { input ->
                    decodeSampled(input.readBytes(), targetSizePx)?.let { return it }
                }
            }
            null
        }
    }.getOrNull()
}

private fun loadEmbeddedArtwork(context: android.content.Context, track: NativeTrack, targetSizePx: Int = 256): Bitmap? = runCatching {
    MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(context, track.uri)
            retriever.embeddedPicture?.let { bytes -> decodeSampled(bytes, targetSizePx) }
        } finally {
            retriever.release()
        }
    }
}.getOrNull()

private fun decodeSampled(bytes: ByteArray, targetSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, targetSizePx)
    })
}

private fun decodeSampledFile(path: String, targetSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, targetSizePx)
    })
}

private fun sampleFor(width: Int, height: Int, targetSizePx: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= targetSizePx) sample *= 2
    return sample
}

internal fun lyricBarText(lyrics: String?, positionMs: Long): String? {
    val lines = parseLyrics(lyrics)
    if (lines.isEmpty()) return lyrics?.trim()?.takeIf { it.isNotBlank() }
    return activeLyricIndex(lines, positionMs).takeIf { it >= 0 }?.let { lines[it].text }
}

/**
 * 当前句逐字卡拉OK高亮文本：已读部分用高亮色，未读部分用浅色。
 * [chars] 为「可见字符 + 起始时间戳」，[positionMs] 为当前播放位置。
 */
private fun karaokeAnnotatedString(
    chars: List<Pair<Char, Long>>,
    positionMs: Long,
    highlighted: androidx.compose.ui.graphics.Color,
    dimmed: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    if (chars.isEmpty()) return AnnotatedString("")
    val readCount = chars.count { it.second <= positionMs }
    val sb = StringBuilder(chars.size)
    chars.forEach { sb.append(it.first) }
    return buildAnnotatedString {
        if (readCount <= 0) {
            append(
                AnnotatedString(
                    sb.toString(),
                    spanStyle = SpanStyle(color = dimmed),
                ),
            )
            return@buildAnnotatedString
        }
        append(
            AnnotatedString(
                sb.substring(0, readCount),
                spanStyle = SpanStyle(color = highlighted),
            ),
        )
        if (readCount < chars.size) {
            append(
                AnnotatedString(
                    sb.substring(readCount),
                    spanStyle = SpanStyle(color = dimmed),
                ),
            )
        }
    }
}

@Composable
private fun LyricBar(lyrics: String?, positionMs: Long) {
    val lines = remember(lyrics) { parseLyrics(lyrics) }
    val index = activeLyricIndex(lines, positionMs)
    if (index < 0) return
    val line = lines[index]
    val nextLineStartMs = lines.getOrNull(index + 1)?.timeMs
    val chars = remember(line.text, nextLineStartMs) { wordChars(line, nextLineStartMs) }
    val annotated = karaokeAnnotatedString(
        chars = chars,
        positionMs = positionMs,
        highlighted = MaterialTheme.colorScheme.primary,
        dimmed = MaterialTheme.colorScheme.onSurface,
    )
    Card(Modifier.fillMaxWidth()) {
        Text(annotated, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun LyricsScreen(
    state: MusicUiState,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onReload: () -> Unit,
) {
    val track = state.currentTrack
    val lines = remember(state.lyrics) { parseLyrics(state.lyrics) }
    val currentIndex = activeLyricIndex(lines, state.positionMs)
    LaunchedEffect(track?.id, state.lyrics) {
        if (track != null && state.lyrics.isNullOrBlank()) onReload()
    }
    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { userScrolling = it }
    }
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && !userScrolling) {
            listState.animateScrollToItem((currentIndex - 4).coerceAtLeast(0))
        }
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppBar(title = "歌词", onBack = onBack, onDevices = null)
        if (track == null) {
            Text("尚未选择歌曲。", modifier = Modifier.padding(24.dp))
            return@Column
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(track.artist)
        }
        if (lines.isEmpty()) {
            Text(
                when {
                    state.lyrics.isNullOrBlank() -> "这首歌暂无歌词，可在联网时自动从在线媒体获取。"
                    else -> state.lyrics.orEmpty()
                },
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    val active = index == currentIndex
                    val nextLineStartMs = lines.getOrNull(index + 1)?.timeMs
                    val chars = remember(line.text, nextLineStartMs) { wordChars(line, nextLineStartMs) }
                    val annotated = if (active) {
                        karaokeAnnotatedString(
                            chars = chars,
                            positionMs = state.positionMs,
                            highlighted = MaterialTheme.colorScheme.primary,
                            dimmed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    } else null
                    val textColor: androidx.compose.ui.graphics.Color =
                        if (active) androidx.compose.ui.graphics.Color.Unspecified
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    Text(
                        text = annotated ?: AnnotatedString(line.text),
                        textAlign = TextAlign.Center,
                        fontSize = if (active) 20.sp else 16.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = textColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                            .clickable { onSeek(line.timeMs) }
                            .semantics {
                                onClick(label = "跳转到此句") { onSeek(line.timeMs); true }
                                if (active) stateDescription = "当前歌词"
                            },
                    )
                }
            }
        }
    }
}

/** 迷你播放器唱片转速：约 4 秒一圈（90°/s），可感知但不干扰注意力。 */
private const val VINYL_DEGREES_PER_SECOND = 90f

@Composable
private fun MiniPlayer(
    modifier: Modifier = Modifier,
    track: NativeTrack,
    playing: Boolean,
    isVideo: Boolean = false,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    previousLabel: String,
    nextLabel: String,
    /** 音频在线解析在途 + 方向：对应切歌按钮显示进度圈（解析中「点了没反应」的可见反馈）。 */
    resolvingBackward: Boolean = false,
    resolvingForward: Boolean = false,
    onDismiss: () -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    Card(
        modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(GlassTheme.RadiusXLarge),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "打开播放器") { onOpenNowPlaying() }
                    .clearAndSetSemantics {
                        contentDescription = "${track.title}，${if (playing) "正在播放" else "已暂停"}"
                        onClick(label = "打开播放器") { onOpenNowPlaying(); true }
                        customActions = listOf(
                            androidx.compose.ui.semantics.CustomAccessibilityAction("隐藏迷你播放器") {
                                onDismiss()
                                true
                            },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniVinylDisc(
                    artworkUrl = track.artworkUrl,
                    seed = track.title,
                    playing = playing,
                    trackKey = track.id,
                    kind = if (isVideo) BrandArtworkKind.Video else BrandArtworkKind.Music,
                )
                // weight 让长标题在盘芯与按钮之间省略，而不是溢出到播放/下一首按钮底下。
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            // 控制按钮带短促触感：播放/暂停稍实（tap），切歌轻点（tick）——均在回调前同步发出。
            val hapticView = LocalView.current
            IconButton(onClick = { MediaHaptics.tap(hapticView); onToggle() }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                )
            }
            // 上一项/下一项：队列里没有相邻条目时置灰（按下无反应正是用户报的问题）。
            IconButton(onClick = { MediaHaptics.tick(hapticView); onPrevious() }, enabled = canSkipPrevious) {
                if (resolvingBackward) {
                    Box(Modifier.size(18.dp).semantics { contentDescription = "$previousLabel，正在连接" }) {
                        CircularProgressIndicator(modifier = Modifier.fillMaxSize())
                    }
                } else {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = previousLabel,
                    )
                }
            }
            IconButton(onClick = { MediaHaptics.tick(hapticView); onNext() }, enabled = canSkipNext) {
                if (resolvingForward) {
                    Box(Modifier.size(18.dp).semantics { contentDescription = "$nextLabel，正在连接" }) {
                        CircularProgressIndicator(modifier = Modifier.fillMaxSize())
                    }
                } else {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = nextLabel,
                    )
                }
            }
        }
    }
}

/**
 * 迷你播放器旋转唱片机：黑胶盘面 + 圆形封面盘芯 + 主轴孔。
 * 播放时匀速旋转（graphicsLayer 只重绘图层），暂停即停并保留角度，切歌角度归零并轻微回弹；
 * 系统关闭动画时完全静止。
 */
@Composable
private fun MiniVinylDisc(
    artworkUrl: String?,
    seed: String,
    playing: Boolean,
    trackKey: Any,
    kind: BrandArtworkKind,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = rememberMotionEnabled()
    val rotation = remember { mutableFloatStateOf(0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(playing, motionEnabled) {
        if (!motionEnabled || !playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                rotation.floatValue =
                    (rotation.floatValue + (now - last) / 1_000_000_000f * VINYL_DEGREES_PER_SECOND) % 360f
                last = now
            }
        }
    }
    LaunchedEffect(trackKey) {
        rotation.floatValue = 0f
        if (motionEnabled) {
            scale.snapTo(0.84f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Box(
        modifier.size(48.dp).graphicsLayer {
            rotationZ = rotation.floatValue
            scaleX = scale.value
            scaleY = scale.value
        },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f
            drawCircle(GlassTheme.BgDeep, radius = radius)
            listOf(0.94f, 0.86f, 0.78f).forEach { fraction ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = radius * fraction,
                    style = Stroke(0.8.dp.toPx()),
                )
            }
        }
        Box(Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape)) {
            OnlineArtwork(
                artworkUrl,
                Modifier.fillMaxSize(),
                targetSizePx = 96,
                placeholderSeed = seed,
                placeholderKind = kind,
            )
        }
        Box(Modifier.align(Alignment.Center).size(5.dp).clip(CircleShape).background(GlassTheme.BgDeep))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TransportIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    enabled: Boolean = true,
    /** 在途指示（在线解析中）：图标位换成进度圈，读屏在描述里附「正在连接」。 */
    loading: Boolean = false,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val view = LocalView.current
    val motionEnabled = rememberMotionEnabled()
    // 按压反馈：短促触感（系统 CLOCK_TICK/KEYBOARD_TAP，毫秒级单发）+ 轻微缩放回弹。
    // 触感只挂在真正生效的操作上（禁用态不振），且在回调前同步发出——不等播放响应，无拖沓感。
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && motionEnabled) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = 0.0001f,
        ),
        label = "transportPress",
    )
    Box(
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .repeatOnLongPress(
                onPressChange = { pressed = it },
                { if (enabled) { MediaHaptics.tap(view); currentOnClick() } },
                { if (enabled) { MediaHaptics.tick(view); currentOnLongClick() } },
            )
            .clearAndSetSemantics {
                contentDescription = if (loading) "$label，正在连接" else label
                if (enabled) {
                    // TalkBack 双击激活与物理点击同振：触感不因读屏开启而缺席（跨页面一致）。
                    onClick { MediaHaptics.tap(view); currentOnClick(); true }
                } else {
                    // 没有相邻条目：明确标记为禁用（TalkBack 会读「不可用」），且不留空回调——
                    // 一个能点却什么都不做的按钮正是用户反馈的问题之一。
                    disabled()
                }
            }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Icon(icon, contentDescription = null)
        }
    }
}

private fun Modifier.repeatOnLongPress(
    onPressChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        onPressChange(true)
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation()
        }
        if (released != null) {
            onPressChange(false)
            onClick()
        } else if (currentEvent.changes.any { it.pressed }) {
            while (currentEvent.changes.any { it.pressed }) {
                onLongPress()
                withTimeoutOrNull(100) {
                    while (currentEvent.changes.any { it.pressed }) awaitPointerEvent()
                }
            }
        }
        onPressChange(false)
    }
}

/** 全局倍速档位：音频播放与视频播放共用同一组档位。 */
private val PlaybackSpeedOptions = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

@Composable
private fun PlaybackSpeedMenu(speed: Double, onSelect: (Double) -> Unit, label: String = "播放速度", modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
            Text("$label：${speed} 倍")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlaybackSpeedOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option} 倍${if (option == speed) "（当前）" else ""}") },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun OnlineQualitySettingsMenu(
    state: MusicUiState,
    onPlaybackQuality: (StreamQualityOption) -> Unit,
    onDownloadQuality: (StreamQualityOption) -> Unit,
) {
    val sourceId = state.onlineActiveSourceId ?: state.musicFreeSources.firstOrNull()?.id
    val qualityOptions = state.sourceQualityOptions[sourceId].orEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OnlineQualityMenu(
            label = "播放音质",
            quality = state.playbackQuality,
            selectedValue = state.playbackQualityValue,
            qualityOptions = qualityOptions,
            onSelect = onPlaybackQuality,
        )
        OnlineQualityMenu(
            label = "下载音质",
            quality = state.downloadQuality,
            selectedValue = state.downloadQualityValue,
            qualityOptions = qualityOptions,
            onSelect = onDownloadQuality,
        )
        Text(
            "音源不支持所选档位时播放/下载会自动降档",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun OnlineQualityMenu(
    label: String,
    quality: StreamQuality,
    selectedValue: String?,
    qualityOptions: List<StreamQualityOption>,
    onSelect: (StreamQualityOption) -> Unit,
) {
    val entries = streamQualityEntries(qualityOptions)
    val selected = entries.firstOrNull { it.value == selectedValue && it.tier == quality }
        ?: entries.firstOrNull { it.tier == quality }
        ?: entries.first()
    val selectedLabel = selected.displayLabel()
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        ) { Text("$label：$selectedLabel") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == selected) "${option.displayLabel()}（当前）" else option.displayLabel()) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

private fun streamQualityEntries(options: List<StreamQualityOption>): List<StreamQualityOption> =
    options.ifEmpty { fallbackQualityOptions() }

@Composable
private fun QueueModeMenu(mode: NativeQueueMode, onSelect: (NativeQueueMode) -> Unit, label: String = "播放模式", modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
            Text("$label：${queueModeLabel(mode)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            NativeQueueMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${queueModeLabel(option)}${if (option == mode) "（当前）" else ""}") },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun ThemeModeMenu(mode: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
            Text("主题：${themeModeLabel(mode)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppThemeMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${themeModeLabel(option)}${if (option == mode) "（当前）" else ""}") },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun GridDensityMenu(density: GridDensity, onSelect: (GridDensity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
            Text("网格：${gridDensityLabel(density)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GridDensity.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${gridDensityLabel(option)}${if (option == density) "（当前）" else ""}") },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

internal fun gridDensityLabel(density: GridDensity): String = when (density) {
    GridDensity.Auto -> "自适应"
    GridDensity.Columns2 -> "3行×2列"
    GridDensity.Columns3 -> "4行×3列"
    GridDensity.Columns4 -> "5行×4列"
    GridDensity.Columns5 -> "6行×5列"
    GridDensity.Columns6 -> "7行×6列"
}

@Composable
internal fun gridCells(density: GridDensity): GridCells {
    val requested = when (density) {
        GridDensity.Auto -> return GridCells.Adaptive(112.dp)
        GridDensity.Columns2 -> 2
        GridDensity.Columns3 -> 3
        GridDensity.Columns4 -> 4
        GridDensity.Columns5 -> 5
        GridDensity.Columns6 -> 6
    }
    // 5/6 列在手机宽度下会把卡片标题压到不足一个汉字宽（一字一行竖排）：
    // 按屏宽封顶列数，保证每列不小于 72dp（网格左右 16dp 边距 + 12dp 列间距）；
    // 平板/横屏宽度足够时仍给满所选列数。
    val maxReadable = ((LocalConfiguration.current.screenWidthDp - 20) / (72 + 12)).coerceAtLeast(1)
    return GridCells.Fixed(requested.coerceAtMost(maxReadable))
}

@Composable
internal fun StatusMessage(status: String?) {
    status?.takeIf(::isStatusError)?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

internal fun isStatusError(status: String): Boolean = listOf("失败", "无法", "不可用", "不支持", "不能", "请先", "未授予")
    .any(status::contains)

private fun playDownloadCompleteTone(context: android.content.Context) {
    runCatching {
        RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
}


private fun queueModeLabel(mode: NativeQueueMode): String = when (mode) {
    NativeQueueMode.Sequential -> "顺序播放"
    NativeQueueMode.RepeatOne -> "单曲循环"
    NativeQueueMode.RepeatAll -> "列表循环"
    NativeQueueMode.Shuffle -> "随机播放"
}

private fun themeModeLabel(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.System -> "跟随系统"
    AppThemeMode.Light -> "浅色"
    AppThemeMode.Dark -> "深色"
}

@Composable
private fun ImportProgressDialog(
    progress: Pair<Int, Int>?,
    importingArchive: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (importingArchive) "正在解压压缩包" else "正在导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = {
                        val total = (progress?.second ?: 1).coerceAtLeast(1)
                        (progress?.first ?: 0).toFloat() / total
                    },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Text(
                    when {
                        progress == null -> if (importingArchive) "正在解压文件…" else "正在导入…"
                        importingArchive -> "正在解压 ${progress.first}/${progress.second} 个文件…"
                        else -> "正在导入 ${progress.first}/${progress.second}…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun PasswordDialog(
    request: PasswordRequest,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩包需要密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${request.displayName} 已加密，请输入解压密码。")
                request.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ImportResultDialog(result: ImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入完成") },
        text = {
            Text(
                "成功导入 ${result.imported} 首，跳过 ${result.skipped} 个重复文件，" +
                    "失败 ${result.failed} 个。",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
