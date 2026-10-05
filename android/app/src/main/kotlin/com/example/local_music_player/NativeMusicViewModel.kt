package com.example.local_music_player

import android.util.Log
import android.app.Application
import android.content.ContentUris
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.random.Random
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class NativeQueueMode { Sequential, RepeatOne, RepeatAll, Shuffle }

enum class AppThemeMode { System, Light, Dark }

enum class SleepTimerMode { None, Minutes, Tracks }

/** 媒体网格密度：自适应（按屏幕宽度自动列数）或固定列数档位。 */
enum class GridDensity { Auto, Columns2, Columns3, Columns4, Columns5, Columns6 }

enum class LibrarySort { Name, Size, Time }

enum class LibraryBrowse { Songs, Albums, Artists, Folders, Videos }

enum class SilenceSkipMode { LongerThan, ShorterThan }

/** 在线搜索类型：按源能力显隐（任一启用源支持才显示该类型）。 */
enum class OnlineSearchKind(val label: String) {
    Music("歌曲"),
    Album("专辑"),
    Sheet("歌单"),
    Artist("歌手"),
}

data class EqPreset(val name: String, val bands: FloatArray, val preamp: Float = 0f) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqPreset) return false
        return name == other.name && bands.contentEquals(other.bands) && preamp == other.preamp
    }
    override fun hashCode(): Int = name.hashCode() * 31 + bands.contentHashCode() + preamp.hashCode()
    companion object {
        val NORMAL = EqPreset("正常", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        val ROCK = EqPreset("摇滚", floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 2f, 3f, 4f))
        val POP = EqPreset("流行", floatArrayOf(-1f, 1f, 3f, 4f, 4f, 3f, 1f, -1f, -1f, 0f))
        val JAZZ = EqPreset("爵士", floatArrayOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 2f))
        val CLASSICAL = EqPreset("古典", floatArrayOf(4f, 3f, 1f, 0f, -1f, -1f, 0f, 1f, 3f, 4f))
        val HIPHOP = EqPreset("嘻哈", floatArrayOf(5f, 4f, 3f, 1f, 0f, 0f, 1f, 2f, 3f, 4f))
        val BASS_BOOST = EqPreset("低音增强", floatArrayOf(8f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), 2f)
        val TREBLE_BOOST = EqPreset("高音增强", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 6f, 8f))
        val ALL = listOf(NORMAL, ROCK, POP, JAZZ, CLASSICAL, HIPHOP, BASS_BOOST, TREBLE_BOOST)
    }
}

/** 旧 5 段均衡器设置 → 10 段：旧 60/230/910/3.6k/14k 就近映射到 62/250/1k/4k/16k，其余补 0。 */
internal fun migrateEqBands(legacy: List<Float>): List<Float> {
    val migrated = MutableList(10) { 0f }
    intArrayOf(1, 3, 5, 7, 9).forEachIndexed { index, target ->
        migrated[target] = legacy.getOrElse(index) { 0f }
    }
    return migrated
}

data class NativeTrack(
    val id: Long,
    val uri: android.net.Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val mimeType: String,
    val folderPath: String,
    val sizeBytes: Long = 0,
    val modifiedTimeMs: Long = 0,
    val bitrateKbps: Int = 0,
    val cueStartMs: Long = 0,
    val isCueTrack: Boolean = false,
    val artworkUrl: String? = null,
    val isVideo: Boolean = false,
)

data class CastHistoryEntry(
    val protocol: String,
    val id: String,
    val name: String,
    val location: String? = null,
    val controlUrl: String? = null,
    val renderingControlUrl: String? = null,
    val renderingControlServiceType: String? = null,
) {
val protocolLabel get() = "DLNA"
}

private fun formatTimestamp(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun cueSegmentDurationMs(
    sourceDurationMs: Long,
    startMs: Long,
    nextStartMs: Long?,
): Long = ((nextStartMs ?: sourceDurationMs) - startMs).takeIf { it > 0 } ?: 0

internal data class ExternalQueueItem(
    val track: ImportedAlbumTrack,
    val id: Long,
    val folderPath: String,
    val sizeBytes: Long,
    val modifiedTimeMs: Long,
    val bitrateKbps: Int,
)

internal fun encodeExternalQueueItem(
    track: ImportedAlbumTrack,
    id: Long,
    folderPath: String,
    sizeBytes: Long,
    modifiedTimeMs: Long,
    bitrateKbps: Int,
): JSONObject = JSONObject().apply {
    put("externalTrack", ImportedAlbumJson.encodeTracks(listOf(track)))
    put("externalTrackId", id)
    put("folderPath", folderPath)
    put("sizeBytes", sizeBytes)
    put("modifiedTimeMs", modifiedTimeMs)
    put("bitrateKbps", bitrateKbps)
}

internal fun encodeExternalQueueItem(track: NativeTrack): JSONObject = encodeExternalQueueItem(
    track = track.toImportedAlbumTrack(),
    id = track.id,
    folderPath = track.folderPath,
    sizeBytes = track.sizeBytes,
    modifiedTimeMs = track.modifiedTimeMs,
    bitrateKbps = track.bitrateKbps,
)

internal fun decodeExternalQueueItem(item: JSONObject): ExternalQueueItem? {
    val track = ImportedAlbumJson.decodeTracks(item.optString("externalTrack")).singleOrNull() ?: return null
    val savedId = item.optLong("externalTrackId", Long.MIN_VALUE)
    return ExternalQueueItem(
        track = track,
        id = if (savedId == Long.MIN_VALUE) track.toNativeTrack().id else savedId,
        folderPath = item.optString("folderPath"),
        sizeBytes = item.optLong("sizeBytes"),
        modifiedTimeMs = item.optLong("modifiedTimeMs"),
        bitrateKbps = item.optInt("bitrateKbps"),
    )
}

private fun ExternalQueueItem.toNativeTrack(): NativeTrack = track.toNativeTrack().copy(
    id = id,
    folderPath = folderPath,
    sizeBytes = sizeBytes,
    modifiedTimeMs = modifiedTimeMs,
    bitrateKbps = bitrateKbps,
)

data class SavedPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<Long>,
    val externalUris: List<String> = emptyList(),
    val externalTracks: List<ImportedAlbumTrack> = emptyList(),
    val onlineTracks: List<OnlineTrack> = emptyList(),
)

data class PlaybackHistoryEntry(
    val trackId: Long,
    val lastPlayedAtMs: Long,
    val playCount: Int,
)

data class MediaReference(
    val source: String,
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val mimeType: String,
    val uri: String? = null,
    val cueStartMs: Long = 0,
    val sizeBytes: Long = 0,
    val artworkUrl: String? = null,
    val onlineTrack: OnlineTrack? = null,
    val cloudPath: String? = null,
    val cloudParentId: String? = null,
)

data class FavoriteFolder(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val items: List<MediaReference> = emptyList(),
    /** 归属模块域：每模块只展示本域的收藏夹（旧数据迁移时按条目拆分）。 */
    val scope: MediaScope = MediaScope.Music,
)

data class SinglePlaybackHistoryEntry(
    val reference: MediaReference,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAtMs: Long,
    val playCount: Int,
)

data class AlbumPlaybackHistoryEntry(
    val albumKey: String,
    val albumTitle: String,
    val artist: String,
    val source: String,
    val albumId: String? = null,
    val query: String? = null,
    val trackReference: MediaReference,
    val trackIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAtMs: Long,
)

private data class AlbumPlaybackContext(
    val key: String,
    val title: String,
    val artist: String,
    val source: String,
    val albumId: String? = null,
    val query: String? = null,
    val queueKey: String,
)

/** 账号管理列表条目：id 用于切换/删除，user 为展示信息。 */
data class BaiduAccountInfo(val id: String, val user: BaiduPanUser)

/** 账号管理列表条目：id 用于切换/删除，user 为展示信息。 */
data class QuarkAccountInfo(val id: String, val user: QuarkPanUser)

data class MusicUiState(
    val tracks: List<NativeTrack> = emptyList(),
    val loading: Boolean = false,
    val permissionGranted: Boolean = false,
    val currentTrack: NativeTrack? = null,
    val queue: List<NativeTrack> = emptyList(),
    val queueIndex: Int = -1,
    val queueMode: NativeQueueMode = NativeQueueMode.RepeatAll,
    val isStreaming: Boolean = false,
    /** 当前音频为直播流（蜻蜓电台等）：无总时长、不可拖动，界面标注「直播」。 */
    val currentTrackLive: Boolean = false,
    /** 正在播放条目的引用键（`source:key`，与收藏/历史条目同口径）：列表据此标注「正在播放」。 */
    val playingReferenceKey: String? = null,
    /** 正在播放的专辑/频道键：频道网格与专辑网格据此标注「正在播放」。 */
    val playingAlbumKey: String? = null,
    val streamLoading: Boolean = false,
    val playing: Boolean = false,
    val miniPlayerDismissed: Boolean = false,
    val positionMs: Long = 0,
    val volume: Float = 1f,
    val playbackSpeed: Double = 1.0,
    val fadeTransitions: Boolean = false,
    val autoMatchLocalLyrics: Boolean = true,
    /** 播放音频时是否自动进入正在播放页：关闭后起播留在当前页面（迷你播放器仍可手动进入）。 */
    val autoOpenPlayingPage: Boolean = true,
    val skipSilenceEnabled: Boolean = false,
    val silenceSkipMode: SilenceSkipMode = SilenceSkipMode.LongerThan,
    val silenceSkipThresholdMs: Long = 0,
    val eqEnabled: Boolean = false,
    val eqBands: List<Float> = List(10) { 0f },
    val eqPreamp: Float = 0f,
    val eqPreset: String = "自定义",
    val audioEffects: AudioEffects = AudioEffects(),
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.None,
    val sleepTimerTotalMs: Long = 0,
    val sleepTimerRemainingMs: Long = 0,
    val sleepTimerTotalTracks: Int = 0,
    val sleepTimerRemainingTracks: Int = 0,
    val sleepTimerStatus: String? = null,
    val lyricTtsEnabled: Boolean = false,
    val lyricTtsEngine: String? = null,
    val lyricTtsOffsetMs: Long = 0,
    val lyricTtsChannel: LyricTtsChannel = LyricTtsChannel.Media,
    val ttsEngines: List<TtsEngineInfo> = emptyList(),
    val lyricTtsStatus: String? = null,
    val themeMode: AppThemeMode = AppThemeMode.System,
    val gridDensity: GridDensity = GridDensity.Columns3,
    val librarySort: LibrarySort = LibrarySort.Name,
    val librarySortAscending: Boolean = true,
    val libraryBrowse: LibraryBrowse = LibraryBrowse.Songs,
    val searchQuery: String = "",
    val onlyFavorites: Boolean = false,
    val favoriteIds: Set<Long> = emptySet(),
    val favoriteFolders: List<FavoriteFolder> = emptyList(),
    val playlists: List<SavedPlaylist> = emptyList(),
    val importedAlbums: List<ImportedAlbum> = emptyList(),
    val hiddenAlbumKeys: Set<String> = emptySet(),
    val trackMetadataOverrides: Map<String, TrackMetadataOverride> = emptyMap(),
    val playbackHistory: List<PlaybackHistoryEntry> = emptyList(),
    val singlePlaybackHistory: List<SinglePlaybackHistoryEntry> = emptyList(),
    val albumPlaybackHistory: List<AlbumPlaybackHistoryEntry> = emptyList(),
    val abStartMs: Long? = null,
    val abEndMs: Long? = null,
    /** 跳过片头片尾总开关：关闭时既不下发跳过、也不显示设置项（关闭即清零隐藏）。 */
    val skipIntroOutroEnabled: Boolean = false,
    val lyrics: String? = null,
    val lyricsScreenOpen: Boolean = false,
    val lyricsSearching: Boolean = false,
val dlnaDevices: List<DlnaDevice> = emptyList(),
    val chromecastDevices: List<String> = emptyList(),
    val chromecastAvailable: Boolean = false,
    val castGroupNames: List<String> = emptyList(),
    val history: List<CastHistoryEntry> = emptyList(),
    val scanningDevices: Boolean = false,
    val switchingDevice: Boolean = false,
    val isCasting: Boolean = false,
    val targetName: String = "本机输出待确认",
    val audioOutputs: List<AudioOutput> = emptyList(),
    val hasClipboardTrack: Boolean = false,
    val hasClipboardFolder: Boolean = false,
    val deleteRequest: IntentSender? = null,
    val status: String? = null,
    val logExporting: Boolean = false,
    val logExportAnnouncement: String? = null,
    val logExportClearBefore: Boolean = false,
    val logClearing: Boolean = false,
    val logClearAnnouncement: String? = null,
    val importing: Boolean = false,
    val importProgress: Pair<Int, Int>? = null,
    val importingArchive: Boolean = false,
    val importResult: ImportResult? = null,
    val passwordRequest: PasswordRequest? = null,
    /** 播放音质档位（MusicFree 插件协议）：请求档位不支持时插件侧自动降档。 */
    val playbackQuality: StreamQuality = StreamQuality.Standard,
    /** Selected native value for the currently active plugin, when one was chosen. */
    val playbackQualityValue: String? = null,
    /** 下载音质档位（MusicFree 插件协议）。 */
    val downloadQuality: StreamQuality = StreamQuality.Super,
    /** Selected native value persisted for new download tasks, when one was chosen. */
    val downloadQualityValue: String? = null,
    // —— MusicFree 插件音源 ——
    /** 已启用的音源（站点 chips 与在线搜索的范围）。 */
    val musicFreeSources: List<MusicFreeSource> = emptyList(),
    /** 已导入的全部音源，供音源管理显示停用项；停用不等于删除。 */
    val installedMusicFreeSources: List<MusicFreeSource> = emptyList(),
    /** 各音源声明的原生音质档位，设置与下载弹窗按平台展示。 */
    val sourceQualityOptions: Map<String, List<StreamQualityOption>> = emptyMap(),
    val onlineSearchQuery: String = "",
    val onlineSearching: Boolean = false,
    /** 聚合搜索结果（选中源优先排在前面）。 */
    val onlineSearchTracks: List<StreamTrack> = emptyList(),
    val onlineSearchCollections: List<OnlineCollection> = emptyList(),
    val onlineSearchArtists: List<StreamArtist> = emptyList(),
    val onlineSearchKind: OnlineSearchKind = OnlineSearchKind.Music,
    /** 当前选中的站点（null = 全部源）。 */
    val onlineActiveSourceId: String? = null,
    val onlineSearchSession: StreamSearchSession? = null,
    /** 当前打开的专辑/歌单/榜单详情。 */
    val onlineCollectionDetail: OnlineCollection? = null,
    /** 当前打开的歌手（作品列表复用 onlineCollectionTracks 展示）。 */
    val onlineArtistDetail: StreamArtist? = null,
    val onlineCollectionTracks: List<StreamTrack> = emptyList(),
    val onlineCollectionLoading: Boolean = false,
    val onlineCollectionEnd: Boolean = true,
    val onlineTopListGroups: List<StreamTopListGroup> = emptyList(),
    val onlineTopListLoading: Boolean = false,
    /** 最近一次在线播放实际生效的音质档位（请求档位被降档时与 playbackQuality 不同）。 */
    val onlineResolvedQuality: StreamQuality? = null,
    // —— 音源管理（我的 → 音源管理） ——
    val sourceTestStatuses: Map<String, StreamSourceTestStatus> = emptyMap(),
    val testingSourceIds: Set<String> = emptySet(),
    /** 已校验待确认导入的音源。 */
    val pendingSourceImports: List<MusicFreeSource> = emptyList(),
    val sourceSelectionMode: Boolean = false,
    val sourceSelection: Set<String> = emptySet(),
    val sourceManagerStatus: String? = null,
    val downloadingOnlineKeys: Set<String> = emptySet(),
    val downloadTasks: List<DownloadTask> = emptyList(),
    val downloadSpeedEta: Map<String, DownloadSpeedEta> = emptyMap(),
    val downloadAnnouncement: String? = null,
    val downloadAddAnnouncement: String? = null,
    val wifiOnlyDownload: Boolean = false,
    val onlineAlbumDownloadKey: String? = null,
    val onlineAlbumDownloadProgress: String? = null,
    val downloadQualityPrompt: DownloadQualityPrompt? = null,
    val panAccount: BaiduPanUser? = null,
    val panAccounts: List<BaiduAccountInfo> = emptyList(),
    val panAuthInProgress: Boolean = false,
    val panDeviceCode: BaiduPanDeviceCode? = null,
    val panQrImage: ByteArray? = null,
    val panPath: String = "/",
    val panFiles: List<BaiduPanFile> = emptyList(),
    val panLoading: Boolean = false,
    val panError: String? = null,
    val panHasMore: Boolean = false,
    val panSearchActive: Boolean = false,
    val panAnnouncement: String? = null,
    val panDownloads: List<BaiduPanDownload> = emptyList(),
    val panDownloadingFolder: Boolean = false,
    val hasClipboardPan: Boolean = false,
    val quarkAccount: QuarkPanUser? = null,
    val quarkAccounts: List<QuarkAccountInfo> = emptyList(),
    val quarkAuthInProgress: Boolean = false,
    val quarkQrImage: ByteArray? = null,
    val quarkPath: String = "",
    val quarkFiles: List<QuarkPanFile> = emptyList(),
    val quarkLoading: Boolean = false,
    val quarkError: String? = null,
    val quarkHasMore: Boolean = false,
    val quarkSearchActive: Boolean = false,
    val quarkAnnouncement: String? = null,
    val quarkDownloads: List<QuarkPanDownload> = emptyList(),
    val quarkDownloadingFolder: Boolean = false,
    val hasClipboardQuark: Boolean = false,
    val videoTracks: List<NativeTrack> = emptyList(),
    val videoQueue: List<NativeTrack> = emptyList(),
    val currentVideo: NativeTrack? = null,
    val videoIndex: Int = -1,
    val videoPlaying: Boolean = false,
    val videoPositionMs: Long = 0,
    val videoDurationMs: Long = 0,
    val videoLoading: Boolean = false,
    val videoBuffering: Boolean = false,
    /** 当前视频为直播流（B 站直播或直播站电视台）：隐藏进度/拖动并标注「直播」。 */
    val videoLive: Boolean = false,
    val videoAudioOnly: Boolean = false,
    val videoSubtitleCues: List<VideoSubtitleCue> = emptyList(),
    val videoSubtitleName: String? = null,
    val videoSubtitleTracks: List<VideoSubtitleTrack> = emptyList(),
    val videoSubtitleSelectedTrackId: String? = null,
    val videoSecondarySubtitleTrackId: String? = null,
    val videoSecondarySubtitleCues: List<VideoSubtitleCue> = emptyList(),
    val videoSubtitleVisible: Boolean = true,
    val videoSubtitleLoadingTrackId: String? = null,
    val videoSecondarySubtitleLoadingTrackId: String? = null,
    val videoSubtitleAiAvailable: Boolean = false,
    val videoSubtitleAiLoading: Boolean = false,
    val videoSubtitleStatus: String? = null,
    val videoSubtitleOffsetMs: Long = 0,
    val videoSubtitleScale: Float = 1f,
    val videoSubtitleTtsEnabled: Boolean = false,
    val videoError: String? = null,
    val videoCasting: Boolean = false,
    val videoCastType: String? = null,
    val videoCastTarget: String = "",
    val videoCastLoading: Boolean = false,
    val videoUsingFfmpeg: Boolean = false,
    val videoSpeed: Double = 1.0,
    val videoSleepTimerTotalMs: Long = 0,
    val videoSleepTimerRemainingMs: Long = 0,
    /** 视频清晰度（腾讯官方档位码）：当前生效档 + 服务端解析时下发的可用档列表。 */
    val videoQualityCode: String = DEFAULT_VIDEO_QUALITY,
    val videoQualityOptions: List<VideoQualityOption> = emptyList(),
    val videoQualitySwitching: Boolean = false,
    /** 当前播放的是「可切码率的视频」——清晰度入口据此显示；无可用档时入口仍显示并提示暂无其他码率。 */
    val videoQualityEntryVisible: Boolean = false,
    /** 音频在线解析在途（已点切歌、播放地址未拿到）：切歌按钮据此刻画在途指示，消除「点了没反应」。 */
    val onlineResolving: Boolean = false,
    /** 在途解析的方向（true=下一首）：在途指示显示在用户实际按下的切歌按钮上。 */
    val onlineResolvingForward: Boolean = true,
)

internal fun shouldPersistQueueTrack(
    trackId: Long,
    onlineTrackIds: Set<Long>,
): Boolean = trackId !in onlineTrackIds

/** 在线流媒体续播的最小播放时长（毫秒）：播放不足 1 分钟不进入续播范围。 */
internal const val ONLINE_RESUME_MIN_MS = 60_000L

/** 是否把当前进度写入续播记录：播放 ≥1 分钟且未接近结尾；未知时长时不限制结尾。 */
internal fun shouldSaveResume(positionMs: Long, durationMs: Long): Boolean {
    val position = positionMs.coerceAtLeast(0)
    if (position < ONLINE_RESUME_MIN_MS) return false
    if (durationMs > 0 && position >= durationMs - 1_000) return false
    return true
}

/**
 * 历史续播的落点：优先按记录曲目的键（其次平台 ID，专辑改版后键会漂移而 ID 稳定）在重拉
 * 结果里匹配，命中才套用记录的进度；键都对不上时退用记录下标，但只有**曲目身份缺失**
 * （插件音源未还原出 onlineTrack）才保留进度，身份对得上却键不符说明
 * 专辑内容已变，从头播；下标也越界（拉取不全/专辑重构）返回 null，由调用方兜底。
 */
internal fun historyResumeLanding(tracks: List<OnlineTrack>, entry: AlbumPlaybackHistoryEntry): Pair<Int, Long>? {
    val online = entry.trackReference.onlineTrack
    val exactKey = online?.key
    val platformId = online?.platformId?.takeIf(String::isNotBlank)
    val matched = exactKey?.let { key -> tracks.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
        ?: platformId?.let { id -> tracks.indexOfFirst { it.platformId == id } }?.takeIf { it >= 0 }
    val positionMs = entry.positionMs.coerceAtLeast(0)
    if (matched != null) return matched to positionMs
    val index = entry.trackIndex.takeIf { it in tracks.indices } ?: return null
    return index to if (online == null) positionMs else 0L
}

/** 把在线曲目编码为续播记录：只存可重建的插件/平台标识，不保存播放地址。 */
internal fun encodeOnlineResumeTrack(track: OnlineTrack): String = JSONObject().apply {
    put("pluginId", track.pluginId)
    put("platform", track.platform)
    put("sourceName", track.sourceName)
    put("platformId", track.platformId)
    put("title", track.title)
    put("artist", track.artist)
    put("album", track.album)
    put("artworkUrl", track.artworkUrl ?: JSONObject.NULL)
    put("durationMs", track.durationMs ?: JSONObject.NULL)
}.toString()

/** 从续播记录解码在线曲目；插件 id 或必填字段无效时返回 null。 */
internal fun decodeOnlineResumeTrack(json: String): OnlineTrack? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val pluginId = obj.optString("pluginId")
    val platformId = obj.optString("platformId")
    val title = obj.optString("title")
    if (pluginId.isBlank() || platformId.isBlank() || title.isBlank()) return null
    val platform = obj.optString("platform", pluginId)
    return OnlineTrack(
        pluginId = pluginId,
        platform = platform,
        sourceName = obj.optString("sourceName", platform),
        platformId = platformId,
        title = title,
        artist = obj.optString("artist"),
        album = obj.optString("album"),
        artworkUrl = obj.optString("artworkUrl").ifBlank { null },
        durationMs = obj.optLong("durationMs", 0).takeIf { it > 0 },
    )
}

internal fun resolvedOnlineDuration(apiDurationMs: Long?, playerDurationMs: Long, fallbackMs: Long): Long =
    listOfNotNull(
        apiDurationMs?.takeIf { it > 0 },
        playerDurationMs.takeIf { it > 0 },
        fallbackMs.takeIf { it > 0 },
    ).firstOrNull() ?: 0L

/** 在线专辑收藏引用的来源标记；播放时据此重新打开专辑。 */
internal const val ONLINE_ALBUM_SOURCE = "onlinealbum"

/** 本地/导入专辑收藏引用的来源标记；播放时据此打开专辑详情页。 */
internal const val LOCAL_ALBUM_SOURCE = "localalbum"

/** 收藏引用 → 在线专辑/歌单/榜单（在线合集收藏项）；其它来源返回 null。 */
internal fun onlineCollectionForFavorite(reference: MediaReference): OnlineCollection? {
    if (reference.source != ONLINE_ALBUM_SOURCE) return null
    val online = reference.onlineTrack ?: return null
    // 合集收藏把 kind 编码在 onlineTrack.platform（album/sheet/toplist）；未知值按专辑兜底。
    val kind = when (online.platform.lowercase()) {
        "sheet" -> StreamCollectionKind.Sheet
        "toplist" -> StreamCollectionKind.TopList
        else -> StreamCollectionKind.Album
    }
    return OnlineCollection(
        pluginId = online.pluginId,
        sourceName = online.sourceName,
        kind = kind,
        collectionId = online.platformId,
        name = reference.title,
        artist = reference.artist,
        artworkUrl = reference.artworkUrl,
        description = null,
        worksNum = null,
    )
}

/** 收藏引用 → 本地/导入专辑键（本地专辑收藏项）；其它来源返回 null。 */
internal fun localAlbumKeyForFavorite(reference: MediaReference): String? =
    reference.key.takeIf { reference.source == LOCAL_ALBUM_SOURCE && it.isNotBlank() }

/**
 * 条目是否走视频内核（视频内核与音频内核不能混在一条队列里）。
 *
 * 只看引用自带的形态信息，不解析曲目、不碰播放器状态——历史/收藏列表要在建队列**之前**
 * 按形态分组，此时逐条解析会带来大量无谓副作用（注册映射、探测本地文件可读性）。
 */
internal fun referenceIsVideo(reference: MediaReference): Boolean = reference.source == "video"

internal const val MEDIA_SEEK_STEP_MS = 10_000L
private const val FAVORITE_FOLDERS_KEY = "favorite_folders_v2"
private const val FAVORITE_DEFAULT_ID = "default"
private const val FAVORITE_DEFAULT_FOLDER_NAME = "默认收藏夹"

/** 各内容域的默认收藏夹 id：音乐沿用旧全局 id「default」（旧数据落在这里），其余域加域后缀。 */
internal fun favoriteDefaultFolderId(scope: MediaScope): String =
    if (scope == MediaScope.Music) FAVORITE_DEFAULT_ID else "$FAVORITE_DEFAULT_ID:${scope.name}"

/** 默认收藏夹判定（含跨域后缀形态）。 */
internal fun isDefaultFavoriteFolderId(id: String): Boolean =
    id == FAVORITE_DEFAULT_ID || MediaScope.entries.any { id == "$FAVORITE_DEFAULT_ID:${it.name}" }

/** 收藏条目的唯一键（`source:key`）：判断「是否已收藏」与去重共用一个口径。 */
internal fun favoriteItemKey(source: String, key: String): String = "$source:$key"

/** 条目是否已在**任一**收藏夹中（跨夹判重；收藏夹选择语义废弃后收藏只有一种状态）。 */
internal fun isReferenceFavorited(folders: List<FavoriteFolder>, source: String, key: String): Boolean =
    folders.any { folder -> folder.items.any { it.source == source && it.key == key } }

/**
 * 一键收藏/取消收藏（不弹收藏夹选择）：已在任何收藏夹 → 从**所有**夹移除；
 * 否则 → 落入该内容域的**默认收藏夹**。返回 (更新后的清单, 是否为新增收藏)。
 *
 * 纯函数（便于单测）；「收藏到指定夹」的旧语义只在收藏夹页管理时保留。
 */
internal fun toggleReferenceInFavorites(
    folders: List<FavoriteFolder>,
    reference: MediaReference,
    scope: MediaScope,
): Pair<List<FavoriteFolder>, Boolean> {
    val exists = isReferenceFavorited(folders, reference.source, reference.key)
    if (!exists) {
        val defaultId = favoriteDefaultFolderId(scope)
        val landed = folders.any { it.id == defaultId }
        val updated = folders.map { folder ->
            if (folder.id != defaultId) folder
            else folder.copy(items = (listOf(reference) + folder.items).distinctBy { favoriteItemKey(it.source, it.key) })
        }
        // 默认夹在 loadFavoriteFolders 恒补齐；此处兜底新建（条目直接随夹落位），保证收藏必有落点。
        val withLanding = if (landed) updated
        else updated + FavoriteFolder(
            id = defaultId,
            name = FAVORITE_DEFAULT_FOLDER_NAME,
            createdAtMs = System.currentTimeMillis(),
            items = listOf(reference),
            scope = scope,
        )
        return withLanding to true
    }
    return folders.map { folder ->
        folder.copy(items = folder.items.filterNot { it.source == reference.source && it.key == reference.key })
    } to false
}

/** 旧版无 scope 字段的默认夹 id → 域（旧全局默认夹归音乐）。 */
private fun scopeOfFavoriteFolderId(id: String): MediaScope =
    MediaScope.entries.firstOrNull { id == "$FAVORITE_DEFAULT_ID:${it.name}" } ?: MediaScope.Music
private const val SINGLE_PLAYBACK_HISTORY_KEY = "single_playback_history_v2"
private const val ALBUM_PLAYBACK_HISTORY_KEY = "album_playback_history_v2"
private const val PLAYBACK_HISTORY_LIMIT = 500
private const val PLAYBACK_PROGRESS_SAVE_INTERVAL_MS = 15_000L
private const val VIDEO_AUDIO_ONLY_KEY = "video_audio_only"
private const val VIDEO_QUALITY_KEY = "video_quality"
private const val SKIP_INTRO_OUTRO_ENABLED_KEY = "skip_intro_outro_enabled"
private const val AUTO_OPEN_PLAYING_PAGE_KEY = "auto_open_playing_page"
private const val DEFAULT_VIDEO_QUALITY = "fhd"
private const val VIDEO_SUBTITLE_OFFSET_KEY = "video_subtitle_offset_ms"
private const val VIDEO_SUBTITLE_SCALE_KEY = "video_subtitle_scale"
private const val VIDEO_SUBTITLE_VISIBLE_KEY = "video_subtitle_visible"
private const val VIDEO_SUBTITLE_TTS_KEY = "video_subtitle_tts_enabled"
private fun videoSubtitleUriKey(videoId: Long): String = "video_subtitle_uri_$videoId"
private const val VIDEO_SUBTITLE_TTS_SOURCE = "video_subtitle"

/** 聚合在线曲目：key（插件:平台:id）一致视为同一首，保留先出现者。 */
internal fun mergeStreamSearchTracks(existing: List<StreamTrack>, incoming: List<StreamTrack>): List<StreamTrack> =
    (existing + incoming).distinctBy { it.pluginId + ":" + it.key }

/** 在线合集条目（专辑/歌单/榜单）→ 展示模型，并带上源名。 */
internal fun streamCollectionToOnlineCollection(source: MusicFreeSource, collection: StreamCollection): OnlineCollection =
    OnlineCollection(
        pluginId = source.id,
        sourceName = source.name,
        kind = collection.kind,
        collectionId = collection.key,
        name = collection.name,
        artist = collection.artist,
        artworkUrl = collection.artwork,
        description = collection.description,
        worksNum = collection.worksNum,
    )

internal data class OnlineAlbumEnqueueResult(val succeeded: Int, val failed: Int, val skipped: Int = 0)

/** 下载音质选择弹窗：track 与 collection 二选一。 */
data class DownloadQualityPrompt(
    val track: StreamTrack? = null,
    val collection: OnlineCollection? = null,
)

internal fun beginOnlineAlbumDownload(state: MusicUiState, collection: OnlineCollection): MusicUiState = state.copy(
    onlineAlbumDownloadKey = collection.key,
    onlineAlbumDownloadProgress = "正在添加下载任务…",
    status = null,
)

internal fun onlineAlbumEnqueueProgress(position: Int): String =
    "正在添加下载任务：已处理 $position 首"

internal suspend fun enqueueOnlineAlbumTracks(
    tracks: List<StreamTrack>,
    qualityApiValue: String,
    folderName: String?,
    enqueue: suspend (OnlineTrack, String, String?, Int?) -> DownloadTask,
    onProgress: (String) -> Unit,
): OnlineAlbumEnqueueResult {
    var succeeded = 0
    var skipped = 0
    var failed = 0
    tracks.forEachIndexed { index, track ->
        onProgress(onlineAlbumEnqueueProgress(index + 1))
        runCatching { enqueue(OnlineTrack.fromStreamTrack(track), qualityApiValue, folderName, index + 1) }
            .onSuccess { task ->
                when (task.status) {
                    DownloadTaskStatus.COMPLETED -> skipped++
                    DownloadTaskStatus.FAILED -> failed++
                    else -> succeeded++
                }
            }
            .onFailure { failed++ }
    }
    return OnlineAlbumEnqueueResult(succeeded, failed, skipped)
}

internal fun onlineAlbumEnqueueSummary(result: OnlineAlbumEnqueueResult): String = buildString {
    append("已加入下载队列：成功 ${result.succeeded} 首")
    if (result.skipped > 0) append("，跳过 ${result.skipped} 首")
    append("，失败 ${result.failed} 首")
}

internal fun finishOnlineAlbumDownload(
    state: MusicUiState,
    result: OnlineAlbumEnqueueResult,
    pageError: String? = null,
): MusicUiState = state.copy(
    onlineAlbumDownloadKey = null,
    onlineAlbumDownloadProgress = null,
    downloadAddAnnouncement = buildString {
        append(onlineAlbumEnqueueSummary(result))
        pageError?.takeIf(String::isNotBlank)?.let { append("，后续分页失败：$it") }
    },
)

internal fun activeOnlineDownloadKeys(tasks: List<DownloadTask>): Set<String> = tasks.asSequence()
    .filter { it.status in setOf(DownloadTaskStatus.QUEUED, DownloadTaskStatus.DOWNLOADING, DownloadTaskStatus.WAITING_NETWORK) }
    .map(DownloadTask::onlineKey)
    .toSet()

internal fun canLoadMoreOnline(hasQuery: Boolean, searching: Boolean, loadingMore: Boolean, hasMore: Boolean): Boolean =
    hasQuery && !searching && !loadingMore && hasMore

internal fun isRemoteCastSource(uriScheme: String?, hasOnlineTrack: Boolean): Boolean =
    hasOnlineTrack || uriScheme.equals("online", true)

internal enum class MediaButtonAction { Play, Pause, Toggle }

internal fun mediaButtonAction(keyCode: Int): MediaButtonAction? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY -> MediaButtonAction.Play
    KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaButtonAction.Pause
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MediaButtonAction.Toggle
    else -> null
}

/**
 * DLNA only accepts media the phone can relay; raw file paths are deliberately excluded.
 * 例外：file:// 仅用于应用自产文件（TTS 成品）经本机代理转投——库内音频一律 content://。
 */
internal fun isDlnaCastSource(uriScheme: String?, hasOnlineTrack: Boolean): Boolean =
    hasOnlineTrack || uriScheme.equals("content", true) || uriScheme.equals("baidupan", true) ||
        uriScheme.equals("quarkpan", true) || uriScheme.equals("file", true) ||
        isRemoteCastSource(uriScheme, hasOnlineTrack)

/** 视频投送白名单：本地/网盘走本机中转，其余不投。 */
internal fun isVideoCastSource(uriScheme: String?): Boolean =
    uriScheme == "content" || uriScheme == "baidupan" || uriScheme == "quarkpan"

internal fun shouldRefreshCastSource(refreshable: Boolean, attempt: Int): Boolean = refreshable && attempt == 0

internal fun remotePlaybackRate(speed: Double): Double = speed.coerceIn(0.5, 2.0)

/** 视频倍速钳制：与音频共用播放内核但档位更宽（长按倍速最高 3x）。 */
internal fun videoPlaybackRate(speed: Double): Double = speed.coerceIn(0.5, 3.0)

/** 长按播放器区域的临时倍速档。 */
internal const val VIDEO_HOLD_SPEED = 2.0

/** 视频倍速档位（对齐 B 站播放器：0.5x–3x 八档）。 */
internal val VideoSpeedOptions = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0)

/** 视频定时播放档位（分钟）。 */
internal val VideoSleepTimerOptions = listOf(15, 30, 45, 60)

/** 倍速显示标签：整数档不带小数（2x），非整数档保留一位（1.5x）。 */
internal fun formatSpeedLabel(speed: Double): String =
    if (speed % 1.0 == 0.0) "${speed.toInt()}x" else "${speed}x"

/** 手势快进/快退的起步量（毫秒）：越过阈值即先跳 10 秒，随滑动距离平方根增长。 */
internal const val GESTURE_SEEK_BASE_MS = 10_000.0

/** 手势快进/快退的增量上限（毫秒）。 */
internal const val GESTURE_SEEK_MAX_MS = 120_000.0

/** 平方根曲线基准距离（px）：滑到该距离时增量达到 GESTURE_SEEK_CURVE_EXTRA_MS。 */
internal const val GESTURE_SEEK_CURVE_DISTANCE_PX = 480.0

/** 滑到基准距离时的额外增量（毫秒）。 */
internal const val GESTURE_SEEK_CURVE_EXTRA_MS = 45_000.0

/**
 * 手势滑动 seek 的目标进度：起点 10s + sqrt 曲线增量（上限 120s），向前/向后由 dragDistancePx 符号决定；
 * 结果钳制在 [0, durationMs]（未知时长不设上限）。与 bilibili-player-android 的手势曲线同款。
 */
internal fun gestureSeekTargetMs(startMs: Long, dragDistancePx: Float, touchSlopPx: Float, durationMs: Long): Long {
    val extraDistance = (kotlin.math.abs(dragDistancePx) - touchSlopPx).coerceAtLeast(0f)
    val totalMs = (GESTURE_SEEK_BASE_MS +
        kotlin.math.sqrt(extraDistance / GESTURE_SEEK_CURVE_DISTANCE_PX) * GESTURE_SEEK_CURVE_EXTRA_MS)
        .toLong()
        .coerceAtMost(GESTURE_SEEK_MAX_MS.toLong())
    val delta = if (dragDistancePx >= 0f) totalMs else -totalMs
    val maxMs = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
    return (startMs + delta).coerceIn(0L, maxMs)
}

internal fun isPrematureRemoteStop(isCasting: Boolean, durationMs: Long, positionMs: Long): Boolean =
    isCasting && durationMs > 0 && positionMs + 1_000 < durationMs

internal fun isCurrentPlaybackCompletion(observedGeneration: Long, currentGeneration: Long): Boolean =
    observedGeneration == currentGeneration

internal fun hasReachedTrackEnd(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs

internal fun isConfirmedRemoteCompletion(positionMs: Long, durationMs: Long, stopped: Boolean): Boolean =
    durationMs > 0 && (positionMs >= durationMs || (stopped && positionMs + 1_000 >= durationMs))

/** 视频顺序播放：下一集索引；末集返回 null（保持结尾不自动跳转）。 */
internal fun videoNextIndex(current: Int, last: Int): Int? =
    if (last < 0 || current < 0 || current >= last) null else current + 1

/** 视频上一集索引；首集返回 null。 */
internal fun videoPreviousIndex(current: Int): Int? =
    if (current <= 0) null else current - 1

/**
 * 视频自动连播判定（本机与投送轮询共用）：确认播完、非直播流、还有下一集才推进；
 * 同一分集只自动推进一次（[videoId] == [advancedVideoId] 时拒绝，切换失败不连环重试）。
 */
internal fun shouldAutoAdvanceVideo(
    videoId: Long,
    advancedVideoId: Long,
    reachedEnd: Boolean,
    isLive: Boolean,
    hasNextEpisode: Boolean,
): Boolean = videoId != advancedVideoId && reachedEnd && !isLive && hasNextEpisode

/**
 * 上一首/下一首是否可用：队列里确实还有别的条目。
 *
 * 单条队列（历史/收藏里直接点一条、从专辑外单独起播）时按钮必须禁用——旧实现下按「下一首」会
 * 重放同一首（RepeatAll 回绕到自身），用户看到的是「按钮没反应」。队列长度是唯一可靠判据。
 */
internal fun queueSkipAvailable(queueSize: Int, index: Int): Boolean = index >= 0 && queueSize > 1

/**
 * 队列内目标下标：越界返回 null（播放器据此禁用按钮，而不是点击后静默无响应）。
 *
 * [wraps] 表示该队列模式越界时回绕到另一端（循环/随机/单曲循环下手动切歌都会回绕）：
 * 此时边界处的按钮仍可用；只有严格顺序播放才在两端置灰。
 */
internal fun skipTargetIndex(current: Int, count: Int, step: Int, wraps: Boolean = false): Int? {
    if (count <= 1 || current < 0) return null
    val target = current + step
    if (target in 0 until count) return target
    return if (wraps) ((target % count) + count) % count else null
}

/** 该队列模式下手动切歌是否回绕：顺序播放不回绕，其余（循环/随机/单曲循环）都回绕。 */
internal fun queueModeWraps(mode: NativeQueueMode): Boolean = mode != NativeQueueMode.Sequential

/** 直播换台：清单内上一个/下一个频道下标；清单只有一条或已在端点时返回 null。 */
internal fun liveChannelTargetIndex(current: Int, count: Int, step: Int): Int? = skipTargetIndex(current, count, step)

/**
 * 「上一项/下一项」是否可用：视频按分集顺序严格判定（两端置灰），音频按队列下标判定。
 * 不可用时界面必须置灰——按下无反应是用户报的原始问题之一。
 */
internal fun skipStepAvailable(
    step: Int,
    isVideo: Boolean,
    audioIndex: Int,
    audioSize: Int,
    videoIndex: Int,
    videoSize: Int,
    /** 音频队列模式：回绕模式下边界处仍可切歌。 */
    audioMode: NativeQueueMode = NativeQueueMode.Sequential,
): Boolean {
    return if (isVideo) {
        // 视频队列没有循环/随机模式：严格按分集顺序，两端置灰。
        skipTargetIndex(videoIndex, videoSize, step) != null
    } else {
        skipTargetIndex(audioIndex, audioSize, step, wraps = queueModeWraps(audioMode)) != null
    }
}

/** 上一项/下一项的按钮文案：直播换台是「频道」，视频队列是「集」，其余是「首」。 */
internal fun skipStepLabel(step: Int, isVideo: Boolean, isLive: Boolean): String {
    val forward = step > 0
    return when {
        isLive -> if (forward) "下一个频道" else "上一个频道"
        isVideo -> if (forward) "下一集" else "上一集"
        else -> if (forward) "下一首" else "上一首"
    }
}

/**
 * 当前直播频道的**频道网格项键**（`平台:专辑/频道 id`），与在线合集 key 同口径。
 * 插件音源版无直播换台上下文，仅保留键工具函数供历史数据对齐。
 */
internal fun liveChannelGridKey(platformName: String, platformId: String?): String? =
    platformId?.takeIf { it.isNotBlank() }?.let { "$platformName:$it" }

internal const val VIDEO_PLAYING_POLL_INTERVAL_MS = 250L
internal const val VIDEO_IDLE_POLL_INTERVAL_MS = 1_000L

internal fun videoProgressPollingDelayMs(isPlaying: Boolean): Long =
    if (isPlaying) VIDEO_PLAYING_POLL_INTERVAL_MS else VIDEO_IDLE_POLL_INTERVAL_MS

/** 系统生命周期退到后台时只停用画面轨，不能把它当成用户主动退出播放器。 */
internal enum class VideoBackgroundMode { KeepAudioPlaying, PausePlayback }

internal fun videoBackgroundMode(systemLifecycleEvent: Boolean): VideoBackgroundMode =
    if (systemLifecycleEvent) VideoBackgroundMode.KeepAudioPlaying else VideoBackgroundMode.PausePlayback

internal fun shouldKeepVideoAudioPlaying(statePlaying: Boolean, enginePlaying: Boolean): Boolean =
    statePlaying || enginePlaying

internal fun shouldShowVideoEpisodes(queueSize: Int): Boolean = queueSize > 1

internal fun isLiveVideoSource(scheme: String?, host: String?): Boolean =
    scheme.equals("bilibili", ignoreCase = true) && host.equals("live", ignoreCase = true)

internal fun isLiveVideoUri(uri: Uri?): Boolean = isLiveVideoSource(uri?.scheme, uri?.host)

internal fun videoHasSeekableDuration(uri: Uri?, durationMs: Long): Boolean =
    durationMs > 0 && !isLiveVideoUri(uri)

/** 引擎切换/换源前取较新的位置，避免重放从 0 开始；接近结尾时留一毫秒避免立即再次结束。 */
internal fun videoResumePosition(statePositionMs: Long, enginePositionMs: Long, durationMs: Long = 0): Long {
    val position = maxOf(statePositionMs, enginePositionMs).coerceAtLeast(0)
    return if (durationMs > 0) position.coerceAtMost((durationMs - 1).coerceAtLeast(0)) else position
}

internal fun canUpdateVideoQueue(currentId: Long?, queueIds: List<Long>, index: Int): Boolean =
    currentId != null && index in queueIds.indices && queueIds[index] == currentId

/** 判定是否为 RealMedia（.rm/.rmvb/.rmm）视频文件名。 */
internal fun isRmvbLikeVideoFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension == "rm" || extension == "rmvb" || extension == "rmm"
}

/** 判定是否为可播放的视频文件名（外部打开路由用；webm 可能为纯音频，改由 MIME 兜底判定）。 */
internal fun isExternalVideoFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return extension in setOf(
        "mp4", "m4v", "mkv", "ts", "flv", "3gp", "mov", "avi",
        "wmv", "asf", "rmvb", "rm", "rmm", "mpg", "mpeg",
    )
}

internal fun shouldAdvanceAfterRemoteStop(isCasting: Boolean, durationMs: Long): Boolean =
    !isCasting || durationMs > 0

internal fun shouldUseRemoteSeek(isCasting: Boolean): Boolean = isCasting

internal fun remoteResumePosition(positionMs: Long, cueStartMs: Long, durationMs: Long): Long {
    val relative = (positionMs - cueStartMs).coerceAtLeast(0)
    return if (durationMs > 0) relative.coerceAtMost(durationMs) else relative
}


internal fun decodeTrackMetadataOverrides(value: String?): Map<String, TrackMetadataOverride> {
    if (value.isNullOrBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(value)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val metadata = json.getJSONObject(key)
                put(
                    key,
                    TrackMetadataOverride(
                        title = metadata.optString("title"),
                        artist = metadata.optString("artist"),
                        album = metadata.optString("album"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())
}

internal fun encodeTrackMetadataOverrides(overrides: Map<String, TrackMetadataOverride>): String =
    JSONObject().apply {
        overrides.forEach { (key, metadata) ->
            put(key, JSONObject().apply {
                put("title", metadata.title)
                put("artist", metadata.artist)
                put("album", metadata.album)
            })
        }
    }.toString()

/**
 * 跳过片头片尾总开关的取值门：开关关闭时即使存有秒数也一律按 0 处理
 * （即「关闭 = 清零生效」，不依赖存储是否真的被清空）。纯函数，便于单测。
 */
internal fun gatedSkipSeconds(enabled: Boolean, headSeconds: Float, tailSeconds: Float): Pair<Float, Float> =
    if (enabled) headSeconds.coerceAtLeast(0f) to tailSeconds.coerceAtLeast(0f) else 0f to 0f

class NativeMusicViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = application.contentResolver
    private val musicApp = application as MusicApplication
    private val player get() = musicApp.player
private val mediaServer = (application as MusicApplication).mediaServer
    private val audioOutputController = (application as MusicApplication).audioOutputs
private val dlnaDiscovery = (application as MusicApplication).dlnaDiscovery
private val pluginManager = (application as MusicApplication).pluginManager
    private val sourceStore = MusicFreeSourceStore(application)
    private val downloads = (application as MusicApplication).downloads
    private val panApi = (application as MusicApplication).panApi
    private val panAccountStore = (application as MusicApplication).panAccountStore
    private val panStreamProxy = (application as MusicApplication).panStreamProxy
    private val panDownloads = (application as MusicApplication).panDownloads
    private val quarkApi = (application as MusicApplication).quarkApi
    private val quarkAccountStore = (application as MusicApplication).quarkAccountStore
    private val quarkStreamProxy = (application as MusicApplication).quarkStreamProxy
    private val quarkDownloads = (application as MusicApplication).quarkDownloads
    private val downloadEngine = (application as MusicApplication).downloadEngine
    private val videoPlayer = (application as MusicApplication).videoPlayer
private val ffmpegVideoPlayer = (application as MusicApplication).ffmpegVideoPlayer
    private var panTokenExpiresAtMs: Long = 0
    private var panAuthJob: Job? = null
    private val panFileByFsId = mutableMapOf<Long, BaiduPanFile>()
    private var quarkAuthJob: Job? = null
    private var quarkRefreshJob: Job? = null
    private val quarkFileByFid = mutableMapOf<String, QuarkPanFile>()
    private var quarkClipboardFile: QuarkPanFile? = null
    private var quarkClipboardShouldMove = false
    private var quarkSearchJob: Job? = null
    private var quarkSearchKeyword = ""
    private var quarkSearchPage = 1
    private var quarkCurrentDirFid = "0"
    private var quarkListPage = 1
    private val quarkDirStack = ArrayDeque<Pair<String, String>>()
    private val historyPreferences = application.getSharedPreferences("cast_history", Context.MODE_PRIVATE)
    private val settingsPreferences = application.getSharedPreferences("player_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(MusicUiState())

    val state: StateFlow<MusicUiState> = _state.asStateFlow()
private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var sleepTimerGeneration = 0
    private var lyricTts: LyricTtsEngine? = null
    private var lastSpokenLineIndex = -1
    private var lastSpokenTrackId: Long? = null
    private var lyricTtsUtteranceSequence = 0L
    private var pendingLyricUtterance: PendingLyricUtterance? = null
    private var cachedLyricsText: String? = null
    private var cachedLyricLines: List<LyricLine> = emptyList()
    private var localFadeTransitionInProgress = false
    private var pendingLocalPlayback: Pair<List<NativeTrack>, Int>? = null
    private var autoAdvanceInProgress = false
    private var playbackGeneration = 0L
    private var notificationState: Triple<Long, Boolean, Boolean>? = null
    private var mediaSessionTrackId: Long? = null
    private var mediaSessionDurationMs: Long = -1L
    private var mediaSessionIsVideo = false
    private var lastVideoSessionUpdateAtMs = 0L

    private data class PendingLyricUtterance(
        val id: String,
        val trackId: Long,
        val lineIndex: Int,
        val text: String,
        val attempt: Int,
        val source: String,
    )

    private val mediaSession = MediaSession(application, "NativeMusicPlayback").apply {
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setCallback(object : MediaSession.Callback() {
            /** 部分耳机/蓝牙接收端只发原始 MEDIA_BUTTON，不走 TransportControls。 */
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val event = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
                when (mediaButtonAction(event.keyCode)) {
                    MediaButtonAction.Play -> onPlay()
                    MediaButtonAction.Pause -> onPause()
                    MediaButtonAction.Toggle -> {
                        if (_state.value.playing || _state.value.videoPlaying) onPause() else onPlay()
                    }
                    null -> return super.onMediaButtonEvent(mediaButtonEvent)
                }
                return true
            }

            override fun onPlay() {
                val state = _state.value
                if (state.currentVideo != null) {
                    if (!state.videoPlaying) videoToggle()
                } else if (!state.playing) togglePlayback()
            }

            override fun onPause() {
                val state = _state.value
                if (state.currentVideo != null) {
                    if (state.videoPlaying) videoToggle()
                } else if (state.playing) togglePlayback()
            }

            override fun onSkipToNext() {
                if (_state.value.currentVideo != null) skipNextVideo() else skipNext()
            }

            override fun onSkipToPrevious() {
                if (_state.value.currentVideo != null) skipPreviousVideo() else skipPrevious()
            }

            override fun onSeekTo(pos: Long) {
                if (_state.value.currentVideo != null) videoSeekTo(pos) else seek(pos)
            }

            override fun onFastForward() = seekSessionStep(forward = true)

            override fun onRewind() = seekSessionStep(forward = false)
        })
    }
private var activeRemote: CastRemote? = null

/**
 * 多房间投送组（2026-09-28 第二梯队）：投送中再选设备 = 加入同播组，同一节目多房间同步播放。
 * [activeRemote] 为主会话（进度/掉线/续播判定基准），组内为附加设备；控制命令扇出到全员。
 */
private val castGroupList = mutableListOf<CastRemote>()
@Volatile private var castServedUrl: String? = null

/** 视频转封装临时文件（cacheDir）：新转封装前删除旧文件。 */
private var remuxTempFile: File? = null

/** 视频投送轮询连续无响应上限（500ms/次 → 约 5 秒）：达到即判定设备掉线。 */
private var videoCastPollFailureCount = 0

/** 视频自动连播闸：已自动推进过的分集 id（同一集播完只推进一次，切换失败不连环重试）。 */
private var videoAutoAdvanceDoneId = -1L

/** 投送组全员（主会话 + 附加设备）；控制类命令扇出到全员。 */
private fun castGroupAll(): List<CastRemote> = listOfNotNull(activeRemote) + castGroupList

/** 投送轮询连续无响应上限（500ms/次 → 约 5 秒）：达到即判定设备掉线。 */
private var castPollFailureCount = 0
private var lastDlnaVolumeReadAtMs = 0L
    private var clipboardTrack: NativeTrack? = null
    private var clipboardShouldMove = false
    private var clipboardFolder: String? = null
    private var clipboardFolderShouldMove = false
    private var panClipboardFile: BaiduPanFile? = null
    private var panClipboardShouldMove = false
    private var panSearchJob: Job? = null
    private var panSearchKeyword = ""
    private var panSearchPage = 1
    private var pendingDelete: List<NativeTrack>? = null
    private val audioDeviceCallback = audioOutputController.observeChanges(::refreshAudioOutputs)
private val onlineTrackById = mutableMapOf<Long, OnlineTrack>()
/** 在线曲目的插件原始 item（播放/歌词取流时回传插件，保真度优于重建的 IMusicItem）。 */
private val onlineRawItemById = mutableMapOf<Long, Any?>()
/** 在线合集（专辑/歌单/榜单）的插件原始条目：钻取分页时回传对应函数。 */
private val onlineCollectionRaw = mutableMapOf<String, StreamCollection>()
/** 在线歌手条目的插件原始数据：作品列表钻取时回传。 */
private val onlineArtistRaw = mutableMapOf<String, StreamArtist>()
/** 在线取流结果短期缓存：切回刚播过的曲目免重复解析。 */
private val onlineMediaCache = HashMap<String, MediaSource>()
private var onlineStartInProgress = false
/** 在途解析的队列下标：解析成功前用户连点切歌，以此（而非未提交的 queueIndex）为基准继续前进。 */
private var pendingOnlineIndex: Int? = null
/** 最近一次手动切歌的方向：在途指示显示在用户实际按下的按钮上（自动连播沿用上次方向）。 */
private var pendingOnlineForward = true
private var albumPlaybackContext: AlbumPlaybackContext? = null
    private var forcedStartPositionMs: Long? = null

    private var onlineSearchJob: Job? = null
    private var sourceSearchJob: Job? = null
    private var onlineTopListJob: Job? = null
    private var streamSeekJob: Job? = null
    private var panPlaybackJob: Job? = null
    private var videoPanJob: Job? = null

    /** 最近一次在线解析成功的直链（ExoPlayer 容器不支持时交给 FFmpeg 直连重放）。 */
    private var onlineResolvedTrackId: Long? = null
    private var onlineResolvedUrl: String? = null
    private var quarkPlaybackJob: Job? = null
    private var videoProgressJob: Job? = null
    private var videoSubtitleJob: Job? = null
    private val videoSubtitleCueCache = mutableMapOf<String, List<VideoSubtitleCue>>()
    private var videoBackgrounded = false
    private var videoWasPlayingBeforeBackground = false
    private var videoAudioOnlyUser = settingsPreferences.getBoolean(VIDEO_AUDIO_ONLY_KEY, false)
    private var lastSpokenVideoLineIndex = -1
    private var lastSpokenVideoId: Long? = null
    private var activeVideoDlna: DlnaRendererController? = null
    private var completedDownloadIds = emptySet<String>()
    private var downloadStatuses = emptyMap<String, DownloadTaskStatus>()
    private var downloadTaskStateInitialized = false

    init {
        registerNetworkRecovery()
        _state.value = _state.value.copy(
            history = loadHistory(),
            queueMode = loadQueueMode(),
            // 倍速不持久化：每次会话从 1.0 开始，只在播放页调节（最终版行为）。
            playbackSpeed = 1.0,
            videoSpeed = 1.0,
            volume = settingsPreferences.getFloat("volume", 1f).coerceIn(0f, 1f),
            wifiOnlyDownload = settingsPreferences.getBoolean("wifi_only_download", false),
            playbackQuality = loadStreamQuality("playback_quality", default = StreamQuality.Standard),
            playbackQualityValue = settingsPreferences.getString("playback_quality_value", null),
            downloadQuality = loadStreamQuality("download_quality", default = StreamQuality.Super),
            downloadQualityValue = settingsPreferences.getString("download_quality_value", null),
            fadeTransitions = settingsPreferences.getBoolean("fade_transitions", false),
            autoMatchLocalLyrics = settingsPreferences.getBoolean("auto_match_local_lyrics", true),
            autoOpenPlayingPage = settingsPreferences.getBoolean(AUTO_OPEN_PLAYING_PAGE_KEY, true),
            skipSilenceEnabled = settingsPreferences.getBoolean("skip_silence_enabled", false),
            silenceSkipMode = settingsPreferences.getString("skip_silence_mode", SilenceSkipMode.LongerThan.name)
                ?.let { runCatching { SilenceSkipMode.valueOf(it) }.getOrNull() } ?: SilenceSkipMode.LongerThan,
            silenceSkipThresholdMs = settingsPreferences.getLong("skip_silence_threshold_ms", 500L)
                .takeIf { settingsPreferences.getBoolean("skip_silence_enabled", false) }
                ?.coerceIn(1L, 60_000L) ?: 0,
            eqEnabled = settingsPreferences.getBoolean("eq_enabled", false),
            eqBands = loadEqBands(),
            eqPreamp = settingsPreferences.getFloat("eq_preamp", 0f),
            eqPreset = settingsPreferences.getString("eq_preset", "自定义") ?: "自定义",
            audioEffects = loadAudioEffects(),
            themeMode = loadThemeMode(),
            gridDensity = loadGridDensity(),
            librarySort = loadLibrarySort(),
            librarySortAscending = settingsPreferences.getBoolean("library_sort_ascending", true),
            libraryBrowse = loadLibraryBrowse(),
            lyricTtsEnabled = settingsPreferences.getBoolean("lyric_tts_enabled", false),
            lyricTtsEngine = settingsPreferences.getString("lyric_tts_engine", null),
            lyricTtsOffsetMs = normalizeLyricOffset(settingsPreferences.getLong("lyric_tts_offset_ms", 0L)),
            lyricTtsChannel = lyricTtsChannelFromValue(settingsPreferences.getString("lyric_tts_channel", null)),
            videoAudioOnly = videoAudioOnlyUser,
            skipIntroOutroEnabled = settingsPreferences.getBoolean(SKIP_INTRO_OUTRO_ENABLED_KEY, false),
            videoSubtitleVisible = settingsPreferences.getBoolean(VIDEO_SUBTITLE_VISIBLE_KEY, true),
            videoSubtitleOffsetMs = settingsPreferences.getLong(VIDEO_SUBTITLE_OFFSET_KEY, 0L).coerceIn(-30_000L, 30_000L),
            videoSubtitleScale = settingsPreferences.getFloat(VIDEO_SUBTITLE_SCALE_KEY, 1f).coerceIn(0.75f, 1.75f),
            videoSubtitleTtsEnabled = settingsPreferences.getBoolean(VIDEO_SUBTITLE_TTS_KEY, false),
            favoriteIds = loadFavoriteIds(),
            favoriteFolders = loadFavoriteFolders(),
            playlists = loadPlaylists(),
            importedAlbums = loadImportedAlbums(),
            hiddenAlbumKeys = loadHiddenAlbumKeys(),
            trackMetadataOverrides = loadTrackMetadataOverrides(),
            playbackHistory = loadPlaybackHistory(),
            singlePlaybackHistory = loadSinglePlaybackHistory(),
            albumPlaybackHistory = loadAlbumPlaybackHistory(),
        )
        ensureLyricTtsReady()
        refreshMusicFreeSources()
        viewModelScope.launch {
            var previous: PlayerWidgetSnapshot? = null
            _state.collect { current ->
                val track = current.currentTrack
                val snapshot = PlayerWidgetSnapshot(
                    title = track?.title.orEmpty(),
                    artist = track?.artist.orEmpty(),
                    lyric = if (track == null) "" else lyricBarText(current.lyrics, current.positionMs).orEmpty(),
                    playing = track != null && current.playing,
                )
                if (previous == snapshot) return@collect
                previous = snapshot
                PlayerWidgetProvider.broadcastUpdate(
                    getApplication(),
                    snapshot.title,
                    snapshot.artist,
                    snapshot.lyric,
                    snapshot.playing,
                )
            }
        }
        player.setEq(_state.value.eqBands.toFloatArray(), _state.value.eqPreamp, _state.value.eqEnabled)
        // 视频倍速恢复：ExoPlayer 引擎记住档位，之后每次起播自动重放。
        videoPlayer.setSpeed(_state.value.videoSpeed)
        videoPlayer.setVideoEnabled(!_state.value.videoAudioOnly)
        player.setEffects(_state.value.audioEffects)
        player.setFadeEnabled(_state.value.fadeTransitions)
        player.setSilenceSkipping(
            _state.value.skipSilenceEnabled,
            _state.value.silenceSkipMode,
            _state.value.silenceSkipThresholdMs,
        )
        if (_state.value.lyricTtsEnabled || _state.value.videoSubtitleTtsEnabled) loadTtsEngines()
        player.setPlaybackCompleteListener { advanceAfterStop() }
        player.setFocusLossListener {
            if (_state.value.playing && !_state.value.isCasting) {
                stopLyricTts()
                _state.value = _state.value.copy(playing = false, status = "播放被其他应用打断，已暂停。")
                updateMediaSession()
            }
        }
        viewModelScope.launch {
            downloads.tasks.collect { tasks ->
                val completedIds = tasks.filter { it.status == DownloadTaskStatus.COMPLETED }.mapTo(mutableSetOf()) { it.id }
                val newlyCompleted = completedIds - completedDownloadIds
                completedDownloadIds = completedIds
                val announcement = if (downloadTaskStateInitialized) {
                    tasks.mapNotNull { task ->
                        if (downloadStatuses[task.id] != task.status) downloadTaskAnnouncement(task.track.title, task.status) else null
                    }.joinToString("；").ifBlank { null }
                } else {
                    null
                }
                downloadStatuses = tasks.associate { it.id to it.status }
                downloadTaskStateInitialized = true
                _state.value = _state.value.copy(
                    downloadTasks = tasks,
                    downloadAnnouncement = announcement ?: _state.value.downloadAnnouncement,
                    downloadingOnlineKeys = activeOnlineDownloadKeys(tasks),
                )
                if (newlyCompleted.isNotEmpty() && _state.value.permissionGranted) refresh(true)
            }
        }
        panTokenExpiresAtMs = panAccountStore.expiresAtMs()
        _state.value = _state.value.copy(
            panAccount = panAccountStore.loadUser(),
            panAccounts = panAccountStore.accounts().map { BaiduAccountInfo(it.id, it.user) },
        )
        quarkApi.setCookie(quarkAccountStore.loadCookie())
        _state.value = _state.value.copy(
            quarkAccount = quarkAccountStore.loadUser(),
            quarkAccounts = quarkAccountStore.accounts().map { QuarkAccountInfo(it.id, it.user) },
        )
        viewModelScope.launch {
            panDownloads.downloads.collect { tasks ->
                _state.value = _state.value.copy(panDownloads = tasks)
            }
        }
        viewModelScope.launch {
            quarkDownloads.downloads.collect { tasks ->
                _state.value = _state.value.copy(quarkDownloads = tasks)
            }
        }
        // 夸克会话 Cookie（__puus）约 3 小时过期，定期刷新并持久化，避免长时间使用后 403。
        quarkRefreshJob = viewModelScope.launch {
            while (true) {
                delay(QUARK_SESSION_REFRESH_INTERVAL_MS)
                if (_state.value.quarkAccount != null && quarkApi.cookie.isNotBlank()) {
                    runCatching { quarkApi.refreshSession() }.onSuccess {
                        quarkAccountStore.saveCookie(quarkApi.cookie)
                    }
                }
            }
        }
        viewModelScope.launch {
            downloadEngine.speedEtaFlow.collect { speedEta ->
                _state.value = _state.value.copy(downloadSpeedEta = speedEta)
            }
        }
        player.setRouteListener { device ->
            if (device != null && !_state.value.isCasting) {
                val output = audioOutputController.describe(device)
                _state.value = _state.value.copy(
                    targetName = output.name,
                    status = null,
                    audioOutputs = audioOutputController.outputs(),
                )
            }
        }
        videoPlayer.onComplete = { videoNext() }
        videoPlayer.onError = { message -> handleVideoPlaybackError(message) }
        videoPlayer.onUnsupported = { message -> handleVideoUnsupported(message) }
        videoPlayer.onStateChanged = {
            val current = _state.value
            if (current.currentVideo != null && !current.videoCasting) {
                val buffering = videoPlayer.isBuffering()
                val playing = videoPlayer.isPlaying()
                _state.value = current.copy(
                    videoPositionMs = videoPlayer.positionMs(),
                    videoDurationMs = videoPlayer.durationMs().takeIf { it > 0 } ?: current.videoDurationMs,
                    videoPlaying = playing,
                    videoBuffering = buffering,
                    videoLoading = current.videoLoading && !buffering && !playing,
                )
                // 节流写入播放历史（内部 15s 一次），断点续播随播放进度推进。
                recordPlaybackProgress()
                updateMediaSession()
            }
        }
    }

    /** ExoPlayer 无法解码/解析时自动回退 FFmpeg 兜底。云端视频先解析代理流地址再交给 FFmpeg。 */
    private fun handleVideoUnsupported(message: String) {
        val state = _state.value
        val video = state.currentVideo ?: return
        val resumePositionMs = videoResumePosition(
            statePositionMs = state.videoPositionMs,
            enginePositionMs = videoPlayer.positionMs(),
            durationMs = state.videoDurationMs,
        )
        if (state.videoUsingFfmpeg) {
            videoErrorState(message)
            return
        }
        if (video.uri.scheme == "baidupan" || video.uri.scheme == "quarkpan") {
            startCloudFfmpegFallback(video, resumePositionMs)
            return
        }
        videoPlayer.bindFallbackEngine(ffmpegVideoPlayer)
        _state.value = state.copy(videoUsingFfmpeg = true, videoError = null, videoLoading = true)
        runCatching {
            videoPlayer.play(video.uri, resumePositionMs)
        }
            .onSuccess { videoPlayer.setVolume(_state.value.volume) }
            .onFailure { videoErrorState("无法播放该视频格式") }
    }

    /**
     * 云端视频 FFmpeg 兜底：ExoPlayer 无法解码 rmvb/wmv 等格式时，
     * 解析本地代理流地址（http://127.0.0.1:PORT/stream）交给 FFmpeg 播放。
     */
    private fun startCloudFfmpegFallback(video: NativeTrack, startPositionMs: Long) {
        videoPanJob?.cancel()
        videoPanJob = viewModelScope.launch {
            val proxyUrl = runCatching {
                withContext(Dispatchers.IO) {
                    when (video.uri.scheme) {
                        "baidupan" -> {
                            val fsId = video.uri.host?.toLongOrNull() ?: return@withContext null
                            panAccessToken()
                            val file = panFileByFsId[fsId] ?: return@withContext null
                            val url = panStreamProxy.start(file)
                            panStreamProxy.resolveDlink()
                            url
                        }
                        "quarkpan" -> {
                            val fid = video.uri.host ?: return@withContext null
                            quarkEnsureCookie()
                            val file = quarkFileByFid[fid] ?: return@withContext null
                            val url = quarkStreamProxy.start(file)
                            quarkStreamProxy.resolveDownloadUrl()
                            url
                        }
                        else -> null
                    }
                }
            }.getOrNull()
            if (proxyUrl == null || _state.value.currentVideo?.id != video.id) {
                if (proxyUrl == null && _state.value.currentVideo?.id == video.id) {
                    videoErrorState("云端视频播放失败，请重试")
                }
                return@launch
            }
            videoPlayer.bindFallbackEngine(ffmpegVideoPlayer)
            _state.value = _state.value.copy(
                videoUsingFfmpeg = true,
                videoError = null,
                videoLoading = true,
            )
            runCatching {
                videoPlayer.playUrl(proxyUrl, startPositionMs)
                videoPlayer.setVolume(_state.value.volume)
            }.onSuccess {
                if (_state.value.currentVideo?.id == video.id) {
                    _state.value = _state.value.copy(videoLoading = false, videoError = null)
                }
            }.onFailure {
                if (_state.value.currentVideo?.id == video.id) {
                    videoErrorState("无法播放该视频格式")
                }
            }
        }
    }

    fun refresh(permissionGranted: Boolean) {
        if (!permissionGranted) {
            _state.value = _state.value.copy(permissionGranted = false, tracks = emptyList(), videoTracks = emptyList(), loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(permissionGranted = true, loading = true, status = null)
            runCatching { withContext(Dispatchers.IO) { queryVideos() } }
                .onSuccess { videos -> _state.value = _state.value.copy(videoTracks = videos) }
            runCatching { withContext(Dispatchers.IO) { queryTracks() } }
                .onSuccess { tracks ->
                    val visibleTracks = applyMetadataOverrides(tracks)
                    migrateLegacyFavorites(visibleTracks)
                    migrateLegacyPlaybackHistory(visibleTracks)
                    val favoriteFolders = loadFavoriteFolders()
                    _state.value = _state.value.copy(
                        tracks = visibleTracks,
                        loading = false,
                        favoriteFolders = favoriteFolders,
                        favoriteIds = favoriteTrackIds(favoriteFolders, visibleTracks),
                        singlePlaybackHistory = loadSinglePlaybackHistory(),
                        albumPlaybackHistory = loadAlbumPlaybackHistory(),
                    )
                    updateMediaSession()
                }
                .onFailure { _state.value = _state.value.copy(loading = false, status = "读取音乐失败，请重试。") }
        }
    }

    fun share(track: NativeTrack) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = track.mimeType
            putExtra(Intent.EXTRA_STREAM, track.uri)
            clipData = ClipData.newUri(resolver, track.title, track.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(Intent.createChooser(intent, "分享音乐").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copy(track: NativeTrack) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(resolver, track.title, track.uri))
        clipboardFolder = null
        clipboardFolderShouldMove = false
        clipboardTrack = track
        clipboardShouldMove = false
        _state.value = _state.value.copy(
            hasClipboardTrack = true,
            hasClipboardFolder = false,
            status = "已复制 ${track.title}",
        )
    }

    fun cut(track: NativeTrack) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(resolver, track.title, track.uri))
        clipboardFolder = null
        clipboardFolderShouldMove = false
        clipboardTrack = track
        clipboardShouldMove = true
        _state.value = _state.value.copy(
            hasClipboardTrack = true,
            hasClipboardFolder = false,
            status = "已剪切 ${track.title}",
        )
    }

    fun copyFolder(folderPath: String) {
        clipboardTrack = null
        clipboardShouldMove = false
        clipboardFolder = folderPath
        clipboardFolderShouldMove = false
        _state.value = _state.value.copy(
            hasClipboardTrack = false,
            hasClipboardFolder = true,
            status = "已复制文件夹 $folderPath",
        )
    }

    fun cutFolder(folderPath: String) {
        clipboardTrack = null
        clipboardShouldMove = false
        clipboardFolder = folderPath
        clipboardFolderShouldMove = true
        _state.value = _state.value.copy(
            hasClipboardTrack = false,
            hasClipboardFolder = true,
            status = "已剪切文件夹 $folderPath",
        )
    }

    fun paste(folderPath: String) {
        val folderSource = clipboardFolder
        val trackSource = clipboardTrack
        if (folderSource == null && trackSource == null) return
        val moveFolder = clipboardFolderShouldMove
        val moveTrack = clipboardShouldMove
        val sources = if (folderSource != null) {
            _state.value.tracks.filter { it.folderPath == folderSource && !it.isCueTrack }
        } else {
            trackSource?.let { listOf(it) } ?: emptyList()
        }
        if (sources.isEmpty()) {
            _state.value = _state.value.copy(status = "没有可粘贴的歌曲。")
            return
        }
        val targetFolder = folderPath.takeIf { it.isNotBlank() }?.trimEnd('/')?.plus("/")
        viewModelScope.launch {
            runCatching {
                sources.forEach { source ->
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, source.title)
                        put(MediaStore.MediaColumns.MIME_TYPE, source.mimeType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, targetFolder)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val destination = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("无法创建目标文件")
                    try {
                        resolver.openInputStream(source.uri).use { input ->
                            requireNotNull(input) { "无法读取源文件" }
                            resolver.openOutputStream(destination).use { output ->
                                requireNotNull(output) { "无法写入目标文件" }
                                input.copyTo(output)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                        }
                    } catch (error: Throwable) {
                        resolver.delete(destination, null, null)
                        throw error
                    }
                    if (moveFolder || moveTrack) resolver.delete(source.uri, null, null)
                }
            }.onSuccess {
                if (moveFolder) {
                    clipboardFolder = null
                    clipboardFolderShouldMove = false
                }
                if (moveTrack) {
                    clipboardTrack = null
                    clipboardShouldMove = false
                }
                refresh(true)
                _state.value = _state.value.copy(
                    hasClipboardTrack = clipboardTrack != null,
                    hasClipboardFolder = clipboardFolder != null,
                    status = if (moveFolder || moveTrack) "已移动到当前文件夹" else "已复制到当前文件夹",
                )
            }.onFailure { _state.value = _state.value.copy(status = "粘贴失败，请重试。") }
        }
    }

    private val archiveExtensions = setOf("zip", "rar", "tar", "gz")
    private val archiveMimes = setOf(
        "application/zip", "application/x-zip", "application/x-zip-compressed",
        "application/x-rar-compressed", "application/vnd.rar", "application/x-rar",
        "application/x-tar", "application/gzip", "application/x-gzip",
        "application/x-compressed-tar", "application/x-compressed", "application/x-7z-compressed",
    )

    fun importIncoming(uris: List<Uri>) {
        val (archives, rest) = uris.partition(::isArchiveUri)
        val (videos, audio) = rest.partition(::isVideoUri)
        if (videos.isNotEmpty()) {
            playVideos(videos.map { buildExternalVideoTrack(it, uriDisplayName(it)) }, 0)
        }
        if (audio.isNotEmpty()) importFiles(audio)
        archives.forEach(::importArchive)
    }

    private fun uriDisplayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "文件"

    private fun isArchiveUri(uri: Uri): Boolean {
        val lower = uriDisplayName(uri).lowercase()
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return true
        if (lower.substringAfterLast('.', "") in archiveExtensions) return true
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        return mime in archiveMimes
    }

    private fun isVideoUri(uri: Uri): Boolean {
        if (isExternalVideoFileName(uriDisplayName(uri))) return true
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        return mime.startsWith("video/")
    }

    private fun buildExternalVideoTrack(uri: Uri, name: String): NativeTrack {
        val extension = name.substringAfterLast('.', "").lowercase()
        return NativeTrack(
            id = externalTrackId(uri),
            uri = uri,
            title = name,
            artist = "",
            album = "",
            durationMs = 0,
            format = extension.uppercase(),
            mimeType = runCatching { resolver.getType(uri) }.getOrNull() ?: "video/*",
            folderPath = "",
            isVideo = true,
        )
    }

    private val supportedAudioExtensions =
        LOCAL_AUDIO_EXTENSIONS

    // 导入文件：不复制文件，在原地址播放并加入“导入文件”播放列表。
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_state.value.importing) {
            _state.value = _state.value.copy(status = "正在处理上一次导入，请稍候再试。")
            return
        }
        viewModelScope.launch {
            val tracks = mutableListOf<NativeTrack>()
            val skipped = mutableListOf<String>()
            uris.forEach { uri ->
                takePersistableReadPermission(uri)
                val name = uriDisplayName(uri)
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension in supportedAudioExtensions) {
                    tracks.add(buildExternalTrack(uri, name))
                } else {
                    skipped.add(name)
                }
            }
            if (tracks.isEmpty()) {
                _state.value = _state.value.copy(
                    status = "未找到支持的音频文件，支持 MP3/OGG/FLAC/WAV/AIFF/M4A/AAC/APE/OPUS/WavPack/DSD/WebM 等常见格式。",
                )
                return@launch
            }
            val visibleTracks = applyMetadataOverrides(tracks)
            val playlistName = "导入文件"
            val playlists = addTracksToPlaylist(playlistName, visibleTracks.map { it.uri.toString() })
            savePlaylists(playlists)
            _state.value = _state.value.copy(playlists = playlists)
            playFromQueue(visibleTracks, 0)
            val skipNote = if (skipped.isNotEmpty()) "，跳过不支持的文件：${skipped.joinToString()}" else ""
            _state.value = _state.value.copy(
                status = "已导入 ${visibleTracks.size} 首到播放列表“$playlistName”并开始播放。$skipNote",
            )
        }
    }

    // 导入文件夹：不复制文件，把文件夹内的音频创建为播放列表并播放。
    fun importFolder(treeUri: Uri) {
        if (_state.value.importing) {
            _state.value = _state.value.copy(status = "正在处理上一次导入，请稍候再试。")
            return
        }
        viewModelScope.launch {
            if (!takePersistableReadPermission(treeUri)) {
                _state.value = _state.value.copy(status = "无法取得文件夹的长期读取权限。")
                return@launch
            }
            _state.value = _state.value.copy(importing = true, importingArchive = false, importProgress = null, status = null)
            val (folderImport, cueTracks) = runCatching {
                withContext(Dispatchers.IO) {
                    val collected = collectAudioFromTree(treeUri)
                    collected to expandCueTracks(collected.audioTracks, collected.cueSheets)
                }
            }.getOrElse {
                _state.value = _state.value.copy(
                    importing = false,
                    importingArchive = false,
                    importProgress = null,
                    status = "读取文件夹失败，请检查读取权限后重试。",
                )
                return@launch
            }
            val tracks = applyMetadataOverrides(cueTracks)
            _state.value = _state.value.copy(importing = false, importingArchive = false)
            if (tracks.isEmpty()) {
                _state.value = _state.value.copy(status = "所选文件夹中没有找到音频文件。")
                return@launch
            }
            val folderName = treeDisplayName(treeUri) ?: "导入文件夹"
            val albumId = importedAlbumId(treeUri.toString())
            val existing = _state.value.importedAlbums.firstOrNull { it.id == albumId }
            val album = ImportedAlbum(
                id = albumId,
                name = existing?.name ?: folderName,
                artist = existing?.artist.orEmpty(),
                treeUri = treeUri.toString(),
                tracks = tracks.map(NativeTrack::toImportedAlbumTrack),
                frontCoverUri = existing?.frontCoverUri ?: folderImport.frontCoverUri,
                backCoverUri = existing?.backCoverUri ?: folderImport.backCoverUri,
                description = if (existing?.descriptionEdited == true) existing.description else folderImport.description,
                descriptionEdited = existing?.descriptionEdited == true,
            )
            val importedAlbums = _state.value.importedAlbums.filterNot { it.id == albumId } + album
            val playlists = _state.value.playlists + SavedPlaylist(
                id = System.currentTimeMillis().toString(),
                name = folderName,
                trackIds = emptyList(),
                externalTracks = tracks.map(NativeTrack::toImportedAlbumTrack),
            )
            savePlaylists(playlists)
            saveImportedAlbums(importedAlbums)
            _state.value = _state.value.copy(playlists = playlists, importedAlbums = importedAlbums)
            playFromQueue(tracks, 0)
            _state.value = _state.value.copy(
                status = "已从“$folderName”创建播放列表、建立专辑并开始播放（${tracks.size} 首）。",
            )
        }
    }

    private fun addTracksToPlaylist(name: String, uris: List<String>): List<SavedPlaylist> {
        val current = _state.value.playlists
        val existing = current.firstOrNull { it.name == name }
        return if (existing != null) {
            current.map { playlist ->
                if (playlist.id == existing.id) {
                    playlist.copy(externalUris = (playlist.externalUris + uris).distinct())
                } else playlist
            }
        } else {
            current + SavedPlaylist(
                id = System.currentTimeMillis().toString(),
                name = name,
                trackIds = emptyList(),
                externalUris = uris,
            )
        }
    }

    private fun buildExternalTrack(uri: Uri, name: String): NativeTrack {
        val extension = name.substringAfterLast('.', "").lowercase()
        val metadata = runCatching {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(getApplication<Application>(), uri)
                    externalTrackMetadata(
                        displayName = name,
                        embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        embeddedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        embeddedAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        embeddedDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                    )
                } finally {
                    retriever.release()
                }
            }
        }.getOrElse { externalTrackMetadata(name, null, null, null, null) }
        return NativeTrack(
            id = externalTrackId(uri),
            uri = uri,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = metadata.durationMs,
            format = extension.uppercase(),
            mimeType = runCatching { resolver.getType(uri) }.getOrNull() ?: "audio/*",
            folderPath = "",
            sizeBytes = 0,
        )
    }

    private fun externalTrackFromUriString(uriString: String): NativeTrack? = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") null else buildExternalTrack(uri, uriDisplayName(uri))
    }.getOrNull()

    private fun externalTrackId(uri: Uri): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (digest[i].toLong() and 0xFF)
        // 强制为负数，与 MediaStore 的正数 _ID 区分；CUE 分轨 id 也为正数。
        return value or Long.MIN_VALUE
    }

    private fun takePersistableReadPermission(uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess

    private fun collectAudioFromTree(treeUri: Uri): FolderImport {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val audioTracks = mutableListOf<NativeTrack>()
        val cueSheets = mutableListOf<CueSheet>()
        var frontCoverUri: String? = null
        var backCoverUri: String? = null
        var description: String? = null
        val pending = ArrayDeque<String>()
        pending.add(treeId)
        var visitedNodes = 1
        while (pending.isNotEmpty()) {
            val documentId = pending.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val folderPath = "$treeId/$documentId"
                while (cursor.moveToNext()) {
                    if (++visitedNodes > 50_000) {
                        throw IllegalStateException("文件夹项目超过 50000 个。")
                    }
                    val childId = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn).orEmpty()
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(childId)
                        continue
                    }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val extension = name.substringAfterLast('.', "").lowercase()
                    when {
                        isCueSheetName(name) -> {
                            val contents = resolver.openInputStream(documentUri)?.use { decodeText(it.readBytes()) }
                                ?: error("无法读取 CUE 文件：$name")
                            cueSheets.add(CueSheet(folderPath, name, contents))
                        }
                        extension in supportedAudioExtensions || mime.startsWith("audio/") ->
                            audioTracks.add(buildExternalTrack(documentUri, name).copy(folderPath = folderPath))
                        description == null && name.equals("简介.txt", ignoreCase = true) -> {
                            val contents = resolver.openInputStream(documentUri)?.use { input ->
                                decodeText(input.readBytes()).removePrefix("\uFEFF").trim()
                            } ?: error("无法读取简介文件：$name")
                            description = contents.takeIf(String::isNotBlank)
                        }
                        artworkIsFront(name) == true && frontCoverUri == null -> frontCoverUri = documentUri.toString()
                        artworkIsFront(name) == false && backCoverUri == null -> backCoverUri = documentUri.toString()
                    }
                }
            }
        }
        return FolderImport(audioTracks, cueSheets, frontCoverUri, backCoverUri, description)
    }

    private fun artworkIsFront(name: String): Boolean? {
        if (name.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg", "png", "webp")) return null
        return when (name.substringBeforeLast('.').lowercase()) {
            "cover", "front", "folder", "albumart" -> true
            "back", "backcover", "封底" -> false
            else -> null
        }
    }

    fun albumTracks(album: ImportedAlbum): List<NativeTrack> =
        applyMetadataOverrides(album.tracks.map(ImportedAlbumTrack::toNativeTrack))

    fun updateAlbum(album: ImportedAlbum) {
        val current = _state.value
        if (current.importedAlbums.none { it.id == album.id }) {
            _state.value = current.copy(status = "未找到要更新的专辑。")
            return
        }
        val updated = album.copy(descriptionEdited = true)
        val albums = current.importedAlbums.map { if (it.id == updated.id) updated else it }
        saveImportedAlbums(albums)
        _state.value = current.copy(importedAlbums = albums, status = "已保存专辑信息。")
    }

    internal fun removeLibraryAlbum(album: LibraryAlbum<NativeTrack>) {
        val current = _state.value
        val result = removeLibraryAlbumRecord(current.importedAlbums, current.hiddenAlbumKeys, album)
        saveImportedAlbums(result.importedAlbums)
        saveHiddenAlbumKeys(result.hiddenKeys)
        _state.value = current.copy(
            importedAlbums = result.importedAlbums,
            hiddenAlbumKeys = result.hiddenKeys,
            status = "已从专辑列表移除“${album.name}”，源文件和播放列表未改变。",
        )
    }

    fun updateAlbumArtwork(albumId: String, front: Boolean, uri: Uri) {
        if (!takePersistableReadPermission(uri)) {
            _state.value = _state.value.copy(status = "无法取得图片的长期读取权限。")
            return
        }
        val current = _state.value
        val album = current.importedAlbums.firstOrNull { it.id == albumId }
        if (album == null) {
            _state.value = current.copy(status = "未找到要更新的专辑。")
            return
        }
        val updated = if (front) album.copy(frontCoverUri = uri.toString()) else album.copy(backCoverUri = uri.toString())
        val albums = current.importedAlbums.map { if (it.id == albumId) updated else it }
        saveImportedAlbums(albums)
        _state.value = current.copy(
            importedAlbums = albums,
            status = if (front) "已保存封面。" else "已保存封底。",
        )
    }

    fun updateTrackMetadata(track: NativeTrack, title: String, artist: String, album: String) {
        val current = _state.value
        val key = track.metadataKey()
        val overrides = current.trackMetadataOverrides + (key to TrackMetadataOverride(title, artist, album))
        fun update(candidate: NativeTrack): NativeTrack =
            if (candidate.metadataKey() == key) candidate.withMetadataOverride(overrides[key]) else candidate

        saveTrackMetadataOverrides(overrides)
        _state.value = current.copy(
            tracks = current.tracks.map(::update),
            queue = current.queue.map(::update),
            currentTrack = current.currentTrack?.let(::update),
            trackMetadataOverrides = overrides,
            status = "已保存歌曲信息。",
        )
        updateMediaSession()
        val path = resolveAudioFilePath(track)
        if (path != null) {
            viewModelScope.launch {
                val wrote = withContext(Dispatchers.IO) { writeTagsToFile(path, title, artist, album) }
                if (wrote) {
                    android.util.Log.d("MetaWrite", "tags written: $path | $title / $artist / $album")
                    scanAndRefresh(path)
                } else {
                    android.util.Log.w("MetaWrite", "write failed: $path")
                    _state.value = _state.value.copy(status = "已保存歌曲信息，但写入源文件标签失败。")
                }
            }
        }
    }

    /** 写标签成功后让系统重扫该文件：MediaStore 从文件重新读取新标签，系统文件管理器与其它应用即可看到改动。 */
    private fun scanAndRefresh(path: String) {
        MediaScannerConnection.scanFile(getApplication(), arrayOf(path), null) { _, _ ->
            refresh(true)
            _state.value = _state.value.copy(status = "已保存歌曲信息。")
        }
    }

    /** 解析可写真实标签的物理路径：非 CUE 的 MediaStore 音频（DATA 列）或 file:// 文件；否则返回 null（回退应用内覆盖）。 */
    private fun resolveAudioFilePath(track: NativeTrack): String? {
        if (track.isCueTrack) return null
        val uri = track.uri
        val resolved = when {
            uri.scheme == "file" -> uri.path
            uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY ->
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            else -> null
        }
        android.util.Log.d("MetaWrite", "resolve path for $uri -> ${resolved ?: "null"}")
        return resolved
    }

    /** 用 jaudiotagger 把标题/艺术家/专辑写入源文件真实标签；返回是否成功。 */
    private fun writeTagsToFile(path: String, title: String, artist: String, album: String): Boolean = runCatching {
        TagOptionSingleton.getInstance().setAndroid(true)
        val file = File(path)
        if (!file.exists() || !file.canWrite()) return false
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, title)
        tag.setField(FieldKey.ARTIST, artist)
        tag.setField(FieldKey.ALBUM, album)
        AudioFileIO.write(audioFile)
        true
    }.getOrDefault(false)

    private fun applyMetadataOverrides(tracks: List<NativeTrack>): List<NativeTrack> {
        val overrides = _state.value.trackMetadataOverrides
        return tracks.map { track -> track.withMetadataOverride(overrides[track.metadataKey()]) }
    }

    private fun treeDisplayName(treeUri: Uri): String? = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    fun importArchive(uri: Uri) {
        if (_state.value.importing) return
        _state.value = _state.value.copy(importing = true, importingArchive = true, importProgress = null, status = null)
        viewModelScope.launch {
            val outcome = ArchiveImporter.import(getApplication(), uri, null) { done, total ->
                _state.value = _state.value.copy(importProgress = done to total)
            }
            when (outcome) {
                is ImportOutcome.Success -> {
                    refresh(true)
                    _state.value = _state.value.copy(
                        importing = false,
                        importingArchive = false,
                        importProgress = null,
                        importResult = outcome.result,
                        status = "导入完成：成功 ${outcome.result.imported} 首。",
                    )
                }
                is ImportOutcome.NeedsPassword -> {
                    _state.value = _state.value.copy(
                        importing = false,
                        importingArchive = false,
                        importProgress = null,
                        passwordRequest = outcome.request,
                    )
                }
                is ImportOutcome.Failure -> {
                    AppErrorRecorder.record("导入", RuntimeException(outcome.message))
                    _state.value = _state.value.copy(
                        importing = false,
                        importingArchive = false,
                        importProgress = null,
                        status = outcome.message,
                    )
                }
            }
        }
    }

    fun submitPassword(password: String) {
        val request = _state.value.passwordRequest ?: return
        _state.value = _state.value.copy(passwordRequest = null, importing = true, importingArchive = true, importProgress = null)
        viewModelScope.launch {
            val outcome = ArchiveImporter.import(getApplication(), request.uri, password) { done, total ->
                _state.value = _state.value.copy(importProgress = done to total)
            }
            when (outcome) {
                is ImportOutcome.Success -> {
                    refresh(true)
                    _state.value = _state.value.copy(
                        importing = false,
                        importingArchive = false,
                        importProgress = null,
                        importResult = outcome.result,
                        status = "导入完成：成功 ${outcome.result.imported} 首。",
                    )
                }
                is ImportOutcome.NeedsPassword -> {
                    _state.value = _state.value.copy(
                        importing = false,
                        importingArchive = false,
                        importProgress = null,
                        passwordRequest = outcome.request,
                    )
                }
                is ImportOutcome.Failure -> {
                    AppErrorRecorder.record("导入", RuntimeException(outcome.message))
                    _state.value = _state.value.copy(
                        importing = false,
                        importingArchive = false,
                        importProgress = null,
                        status = outcome.message,
                    )
                }
            }
        }
    }

    fun cancelImportPassword() {
        _state.value = _state.value.copy(passwordRequest = null, status = "已取消导入。")
    }

    fun dismissImportResult() {
        _state.value = _state.value.copy(importResult = null)
    }

    fun requestDelete(track: NativeTrack) {
        if (track.isCueTrack) {
            _state.value = _state.value.copy(status = "CUE 分轨不能单独删除，请删除整轨音频文件。")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = listOf(track)
            _state.value = _state.value.copy(
                deleteRequest = MediaStore.createDeleteRequest(resolver, listOf(track.uri)).intentSender,
                status = "请在系统窗口中确认删除。",
            )
            return
        }
        deleteImmediately(track)
    }

    /** 删除整个文件夹：API R+ 用系统批量删除确认，旧版本逐曲目立即删除。 */
    fun deleteFolder(folderPath: String) {
        val prefix = "$folderPath/"
        val targets = _state.value.tracks.filter { (it.folderPath == folderPath || it.folderPath.startsWith(prefix)) && !it.isCueTrack } +
            _state.value.videoTracks.filter { it.folderPath == folderPath || it.folderPath.startsWith(prefix) }
        if (targets.isEmpty()) {
            _state.value = _state.value.copy(status = "该文件夹没有可删除的歌曲或视频。")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = targets
            _state.value = _state.value.copy(
                deleteRequest = MediaStore.createDeleteRequest(resolver, targets.map { it.uri }).intentSender,
                status = "请在系统窗口中确认删除 ${targets.size} 个文件。",
            )
            return
        }
        targets.forEach(::deleteImmediately)
    }

    fun completeDelete(granted: Boolean) {
        val targets = pendingDelete
        pendingDelete = null
        _state.value = _state.value.copy(deleteRequest = null)
        if (targets == null) return
        if (granted) {
            val audioIds = targets.filter { !it.isVideo }.map { it.id }.toSet()
            val videoIds = targets.filter { it.isVideo }.map { it.id }.toSet()
            _state.value = _state.value.copy(
                tracks = _state.value.tracks.filterNot { it.id in audioIds },
                videoTracks = _state.value.videoTracks.filterNot { it.id in videoIds },
                status = if (targets.size == 1) "已删除 ${targets.first().title}" else "已删除 ${targets.size} 个文件",
            )
        } else _state.value = _state.value.copy(status = "已取消删除。")
    }

    private fun deleteImmediately(track: NativeTrack) {
        viewModelScope.launch {
            runCatching { resolver.delete(track.uri, null, null) }
                .onSuccess { deleted ->
                    _state.value = if (deleted > 0) {
                        if (track.isVideo) {
                            _state.value.copy(
                                videoTracks = _state.value.videoTracks.filterNot { it.id == track.id },
                                status = "已删除 ${track.title}",
                            )
                        } else {
                            _state.value.copy(
                                tracks = _state.value.tracks.filterNot { it.id == track.id },
                                status = "已删除 ${track.title}",
                            )
                        }
                    } else _state.value.copy(status = "删除失败，请重试。")
                }
                .onFailure { _state.value = _state.value.copy(status = "删除失败，请重试。") }
        }
    }


    // ---------- MusicFree 插件音源：源管理 ----------

    /** 待确认导入音源的插件 JS 文本：confirmSourceImport 时落盘。 */
    private val pendingSourceJs = mutableMapOf<String, String>()

    /** 从 manager 同步启用中的音源到 UI 状态；选中站点被删/停用后回落首个音源。 */
    fun refreshMusicFreeSources() {
        val installed = runCatching { pluginManager.sources() }.getOrDefault(emptyList())
        val sources = installed.filter { it.enabled }
        val activeSourceId = _state.value.onlineActiveSourceId
            ?.takeIf { id -> sources.any { it.id == id } }
            ?: sources.firstOrNull()?.id
        _state.value = _state.value.copy(
            musicFreeSources = sources,
            installedMusicFreeSources = installed,
            onlineActiveSourceId = activeSourceId,
            sourceQualityOptions = _state.value.sourceQualityOptions.filterKeys { id -> sources.any { it.id == id } },
        )
        viewModelScope.launch {
            val qualityOptions = withContext(Dispatchers.IO) {
                sources.associate { source ->
                    source.id to runCatching { pluginManager.getPlugin(source).qualityOptions() }
                        .getOrDefault(emptyList())
                }
            }
            _state.value = _state.value.copy(sourceQualityOptions = qualityOptions)
        }
    }

    private fun enabledSources(): List<MusicFreeSource> =
        runCatching { pluginManager.sources() }.getOrDefault(emptyList()).filter { it.enabled }

    private fun pluginFor(sourceId: String): MusicFreePlugin? =
        enabledSources().firstOrNull { it.id == sourceId }?.let { source ->
            runCatching { pluginManager.getPlugin(source) }.getOrNull()
        }

    /** 插件 JS 顶部推断平台名（platform: "xxx"）；解析不出返回 null。 */
    private fun pluginPlatformName(js: String): String? =
        Regex("platform\\s*[:=]\\s*[\"']([^\"']+)[\"']").find(js)?.groupValues?.get(1)?.takeIf(String::isNotBlank)

    /** 导入本地插件文件（或订阅 JSON 文件）：读文本 → 校验 → 弹确认框。 */
    fun importSourceFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = mutableListOf<MusicFreeSource>()
            val failures = mutableListOf<String>()
            for (uri in uris) {
                val displayName = uriDisplayName(uri)
                val js = runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                if (js.isNullOrBlank()) {
                    failures.add(displayName)
                    continue
                }
                // 订阅 JSON（{plugins:[...]}）展开成多个待导入源
                val subscription = runCatching { sourceStore.parseSubscription(js) }.getOrNull().orEmpty()
                if (subscription.isNotEmpty()) {
                    imported += subscription
                    continue
                }
                val name = displayName.substringBeforeLast('.').ifBlank { "插件音源" }
                val source = MusicFreeSource(
                    id = sourceStore.sourceIdFromUrl("file:$name:${js.length}:${js.hashCode()}"),
                    name = name,
                )
                val valid = runCatching { pluginManager.validate(source, js) }.isSuccess
                if (!valid) {
                    failures.add(name)
                    continue
                }
                pendingSourceJs[source.id] = js
                imported += source
            }
            beginPendingImports(imported, failures)
        }
    }

    /** 链接导入：插件直连地址或订阅 JSON 链接。 */
    fun importSourceUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sourceManagerStatus = "正在下载音源…")
            val text = runCatching { withContext(Dispatchers.IO) { pluginManager.fetchText(trimmed) } }
                .onFailure {
                    _state.value = _state.value.copy(sourceManagerStatus = "下载失败：${it.message ?: "请检查链接"}")
                }
                .getOrNull()
            if (text.isNullOrBlank()) return@launch
            val subscription = runCatching { sourceStore.parseSubscription(text) }.getOrNull().orEmpty()
            if (subscription.isNotEmpty()) {
                beginPendingImports(subscription, emptyList())
                return@launch
            }
            val name = pluginPlatformName(text)
                ?: trimmed.substringAfterLast('/').substringBeforeLast('.').ifBlank { "插件音源" }
            val source = MusicFreeSource(id = sourceStore.sourceIdFromUrl(trimmed), name = name, url = trimmed)
            val valid = runCatching { pluginManager.validate(source, text) }
            if (valid.isFailure) {
                _state.value = _state.value.copy(sourceManagerStatus = "插件校验失败：${valid.exceptionOrNull()?.message ?: "无法加载"}")
                return@launch
            }
            pendingSourceJs[source.id] = text
            beginPendingImports(listOf(source), emptyList())
        }
    }

    /** 汇集待确认导入清单：已存在的源跳过；校验失败的计入失败提示。 */
    private fun beginPendingImports(sources: List<MusicFreeSource>, failures: List<String>) {
        val existing = runCatching { pluginManager.sources() }.getOrDefault(emptyList()).mapTo(mutableSetOf()) { it.id }
        val fresh = sources.filter { it.id !in existing }
        val status = buildString {
            if (fresh.isEmpty() && failures.isNotEmpty()) append("导入失败：${failures.joinToString("、")}")
            if (fresh.isEmpty() && failures.isEmpty()) append("这些音源已存在，无需重复导入")
        }.ifBlank { null }
        _state.value = _state.value.copy(
            pendingSourceImports = fresh,
            sourceManagerStatus = status,
        )
    }

    /** 确认导入选中的音源：逐个校验并落盘插件文件，成功后刷新源列表。 */
    fun confirmSourceImport(ids: Set<String>) {
        val pending = _state.value.pendingSourceImports.filter { it.id in ids }
        _state.value = _state.value.copy(pendingSourceImports = emptyList())
        if (pending.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            val failures = mutableListOf<String>()
            val saved = runCatching { pluginManager.sources() }.getOrDefault(emptyList()).toMutableList()
            for (source in pending) {
                val js = pendingSourceJs[source.id]
                    ?: runCatching { withContext(Dispatchers.IO) { pluginManager.fetchText(source.url) } }.getOrNull()
                if (js.isNullOrBlank()) {
                    failures.add(source.name)
                    continue
                }
                val valid = runCatching { pluginManager.validate(source, js) }.isSuccess
                if (!valid) {
                    failures.add(source.name)
                    continue
                }
                runCatching { sourceStore.writePluginFile(source, js) }
                saved.removeAll { it.id == source.id }
                saved.add(source)
                added++
            }
            pendingSourceJs.clear()
            if (added > 0) {
                withContext(Dispatchers.IO) { pluginManager.saveSources(saved) }
                refreshMusicFreeSources()
            }
            _state.value = _state.value.copy(sourceManagerStatus = buildString {
                append("已导入 $added 个音源")
                if (failures.isNotEmpty()) append("，失败：${failures.joinToString("、")}")
            })
        }
    }

    fun cancelSourceImport() {
        pendingSourceJs.clear()
        _state.value = _state.value.copy(pendingSourceImports = emptyList())
    }

    fun toggleSource(id: String, enabled: Boolean) {
        val updated = setMusicFreeSourceEnabled(
            runCatching { pluginManager.sources() }.getOrDefault(emptyList()),
            id,
            enabled,
        )
        pluginManager.saveSources(updated)
        refreshMusicFreeSources()
    }

    fun removeSource(id: String) = removeSources(setOf(id))

    fun removeSources(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { id -> runCatching { pluginManager.removePlugin(id) } }
                val remaining = pluginManager.sources().filterNot { it.id in ids }
                pluginManager.saveSources(remaining)
            }
            onlineMediaCache.clear()
            refreshMusicFreeSources()
            _state.value = _state.value.copy(
                sourceManagerStatus = "已删除 ${ids.size} 个音源",
                onlineCollectionDetail = null,
                onlineCollectionTracks = emptyList(),
                onlineCollectionLoading = false,
                onlineCollectionEnd = true,
                onlineTopListGroups = emptyList(),
            )
        }
    }

    fun setSourceSelectionMode(enabled: Boolean) {
        _state.value = _state.value.copy(
            sourceSelectionMode = enabled,
            sourceSelection = if (enabled) _state.value.sourceSelection else emptySet(),
        )
    }

    fun toggleSourceSelection(id: String) {
        val selection = _state.value.sourceSelection
        _state.value = _state.value.copy(
            sourceSelection = if (id in selection) selection - id else selection + id,
        )
    }

    fun toggleSourceSelectAll() {
        val sources = _state.value.installedMusicFreeSources
        val selection = _state.value.sourceSelection
        _state.value = _state.value.copy(
            sourceSelection = if (selection.size == sources.size) emptySet() else sources.mapTo(mutableSetOf()) { it.id },
        )
    }

    fun clearSourceManagerStatus() {
        if (_state.value.sourceManagerStatus != null) {
            _state.value = _state.value.copy(sourceManagerStatus = null)
        }
    }

    /** 测活：搜索一个随机词 + 取第一条解析播放地址，两条链路结论写入 sourceTestStatuses。 */
    fun testSource(id: String) {
        val source = enabledSources().firstOrNull { it.id == id } ?: return
        if (id in _state.value.testingSourceIds) return
        _state.value = _state.value.copy(
            testingSourceIds = _state.value.testingSourceIds + id,
            sourceTestStatuses = _state.value.sourceTestStatuses +
                (id to StreamSourceTestStatus(StreamSourceSearchResult.Loading, null)),
        )
        viewModelScope.launch {
            val query = listOf("海阔天空", "晴天", "love", "test", "周杰伦").random()
            val (result, tracks) = MusicFreeSearch.searchSource(pluginManager, query, source)
            val resourceAvailable = if (result is StreamSourceSearchResult.Success && tracks.isNotEmpty()) {
                runCatching {
                    val plugin = pluginManager.getPlugin(source)
                    val item = plugin.enrichTrack(tracks.first())
                    plugin.getMediaSource(item, StreamQuality.Standard) != null
                }.getOrNull()
            } else {
                null
            }
            if (id in _state.value.testingSourceIds) {
                _state.value = _state.value.copy(
                    testingSourceIds = _state.value.testingSourceIds - id,
                    sourceTestStatuses = _state.value.sourceTestStatuses +
                        (id to StreamSourceTestStatus(result, resourceAvailable)),
                )
            }
        }
    }

    // ---------- MusicFree 插件音源：在线搜索 ----------

    /** 本次搜索需要等待落定的源数量（会话结果达到该数即收敛）。 */
    private var activeSearchSourceCount = 0

    fun searchOnline(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val sources = enabledSources()
        if (sources.isEmpty()) {
            _state.value = _state.value.copy(status = "尚未导入音源，请先到「我的 → 音源管理」导入")
            return
        }
        sourceSearchJob?.cancel()
        val activeId = _state.value.onlineActiveSourceId ?: sources.first().id
        val selectedSource = sources.firstOrNull { it.id == activeId }
        if (selectedSource == null) {
            _state.value = _state.value.copy(onlineSearching = false, status = "请选择可用音源")
            return
        }
        val kind = _state.value.onlineSearchKind
        _state.value = _state.value.copy(
            onlineSearchQuery = trimmed,
            onlineSearching = true,
            onlineSearchTracks = emptyList(),
            onlineSearchCollections = emptyList(),
            onlineSearchArtists = emptyList(),
            onlineSearchSession = StreamSearchSession.new(trimmed, activeId),
            status = null,
        )
        sourceSearchJob = viewModelScope.launch {
            val applicable = listOf(selectedSource).filter { source ->
                val plugin = runCatching { pluginManager.getPlugin(source) }.getOrNull()
                when (kind) {
                    OnlineSearchKind.Music -> plugin?.supportsMusicSearch == true
                    OnlineSearchKind.Album -> plugin?.supportsAlbumSearch == true
                    OnlineSearchKind.Sheet -> plugin?.supportsSheetSearch == true
                    OnlineSearchKind.Artist -> plugin?.supportsArtistSearch == true
                }
            }
            if (applicable.isEmpty()) {
                _state.value = _state.value.copy(onlineSearching = false, status = "当前音源不支持${kind.label}搜索")
                return@launch
            }
            activeSearchSourceCount = applicable.size
            coroutineScope {
                applicable.forEach { source ->
                    launch {
                        when (kind) {
                            OnlineSearchKind.Music -> {
                                val (result, tracks) = MusicFreeSearch.searchSource(pluginManager, trimmed, source)
                                if (_state.value.onlineSearchQuery != trimmed) return@launch
                                settleSearchSource(source.id, result)
                                if (result is StreamSourceSearchResult.Success) {
                                    _state.value = _state.value.copy(
                                        onlineSearchTracks = mergeStreamSearchTracks(_state.value.onlineSearchTracks, tracks),
                                    )
                                }
                            }
                            OnlineSearchKind.Album -> searchCollectionsFrom(source, trimmed) { plugin, q, page ->
                                plugin.searchAlbums(q, page)
                            }
                            OnlineSearchKind.Sheet -> searchCollectionsFrom(source, trimmed) { plugin, q, page ->
                                plugin.searchSheets(q, page)
                            }
                            OnlineSearchKind.Artist -> searchArtistsFrom(source, trimmed)
                        }
                    }
                }
            }
        }
    }

    private suspend fun searchCollectionsFrom(
        source: MusicFreeSource,
        query: String,
        call: suspend (MusicFreePlugin, String, Int) -> List<StreamCollection>,
    ) {
        val result = runCatching {
            withContext(Dispatchers.IO) { call(pluginManager.getPlugin(source), query, 1) }
        }
        if (_state.value.onlineSearchQuery != query) return
        result.fold(
            onSuccess = { collections ->
                collections.forEach { raw ->
                    onlineCollectionRaw[streamCollectionToOnlineCollection(source, raw).key] = raw
                }
                _state.value = _state.value.copy(
                    onlineSearchCollections = _state.value.onlineSearchCollections +
                        collections.map { streamCollectionToOnlineCollection(source, it) },
                )
                settleSearchSource(source.id, StreamSourceSearchResult.Success(collections.map(StreamCollection::key)))
            },
            onFailure = { error ->
                settleSearchSource(source.id, StreamSourceSearchResult.Failed(error.message ?: "搜索失败"))
            },
        )
    }

    private suspend fun searchArtistsFrom(source: MusicFreeSource, query: String) {
        val result = runCatching {
            withContext(Dispatchers.IO) { pluginManager.getPlugin(source).searchArtists(query, 1) }
        }
        if (_state.value.onlineSearchQuery != query) return
        result.fold(
            onSuccess = { artists ->
                artists.forEach { onlineArtistRaw[artistKey(it)] = it }
                _state.value = _state.value.copy(
                    onlineSearchArtists = _state.value.onlineSearchArtists + artists,
                )
                settleSearchSource(source.id, StreamSourceSearchResult.Success(artists.map(StreamArtist::key)))
            },
            onFailure = { error ->
                settleSearchSource(source.id, StreamSourceSearchResult.Failed(error.message ?: "搜索失败"))
            },
        )
    }

    /** 单源搜索落定：写会话并判断整体是否收敛；全部源落定且无结果时给一句状态提示。 */
    private fun settleSearchSource(sourceId: String, result: StreamSourceSearchResult) {
        val state = _state.value
        val query = state.onlineSearchQuery
        val session = (state.onlineSearchSession ?: StreamSearchSession.new(query, state.onlineActiveSourceId))
            .withSource(sourceId, result)
        val settled = session.sourceResults.size
        val finished = settled >= activeSearchSourceCount
        val nothing = state.onlineSearchTracks.isEmpty() &&
            state.onlineSearchCollections.isEmpty() &&
            state.onlineSearchArtists.isEmpty()
        _state.value = state.copy(
            onlineSearchSession = session,
            onlineSearching = state.onlineSearching && !finished,
            status = if (finished && nothing) "未找到相关${state.onlineSearchKind.label}" else state.status,
        )
    }

    /** 切换搜索类型（歌曲/专辑/歌单/歌手）：清空旧结果并按当前关键词重搜。 */
    fun setOnlineSearchKind(kind: OnlineSearchKind) {
        if (_state.value.onlineSearchKind == kind) return
        _state.value = _state.value.copy(
            onlineSearchKind = kind,
            onlineSearchTracks = emptyList(),
            onlineSearchCollections = emptyList(),
            onlineSearchArtists = emptyList(),
            onlineSearchSession = null,
            status = null,
        )
        val query = _state.value.onlineSearchQuery
        if (query.isNotBlank()) searchOnline(query)
    }

    /** 切换站点：立即按当前关键词重搜；在线页始终只查询当前站点。 */
    fun selectOnlineSource(sourceId: String?) {
        val selected = sourceId?.takeIf { id -> enabledSources().any { it.id == id } }
            ?: enabledSources().firstOrNull()?.id
        if (_state.value.onlineActiveSourceId == selected) return
        _state.value = _state.value.copy(onlineActiveSourceId = selected, onlineTopListGroups = emptyList())
        val query = _state.value.onlineSearchQuery
        if (query.isNotBlank()) searchOnline(query)
    }

    fun clearOnlineSearch() {
        sourceSearchJob?.cancel()
        _state.value = _state.value.copy(
            onlineSearchQuery = "",
            onlineSearching = false,
            onlineSearchTracks = emptyList(),
            onlineSearchCollections = emptyList(),
            onlineSearchArtists = emptyList(),
            onlineSearchSession = null,
            status = null,
        )
    }

    // ---------- MusicFree 插件音源：专辑/歌单/榜单钻取 ----------

    /** 合集钻取的下一页页码（openOnlineCollection 时重置为 1）。 */
    private var onlineCollectionNextPage = 1

    /** 收藏/历史恢复时插件原始条目缺失，用展示字段重建 IMusicItem/ISheetItem 同形 Map。 */
    private fun reconstructedCollectionRaw(collection: OnlineCollection): StreamCollection = StreamCollection(
        key = collection.collectionId,
        pluginId = collection.pluginId,
        name = collection.name,
        artist = collection.artist,
        artwork = collection.artworkUrl,
        description = collection.description,
        worksNum = collection.worksNum,
        kind = collection.kind,
        raw = buildMap<String, Any?> {
            put("id", collection.collectionId)
            put("title", collection.name)
            put("artist", collection.artist)
            collection.artworkUrl?.let { put("artwork", it) }
            collection.description?.let { put("description", it) }
            collection.worksNum?.let { put("worksNum", it) }
        },
    )

    private fun collectionDetailLabel(collection: OnlineCollection): String = when (collection.kind) {
        StreamCollectionKind.Album -> "专辑"
        StreamCollectionKind.Sheet -> "歌单"
        StreamCollectionKind.TopList -> "榜单"
    }

    /** 拉取合集的一页曲目（按合集类型分发到插件的对应函数）。 */
    private suspend fun fetchCollectionPage(collection: OnlineCollection, page: Int): SearchPage =
        withContext(Dispatchers.IO) {
            val plugin = pluginFor(collection.pluginId) ?: return@withContext SearchPage(true, emptyList())
            val raw = onlineCollectionRaw[collection.key] ?: reconstructedCollectionRaw(collection)
            when (collection.kind) {
                StreamCollectionKind.Album -> plugin.getAlbumInfo(raw, page)
                StreamCollectionKind.Sheet -> plugin.getMusicSheetInfo(raw, page)
                StreamCollectionKind.TopList -> plugin.getTopListDetail(raw, page)
            }
        }

    /** 打开专辑/歌单/榜单详情：先加载第一页，后续页由 loadMoreOnlineCollectionPage 追加。 */
    fun openOnlineCollection(collection: OnlineCollection) {
        val detail = _state.value.onlineCollectionDetail
        if (detail?.key == collection.key && _state.value.onlineCollectionTracks.isNotEmpty() &&
            !_state.value.onlineCollectionLoading
        ) return
        onlineCollectionNextPage = 1
        _state.value = _state.value.copy(
            onlineCollectionDetail = collection,
            onlineCollectionTracks = emptyList(),
            onlineCollectionLoading = true,
            onlineCollectionEnd = false,
            status = null,
        )
        viewModelScope.launch {
            val page = fetchCollectionPage(collection, 1)
            if (_state.value.onlineCollectionDetail?.key != collection.key) return@launch
            onlineCollectionNextPage = 2
            _state.value = _state.value.copy(
                onlineCollectionTracks = page.items,
                onlineCollectionLoading = false,
                onlineCollectionEnd = page.isEnd,
                status = if (page.items.isEmpty()) "该${collectionDetailLabel(collection)}暂无曲目" else null,
            )
        }
    }

    /** 合集详情翻页：滚动接近末尾时追加下一页；失败静默收尾（保留已加载内容）。 */
    fun loadMoreOnlineCollectionPage() {
        val collection = _state.value.onlineCollectionDetail ?: return
        if (_state.value.onlineCollectionLoading || _state.value.onlineCollectionEnd) return
        val page = onlineCollectionNextPage
        _state.value = _state.value.copy(onlineCollectionLoading = true)
        viewModelScope.launch {
            val result = runCatching { fetchCollectionPage(collection, page) }.getOrNull()
            if (_state.value.onlineCollectionDetail?.key != collection.key) return@launch
            if (result == null) {
                _state.value = _state.value.copy(onlineCollectionLoading = false, onlineCollectionEnd = true)
                return@launch
            }
            val existing = _state.value.onlineCollectionTracks
            val fresh = result.items.filter { item -> existing.none { it.pluginId == item.pluginId && it.key == item.key } }
            onlineCollectionNextPage = page + 1
            _state.value = _state.value.copy(
                onlineCollectionTracks = existing + fresh,
                onlineCollectionLoading = false,
                onlineCollectionEnd = result.isEnd || fresh.isEmpty(),
            )
        }
    }

    fun closeOnlineCollection() {
        _state.value = _state.value.copy(
            onlineCollectionDetail = null,
            onlineCollectionTracks = emptyList(),
            onlineCollectionLoading = false,
            onlineCollectionEnd = true,
        )
    }

    /** 加载当前站点的榜单分组，直接显示在在线页。 */
    fun loadOnlineTopLists(sourceId: String? = null) {
        val sources = enabledSources()
        if (sources.isEmpty()) {
            _state.value = _state.value.copy(status = "尚未导入音源，请先到「我的 → 音源管理」导入")
            return
        }
        val selectedId = sourceId ?: _state.value.onlineActiveSourceId ?: sources.first().id
        val source = sources.firstOrNull { it.id == selectedId } ?: sources.first()
        if (_state.value.onlineTopListLoading && _state.value.onlineActiveSourceId == source.id) return
        onlineTopListJob?.cancel()
        _state.value = _state.value.copy(onlineTopListLoading = true, status = null)
        onlineTopListJob = viewModelScope.launch {
            val groups = runCatching {
                withContext(Dispatchers.IO) { pluginManager.getPlugin(source).getTopLists() }
            }.getOrDefault(emptyList())
            groups.forEach { group ->
                group.sheets.forEach { raw ->
                    onlineCollectionRaw[streamCollectionToOnlineCollection(sourceFor(raw.pluginId), raw).key] = raw
                }
            }
            if (_state.value.onlineActiveSourceId != source.id) return@launch
            _state.value = _state.value.copy(
                onlineTopListGroups = groups,
                onlineTopListLoading = false,
                onlineActiveSourceId = source.id,
            )
        }
    }

    private fun sourceFor(sourceId: String): MusicFreeSource =
        enabledSources().firstOrNull { it.id == sourceId }
            ?: MusicFreeSource(id = sourceId, name = sourceId, enabled = false)

    /** 打开歌手作品列表：复用合集详情的曲目列表区（分页走 getArtistWorks）。 */
    fun openOnlineArtist(artist: StreamArtist) {
        onlineArtistRaw[artistKey(artist)] = onlineArtistRaw[artistKey(artist)] ?: artist
        onlineArtistNextPage = 1
        _state.value = _state.value.copy(
            onlineArtistDetail = artist,
            onlineCollectionDetail = null,
            onlineCollectionTracks = emptyList(),
            onlineCollectionLoading = true,
            onlineCollectionEnd = false,
            status = null,
        )
        viewModelScope.launch {
            val page = fetchArtistWorksPage(artist, 1)
            if (_state.value.onlineArtistDetail?.key != artist.key) return@launch
            onlineArtistNextPage = 2
            _state.value = _state.value.copy(
                onlineCollectionTracks = page.items,
                onlineCollectionLoading = false,
                onlineCollectionEnd = page.isEnd,
                status = if (page.items.isEmpty()) "该歌手暂无作品" else null,
            )
        }
    }

    fun closeOnlineArtist() {
        _state.value = _state.value.copy(
            onlineArtistDetail = null,
            onlineCollectionTracks = emptyList(),
            onlineCollectionLoading = false,
            onlineCollectionEnd = true,
        )
    }

    private var onlineArtistNextPage = 1

    private suspend fun fetchArtistWorksPage(artist: StreamArtist, page: Int): SearchPage = withContext(Dispatchers.IO) {
        val raw = onlineArtistRaw[artistKey(artist)] ?: artist
        pluginFor(artist.pluginId)?.getArtistWorks(raw, page) ?: SearchPage(true, emptyList())
    }

    private fun artistKey(artist: StreamArtist): String = "${artist.pluginId}:${artist.key}"

    /** 分类专辑翻页。 */

    /** 播放一批在线曲目（OnlineTrack 形态，用于收藏/历史等已持久化的条目）。 */
    fun playOnline(tracks: List<OnlineTrack>, startIndex: Int) {
        if (startIndex !in tracks.indices) return
        val queue = tracks.map(::onlineTrackToNativeTrack)
        onlineTrackById.clear()
        onlineRawItemById.clear()
        tracks.forEachIndexed { index, track ->
            onlineTrackById[queue[index].id] = track
        }
        _state.value = _state.value.copy(miniPlayerDismissed = false)
        playFromQueue(queue, startIndex)
    }

    /** 播放一批插件搜索/钻取结果（StreamTrack 形态，保留插件原始 item 提高解析保真度）。 */
    fun playStreamTracks(tracks: List<StreamTrack>, startIndex: Int) {
        if (startIndex !in tracks.indices) return
        val onlineTracks = tracks.map(OnlineTrack::fromStreamTrack)
        val queue = onlineTracks.map(::onlineTrackToNativeTrack)
        onlineTrackById.clear()
        onlineRawItemById.clear()
        onlineTracks.forEachIndexed { index, track ->
            onlineTrackById[queue[index].id] = track
            onlineRawItemById[queue[index].id] = tracks[index].raw
        }
        _state.value = _state.value.copy(miniPlayerDismissed = false)
        playFromQueue(queue, startIndex)
    }

    fun dismissMiniPlayer() {
        if (_state.value.currentTrack != null || _state.value.currentVideo != null) {
            _state.value = _state.value.copy(miniPlayerDismissed = true)
        }
    }

    /** 播放整张在线合集（专辑/歌单/榜单）：拉取曲目入队并记专辑上下文，供历史与「正在播放」标记。 */
    fun playOnlineCollection(collection: OnlineCollection, forcedStartPositionMs: Long? = null) {
        viewModelScope.launch {
            val tracks = if (_state.value.onlineCollectionDetail?.key == collection.key &&
                _state.value.onlineCollectionTracks.isNotEmpty()
            ) {
                _state.value.onlineCollectionTracks
            } else {
                _state.value = _state.value.copy(status = "正在加载${collectionDetailLabel(collection)}…")
                var all = emptyList<StreamTrack>()
                var page = 1
                while (page <= 30) {
                    val result = runCatching { fetchCollectionPage(collection, page) }.getOrNull()
                        ?: break
                    val fresh = result.items.filter { item -> all.none { it.pluginId == item.pluginId && it.key == item.key } }
                    all += fresh
                    if (result.isEnd || fresh.isEmpty()) break
                    page++
                }
                all
            }
            if (tracks.isEmpty()) {
                _state.value = _state.value.copy(status = "该${collectionDetailLabel(collection)}暂无曲目")
                return@launch
            }
            playStreamCollectionTracks(collection, tracks, 0, forcedStartPositionMs)
        }
    }

    /** 合集曲目入队播放：与 playStreamTracks 相同，但记下专辑上下文（历史/标记用）。 */
    fun playStreamCollectionTracks(
        collection: OnlineCollection,
        tracks: List<StreamTrack>,
        startIndex: Int,
        forcedStartPositionMs: Long? = null,
    ) {
        if (startIndex !in tracks.indices) return
        val onlineTracks = tracks.map(OnlineTrack::fromStreamTrack)
        val queue = onlineTracks.map(::onlineTrackToNativeTrack)
        onlineTrackById.clear()
        onlineRawItemById.clear()
        onlineTracks.forEachIndexed { index, track ->
            onlineTrackById[queue[index].id] = track
            onlineRawItemById[queue[index].id] = tracks[index].raw
        }
        albumPlaybackContext = AlbumPlaybackContext(
            key = collection.key,
            title = collection.name,
            artist = collection.artist,
            source = "onlinealbum",
            queueKey = queueHistoryKey(queue),
        )
        _state.value = _state.value.copy(miniPlayerDismissed = false, status = null)
        if (forcedStartPositionMs != null) playFromQueueAtPosition(queue, startIndex, forcedStartPositionMs)
        else playFromQueue(queue, startIndex)
    }

    /**
     * 上一首/下一首（音频）统一入口：直播换台优先，其次交给队列；
     * 队列或频道清单里没有相邻条目时不做任何事（按钮由 `queueSkipAvailable` 置灰）。
     */
    fun skipPrevious() {
        previous()
    }

    fun skipNext() {
        next()
    }

    /** 上一集/下一集（视频）统一入口：直播换台优先，其次按视频队列切集。 */
    fun skipPreviousVideo() {
        videoPrevious()
    }

    fun skipNextVideo() {
        videoNext()
    }

    fun playLibraryAlbumTracks(albumKey: String, albumTitle: String, artist: String, tracks: List<NativeTrack>, startIndex: Int) {
        if (startIndex !in tracks.indices) return
        val queue = applyMetadataOverrides(tracks)
        albumPlaybackContext = AlbumPlaybackContext(
            key = albumKey,
            title = albumTitle,
            artist = artist,
            source = "local",
            queueKey = queueHistoryKey(queue),
        )
        playFromQueue(queue, startIndex)
    }

    /** 从歌曲条目打开歌词：若歌曲未在播放则先播放，再请求打开歌词屏（MusicApp 观察后导航）。 */
    fun openLyricsFromSong(track: NativeTrack) {
        if (_state.value.currentTrack?.id != track.id) {
            val online = onlineTrackById[track.id]
            when {
                online != null -> playOnline(listOf(online), 0)
                else -> playFromQueue(listOf(track), 0)
            }
        }
        _state.value = _state.value.copy(lyricsScreenOpen = true)
        onlineTrackById[track.id]?.let { requestOnlineLyrics(it, onlineRawItemById[track.id]) }
        if (_state.value.lyrics.isNullOrBlank() && onlineTrackById[track.id] == null && !track.isVideo) {
            viewModelScope.launch {
                val fetched = runCatching { fetchAndSaveOnlineLyrics(track) }.getOrNull()
                if (_state.value.currentTrack?.id == track.id && !fetched.isNullOrBlank()) {
                    _state.value = _state.value.copy(lyrics = fetched)
                }
            }
        }
    }

    fun clearLyricsScreenOpen() {
        if (_state.value.lyricsScreenOpen) {
            _state.value = _state.value.copy(lyricsScreenOpen = false)
        }
    }

    /** 从在线曲目打开歌词：若该曲未在播放则先播放，再请求打开歌词屏。 */
    fun openLyricsForOnlineTrack(onlineTrack: OnlineTrack) {
        val currentOnline = _state.value.currentTrack?.let { onlineTrackById[it.id] }
        if (currentOnline?.key != onlineTrack.key) {
            playOnline(listOf(onlineTrack), 0)
        }
        _state.value = _state.value.copy(lyricsScreenOpen = true)
        requestOnlineLyrics(onlineTrack, null)
    }

    fun requestLyricsForCurrentTrack() {
        val track = _state.value.currentTrack ?: return
        onlineTrackById[track.id]?.let { requestOnlineLyrics(it, onlineRawItemById[track.id]) }
    }

    /** 在线曲目歌词：经对应插件的 getLyric 获取；插件不支持或未命中时静默保留现状。 */
    private fun requestOnlineLyrics(onlineTrack: OnlineTrack, rawItem: Any?) {
        viewModelScope.launch {
            val lyrics = runCatching {
                withContext(Dispatchers.IO) {
                    val plugin = pluginFor(onlineTrack.pluginId) ?: return@withContext null
                    val streamTrack = onlineTrackToStreamTrack(onlineTrack, rawItem ?: onlineTrack.pluginItem())
                    plugin.getLyric(streamTrack)
                }
            }.getOrNull()
            val clean = sanitizeLyrics(lyrics?.rawLrc ?: lyrics?.translation)
            if (!clean.isNullOrBlank() && onlineTrackById[_state.value.currentTrack?.id]?.key == onlineTrack.key) {
                _state.value = _state.value.copy(lyrics = clean, status = null)
            }
        }
    }

    /** OnlineTrack → 插件调用形态的 StreamTrack（raw 来自缓存或重建的 IMusicItem）。 */
    private fun onlineTrackToStreamTrack(track: OnlineTrack, raw: Any?): StreamTrack = StreamTrack(
        key = track.platformId,
        pluginId = track.pluginId,
        sourceName = track.sourceName,
        name = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs ?: 0,
        platform = track.platform,
        artwork = track.artworkUrl,
        raw = raw,
    )

    /** 歌词规范化：挡住空串与历史 bug 写入的字面量 "null"（服务端 JSON null 曾被 optString 变成 "null" 存进 .lrc）。
     * 行级同样过滤，防止部分行混入。 */
    private fun sanitizeLyrics(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) } ?: return null
        val lines = text.lines().filter { it.trim() != "null" }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    /** 本地歌曲自动补全歌词：本地无歌词时按「歌名+歌手」在启用插件里搜索并取歌词，
     * 成功后写入同目录 .lrc 永久保存，并返回歌词文本。 */
    private suspend fun fetchAndSaveOnlineLyrics(track: NativeTrack): String? = withContext(Dispatchers.IO) {
        if (track.isVideo || _state.value.isStreaming) return@withContext null
        if (track.uri.scheme != "content" && track.uri.scheme != "file") return@withContext null
        // 本地曲目标题来自 MediaStore DISPLAY_NAME，含扩展名与常见曲号/歌手前缀；先推导歌曲名候选再搜索，
        // 否则「歌名+歌手」匹配会因 ".mp3/.flac" 后缀或前缀永远失败。
        val candidates = localLyricSearchTitles(track.title, track.artist)
        if (candidates.isEmpty()) return@withContext null
        for (candidate in candidates) {
            for (source in enabledSources()) {
                val plugin = runCatching { pluginManager.getPlugin(source) }.getOrNull() ?: continue
                if (!plugin.hasLyric) continue
                val page = runCatching { plugin.search(candidate, 1) }.getOrNull() ?: continue
                val match = page.items.firstOrNull { item -> streamTrackMatchesLyric(item, candidate, track.artist) } ?: continue
                val lyrics = runCatching { plugin.getLyric(match) }.getOrNull() ?: continue
                val clean = sanitizeLyrics(lyrics.rawLrc ?: lyrics.translation) ?: continue
                saveLocalLyrics(track, clean)
                return@withContext clean
            }
        }
        null
    }

    /** 搜索结果与本地歌曲的名称/歌手匹配（忽略空格与大小写）。 */
    private fun streamTrackMatchesLyric(item: StreamTrack, title: String, artist: String): Boolean {
        val itemName = item.name.replace(" ", "").lowercase()
        val target = title.replace(" ", "").lowercase()
        val nameOk = itemName == target || itemName.contains(target) || target.contains(itemName)
        val artistOk = artist.isBlank() || item.artist.isBlank() ||
            item.artist.replace(" ", "").lowercase().contains(artist.replace(" ", "").lowercase())
        return nameOk && artistOk
    }

    /** 把歌词写入歌曲同目录 `标题.lrc`，与 loadExternalLyrics 的查找规则一致。 */
    private fun saveLocalLyrics(track: NativeTrack, lyrics: String): Boolean = runCatching {
        val title = track.title.substringBeforeLast('.', track.title).trim()
        val displayName = "$title.lrc"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = track.folderPath.trim('/') + "/"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return false
            resolver.openOutputStream(uri, "w")?.use { out -> out.write(lyrics.toByteArray(Charsets.UTF_8)) }
                ?: return false
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            val dir = File(Environment.getExternalStorageDirectory(), track.folderPath)
            dir.mkdirs()
            File(dir, displayName).writeText(lyrics, Charsets.UTF_8)
        }
        true
    }.getOrDefault(false)

    /** 下载在线单曲：弹音质档位选择框（StreamQuality 四档）。 */
    fun requestDownloadOnline(track: StreamTrack) {
        _state.value = _state.value.copy(downloadQualityPrompt = DownloadQualityPrompt(track = track))
    }

    /** 下载整张在线合集（专辑/歌单/榜单）：弹音质档位选择框。 */
    fun requestDownloadOnlineCollection(collection: OnlineCollection) {
        _state.value = _state.value.copy(downloadQualityPrompt = DownloadQualityPrompt(collection = collection))
    }

    /** 弹窗确认：按所选音质（仅本次）入队并关闭弹窗。 */
    fun confirmDownloadQuality(option: StreamQualityOption) {
        val prompt = _state.value.downloadQualityPrompt ?: return
        _state.value = _state.value.copy(downloadQualityPrompt = null)
        when {
            prompt.track != null -> enqueueOnlineDownload(prompt.track, option)
            prompt.collection != null -> enqueueOnlineCollection(prompt.collection, option)
        }
    }

    fun cancelDownloadQualityPrompt() {
        _state.value = _state.value.copy(downloadQualityPrompt = null)
    }

    private fun enqueueOnlineDownload(track: StreamTrack, option: StreamQualityOption) {
        viewModelScope.launch {
            runCatching {
                val task = downloads.enqueue(OnlineTrack.fromStreamTrack(track), option.value)
                if (task.status == DownloadTaskStatus.FAILED || task.status == DownloadTaskStatus.PAUSED) {
                    downloads.retry(task.id)
                }
            }
                .onSuccess {
                    DownloadService.start(getApplication())
                    announceDownloadAdd("已加入下载队列：${track.name}")
                }
                .onFailure { _ -> _state.value = _state.value.copy(status = "无法创建下载任务，请重试") }
        }
    }

    private fun enqueueOnlineCollection(collection: OnlineCollection, option: StreamQualityOption) {
        if (_state.value.onlineAlbumDownloadKey != null) return
        _state.value = beginOnlineAlbumDownload(_state.value, collection)
        viewModelScope.launch {
            val folderName = albumDownloadFolderName(collection.name)
            var pageError: String? = null
            val result = runCatching {
                var all = emptyList<StreamTrack>()
                var page = 1
                while (page <= 50) {
                    val pageResult = runCatching { fetchCollectionPage(collection, page) }.getOrElse { error ->
                        if (page == 1) throw error
                        pageError = error.message ?: "加载更多失败，请重试"
                        break
                    }
                    val fresh = pageResult.items.filter { item -> all.none { it.pluginId == item.pluginId && it.key == item.key } }
                    all += fresh
                    if (pageResult.isEnd || fresh.isEmpty()) break
                    page++
                }
                enqueueOnlineAlbumTracks(
                    tracks = all,
                    qualityApiValue = option.value,
                    folderName = folderName,
                    enqueue = { track, value, folder, trackNo -> downloads.enqueue(track, value, folder, trackNo) },
                    onProgress = { progress ->
                        _state.value = _state.value.copy(onlineAlbumDownloadProgress = progress)
                    },
                )
            }.getOrElse { error ->
                pageError = error.message ?: "加载失败，请稍后重试"
                OnlineAlbumEnqueueResult(0, 1)
            }
            if (result.succeeded > 0) DownloadService.start(getApplication())
            _state.value = finishOnlineAlbumDownload(_state.value, result, pageError)
        }
    }

    fun pauseDownload(id: String) = DownloadService.pause(getApplication(), id)

    fun resumeDownload(id: String) = DownloadService.resume(getApplication(), id)

    fun pauseAllDownloads() {
        val state = _state.value
        val active = setOf(
            DownloadTaskStatus.QUEUED,
            DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.WAITING_NETWORK,
            DownloadTaskStatus.FINALIZING,
        )
        state.downloadTasks.filter { it.status in active }.forEach { DownloadService.pause(getApplication(), it.id) }
        state.panDownloads.filter { it.status in active }.forEach { downloadEngine.pausePan(it.fsId) }
        state.quarkDownloads.filter { it.status in active }.forEach { downloadEngine.pauseQuark(it.fid) }
    }

    fun resumeAllDownloads() {
        val state = _state.value
        state.downloadTasks.filter { it.status in setOf(DownloadTaskStatus.PAUSED, DownloadTaskStatus.FAILED) }
            .forEach { DownloadService.resume(getApplication(), it.id) }
        state.panDownloads.filter { it.status in setOf(DownloadTaskStatus.PAUSED, DownloadTaskStatus.FAILED) }
            .forEach { downloadEngine.resumePan(it.fsId) }
        state.quarkDownloads.filter { it.status in setOf(DownloadTaskStatus.PAUSED, DownloadTaskStatus.FAILED) }
            .forEach { downloadEngine.resumeQuark(it.fid) }
    }

    fun retryDownload(id: String) = DownloadService.retry(getApplication(), id)

    fun cancelDownload(id: String) = DownloadService.cancel(getApplication(), id)

    fun removeDownloadRecord(id: String) = viewModelScope.launch { downloads.removeCompletedRecord(id) }

    fun clearDownloadAnnouncement() {
        _state.value = _state.value.copy(downloadAnnouncement = null)
    }

    private fun announceDownloadAdd(message: String) {
        _state.value = _state.value.copy(downloadAddAnnouncement = message)
    }

    fun clearDownloadAddAnnouncement() {
        _state.value = _state.value.copy(downloadAddAnnouncement = null)
    }

    fun setWifiOnlyDownload(enabled: Boolean) {
        settingsPreferences.edit().putBoolean("wifi_only_download", enabled).apply()
        _state.value = _state.value.copy(wifiOnlyDownload = enabled)
    }

    fun clearPanAnnouncement() {
        _state.value = _state.value.copy(panAnnouncement = null)
    }

    fun clearQuarkAnnouncement() {
        _state.value = _state.value.copy(quarkAnnouncement = null)
    }

    /** 二维码登录：获取设备码并开始自动轮询，扫码授权后自动完成登录。 */
    fun panStartQrLogin() {
        if (_state.value.panAuthInProgress) return
        panAuthJob?.cancel()
        _state.value = _state.value.copy(
            panAuthInProgress = true,
            panError = null,
            panDeviceCode = null,
            panQrImage = null,
        )
        panAuthJob = viewModelScope.launch {
            val code = runCatching { withContext(Dispatchers.IO) { panApi.deviceCode() } }
                .getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _state.value = _state.value.copy(
                        panAuthInProgress = false,
                        panError = error.message ?: "百度网盘登录失败",
                    )
                    return@launch
                }
            _state.value = _state.value.copy(panDeviceCode = code, panError = null)
            runCatching { withContext(Dispatchers.IO) { panApi.qrImageBytes(code.qrcodeUrl) } }
                .onSuccess { bytes ->
                    _state.value = _state.value.copy(panQrImage = bytes)
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _state.value = _state.value.copy(panError = error.message ?: "二维码加载失败")
                }
            panPollDeviceToken(code)
        }
    }

    fun panCancelQrLogin() {
        panAuthJob?.cancel()
        panAuthJob = null
        _state.value = _state.value.copy(
            panAuthInProgress = false,
            panDeviceCode = null,
            panQrImage = null,
            panError = null,
        )
    }

    /**
     * 轮询设备码授权状态；用户扫码确认后自动保存 token 并拉取账号信息。
     * 设备码有效期只有 5 分钟：网页授权登录慢于窗口时自动换新码继续等（登录页仍开着才有意义），
     * 最多换 3 次，避免用户停留后无限轮询。
     */
    private suspend fun panPollDeviceToken(code: BaiduPanDeviceCode) {
        var current = code
        var reissues = 0
        while (true) {
            if (reissues > 0) {
                _state.value = _state.value.copy(
                    panDeviceCode = current,
                    panQrImage = null,
                    panError = null,
                    status = "设备码已刷新，请重新扫码或重新打开授权页",
                )
                runCatching { withContext(Dispatchers.IO) { panApi.qrImageBytes(current.qrcodeUrl) } }
                    .onSuccess { bytes ->
                        _state.value = _state.value.copy(panQrImage = bytes)
                    }
            }
            val deadline = System.currentTimeMillis() + current.expiresInMs.coerceAtLeast(0)
            val interval = current.intervalMs.coerceAtLeast(1_000)
            poll@ while (System.currentTimeMillis() < deadline) {
                val result = try {
                    withContext(Dispatchers.IO) { panApi.exchangeDeviceToken(current.deviceCode) }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    delay(interval)
                    continue
                }
                when (result) {
                    is BaiduPanDeviceTokenResult.Ok -> {
                        panFinishLogin(result.token)
                        return
                    }
                    BaiduPanDeviceTokenResult.Pending -> delay(interval)
                    BaiduPanDeviceTokenResult.Expired -> break@poll
                    is BaiduPanDeviceTokenResult.Failed -> {
                        _state.value = _state.value.copy(
                            panAuthInProgress = false,
                            panError = result.message,
                        )
                        return
                    }
                }
            }
            if (reissues >= 3) {
                _state.value = _state.value.copy(
                    panAuthInProgress = false,
                    panError = "设备码已过期，请点击刷新重新获取",
                )
                return
            }
            current = try {
                withContext(Dispatchers.IO) { panApi.deviceCode() }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    panAuthInProgress = false,
                    panError = error.message ?: "设备码已过期，请重新登录",
                )
                return
            }
            reissues++
        }
    }

    /** 授权码模式登录完成：WebView 拦截百度回调 code 后换 token 落库，全程无需设备码。 */
    fun panCompleteCodeLogin(authCode: String) {
        if (_state.value.panAuthInProgress) return
        panAuthJob?.cancel()
        panAuthJob = null
        _state.value = _state.value.copy(panAuthInProgress = true, panError = null)
        panAuthJob = viewModelScope.launch {
            val token = runCatching {
                withContext(Dispatchers.IO) { panApi.exchangeAuthorizationCode(authCode) }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.value = _state.value.copy(
                    panAuthInProgress = false,
                    panError = error.message ?: "百度网盘登录失败",
                )
                return@launch
            }
            panFinishLogin(token)
        }
    }

    /** 授权码模式是否就绪与授权页地址：未配置回调地址时返回 null，调用方回退设备码流程。 */
    fun panAuthorizePageUrl(): String? = panApi.authorizePageUrl()

    /** 授权码模式回调地址（供 WebView 拦截匹配）。 */
    val panOAuthRedirectUri: String get() = panApi.oauthRedirectUri

    /** 拿到 token 后的统一登录收尾：拉取账号信息、落库并更新状态。 */
    private suspend fun panFinishLogin(token: BaiduPanToken) {
        val user = runCatching {
            withContext(Dispatchers.IO) { panApi.userInfo(token.accessToken) }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            _state.value = _state.value.copy(
                panAuthInProgress = false,
                panError = error.message ?: "获取百度账号信息失败",
            )
            return
        }
        panAccountStore.saveSession(token, user)
        panTokenExpiresAtMs = panAccountStore.expiresAtMs()
        _state.value = _state.value.copy(
            panAccount = user,
            panAccounts = panAccountStore.accounts().map { BaiduAccountInfo(it.id, it.user) },
            panAuthInProgress = false,
            panDeviceCode = null,
            panQrImage = null,
            panError = null,
            status = "百度网盘登录成功",
        )
    }

    /** 删除指定百度网盘账号；若删除的是当前激活账号则自动切换到其余账号。 */
    fun panRemoveAccount(id: String) {
        panAuthJob?.cancel()
        panAuthJob = null
        panStreamProxy.stop()
        panAccountStore.removeAccount(id)
        panTokenExpiresAtMs = panAccountStore.expiresAtMs()
        panClipboardFile = null
        panClipboardShouldMove = false
        _state.value = _state.value.copy(
            panAccount = panAccountStore.loadUser(),
            panAccounts = panAccountStore.accounts().map { BaiduAccountInfo(it.id, it.user) },
            panFiles = emptyList(),
            panPath = "/",
            panDeviceCode = null,
            panQrImage = null,
            panError = null,
            hasClipboardPan = false,
            status = "已删除该百度网盘账号",
        )
    }

    /** 删除当前激活的百度网盘账号（兼容旧“退出登录”入口）。 */
    fun panLogout() {
        val id = panAccountStore.activeId() ?: return
        panRemoveAccount(id)
    }

    /** 切换到指定百度网盘账号（账号管理：随时切换）。 */
    fun panSwitchAccount(id: String) {
        if (panAccountStore.activeId() == id) return
        panStreamProxy.stop()
        panAccountStore.setActive(id)
        panTokenExpiresAtMs = panAccountStore.expiresAtMs()
        panClipboardFile = null
        panClipboardShouldMove = false
        val user = panAccountStore.loadUser()
        _state.value = _state.value.copy(
            panAccount = user,
            panAccounts = panAccountStore.accounts().map { BaiduAccountInfo(it.id, it.user) },
            panFiles = emptyList(),
            panPath = "/",
            hasClipboardPan = false,
            status = user?.let { "已切换到百度网盘账号：${it.netdiskName.ifBlank { it.name }}" } ?: "百度网盘账号已切换",
        )
    }

    fun quarkStartQrLogin() {
        if (_state.value.quarkAuthInProgress) return
        quarkAuthJob?.cancel()
        _state.value = _state.value.copy(
            quarkAuthInProgress = true,
            quarkError = null,
            quarkQrImage = null,
        )
        quarkAuthJob = viewModelScope.launch {
            // 获取二维码 token 偶发网络抖动，自动重试一次。
            var session: QuarkPanQrSession? = null
            var sessionError: Throwable? = null
            repeat(2) { attempt ->
                val result = runCatching {
                    withContext(Dispatchers.IO) { quarkApi.getQrSession() }
                }
                if (result.isSuccess) {
                    session = result.getOrNull()
                    return@repeat
                }
                val error = result.exceptionOrNull()
                if (error is kotlinx.coroutines.CancellationException) throw error
                sessionError = error
                Log.e("QuarkPan", "quarkStartQrLogin getQrSession failed (attempt ${attempt + 1})", error)
                if (attempt == 0) delay(1_000)
            }
            val currentSession = session
            if (currentSession == null) {
                _state.value = _state.value.copy(
                    quarkAuthInProgress = false,
                    quarkError = sessionError?.message ?: "夸克网盘登录失败",
                )
                return@launch
            }
            Log.d("QuarkPan", "quarkStartQrLogin session token=${currentSession.qrToken.take(10)} qrUrl=${currentSession.qrUrl.take(60)}")
            val qrBytesResult = runCatching {
                withContext(Dispatchers.IO) { quarkQrImageBytes(currentSession.qrUrl) }
            }
            if (qrBytesResult.isFailure) {
                Log.e("QuarkPan", "quarkStartQrLogin QR generation failed", qrBytesResult.exceptionOrNull())
            }
            val qrBytes = qrBytesResult.getOrNull()
            if (qrBytes == null) {
                _state.value = _state.value.copy(
                    quarkAuthInProgress = false,
                    quarkError = "二维码生成失败，请点击刷新重试",
                )
                return@launch
            }
            Log.d("QuarkPan", "quarkStartQrLogin QR generated ${qrBytes.size} bytes")
            _state.value = _state.value.copy(quarkQrImage = qrBytes, quarkError = null)
            quarkPollQrLogin(currentSession)
        }
    }

    fun quarkCancelQrLogin() {
        quarkAuthJob?.cancel()
        quarkAuthJob = null
        _state.value = _state.value.copy(
            quarkAuthInProgress = false,
            quarkQrImage = null,
            quarkError = null,
        )
    }

    /** 轮询二维码登录状态；用户扫码确认后自动换 Cookie 并拉取账号信息。 */
    private suspend fun quarkPollQrLogin(session: QuarkPanQrSession) {
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            val result = try {
                withContext(Dispatchers.IO) { quarkApi.pollQrLogin(session.qrToken) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                delay(2_000)
                continue
            }
            when (result) {
                is QuarkPanQrPollResult.Ok -> {
                    val user = runCatching {
                        withContext(Dispatchers.IO) { quarkApi.exchangeTicket(result.serviceTicket) }
                    }.getOrElse { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        _state.value = _state.value.copy(
                            quarkAuthInProgress = false,
                            quarkError = error.message ?: "获取夸克账号信息失败",
                        )
                        return
                    }
                    quarkAccountStore.saveSession(quarkApi.cookie, user)
                    _state.value = _state.value.copy(
                        quarkAccount = user,
                        quarkAccounts = quarkAccountStore.accounts().map { QuarkAccountInfo(it.id, it.user) },
                        quarkAuthInProgress = false,
                        quarkQrImage = null,
                        quarkError = null,
                        status = "夸克网盘登录成功",
                    )
                    return
                }
                QuarkPanQrPollResult.Pending -> delay(2_000)
                QuarkPanQrPollResult.Expired -> {
                    _state.value = _state.value.copy(
                        quarkAuthInProgress = false,
                        quarkError = "二维码已过期，请点击刷新重新获取",
                    )
                    return
                }
                is QuarkPanQrPollResult.Failed -> {
                    _state.value = _state.value.copy(
                        quarkAuthInProgress = false,
                        quarkError = result.message,
                    )
                    return
                }
            }
        }
        _state.value = _state.value.copy(
            quarkAuthInProgress = false,
            quarkError = "二维码已过期，请点击刷新重新获取",
        )
    }

    /** 删除指定夸克网盘账号；若删除的是当前激活账号则自动切换到其余账号。 */
    fun quarkRemoveAccount(id: String) {
        quarkAuthJob?.cancel()
        quarkAuthJob = null
        quarkStreamProxy.stop()
        quarkAccountStore.removeAccount(id)
        quarkApi.setCookie(quarkAccountStore.loadCookie())
        quarkClipboardFile = null
        quarkClipboardShouldMove = false
        quarkDirStack.clear()
        quarkCurrentDirFid = "0"
        _state.value = _state.value.copy(
            quarkAccount = quarkAccountStore.loadUser(),
            quarkAccounts = quarkAccountStore.accounts().map { QuarkAccountInfo(it.id, it.user) },
            quarkFiles = emptyList(),
            quarkPath = "",
            quarkQrImage = null,
            quarkError = null,
            hasClipboardQuark = false,
            status = "已删除该夸克网盘账号",
        )
    }

    /** 删除当前激活的夸克网盘账号（兼容旧“退出登录”入口）。 */
    fun quarkLogout() {
        val id = quarkAccountStore.activeId() ?: return
        quarkRemoveAccount(id)
    }

    /** 切换到指定夸克网盘账号（账号管理：随时切换）。 */
    fun quarkSwitchAccount(id: String) {
        if (quarkAccountStore.activeId() == id) return
        quarkStreamProxy.stop()
        quarkAccountStore.setActive(id)
        quarkApi.setCookie(quarkAccountStore.loadCookie())
        quarkClipboardFile = null
        quarkClipboardShouldMove = false
        quarkDirStack.clear()
        quarkCurrentDirFid = "0"
        val user = quarkAccountStore.loadUser()
        _state.value = _state.value.copy(
            quarkAccount = user,
            quarkAccounts = quarkAccountStore.accounts().map { QuarkAccountInfo(it.id, it.user) },
            quarkFiles = emptyList(),
            quarkPath = "",
            quarkError = null,
            hasClipboardQuark = false,
            status = user?.let { "已切换到夸克网盘账号：${it.name}" } ?: "夸克网盘账号已切换",
        )
    }

    /**
     * 网页登录完成：从内置 WebView 抓取夸克 Cookie，校验账号并保存会话。
     * 网页登录（官方登录页）作为扫码登录的兜底，登录入口仍在账号中心。
     */
    fun quarkCompleteWebLogin() {
        viewModelScope.launch {
            val cookie = quarkCaptureWebCookies()
            Log.d("QuarkPan", "quarkCompleteWebLogin captured cookie: ${cookie.take(400)}")
            if (cookie.isBlank() || !cookie.contains("__puus=")) {
                _state.value = _state.value.copy(
                    quarkError = "未检测到夸克登录信息，请确认已在网页中完成登录",
                )
                return@launch
            }
            quarkApi.setCookie(cookie)
            val user = runCatching {
                withContext(Dispatchers.IO) { quarkApi.userInfo() }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e("QuarkPan", "quarkCompleteWebLogin userInfo failed", error)
                _state.value = _state.value.copy(
                    quarkError = error.message ?: "获取夸克账号信息失败，请重试",
                )
                return@launch
            }
            Log.d("QuarkPan", "quarkCompleteWebLogin user OK: ${user.name}")
            quarkAccountStore.saveSession(cookie, user)
            _state.value = _state.value.copy(
                quarkAccount = user,
                quarkAccounts = quarkAccountStore.accounts().map { QuarkAccountInfo(it.id, it.user) },
                quarkError = null,
                status = "夸克网盘登录成功",
            )
        }
    }

    /** 网页登录页是否已出现会话 Cookie，供 WebView 自动完成登录检测。 */
    fun quarkWebSessionReady(): Boolean = hasQuarkSessionCookie()

    /** 从 WebView CookieManager 抓取夸克各域名的会话 Cookie。 */
    private fun quarkCaptureWebCookies(): String {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val map = LinkedHashMap<String, String>()
        QUARK_WEB_COOKIE_DOMAINS.forEach { domain ->
            val cookie = cookieManager.getCookie(domain)
            if (!cookie.isNullOrBlank()) {
                map.putAll(parseQuarkCookieString(cookie))
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 网页是否已出现夸克会话 Cookie（__puus 等），用于自动完成网页登录。 */
    private fun hasQuarkSessionCookie(): Boolean {
        return QUARK_WEB_COOKIE_DOMAINS.any { domain ->
            val cookie = android.webkit.CookieManager.getInstance().getCookie(domain) ?: return@any false
            cookie.contains("__puus=") || cookie.contains("__pus=") || cookie.contains("stok=")
        }
    }
    fun panListDir(path: String, loadMore: Boolean = false) {
        if (_state.value.panLoading || _state.value.panAuthInProgress) return

        val start = if (loadMore) _state.value.panFiles.size else 0
        val targetPath = if (loadMore) _state.value.panPath else path
        _state.value = _state.value.copy(
            panLoading = true,
            panError = null,
            panPath = targetPath,
            panSearchActive = if (loadMore) _state.value.panSearchActive else false,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { panListDirWithRefresh(targetPath, start) }
            }.onSuccess { list ->
                val files = if (loadMore) _state.value.panFiles + list.files else list.files
                files.forEach { panFileByFsId[it.fsId] = it }
                _state.value = _state.value.copy(
                    panFiles = files,
                    panLoading = false,
                    panHasMore = list.files.size >= BAIDU_PAN_PAGE_SIZE,
                    panError = panErrorMessage(list.errno).takeIf { list.errno != 0 },
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    panLoading = false,
                    panError = error.message ?: "百度网盘加载失败",
                )
            }
        }
    }

    private suspend fun panListDirWithRefresh(path: String, start: Int): BaiduPanFileList {
        val token = panAccessToken()
        val result = panApi.listDir(token, path, start)
        if (result.errno == BAIDU_PAN_ERRNO_TOKEN_EXPIRED) {
            return panApi.listDir(panRefreshToken(), path, start)
        }
        return result
    }

    /** 网盘全局搜索：进入搜索模式，用搜索结果替换当前列表。 */
    fun panSearch(query: String) {
        if (_state.value.panLoading || _state.value.panAuthInProgress) return
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            panExitSearch()
            return
        }
        panSearchKeyword = keyword
        panSearchPage = 1
        _state.value = _state.value.copy(
            panSearchActive = true,
            panLoading = true,
            panError = null,
            panFiles = emptyList(),
        )
        panRunSearch(panSearchPage, append = false)
    }

    /** 搜索模式下加载下一页搜索结果。 */
    fun panSearchLoadMore() {
        if (_state.value.panLoading || !_state.value.panSearchActive || panSearchKeyword.isBlank()) return
        panSearchPage += 1
        panRunSearch(panSearchPage, append = true)
    }

    /** 退出搜索模式，恢复当前目录浏览。 */
    fun panExitSearch() {
        if (!_state.value.panSearchActive) return
        panSearchJob?.cancel()
        panSearchJob = null
        _state.value = _state.value.copy(panSearchActive = false, panLoading = false)
        panListDir(_state.value.panPath)
    }

    private fun panRunSearch(page: Int, append: Boolean) {
        panSearchJob?.cancel()
        _state.value = _state.value.copy(panLoading = true, panError = null)
        panSearchJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { panSearchWithRefresh(panSearchKeyword, page) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    panLoading = false,
                    panError = error.message ?: "百度网盘搜索失败",
                )
                return@launch
            }
            val files = if (append) _state.value.panFiles + result.files else result.files
            files.forEach { panFileByFsId[it.fsId] = it }
            _state.value = _state.value.copy(
                panFiles = files,
                panLoading = false,
                panHasMore = result.files.size >= BAIDU_PAN_SEARCH_PAGE_SIZE,
                panError = panErrorMessage(result.errno).takeIf { result.errno != 0 },
            )
        }
    }

    private suspend fun panSearchWithRefresh(keyword: String, page: Int): BaiduPanFileList {
        val token = panAccessToken()
        val result = panApi.search(token, keyword, page)
        if (result.errno == BAIDU_PAN_ERRNO_TOKEN_EXPIRED) {
            return panApi.search(panRefreshToken(), keyword, page)
        }
        return result
    }

    private suspend fun panAccessToken(): String {
        val stored = panAccountStore.loadToken() ?: error("百度网盘未登录")
        if (System.currentTimeMillis() < panTokenExpiresAtMs) return stored.accessToken
        return panRefreshToken()
    }

    private suspend fun panRefreshToken(): String {
        val stored = panAccountStore.loadToken() ?: error("百度网盘未登录")
        val token = panApi.refreshAccessToken(stored.refreshToken)
        panAccountStore.saveToken(token.accessToken, token.refreshToken, token.expiresInMs)
        panTokenExpiresAtMs = System.currentTimeMillis() + token.expiresInMs
        return token.accessToken
    }

    fun playBaiduPanFiles(files: List<BaiduPanFile>, index: Int) {
        val audioFiles = files.filter { !it.isDir && isPanAudioFileName(it.serverFilename) }
        if (index !in audioFiles.indices) return
        audioFiles.forEach { panFileByFsId[it.fsId] = it }
        playFromQueue(audioFiles.map(::baiduPanTrack), index)
    }

    /** 进入/刷新夸克目录；fid 为该目录 id，根目录为 "0"。 */
    fun quarkListDir(fid: String, loadMore: Boolean = false) {
        if (_state.value.quarkLoading || _state.value.quarkAuthInProgress) return
        val targetFid = if (loadMore) quarkCurrentDirFid else fid
        if (loadMore) {
            quarkListPage += 1
        } else {
            quarkListPage = 1
        }
        val page = quarkListPage
        _state.value = _state.value.copy(
            quarkLoading = true,
            quarkError = null,
            quarkSearchActive = if (loadMore) _state.value.quarkSearchActive else false,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { quarkListDirWithRefresh(targetFid, page) }
            }.onSuccess { list ->
                val previousSize = _state.value.quarkFiles.size
                val files = if (loadMore) {
                    (_state.value.quarkFiles + list.files).distinctBy { it.fid }
                } else {
                    list.files
                }
                val hasMore = if (list.total > 0) {
                    files.size < list.total
                } else {
                    files.size > previousSize && list.files.size >= QUARK_PAN_PAGE_SIZE
                }
                files.forEach { quarkFileByFid[it.fid] = it }
                quarkCurrentDirFid = targetFid
                _state.value = _state.value.copy(
                    quarkFiles = files,
                    quarkLoading = false,
                    quarkHasMore = hasMore,
                    quarkPath = quarkDisplayPath(),
                    quarkError = null,
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    quarkLoading = false,
                    quarkError = error.message ?: "夸克网盘加载失败",
                )
            }
        }
    }

    /** 打开夸克文件夹：把 (fid, 名称) 压栈并列出其内容。 */
    fun quarkOpenDir(file: QuarkPanFile) {
        if (!file.isDir) return
        quarkDirStack.addLast(file.fid to file.fileName)
        _state.value = _state.value.copy(quarkPath = quarkDisplayPath())
        quarkListDir(file.fid)
    }

    /** 返回夸克上级目录；在根目录时清空并回到根。 */
    fun quarkGoUp() {
        if (quarkDirStack.isNotEmpty()) quarkDirStack.removeLast()
        val parentFid = quarkDirStack.lastOrNull()?.first ?: "0"
        _state.value = _state.value.copy(quarkPath = quarkDisplayPath())
        quarkListDir(parentFid)
    }

    private fun quarkDisplayPath(): String =
        quarkDirStack.joinToString("/") { it.second }.ifBlank { "" }

    private suspend fun quarkListDirWithRefresh(fid: String, page: Int): QuarkPanFileList {
        quarkEnsureCookie()
        return quarkApi.listDir(fid, page)
    }

    /** 会话 Cookie 为空时从存储恢复；仍为空则视为未登录。 */
    private suspend fun quarkEnsureCookie() {
        if (quarkApi.cookie.isBlank()) {
            val stored = quarkAccountStore.loadCookie()
            if (stored.isBlank()) error("夸克网盘未登录")
            quarkApi.setCookie(stored)
        }
    }

    /** 夸克全盘搜索：进入搜索模式，用搜索结果替换当前列表。 */
    fun quarkSearch(query: String) {
        if (_state.value.quarkLoading || _state.value.quarkAuthInProgress) return
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            quarkExitSearch()
            return
        }
        quarkSearchKeyword = keyword
        quarkSearchPage = 1
        _state.value = _state.value.copy(
            quarkSearchActive = true,
            quarkLoading = true,
            quarkError = null,
            quarkFiles = emptyList(),
        )
        quarkRunSearch(quarkSearchPage, append = false)
    }

    /** 搜索模式下加载下一页搜索结果。 */
    fun quarkSearchLoadMore() {
        if (_state.value.quarkLoading || !_state.value.quarkSearchActive || quarkSearchKeyword.isBlank()) return
        quarkSearchPage += 1
        quarkRunSearch(quarkSearchPage, append = true)
    }

    /** 退出搜索模式，恢复当前目录浏览。 */
    fun quarkExitSearch() {
        if (!_state.value.quarkSearchActive) return
        quarkSearchJob?.cancel()
        quarkSearchJob = null
        _state.value = _state.value.copy(quarkSearchActive = false, quarkLoading = false)
        quarkListDir(quarkCurrentDirFid)
    }

    private fun quarkRunSearch(page: Int, append: Boolean) {
        quarkSearchJob?.cancel()
        _state.value = _state.value.copy(quarkLoading = true, quarkError = null)
        quarkSearchJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { quarkSearchWithRefresh(quarkSearchKeyword, page) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    quarkLoading = false,
                    quarkError = error.message ?: "夸克网盘搜索失败",
                )
                return@launch
            }
            val previousSize = _state.value.quarkFiles.size
            val files = if (append) {
                (_state.value.quarkFiles + result.files).distinctBy { it.fid }
            } else {
                result.files
            }
            val hasMore = if (result.total > 0) {
                files.size < result.total
            } else {
                files.size > previousSize && result.files.size >= QUARK_PAN_SEARCH_PAGE_SIZE
            }
            files.forEach { quarkFileByFid[it.fid] = it }
            _state.value = _state.value.copy(
                quarkFiles = files,
                quarkLoading = false,
                quarkHasMore = hasMore,
                quarkError = null,
            )
        }
    }

    private suspend fun quarkSearchWithRefresh(keyword: String, page: Int): QuarkPanFileList {
        quarkEnsureCookie()
        return quarkApi.search(keyword, page)
    }

    private fun baiduPanTrack(file: BaiduPanFile): NativeTrack = NativeTrack(
        id = externalTrackId(Uri.parse("baidupan://${file.fsId}")),
        uri = Uri.parse("baidupan://${file.fsId}"),
        title = file.serverFilename.substringBeforeLast('.', file.serverFilename),
        artist = "百度网盘",
        album = "百度网盘",
        durationMs = 0,
        format = file.serverFilename.substringAfterLast('.', "").uppercase(),
        mimeType = "audio/mpeg",
        folderPath = "",
        sizeBytes = file.size,
    )

    /** 云端视频进入独立视频队列（不进入音频播放器状态）。 */
    fun playBaiduPanVideos(files: List<BaiduPanFile>, index: Int) {
        val videos = files.filter { !it.isDir && isPanVideoFileName(it.serverFilename) }
        if (index !in videos.indices) return
        videos.forEach { panFileByFsId[it.fsId] = it }
        playVideos(videos.map(::baiduPanVideoTrack), index)
    }

    private fun baiduPanVideoTrack(file: BaiduPanFile): NativeTrack = NativeTrack(
        id = externalTrackId(Uri.parse("baidupan://${file.fsId}")),
        uri = Uri.parse("baidupan://${file.fsId}"),
        title = file.serverFilename.substringBeforeLast('.', file.serverFilename),
        artist = "",
        album = "",
        durationMs = 0,
        format = file.serverFilename.substringAfterLast('.', "").uppercase(),
        mimeType = baiduPanVideoMimeType(file.serverFilename),
        folderPath = "",
        sizeBytes = file.size,
        isVideo = true,
    )

    private fun baiduPanVideoMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "3gp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "wmv" -> "video/x-ms-wmv"
        "rmvb", "rm" -> "video/vnd.rn-realvideo"
        "mpg", "mpeg" -> "video/mpeg"
        else -> "video/*"
    }

    fun playQuarkPanFiles(files: List<QuarkPanFile>, index: Int) {
        val audioFiles = files.filter { !it.isDir && isPanAudioFileName(it.fileName) }
        if (index !in audioFiles.indices) return
        audioFiles.forEach { quarkFileByFid[it.fid] = it }
        playFromQueue(audioFiles.map(::quarkTrack), index)
    }

    private fun quarkTrack(file: QuarkPanFile): NativeTrack = NativeTrack(
        id = externalTrackId(Uri.parse("quarkpan://${file.fid}")),
        uri = Uri.parse("quarkpan://${file.fid}"),
        title = file.fileName.substringBeforeLast('.', file.fileName),
        artist = "夸克网盘",
        album = "夸克网盘",
        durationMs = 0,
        format = file.fileName.substringAfterLast('.', "").uppercase(),
        mimeType = "audio/mpeg",
        folderPath = "",
        sizeBytes = file.size,
    )

    /** 夸克云端视频进入独立视频队列（不进入音频播放器状态）。 */
    fun playQuarkPanVideos(files: List<QuarkPanFile>, index: Int) {
        val videos = files.filter { !it.isDir && isPanVideoFileName(it.fileName) }
        if (index !in videos.indices) return
        videos.forEach { quarkFileByFid[it.fid] = it }
        playVideos(videos.map(::quarkVideoTrack), index)
    }

    private fun quarkVideoTrack(file: QuarkPanFile): NativeTrack = NativeTrack(
        id = externalTrackId(Uri.parse("quarkpan://${file.fid}")),
        uri = Uri.parse("quarkpan://${file.fid}"),
        title = file.fileName.substringBeforeLast('.', file.fileName),
        artist = "",
        album = "",
        durationMs = 0,
        format = file.fileName.substringAfterLast('.', "").uppercase(),
        mimeType = quarkVideoMimeType(file.fileName),
        folderPath = "",
        sizeBytes = file.size,
        isVideo = true,
    )

    private fun quarkVideoMimeType(name: String): String =
        baiduPanVideoMimeType(name)
    fun panStartDownload(file: BaiduPanFile) {
        if (file.isDir) return

        viewModelScope.launch {
            val added = panDownloads.enqueue(file)
            if (added) {
                DownloadService.start(getApplication())
                announceDownloadAdd("已加入下载队列：${file.serverFilename}")
            } else {
                _state.value = _state.value.copy(status = "该文件已在下载列表中")
            }
        }
    }

    /** 整个文件夹下载：递归枚举所有文件（含子目录）加入下载队列，按「文件所在文件夹」新建子目录。 */
    fun panStartDownloadFolder(folder: BaiduPanFile) {
        if (!folder.isDir) return
        if (_state.value.panDownloadingFolder) return
        viewModelScope.launch {
            _state.value = _state.value.copy(panDownloadingFolder = true, status = "正在读取文件夹 ${folder.serverFilename}…")
            val files = runCatching { panApi.listDirRecursive(panAccessToken(), folder.path) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(panDownloadingFolder = false)
            if (files.isEmpty()) {
                _state.value = _state.value.copy(status = "文件夹为空或读取失败，请重试。")
                return@launch
            }
            val folderPrefix = folder.path.trimEnd('/')
            var addedCount = 0
            var skipped = 0
            for (file in files) {
                val relDir = file.path.substringBeforeLast('/', "").removePrefix(folderPrefix).trim('/')
                val subPath = buildList {
                    if (folder.serverFilename.isNotBlank()) add(folder.serverFilename)
                    if (relDir.isNotBlank()) add(relDir)
                }.joinToString("/")
                if (panDownloads.enqueue(file, subPath)) addedCount++ else skipped++
            }
            if (addedCount > 0) {
                DownloadService.start(getApplication())
                announceDownloadAdd(
                    "已加入下载队列，共 $addedCount 个文件" +
                        (if (skipped > 0) "，跳过 $skipped 个已存在" else ""),
                )
            } else {
                _state.value = _state.value.copy(status = "文件夹内文件都已存在下载列表中")
            }
        }
    }

    fun panPauseDownload(fsId: Long) =
        downloadEngine.pausePan(fsId)

    fun panResumeDownload(fsId: Long) =
        downloadEngine.resumePan(fsId)

    fun panCancelDownload(fsId: Long) =
        downloadEngine.cancelPan(fsId)

    fun panRemoveDownload(fsId: Long) = viewModelScope.launch { panDownloads.removeRecord(fsId) }

    fun panCopyFile(file: BaiduPanFile) {
        panClipboardFile = file
        panClipboardShouldMove = false
        _state.value = _state.value.copy(
            hasClipboardPan = true,
            status = "已复制 ${file.serverFilename}",
        )
    }

    fun panCutFile(file: BaiduPanFile) {
        panClipboardFile = file
        panClipboardShouldMove = true
        _state.value = _state.value.copy(
            hasClipboardPan = true,
            status = "已剪切 ${file.serverFilename}",
        )
    }

    /** 粘贴到当前网盘目录：复制或移动剪贴板中的文件/文件夹，成功后刷新当前目录。 */
    fun panPaste() {
        val source = panClipboardFile ?: return
        val moveSource = panClipboardShouldMove
        val destDir = _state.value.panPath
        if (moveSource && source.path.substringBeforeLast('/', "/").ifBlank { "/" } == destDir) {
            _state.value = _state.value.copy(status = "该文件已在当前文件夹中。")
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    panCopyOrMoveWithRefresh(source.path, destDir, if (moveSource) "move" else "copy")
                }
            }.onSuccess { errno ->
                if (errno == 0) {
                    if (moveSource) {
                        panClipboardFile = null
                        panClipboardShouldMove = false
                    }
                    _state.value = _state.value.copy(
                        hasClipboardPan = panClipboardFile != null,
                        panAnnouncement = if (moveSource) "已移动到 ${destDir}" else "已复制到 ${destDir}",
                    )
                    panListDir(_state.value.panPath)
                } else {
                    _state.value = _state.value.copy(panError = panErrorMessage(errno))
                }
            }.onFailure { error ->
                _state.value = _state.value.copy(panError = error.message ?: "粘贴云端文件失败")
            }
        }
    }

    /** 删除网盘文件/文件夹：先取消对应下载任务，再调用网盘删除接口并刷新当前目录。 */
    fun panDeleteFile(file: BaiduPanFile) {
        viewModelScope.launch {
            panCancelDownload(file.fsId)
            runCatching {
                withContext(Dispatchers.IO) { panDeleteWithRefresh(file.path) }
            }.onSuccess { errno ->
                if (errno == 0 || errno == -9 || errno == -10) {
                    _state.value = _state.value.copy(
                        panAnnouncement = "已删除云端文件：${file.serverFilename}",
                    )
                    panListDir(_state.value.panPath)
                } else {
                    _state.value = _state.value.copy(panError = panErrorMessage(errno))
                }
            }.onFailure { error ->
                _state.value = _state.value.copy(panError = error.message ?: "删除云端文件失败")
            }
        }
    }

    private suspend fun panDeleteWithRefresh(path: String): Int {
        val token = panAccessToken()
        val errno = panApi.deleteFile(token, path)
        if (errno == BAIDU_PAN_ERRNO_TOKEN_EXPIRED) {
            return panApi.deleteFile(panRefreshToken(), path)
        }
        return errno
    }

    private suspend fun panCopyOrMoveWithRefresh(path: String, dest: String, opera: String): Int {
        val token = panAccessToken()
        val errno = panApi.copyOrMoveFile(token, opera, listOf(path), dest)
        if (errno == BAIDU_PAN_ERRNO_TOKEN_EXPIRED) {
            return panApi.copyOrMoveFile(panRefreshToken(), opera, listOf(path), dest)
        }
        return errno
    }

    fun quarkStartDownload(file: QuarkPanFile) {
        if (file.isDir) return
        viewModelScope.launch {
            val added = quarkDownloads.enqueue(file)
            if (added) {
                DownloadService.start(getApplication())
                announceDownloadAdd("已加入下载队列：${file.fileName}")
            } else {
                _state.value = _state.value.copy(status = "该文件已在下载列表中")
            }
        }
    }

    /** 夸克整个文件夹下载：递归枚举所有文件（含子目录）加入下载队列。 */
    fun quarkStartDownloadFolder(folder: QuarkPanFile) {
        if (!folder.isDir) return
        if (_state.value.quarkDownloadingFolder) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                quarkDownloadingFolder = true,
                status = "正在读取文件夹 ${folder.fileName}…",
            )
            val items = runCatching {
                withContext(Dispatchers.IO) {
                    quarkEnsureCookie()
                    quarkApi.listDirRecursive(folder.fid)
                }
            }.getOrDefault(emptyList())
            _state.value = _state.value.copy(quarkDownloadingFolder = false)
            if (items.isEmpty()) {
                _state.value = _state.value.copy(status = "文件夹为空或读取失败，请重试。")
                return@launch
            }
            var addedCount = 0
            var skipped = 0
            for ((file, relDir) in items) {
                val subPath = buildList {
                    add(folder.fileName)
                    if (relDir.isNotBlank()) add(relDir)
                }.joinToString("/")
                if (quarkDownloads.enqueue(file, subPath)) addedCount++ else skipped++
            }
            if (addedCount > 0) {
                DownloadService.start(getApplication())
                announceDownloadAdd(
                    "已加入下载队列，共 $addedCount 个文件" +
                        (if (skipped > 0) "，跳过 $skipped 个已存在" else ""),
                )
            } else {
                _state.value = _state.value.copy(status = "文件夹内文件都已存在下载列表中")
            }
        }
    }

    fun quarkPauseDownload(fid: String) =
        downloadEngine.pauseQuark(fid)

    fun quarkResumeDownload(fid: String) =
        downloadEngine.resumeQuark(fid)

    fun quarkCancelDownload(fid: String) =
        downloadEngine.cancelQuark(fid)

    fun quarkRemoveDownload(fid: String) = viewModelScope.launch { quarkDownloads.removeRecord(fid) }

    fun quarkCopyFile(file: QuarkPanFile) {
        quarkClipboardFile = file
        quarkClipboardShouldMove = false
        _state.value = _state.value.copy(
            hasClipboardQuark = true,
            status = "已复制 ${file.fileName}",
        )
    }

    fun quarkCutFile(file: QuarkPanFile) {
        quarkClipboardFile = file
        quarkClipboardShouldMove = true
        _state.value = _state.value.copy(
            hasClipboardQuark = true,
            status = "已剪切 ${file.fileName}",
        )
    }

    /** 粘贴到夸克当前目录：复制或移动剪贴板中的文件/文件夹，成功后刷新当前目录。 */
    fun quarkPaste() {
        val source = quarkClipboardFile ?: return
        val moveSource = quarkClipboardShouldMove
        val destFid = quarkCurrentDirFid
        if (moveSource && source.pdirFid == destFid) {
            _state.value = _state.value.copy(status = "该文件已在当前文件夹中。")
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    quarkEnsureCookie()
                    quarkApi.copyOrMove(if (moveSource) 1 else 0, listOf(source.fid), destFid)
                }
            }.onSuccess {
                if (moveSource) {
                    quarkClipboardFile = null
                    quarkClipboardShouldMove = false
                }
                _state.value = _state.value.copy(
                    hasClipboardQuark = quarkClipboardFile != null,
                    quarkAnnouncement = if (moveSource) "已移动到当前文件夹" else "已复制到当前文件夹",
                )
                quarkListDir(quarkCurrentDirFid)
            }.onFailure { error ->
                _state.value = _state.value.copy(quarkError = error.message ?: "粘贴云端文件失败")
            }
        }
    }

    /** 删除夸克文件/文件夹：先取消对应下载任务，再调用删除接口并刷新当前目录。 */
    fun quarkDeleteFile(file: QuarkPanFile) {
        viewModelScope.launch {
            quarkCancelDownload(file.fid)
            runCatching {
                withContext(Dispatchers.IO) {
                    quarkEnsureCookie()
                    quarkApi.delete(listOf(file.fid))
                }
            }.onSuccess {
                _state.value = _state.value.copy(
                    quarkAnnouncement = "已删除云端文件：${file.fileName}",
                )
                quarkListDir(quarkCurrentDirFid)
            }.onFailure { error ->
                _state.value = _state.value.copy(quarkError = error.message ?: "删除云端文件失败")
            }
        }
    }

    /** 在线曲目 → 队列曲目：online:// 插件id/平台id，播放时经插件 getMediaSource 解析直链。 */
    private fun onlineTrackToNativeTrack(track: OnlineTrack): NativeTrack = NativeTrack(
        id = externalTrackId(Uri.parse("online://${Uri.encode(track.pluginId)}/${Uri.encode(track.platformId)}")),
        uri = Uri.parse("online://${Uri.encode(track.pluginId)}/${Uri.encode(track.platformId)}"),
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs ?: 0,
        format = "mp3",
        mimeType = "audio/mpeg",
        folderPath = "",
        artworkUrl = track.artworkUrl,
    )

    /** 读取音质档位：按 pluginValue 匹配；旧档位值（128k/320k/hq/flac/lossless 等）映射后迁移一次。 */
    private fun loadStreamQuality(key: String, default: StreamQuality): StreamQuality {
        val stored = settingsPreferences.getString(key, null) ?: return default
        StreamQuality.entries.firstOrNull { it.pluginValue.equals(stored, ignoreCase = true) }?.let { return it }
        val migrated = when (stored.lowercase()) {
            "128k" -> StreamQuality.Low
            "320k", "hq" -> StreamQuality.Standard
            "flac", "sq" -> StreamQuality.High
            "lossless", "hires", "vinyl" -> StreamQuality.Super
            else -> null
        } ?: return default
        settingsPreferences.edit().putString(key, migrated.pluginValue).apply()
        return migrated
    }

    /** 读取 10 段均衡器设置；旧 5 段设置就近映射迁移一次（62/250/1k/4k/16k 对应旧 60/230/910/3.6k/14k）。 */
    private fun loadEqBands(): List<Float> {
        if (!settingsPreferences.getBoolean("eq_10_migrated", false)) {
            val legacy = (0..4).map { settingsPreferences.getFloat("eq_band_$it", 0f) }
            val migrated = migrateEqBands(legacy)
            settingsPreferences.edit().apply {
                migrated.forEachIndexed { i, v -> putFloat("eq_band_$i", v) }
                putBoolean("eq_10_migrated", true)
                apply()
            }
            return migrated
        }
        return (0..9).map { settingsPreferences.getFloat("eq_band_$it", 0f) }
    }

    fun setPlaybackQuality(option: StreamQualityOption) {
        settingsPreferences.edit()
            .putString("playback_quality", option.tier.pluginValue)
            .putString("playback_quality_value", option.value)
            .apply()
        _state.value = _state.value.copy(playbackQuality = option.tier, playbackQualityValue = option.value)
    }

    fun setDownloadQuality(option: StreamQualityOption) {
        settingsPreferences.edit()
            .putString("download_quality", option.tier.pluginValue)
            .putString("download_quality_value", option.value)
            .apply()
        _state.value = _state.value.copy(downloadQuality = option.tier, downloadQualityValue = option.value)
    }


    private data class CastSource(
        val url: String,
        val mimeType: String,
        val durationMs: Long,
        val refreshable: Boolean,
        /** 插件在线流所需的上游请求头，只能由手机代理持有，不能下发给 DLNA 设备。 */
        val headers: Map<String, String> = emptyMap(),
        /** 地址公开且无需附加请求头（公开直播直链）：接收端声明支持该格式时可直投，否则仍走本机代理。 */
        val directEligible: Boolean = false,
    )

    /** 插件取流的统一入口：走缓存并按请求档位降档；失败抛出由调用方提示。 */
    private suspend fun resolveOnlineMedia(
        onlineTrack: OnlineTrack,
        rawItem: Any?,
        quality: StreamQuality,
        forceRefresh: Boolean = false,
    ): MediaSource = withContext(Dispatchers.IO) {
        val source = pluginManager.sources().firstOrNull { it.id == onlineTrack.pluginId }
            ?: throw IllegalStateException("音源已删除，无法播放：${onlineTrack.sourceName.ifBlank { onlineTrack.pluginId }}")
        val plugin = pluginManager.getPlugin(source)
        val sourceOptions = _state.value.sourceQualityOptions[source.id].orEmpty()
        val requestedValue = _state.value.playbackQualityValue
            ?.takeIf { value -> sourceOptions.any { it.value == value } }
            ?: sourceOptions.firstOrNull { it.tier == quality }?.value
        val cacheKey = "${onlineTrack.pluginId}:${onlineTrack.platformId}:${requestedValue ?: quality.pluginValue}"
        if (!forceRefresh) onlineMediaCache[cacheKey]?.let { return@withContext it }
        if (onlineMediaCache.size > 64) onlineMediaCache.clear()
        val streamTrack = onlineTrackToStreamTrack(onlineTrack, rawItem)
        val item = plugin.enrichTrack(streamTrack)
        val media = plugin.getMediaSource(item, quality, requestedValue)
            ?: throw IllegalStateException("暂时无法获取播放地址，请重试")
        onlineMediaCache[cacheKey] = media
        media
    }


    private suspend fun resolveCastSource(
        track: NativeTrack,
        forceRefresh: Boolean = false,
        renderer: CastRemote? = null,
    ): CastSource {
        val onlineTrack = onlineTrackById[track.id]
        if (onlineTrack != null) {
            val resolved = resolveOnlineMedia(
                onlineTrack,
                onlineRawItemById[track.id],
                _state.value.playbackQuality,
                forceRefresh,
            )
            return CastSource(
                resolved.url,
                if (dlnaIsHlsSource(resolved.url, track.mimeType)) {
                    "application/vnd.apple.mpegurl"
                } else track.mimeType,
                track.durationMs,
                refreshable = true,
                headers = resolved.headers,
                // 插件协议只保证 URL/headers，不保证可供 DLNA 直投的 MIME/容器；统一走手机代理，
                // 既保留请求头，也让代理透传上游实际 Content-Type。
                directEligible = false,
            )
        }
        val lanAddress = LocalMediaServer.resolvePrivateLanAddress()
        if (track.uri.scheme == "baidupan") {
            val file = track.uri.host?.toLongOrNull()?.let(panFileByFsId::get)
                ?: throw IllegalStateException("网盘文件已失效，请重新打开")
            val url = withContext(Dispatchers.IO) {
                panAccessToken()
                panStreamProxy.start(file, lanAddress).also { panStreamProxy.resolveDlink() }
            }
            return CastSource(url, track.mimeType, track.durationMs, refreshable = false)
        }
        if (track.uri.scheme == "quarkpan") {
            val file = track.uri.host?.let(quarkFileByFid::get)
                ?: throw IllegalStateException("网盘文件已失效，请重新打开")
            val url = withContext(Dispatchers.IO) {
                quarkEnsureCookie()
                quarkStreamProxy.start(file, lanAddress).also { quarkStreamProxy.resolveDownloadUrl() }
            }
            return CastSource(url, track.mimeType, track.durationMs, refreshable = false)
        }
        if (isRemoteCastSource(track.uri.scheme, false)) {
            throw IllegalStateException("该在线曲目已失效")
        }
        // 音频格式兼容层（2026-09-28）：本地音频在设备 Sink 明确不含该格式、且源可实时解码时，
        // 转码为 WAV 投送（WAV 是 DLNA 音频端兼容性最好的口径）；探测失败回落原格式直通。
        val transcode = renderer != null && track.mimeType.startsWith("audio", true) && runCatching {
            withContext(Dispatchers.IO) {
                val sinkMimes = renderer.receiverSinkMimes()
                dlnaShouldTranscodeAudio(sinkMimes, track.mimeType) &&
                    DlnaAudioTranscoder(getApplication(), track.uri, DlnaTranscodeDiscardStream).probe() != null
            }
        }.getOrDefault(false)
        val servedMime = if (transcode) "audio/wav" else track.mimeType
        return CastSource(
            mediaServer.start(track, lanAddress, transcode = transcode).toString(),
            servedMime,
            track.durationMs,
            refreshable = false,
        )
    }

    private fun castTrack(track: NativeTrack, source: CastSource): NativeTrack = track.copy(
        durationMs = source.durationMs.takeIf { it > 0 } ?: track.durationMs,
        mimeType = source.mimeType.ifBlank { track.mimeType },
    )

    private suspend fun loadDlnaSource(
        renderer: CastRemote,
        track: NativeTrack,
        positionMs: Long,
        initialSource: CastSource,
        preloaded: PreloadedCast? = null,
    ): CastSource {
        // —— 预载消费（gapless）：本机会话与设备预注册都已就绪，跳过解析/建会话 ——
        if (preloaded != null) {
            if (preloaded.nextAccepted && positionMs <= 0) {
                // 设备已在预注册地址上自动接力：恢复播放即可；没接住（个别设备忽略
                // SetNextAVTransportURI）则退回完整加载，不依赖设备的自觉。
                renderer.play(_state.value.playbackSpeed)
                delay(800)
                if (!runCatching { renderer.isStopped() }.getOrDefault(true)) {
                    attachCastEvents(renderer, isVideo = false)
                    return preloaded.source
                }
                AppErrorRecorder.event("DlnaCast", "设备未自动接力预载曲目，退回完整加载")
            }
            val castTrack = castTrack(track, preloaded.source)
            renderer.load(preloaded.source.url, castTrack, isVideo = false)
            renderer.play(_state.value.playbackSpeed)
            if (castTrack.cueStartMs + positionMs > 0) renderer.seekWhenReady(castTrack.cueStartMs + positionMs)
            attachCastEvents(renderer, isVideo = false)
            castServedUrl = preloaded.source.url
            return preloaded.source
        }
        var source = initialSource
        var attempt = 0
        while (true) {
            try {
                // 直投判定（2026-09-28）：公开直播直链且设备声明支持该格式时直接交给设备；
                // 其余（签名/时效/需要请求头/设备不支持）一律经本机代理，签名地址不出本机。
                val directCast = source.directEligible && runCatching {
                    dlnaReceiverSupports(
                        renderer.receiverSinkMimes().orEmpty(),
                        source.mimeType,
                        dlnaIsHlsSource(source.url, source.mimeType),
                    )
                }.getOrDefault(false)
                val receiverSource = if (!directCast && source.refreshable) source.copy(
                    url = mediaServer.startRemote(
                        track,
                        LocalMediaServer.resolvePrivateLanAddress(),
                        LocalMediaServer.RemoteMediaSource(
                            url = source.url,
                            headers = source.headers,
                            refresh = {
                                runBlocking {
                                    runCatching { resolveCastSource(track, forceRefresh = true) }
                                        .getOrNull()
                                        ?.let { refreshed ->
                                            LocalMediaServer.RemoteMediaSource(
                                                url = refreshed.url,
                                                headers = refreshed.headers,
                                            )
                                        }
                                }
                            },
                        ),
                    ).toString(),
                ) else source
                val castTrack = castTrack(track, receiverSource)
                renderer.load(receiverSource.url, castTrack, isVideo = false)
                renderer.play(_state.value.playbackSpeed)
                if (castTrack.cueStartMs + positionMs > 0) renderer.seekWhenReady(castTrack.cueStartMs + positionMs)
                attachCastEvents(renderer, isVideo = false)
                // 记录迁移依据：上游源 + 本机代理 IP（直投不经手机，无需迁移）
                castUpstream = track to source
                castServedHost = if (directCast) null
                else runCatching { LocalMediaServer.resolvePrivateLanAddress().hostAddress }.getOrNull()
                castServedUrl = receiverSource.url
                return receiverSource
            } catch (error: Throwable) {
                if (!shouldRefreshCastSource(source.refreshable, attempt++)) throw error
                source = resolveCastSource(track, forceRefresh = true, renderer = renderer)
            }
        }
    }

    // —— 投送 gapless 预载（依赖多会话 LocalMediaServer，2026-09-28 起） ——

    /** 已预载的下一首：解析源 + 已就绪的本机预载会话地址 + 设备是否接受了预注册。 */
    private class PreloadedCast(
        val trackId: Long,
        val source: CastSource,
        val nextAccepted: Boolean,
    )

    @Volatile private var preloadedCast: PreloadedCast? = null

    /** 预载队列下一首：在线源与本地文件均可（网盘代理为单会话，暂走原路径重载）。 */
    private fun preloadCastNext(queue: List<NativeTrack>, index: Int) {
        if (preloadedCast != null) return
        val renderer = activeRemote ?: return
        val next = queue.getOrNull(index + 1) ?: return
        viewModelScope.launch {
            runCatching {
                val lanAddress = LocalMediaServer.resolvePrivateLanAddress()
                val onlineTrack = onlineTrackById[next.id]
                val source: CastSource = when {
                    onlineTrack != null -> resolveCastSource(next, renderer = renderer)
                    next.uri.scheme == "content" -> {
                        // 本地源：接收端明确不支持该格式（需转码）的曲目不预载，转码会话换曲时再建
                        val sinkMimes = runCatching { renderer.receiverSinkMimes() }.getOrNull()
                        if (dlnaShouldTranscodeAudio(sinkMimes, next.mimeType)) {
                            return@runCatching
                        }
                        CastSource(
                            mediaServer.startPreloadLocal(next, lanAddress).toString(),
                            next.mimeType, next.durationMs, refreshable = false,
                        )
                    }
                    else -> return@runCatching
                }
                val servedUrl = if (onlineTrack != null) {
                    mediaServer.startPreloadRemote(
                        next,
                        lanAddress,
                        LocalMediaServer.RemoteMediaSource(url = source.url, headers = source.headers),
                    ).toString()
                } else source.url
                val nextAccepted = runCatching {
                    renderer.preloadNext(servedUrl, castTrack(next, source.copy(url = servedUrl)))
                }.getOrDefault(false)
                preloadedCast = PreloadedCast(next.id, source.copy(url = servedUrl), nextAccepted)
                AppErrorRecorder.event("DlnaCast", "已预载下一首 devicePreload=$nextAccepted")
            }
        }
    }

    /** 换曲到达预载曲目时消费预载：会话仍在则提升为主会话并复用；否则回落正常解析。 */
    private fun consumePreloadedCast(track: NativeTrack): PreloadedCast? {
        // 记录只读一次：无论匹配与否都失效，避免陈旧记录永久阻断后续预载
        val preloaded = preloadedCast ?: return null
        preloadedCast = null
        if (preloaded.trackId != track.id) return null
        // 预载会话已被退役（跳曲/重连等）时 promote 返回 null → 走正常解析
        val token = mediaServer.promotePreload() ?: return null
        if (!mediaServer.isSessionAlive(token)) return null
        return preloaded
    }

    /** GENA 事件接线：设备暂停/恢复/停止即时同步本地状态（订阅失败静默，轮询兜底不变）。 */
    private fun attachCastEvents(renderer: CastRemote, isVideo: Boolean) {
        viewModelScope.launch {
            runCatching { renderer.attachEvents { state -> onCastTransportState(state, isVideo) } }
        }
    }

    private fun onCastTransportState(state: String, isVideo: Boolean) {
        if (isVideo) {
            when (state) {
                "PAUSED_PLAYBACK" -> if (_state.value.videoPlaying) {
                    _state.value = _state.value.copy(videoPlaying = false)
                }
                "PLAYING" -> if (!_state.value.videoPlaying && _state.value.videoCasting) {
                    _state.value = _state.value.copy(videoPlaying = true)
                }
                "STOPPED", "NO_MEDIA_PRESENT" -> if (_state.value.videoCasting && _state.value.videoPlaying) {
                    _state.value = _state.value.copy(videoPlaying = false)
                }
            }
            return
        }
        if (!_state.value.isCasting) return
        when (state) {
            "PAUSED_PLAYBACK" -> if (_state.value.playing) {
                _state.value = _state.value.copy(playing = false)
                updateMediaSession()
            }
            "PLAYING" -> if (!_state.value.playing) {
                _state.value = _state.value.copy(playing = true)
                updateMediaSession()
            }
            "STOPPED", "NO_MEDIA_PRESENT" -> {
                val current = _state.value
                if (isConfirmedRemoteCompletion(current.positionMs, current.currentTrack?.durationMs ?: 0, stopped = true)) {
                    advanceAfterStop(playbackGeneration)
                } else {
                    _state.value = current.copy(playing = false, status = "投送设备提前停止播放，请重试。")
                    updateMediaSession()
                }
            }
        }
    }

    /** 工作台成品（应用私有 file://）接入主播放器：随后用全屏播放页的「播放设备」即可投送。 */
    fun playGeneratedAudio(title: String, file: java.io.File, mimeType: String = "audio/wav") {
        if (!file.exists()) return
        val track = NativeTrack(
            id = -file.absolutePath.hashCode().toLong(),
            uri = android.net.Uri.fromFile(file),
            title = title,
            artist = "月播工作台",
            album = "",
            durationMs = 0,
            format = mimeType.substringAfter('/'),
            mimeType = mimeType,
            folderPath = file.parent.orEmpty(),
        )
        playFromQueue(listOf(track), 0)
    }

    fun playFromQueue(queue: List<NativeTrack>, index: Int) {
        if (index !in queue.indices) return
        if (_state.value.currentVideo != null) closeVideoPlayer()
        _state.value = _state.value.copy(miniPlayerDismissed = false)
        val generation = ++playbackGeneration
        player.setPlaybackCompleteListener { advanceAfterStop(generation) }
        streamSeekJob?.cancel()
        val visibleQueue = applyMetadataOverrides(queue)
        albumPlaybackContext?.takeIf { it.queueKey != queueHistoryKey(visibleQueue) }?.let { albumPlaybackContext = null }
        val track = visibleQueue[index]
        stopLyricTts()
        val forcedStartPosition = forcedStartPositionMs
        val resumePos = resumePositionFor(track)
        // 片头跳过：仅在本机全新播放（无强制起点、非续播）时，从片头秒数处开始
        val skipHeadMs = if (forcedStartPosition == null && resumePos == 0L) {
            (skipConfigFor(track).first * 1000).toLong()
        } else 0L
        val startPosition = forcedStartPosition ?: (resumePos + skipHeadMs)
        forcedStartPositionMs = null
        if (_state.value.isCasting) activeRemote?.let { renderer ->
            viewModelScope.launch {
                runCatching {
                    // 消费预载（若上一轮已为本曲解析并建好本机会话）：跳过解析与会话创建
                    val consumed = consumePreloadedCast(track)
                    val source = consumed?.source ?: resolveCastSource(track, renderer = renderer)
                    loadDlnaSource(renderer, track, startPosition, source, consumed)
                }.onSuccess { source ->
                    val currentTrack = castTrack(track, source)
                    _state.value = _state.value.copy(queue = visibleQueue, queueIndex = index, currentTrack = currentTrack, positionMs = startPosition, playing = true, lyrics = null, status = null)
                    autoAdvanceInProgress = false
                    rememberPlayback(track)
                    saveQueue()
                    updateMediaSession()
                    // 多房间：附加设备随主会话同步换曲（各自独立播放同一新地址）
                    if (castGroupList.isNotEmpty()) {
                        runCatching {
                            castGroupList.forEach { extra ->
                                runCatching {
                                    extra.load(source.url, currentTrack, isVideo = false)
                                    extra.play(_state.value.playbackSpeed)
                                }
                            }
                        }
                    }
                    preloadCastNext(visibleQueue, index)
                }.onFailure {
                    markPlaybackFailure()
                }
            }
            return
        }
        startLocalPlayback(visibleQueue, index, forcedStartPosition)
    }

    private fun startLocalPlayback(queue: List<NativeTrack>, index: Int, forcedStartPosition: Long? = null) {
        if (index !in queue.indices) return
        if (forcedStartPosition != null) forcedStartPositionMs = forcedStartPosition
        pendingLocalPlayback = queue to index
        if (_state.value.fadeTransitions && player.isPlaying()) {
            if (!localFadeTransitionInProgress) {
                localFadeTransitionInProgress = true
                if (!player.fadeOut(::completeLocalFadeTransition)) completeLocalFadeTransition()
            }
            return
        }
        pendingLocalPlayback = null
        startLocalPlaybackNow(queue, index)
    }

    private fun completeLocalFadeTransition() {
        localFadeTransitionInProgress = false
        if (autoAdvanceInProgress && consumeTrackSleepTimerAtCompletion()) {
            autoAdvanceInProgress = false
            pendingLocalPlayback = null
            return
        }
        pendingLocalPlayback?.also { (pendingQueue, pendingIndex) ->
            pendingLocalPlayback = null
            startLocalPlaybackNow(pendingQueue, pendingIndex)
        }
    }

    private fun startLocalPlaybackNow(queue: List<NativeTrack>, index: Int) {
        val track = queue[index]
        val startPosition = forcedStartPositionMs ?: resumePositionFor(track)
        forcedStartPositionMs = null
        val onlineTrack = onlineTrackById[track.id]
        if (onlineTrack != null) {
            startOnlinePlayback(queue, index, track, onlineTrack, startPosition)
            return
        }
        if (track.uri.scheme == "baidupan") {
            startBaiduPanPlayback(queue, index, track, startPosition)
            return
        }
        if (track.uri.scheme == "quarkpan") {
            startQuarkPanPlayback(queue, index, track, startPosition)
            return
        }
        runCatching { player.play(track, startPosition) }
            .onSuccess {
                player.setVolume(_state.value.volume)
                player.setSpeed(_state.value.playbackSpeed)
                val embeddedLyrics = player.lyrics()
                _state.value = _state.value.copy(
                    currentTrack = track,
                    queue = queue,
                    queueIndex = index,
                    isStreaming = false,
                    currentTrackLive = false,
                    playing = true,
                    positionMs = startPosition,
                    lyrics = null,
                    onlineResolvedQuality = null,
                    status = null,
                )
                viewModelScope.launch {
                    val local = withContext(Dispatchers.IO) { sanitizeLyrics(loadExternalLyrics(track)) }
                    var lyrics = local ?: sanitizeLyrics(embeddedLyrics)
                    // 本地音乐无歌词且已开启自动匹配时，后台从在线媒体拉取并永久保存到同目录 .lrc。
                    if (lyrics == null && _state.value.autoMatchLocalLyrics && track.uri.scheme != null &&
                        (track.uri.scheme == "content" || track.uri.scheme == "file") && !track.isVideo
                    ) {
                        // runCatching：歌词拉取失败（含登录态中途失效）绝不允许炸掉播放流程
                        val fetched = runCatching { fetchAndSaveOnlineLyrics(track) }.getOrNull()
                        if (_state.value.currentTrack?.id == track.id && !fetched.isNullOrBlank()) {
                            lyrics = fetched
                        }
                    }
                    if (_state.value.currentTrack?.id == track.id) {
                        _state.value = _state.value.copy(lyrics = lyrics)
                    }
                }
                autoAdvanceInProgress = false
                rememberPlayback(track)
                saveQueue()
                updateMediaSession()
                startProgressPolling()
                // 单曲断点续播：确保本地播放实际跳到目标位置（play 内的一次性 seek 失败时兜底重定位）。
                if (startPosition > 0) seek(startPosition)
            }
            .onFailure {
                markPlaybackFailure("播放失败，请重试。")
            }
    }

    /** 百度网盘在线播放：先乐观更新 UI，再在后台启动本地代理 → BASS 播放代理地址（支持 seek 与缓存地址续播）。 */
    private fun startBaiduPanPlayback(
        queue: List<NativeTrack>,
        index: Int,
        track: NativeTrack,
        startPosition: Long,
    ) {
        val file = track.uri.host?.toLongOrNull()?.let { fsId ->
            panFileByFsId[fsId] ?: BaiduPanFile(
                fsId = fsId,
                path = "/${track.title}.${track.format.lowercase()}",
                isDir = false,
                serverFilename = "${track.title}.${track.format.lowercase()}",
                size = track.sizeBytes,
                category = 2,
                serverMtime = 0,
            )
        }
        if (file == null) {
            markPlaybackFailure("百度网盘文件已失效，请重新打开")
            return
        }
        streamSeekJob?.cancel()
        panPlaybackJob?.cancel()
        _state.value = _state.value.copy(
            currentTrack = track,
            queue = queue,
            queueIndex = index,
            isStreaming = true,
            streamLoading = true,
            playing = true,
            positionMs = startPosition,
            lyrics = null,
            status = null,
        )
        rememberPlayback(track)
        saveQueue()
        updateMediaSession()
        startProgressPolling()
        panPlaybackJob = viewModelScope.launch {
            val proxyUrl = runCatching {
                withContext(Dispatchers.IO) {
                    panAccessToken()
                    val proxied = panStreamProxy.start(file)
                    panStreamProxy.resolveDlink()
                    proxied
                }
            }.getOrNull()
            if (proxyUrl == null || _state.value.currentTrack?.id != track.id) {
                if (proxyUrl == null && _state.value.currentTrack?.id == track.id) {
                    finishPanPlaybackFailure("百度网盘播放失败，请重新登录")
                }
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    player.playUrl(proxyUrl, track.durationMs, startPosition)
                    player.setVolume(_state.value.volume)
                    player.setSpeed(_state.value.playbackSpeed)
                }
            }.onSuccess {
                if (_state.value.currentTrack?.id != track.id) return@launch
                val currentTrack = track.copy(
                    durationMs = resolvedOnlineDuration(null, player.durationMs(), track.durationMs),
                )
                val currentQueue = queue.map { item -> if (item.id == track.id) currentTrack else item }
                _state.value = _state.value.copy(
                    currentTrack = currentTrack,
                    queue = currentQueue,
                    streamLoading = false,
                    status = null,
                )
                autoAdvanceInProgress = false
                saveQueue()
                updateMediaSession()
                if (!_state.value.playing) player.pause()
            }.onFailure {
                if (_state.value.currentTrack?.id == track.id) {
                    finishPanPlaybackFailure("百度网盘播放失败，请重试")
                }
            }
        }
    }

    private fun finishPanPlaybackFailure(message: String) {
        val wasAutoAdvancing = autoAdvanceInProgress
        autoAdvanceInProgress = false
        stopTrackSleepTimerAfterPlaybackFailure(wasAutoAdvancing)
        _state.value = _state.value.copy(
            isStreaming = false,
            streamLoading = false,
            playing = false,
            status = message,
        )
    }

    // ---------- 视频播放（独立队列，不进入音频状态） ----------

    /** 当前视频队列所属的在线剧集专辑 key；非剧集来源开播时清空，用于分页续载后把新分集追加进队列。 */
    private var videoQueueAlbumKey: String? = null

    /** 视频剧集专辑上下文：记录剧集名/来源/分集序号，供播放历史专辑页续播。有效性由 videoQueueAlbumKey 把关。 */
    private var videoAlbumContext: AlbumPlaybackContext? = null

    /** 长按临时倍速：记住按住前的倍速，松开恢复；null = 当前没有进行中的长按倍速。 */
    private var videoHoldSpeedPrevious: Double? = null

    private var videoSleepTimerJob: Job? = null
    private var videoSleepTimerGeneration = 0

    /** 本地/云端视频开始播放：先暂停音频，再打开独立视频播放器；startPositionMs 供历史断点续播。 */
    fun playVideos(tracks: List<NativeTrack>, index: Int, startPositionMs: Long = 0) {
        val videos = tracks.filter { it.isVideo }
        if (index !in videos.indices) return
        // 投送中切集（上一集/下一集/选集/直播换台）：沿用当前投送会话直接换片，不回本机。
        if (_state.value.videoCasting) {
            if (activeVideoDlna != null) {
                castSwitchVideo(videos, index, startPositionMs)
                return
            }
            // 会话已丢失：按本机路径落地并清掉失效的投送标记。
            _state.value = _state.value.copy(videoCasting = false, videoCastType = null, videoCastTarget = "")
        }
        // 非剧集来源开播时解除队列与剧集专辑的关联。
        videoQueueAlbumKey = null
        videoAlbumContext = null
        videoHoldSpeedPrevious = null
        videoBackgrounded = false
        videoWasPlayingBeforeBackground = false
        videoAutoAdvanceDoneId = -1L
        videoPanJob?.cancel()
        videoProgressJob?.cancel()
        videoSubtitleJob?.cancel()
        if (_state.value.playing) {
            player.pause()
            _state.value = _state.value.copy(playing = false)
        }
        val video = videos[index]
        val audioOnly = videoAudioOnlyUser || videoBackgrounded
        _state.value = _state.value.copy(
            miniPlayerDismissed = false,
            videoQueue = videos,
            currentVideo = video,
            videoIndex = index,
            videoPlaying = true,
            videoLoading = true,
            videoBuffering = false,
            videoLive = isLiveVideoUri(video.uri),
            videoAudioOnly = audioOnly,
            videoSubtitleCues = emptyList(),
            videoSubtitleName = null,
            videoSubtitleTracks = emptyList(),
            videoSubtitleSelectedTrackId = null,
            videoSecondarySubtitleTrackId = null,
            videoSecondarySubtitleCues = emptyList(),
            videoSubtitleLoadingTrackId = null,
            videoSecondarySubtitleLoadingTrackId = null,
            videoSubtitleAiAvailable = false,
            videoSubtitleAiLoading = false,
            videoSubtitleStatus = null,
            videoPositionMs = startPositionMs.coerceAtLeast(0),
            videoDurationMs = video.durationMs,
            videoError = null,
        )
        videoPlayer.setVideoEnabled(!audioOnly)
        updateMediaSession()
        recordPlaybackProgress(force = true, incrementCount = true)
        startVideoProgressPolling()
        loadVideoSubtitles(video)
        if (video.uri.scheme == "baidupan") {
            startBaiduPanVideoPlayback(video, startPositionMs)
        } else if (video.uri.scheme == "quarkpan") {
            startQuarkPanVideoPlayback(video, startPositionMs)
        } else {
            val useFfmpeg = isFfmpegFallbackFormat(video.title)
            videoPlayer.bindFallbackEngine(if (useFfmpeg) ffmpegVideoPlayer else null)
            _state.value = _state.value.copy(videoUsingFfmpeg = useFfmpeg)
            runCatching { videoPlayer.play(video.uri, startPositionMs.coerceAtLeast(0)) }
                .onSuccess { videoPlayer.setVolume(_state.value.volume) }
                .onFailure { videoErrorState("无法播放该视频格式") }
        }
    }

    /** 后台补全同一视频的选集队列，不重启当前播放。 */
    fun setVideoAudioOnly(enabled: Boolean) {
        videoAudioOnlyUser = enabled
        settingsPreferences.edit().putBoolean(VIDEO_AUDIO_ONLY_KEY, enabled).apply()
        val actual = enabled || videoBackgrounded
        videoPlayer.setVideoEnabled(!actual)
        if (_state.value.currentVideo != null) {
            _state.value = _state.value.copy(videoAudioOnly = actual)
        }
    }

    fun resumeVideoFromBackground() {
        val shouldResume = videoWasPlayingBeforeBackground
        videoBackgrounded = false
        val video = _state.value.currentVideo ?: return
        videoPlayer.setVideoEnabled(!videoAudioOnlyUser)
        if (shouldResume) videoPlayer.resume()
        _state.value = _state.value.copy(
            videoAudioOnly = videoAudioOnlyUser,
            videoPlaying = shouldResume || videoPlayer.isPlaying(),
            videoBuffering = videoPlayer.isBuffering(),
            videoPositionMs = videoPlayer.positionMs(),
        )
        videoWasPlayingBeforeBackground = false
        updateMediaSession(forceNotification = true)
    }

    fun loadVideoSubtitle(uri: Uri) {
        val video = _state.value.currentVideo ?: return
        takePersistableReadPermission(uri)
        settingsPreferences.edit().putString(videoSubtitleUriKey(video.id), uri.toString()).apply()
        loadVideoSubtitles(video)
    }

    /**
     * 清除当前选中的字幕。外挂字幕与 AI 定时字幕随视频重新加载，清除后从轨道列表移除；
     * B 站远程字幕保留在列表里，用户可以在字幕设置中重新选择。
     */
    fun clearVideoSubtitle() {
        val video = _state.value.currentVideo ?: return
        settingsPreferences.edit().remove(videoSubtitleUriKey(video.id)).apply()
        val state = _state.value
        val externalId = "external:${video.id}"
        videoSubtitleCueCache.remove(externalId)
        val selectedId = state.videoSubtitleSelectedTrackId ?: return
        videoSubtitleCueCache.remove(selectedId)
        val isAiTrack = selectedId.startsWith("ai:")
        val dropFromList = selectedId == externalId || isAiTrack
        _state.value = state.copy(
            videoSubtitleTracks = if (dropFromList) {
                state.videoSubtitleTracks.filterNot { it.id == selectedId }
            } else {
                state.videoSubtitleTracks
            },
            videoSubtitleSelectedTrackId = null,
            videoSubtitleCues = emptyList(),
            videoSubtitleName = null,
            videoSubtitleLoadingTrackId = null,
            videoSubtitleAiAvailable = if (isAiTrack) false else state.videoSubtitleAiAvailable,
            videoSubtitleStatus = "已清除当前字幕",
        )
    }

    fun setVideoSubtitleVisible(visible: Boolean) {
        settingsPreferences.edit().putBoolean(VIDEO_SUBTITLE_VISIBLE_KEY, visible).apply()
        _state.value = _state.value.copy(videoSubtitleVisible = visible)
    }

    fun setVideoSubtitleOffset(offsetMs: Long) {
        val value = offsetMs.coerceIn(-30_000L, 30_000L)
        settingsPreferences.edit().putLong(VIDEO_SUBTITLE_OFFSET_KEY, value).apply()
        _state.value = _state.value.copy(videoSubtitleOffsetMs = value)
    }

    fun setVideoSubtitleScale(scale: Float) {
        val value = scale.coerceIn(0.75f, 1.75f)
        settingsPreferences.edit().putFloat(VIDEO_SUBTITLE_SCALE_KEY, value).apply()
        _state.value = _state.value.copy(videoSubtitleScale = value)
    }

    fun toggleVideoSubtitleTts() {
        val enabled = !_state.value.videoSubtitleTtsEnabled
        settingsPreferences.edit().putBoolean(VIDEO_SUBTITLE_TTS_KEY, enabled).apply()
        if (enabled) {
            ensureLyricTtsReady()
            _state.value = _state.value.copy(videoSubtitleTtsEnabled = true)
            loadTtsEngines()
        } else {
            lyricTts?.stop()
            lastSpokenVideoLineIndex = -1
            lastSpokenVideoId = null
            _state.value = _state.value.copy(videoSubtitleTtsEnabled = false)
        }
    }

    fun speakCurrentVideoSubtitle() {
        val video = _state.value.currentVideo ?: return
        val cues = _state.value.videoSubtitleCues
        val index = activeVideoSubtitleIndex(cues, _state.value.videoPositionMs + _state.value.videoSubtitleOffsetMs)
        if (index >= 0) enqueueLyricTtsLine(video.id, index, cues[index].text, 0, VIDEO_SUBTITLE_TTS_SOURCE)
    }

    fun selectVideoSubtitleTrack(trackId: String?) {
        val state = _state.value
        val video = state.currentVideo ?: return
        if (trackId == null) {
            _state.value = state.copy(
                videoSubtitleSelectedTrackId = null,
                videoSubtitleCues = emptyList(),
                videoSubtitleName = null,
                videoSubtitleLoadingTrackId = null,
            )
            return
        }
        val track = state.videoSubtitleTracks.firstOrNull { it.id == trackId } ?: return
        if (track.isLocked) return
        videoSubtitleJob?.cancel()
        val cached = videoSubtitleCueCache[track.id]
        if (cached != null) {
            _state.value = state.copy(
                videoSubtitleSelectedTrackId = track.id,
                videoSubtitleCues = cached,
                videoSubtitleName = track.label,
                videoSubtitleLoadingTrackId = null,
                videoSubtitleStatus = null,
            )
            return
        }
        _state.value = state.copy(
            videoSubtitleSelectedTrackId = track.id,
            videoSubtitleCues = emptyList(),
            videoSubtitleName = track.label,
            videoSubtitleLoadingTrackId = track.id,
            videoSubtitleStatus = "正在加载字幕…",
        )
        videoSubtitleJob = viewModelScope.launch(Dispatchers.IO) {
            loadVideoSubtitleTrack(video, track, secondary = false)
        }
    }

    fun selectVideoSecondarySubtitleTrack(trackId: String?) {
        val state = _state.value
        val video = state.currentVideo ?: return
        if (trackId == null) {
            _state.value = state.copy(
                videoSecondarySubtitleTrackId = null,
                videoSecondarySubtitleCues = emptyList(),
                videoSecondarySubtitleLoadingTrackId = null,
            )
            return
        }
        val track = state.videoSubtitleTracks.firstOrNull { it.id == trackId } ?: return
        if (track.isLocked || track.id == state.videoSubtitleSelectedTrackId) return
        videoSubtitleJob?.cancel()
        val cached = videoSubtitleCueCache[track.id]
        if (cached != null) {
            _state.value = state.copy(
                videoSecondarySubtitleTrackId = track.id,
                videoSecondarySubtitleCues = cached,
                videoSecondarySubtitleLoadingTrackId = null,
            )
            return
        }
        _state.value = state.copy(
            videoSecondarySubtitleTrackId = track.id,
            videoSecondarySubtitleCues = emptyList(),
            videoSecondarySubtitleLoadingTrackId = track.id,
            videoSubtitleStatus = "正在加载副字幕…",
        )
        videoSubtitleJob = viewModelScope.launch(Dispatchers.IO) {
            loadVideoSubtitleTrack(video, track, secondary = true)
        }
    }

    fun loadAiVideoSubtitle() {
        // B 站 AI 字幕随服务端在线体系下线：入口保留但始终提示不可用。
        _state.value = _state.value.copy(videoSubtitleStatus = "当前视频没有可用的 AI 定时字幕")
    }

    fun selectVideoSubtitleCue(cueId: String) {
        val startMs = cueId.substringAfterLast(':').toLongOrNull() ?: return
        videoSeekTo((startMs - _state.value.videoSubtitleOffsetMs).coerceAtLeast(0L))
    }

    fun seekPreviousVideoSubtitle() = seekRelativeVideoSubtitle(-1)

    fun replayCurrentVideoSubtitle() = seekRelativeVideoSubtitle(0)

    fun seekNextVideoSubtitle() = seekRelativeVideoSubtitle(1)

    private fun seekRelativeVideoSubtitle(delta: Int) {
        val state = _state.value
        if (state.currentVideo == null || state.videoSubtitleCues.isEmpty()) return
        val adjustedPosition = state.videoPositionMs + state.videoSubtitleOffsetMs
        val active = activeVideoSubtitleIndex(state.videoSubtitleCues, adjustedPosition)
        val base = if (active >= 0) active else state.videoSubtitleCues.indexOfLast { it.startMs <= adjustedPosition }
        val target = when {
            delta < 0 -> (if (base < 0) 0 else base - 1).coerceAtLeast(0)
            delta > 0 -> (if (base < 0) 0 else base + 1).coerceAtMost(state.videoSubtitleCues.lastIndex)
            else -> if (base < 0) 0 else base
        }
        videoSeekTo((state.videoSubtitleCues[target].startMs - state.videoSubtitleOffsetMs).coerceAtLeast(0L))
    }

    private fun loadVideoSubtitles(video: NativeTrack) {
        videoSubtitleJob?.cancel()
        videoSubtitleCueCache.clear()
        videoSubtitleJob = viewModelScope.launch(Dispatchers.IO) {
            val boundUri = settingsPreferences.getString(videoSubtitleUriKey(video.id), null)?.let(Uri::parse)
            val bound = boundUri?.let { uri -> readVideoSubtitle(uri, uriDisplayName(uri)) }
                ?.takeIf { it.second.isNotEmpty() }
            val result = bound ?: findLocalVideoSubtitle(video)
            if (_state.value.currentVideo?.id != video.id) return@launch
            val externalTrack = result?.let {
                VideoSubtitleTrack(
                    id = "external:${video.id}",
                    label = it.first,
                    source = VideoSubtitleSource.External,
                )
            }
            result?.second?.let { videoSubtitleCueCache[externalTrack!!.id] = it }
            _state.value = _state.value.copy(
                videoSubtitleTracks = externalTrack?.let(::listOf).orEmpty(),
                videoSubtitleSelectedTrackId = externalTrack?.id,
                videoSubtitleCues = result?.second.orEmpty(),
                videoSubtitleName = result?.first,
                videoSubtitleStatus = null,
                videoSubtitleAiAvailable = false,
            )
            lastSpokenVideoLineIndex = -1
            lastSpokenVideoId = video.id
        }
    }

    private suspend fun loadVideoSubtitleTrack(
        video: NativeTrack,
        track: VideoSubtitleTrack,
        secondary: Boolean,
    ) {
        runCatching { fetchVideoSubtitleCues(track) }
            .onSuccess { cues ->
                videoSubtitleCueCache[track.id] = cues
                if (_state.value.currentVideo?.id != video.id) return@onSuccess
                _state.value = if (secondary) {
                    _state.value.copy(
                        videoSecondarySubtitleCues = cues,
                        videoSecondarySubtitleLoadingTrackId = null,
                        videoSubtitleStatus = null,
                    )
                } else {
                    _state.value.copy(
                        videoSubtitleCues = cues,
                        videoSubtitleLoadingTrackId = null,
                        videoSubtitleStatus = null,
                    )
                }
            }
            .onFailure { error ->
                if (_state.value.currentVideo?.id != video.id) return@onFailure
                _state.value = if (secondary) {
                    _state.value.copy(
                        videoSecondarySubtitleLoadingTrackId = null,
                        videoSubtitleStatus = error.message ?: "副字幕加载失败",
                    )
                } else {
                    _state.value.copy(
                        videoSubtitleLoadingTrackId = null,
                        videoSubtitleStatus = error.message ?: "字幕加载失败",
                    )
                }
            }
    }

    private suspend fun fetchVideoSubtitleCues(track: VideoSubtitleTrack): List<VideoSubtitleCue> =
        when (track.source) {
            VideoSubtitleSource.External,
            VideoSubtitleSource.AiTranscript,
            -> videoSubtitleCueCache[track.id].orEmpty()
            // B 站随服务端在线体系一并下线：历史轨道不再能取到内容。
            VideoSubtitleSource.Bilibili -> error("该字幕来源已不可用")
        }

    private fun readVideoSubtitle(uri: Uri, name: String): Pair<String, List<VideoSubtitleCue>>? = runCatching {
        val raw = resolver.openInputStream(uri)?.use { decodeText(it.readBytes()) } ?: return null
        name to parseVideoSubtitles(name, raw)
    }.getOrNull()

    private fun findLocalVideoSubtitle(video: NativeTrack): Pair<String, List<VideoSubtitleCue>>? {
        // 与歌词/封面直读同款守卫：无所有文件访问时不做裸文件系统扫描，回退手动选字幕。
        val canDirectRead = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        if (!canDirectRead) return null
        val directory = when (video.uri.scheme?.lowercase()) {
            "file" -> video.uri.path?.let(::File)?.parentFile
            else -> video.folderPath.takeIf(String::isNotBlank)?.let {
                File(Environment.getExternalStorageDirectory(), it)
            }
        } ?: return null
        val base = video.title.substringBeforeLast('.', video.title)
        val file = directory.listFiles()?.firstOrNull {
            it.isFile && isVideoSubtitleFileName(it.name) &&
                it.name.substringBeforeLast('.').equals(base, true)
        } ?: return null
        return runCatching { file.name to parseVideoSubtitles(file.name, decodeText(file.readBytes())) }
            .getOrNull()
            ?.takeIf { it.second.isNotEmpty() }
    }

    private fun isVideoSubtitleFileName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("srt", "ass", "ssa", "vtt", "lrc", "ttml", "xml")

    private fun followVideoSubtitleLine(positionMs: Long) {
        if (!_state.value.videoSubtitleTtsEnabled) return
        val video = _state.value.currentVideo ?: return
        val cues = _state.value.videoSubtitleCues
        val index = activeVideoSubtitleIndex(cues, positionMs + _state.value.videoSubtitleOffsetMs)
        if (index < 0 || index == lastSpokenVideoLineIndex && video.id == lastSpokenVideoId) return
        if (cues[index].text.isBlank()) return
        ensureLyricTtsReady()
        if (lyricTts?.isReady() == true) enqueueLyricTtsLine(video.id, index, cues[index].text, 0, VIDEO_SUBTITLE_TTS_SOURCE)
    }

    fun updateVideoQueue(tracks: List<NativeTrack>, index: Int) {
        val videos = tracks.filter { it.isVideo }
        val state = _state.value
        if (!canUpdateVideoQueue(state.currentVideo?.id, videos.map(NativeTrack::id), index)) return
        val current = videos[index]
        _state.value = state.copy(
            videoQueue = videos,
            currentVideo = current,
            videoIndex = index,
            videoDurationMs = current.durationMs.takeIf { it > 0 } ?: state.videoDurationMs,
        )
        updateMediaSession()
    }

    /** 在线剧集播放失败兜底：不再有服务端在线剧集，直接向用户报错。 */
    private fun handleVideoPlaybackError(message: String) {
        videoErrorState(message)
    }

    /** 云端视频在线播放：本地代理 URL → ExoPlayer（代理已有 Range 支持，可拖动进度）。 */
    private fun startBaiduPanVideoPlayback(track: NativeTrack, startPositionMs: Long = 0) {
        val file = track.uri.host?.toLongOrNull()?.let { fsId ->
            panFileByFsId[fsId] ?: BaiduPanFile(
                fsId = fsId,
                path = "/${track.title}.${track.format.lowercase()}",
                isDir = false,
                serverFilename = "${track.title}.${track.format.lowercase()}",
                size = track.sizeBytes,
                category = 2,
                serverMtime = 0,
            )
        }
        if (file == null) {
            videoErrorState("百度网盘文件已失效，请重新打开")
            return
        }
        videoPanJob?.cancel()
        videoPanJob = viewModelScope.launch {
            val proxyUrl = runCatching {
                withContext(Dispatchers.IO) {
                    panAccessToken()
                    val proxied = panStreamProxy.start(file)
                    panStreamProxy.resolveDlink()
                    proxied
                }
            }.getOrNull()
            if (proxyUrl == null || _state.value.currentVideo?.id != track.id) {
                if (proxyUrl == null && _state.value.currentVideo?.id == track.id) {
                    videoErrorState("百度网盘视频播放失败，请重新登录")
                }
                return@launch
            }
            // ExoPlayer 要求在同一 Looper 线程访问（主线程）；仅代理获取走 IO。
            runCatching {
                videoPlayer.playUrl(proxyUrl, startPositionMs.coerceAtLeast(0))
                videoPlayer.setVolume(_state.value.volume)
            }.onSuccess {
                if (_state.value.currentVideo?.id == track.id) {
                    _state.value = _state.value.copy(videoLoading = false, videoError = null)
                }
            }.onFailure {
                if (_state.value.currentVideo?.id == track.id) {
                    videoErrorState("百度网盘视频播放失败，请重试")
                }
            }
        }
    }

    /** 夸克在线音频：本地代理 URL → BASS。 */
    private fun startQuarkPanPlayback(
        queue: List<NativeTrack>,
        index: Int,
        track: NativeTrack,
        startPosition: Long,
    ) {
        val file = track.uri.host?.let { fid ->
            quarkFileByFid[fid] ?: QuarkPanFile(
                fid = fid,
                pdirFid = "0",
                fileName = "${track.title}.${track.format.lowercase()}",
                isDir = false,
                size = track.sizeBytes,
                category = 2,
                updatedAt = 0,
            )
        }
        if (file == null) {
            markPlaybackFailure("夸克网盘文件已失效，请重新打开")
            return
        }
        streamSeekJob?.cancel()
        quarkPlaybackJob?.cancel()
        _state.value = _state.value.copy(
            currentTrack = track,
            queue = queue,
            queueIndex = index,
            isStreaming = true,
            streamLoading = true,
            playing = true,
            positionMs = startPosition,
            lyrics = null,
            status = null,
        )
        rememberPlayback(track)
        saveQueue()
        updateMediaSession()
        startProgressPolling()
        quarkPlaybackJob = viewModelScope.launch {
            val proxyUrl = runCatching {
                withContext(Dispatchers.IO) {
                    quarkEnsureCookie()
                    val proxied = quarkStreamProxy.start(file)
                    quarkStreamProxy.resolveDownloadUrl()
                    proxied
                }
            }.getOrNull()
            if (proxyUrl == null || _state.value.currentTrack?.id != track.id) {
                if (proxyUrl == null && _state.value.currentTrack?.id == track.id) {
                    finishPanPlaybackFailure("夸克网盘播放失败，请重新登录")
                }
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    player.playUrl(proxyUrl, track.durationMs, startPosition)
                    player.setVolume(_state.value.volume)
                    player.setSpeed(_state.value.playbackSpeed)
                }
            }.onSuccess {
                if (_state.value.currentTrack?.id != track.id) return@launch
                val currentTrack = track.copy(
                    durationMs = resolvedOnlineDuration(null, player.durationMs(), track.durationMs),
                )
                val currentQueue = queue.map { item -> if (item.id == track.id) currentTrack else item }
                _state.value = _state.value.copy(
                    currentTrack = currentTrack,
                    queue = currentQueue,
                    streamLoading = false,
                    status = null,
                )
                autoAdvanceInProgress = false
                saveQueue()
                updateMediaSession()
                if (!_state.value.playing) player.pause()
            }.onFailure {
                if (_state.value.currentTrack?.id == track.id) {
                    finishPanPlaybackFailure("夸克网盘播放失败，请重试")
                }
            }
        }
    }

    /** 夸克云端视频在线播放：本地代理 URL → ExoPlayer。 */
    private fun startQuarkPanVideoPlayback(track: NativeTrack, startPositionMs: Long = 0) {
        val file = track.uri.host?.let { fid ->
            quarkFileByFid[fid] ?: QuarkPanFile(
                fid = fid,
                pdirFid = "0",
                fileName = "${track.title}.${track.format.lowercase()}",
                isDir = false,
                size = track.sizeBytes,
                category = 2,
                updatedAt = 0,
            )
        }
        if (file == null) {
            videoErrorState("夸克网盘文件已失效，请重新打开")
            return
        }
        videoPanJob?.cancel()
        videoPanJob = viewModelScope.launch {
            // 先解析下载地址并跟随重定向拿到最终 CDN 地址（用 Range 探测，不整段下载）。
            val directUrl = runCatching {
                withContext(Dispatchers.IO) {
                    quarkEnsureCookie()
                    val cookie = quarkApi.cookie
                    val url = quarkApi.getDownloadUrl(file.fid)
                    openQuarkPanDownloadConnection(downloadUrl = url, cookie = cookie, range = "bytes=0-0")
                        .let { conn ->
                            try {
                                if (conn.responseCode in 200..299) conn.url.toString() else null
                            } finally {
                                conn.disconnect()
                            }
                        }
                }
            }.getOrNull()
            if (_state.value.currentVideo?.id != track.id) return@launch
            if (directUrl != null) {
                Log.d("QuarkPan", "quark video direct play: ${directUrl.take(80)}")
                val headers = mapOf(
                    "User-Agent" to QUARK_PAN_UA,
                    "Referer" to QUARK_PAN_REFERER,
                    "Cookie" to quarkApi.cookie,
                )
                runCatching {
                    videoPlayer.playUrl(directUrl, startPositionMs, headers)
                    videoPlayer.setVolume(_state.value.volume)
                }.onSuccess {
                    if (_state.value.currentVideo?.id == track.id) {
                        _state.value = _state.value.copy(videoLoading = false, videoError = null)
                    }
                }.onFailure {
                    if (_state.value.currentVideo?.id == track.id) {
                        startQuarkPanVideoPlaybackViaProxy(
                            file,
                            track,
                            videoResumePosition(
                                _state.value.videoPositionMs,
                                videoPlayer.positionMs(),
                                _state.value.videoDurationMs,
                            ),
                        )
                    }
                }
            } else {
                Log.w("QuarkPan", "quark video direct resolve failed, fallback to proxy")
                startQuarkPanVideoPlaybackViaProxy(file, track, startPositionMs)
            }
        }
    }

    /** 夸克视频经本地代理播放（直连失败时的回退路径）。 */
    private fun startQuarkPanVideoPlaybackViaProxy(file: QuarkPanFile, track: NativeTrack, startPositionMs: Long) {
        videoPanJob?.cancel()
        videoPanJob = viewModelScope.launch {
            val proxyUrl = runCatching {
                withContext(Dispatchers.IO) {
                    quarkEnsureCookie()
                    val proxied = quarkStreamProxy.start(file)
                    quarkStreamProxy.resolveDownloadUrl()
                    proxied
                }
            }.getOrNull()
            if (proxyUrl == null || _state.value.currentVideo?.id != track.id) {
                if (proxyUrl == null && _state.value.currentVideo?.id == track.id) {
                    videoErrorState("夸克网盘视频播放失败，请重新登录")
                }
                return@launch
            }
            runCatching {
                videoPlayer.playUrl(proxyUrl, startPositionMs)
                videoPlayer.setVolume(_state.value.volume)
            }.onSuccess {
                if (_state.value.currentVideo?.id == track.id) {
                    _state.value = _state.value.copy(videoLoading = false, videoError = null)
                }
            }.onFailure {
                if (_state.value.currentVideo?.id == track.id) {
                    videoErrorState("夸克网盘视频播放失败，请重试")
                }
            }
        }
    }
    fun videoToggle() {
        if (_state.value.videoCasting) {
            videoCastToggle()
            return
        }
        val state = _state.value
        if (state.currentVideo == null) return

        if (state.videoPlaying || state.videoBuffering) {
            videoPlayer.pause()
            if (videoBackgrounded) videoWasPlayingBeforeBackground = false
            _state.value = state.copy(videoPlaying = false, videoBuffering = false, videoLoading = false)
        } else {
            videoPlayer.resume()
            if (videoBackgrounded) videoWasPlayingBeforeBackground = true
            _state.value = state.copy(videoPlaying = true, videoError = null)
        }
        updateMediaSession()
    }

    fun videoSeekTo(ms: Long) {
        if (_state.value.currentVideo == null) return
        if (_state.value.videoCasting) {
            videoCastSeekTo(ms)
            return
        }
        videoPlayer.seekTo(ms)
        _state.value = _state.value.copy(videoPositionMs = ms.coerceAtLeast(0))
        updateMediaSession()
    }

    /** 视频音量：投送中调节的是电视端音量（与音频投送 setVolume 同一口径），本机播放调节播放器。 */
    fun setVideoVolume(value: Float) {
        val target = value.coerceIn(0f, 1f)
        val renderer = activeVideoDlna
        if (_state.value.videoCasting && renderer != null) {
            viewModelScope.launch {
                runCatching { renderer.setVolume(target) }
                    .onSuccess { supported ->
                        if (supported) {
                            _state.value = _state.value.copy(volume = target, videoError = null)
                            settingsPreferences.edit().putFloat("volume", target).apply()
                        } else {
                            _state.value = _state.value.copy(videoError = "此播放设备不支持音量控制。")
                        }
                    }
                    .onFailure { _state.value = _state.value.copy(videoError = "此播放设备不支持音量控制。") }
            }
            return
        }
        videoPlayer.setVolume(target)
    }

    /** 视频倍速：仅 ExoPlayer 支持；FFmpeg 兜底与 DLNA 投送中不支持，档位保持不变。
     *  作用于当前播放会话，不持久化（最终版行为：倍速只在播放页调节）。 */
    fun setVideoSpeed(speed: Double) {
        val state = _state.value
        if (state.videoCasting || state.videoUsingFfmpeg) return
        val target = videoPlaybackRate(speed)
        videoPlayer.setSpeed(target)
        _state.value = state.copy(videoSpeed = target)
    }

    /** 长按播放器区域的临时倍速（默认 2x）：记住原倍速，松开时 endVideoHoldSpeed 恢复；不写入偏好。 */
    fun startVideoHoldSpeed(speed: Double = VIDEO_HOLD_SPEED) {
        val state = _state.value
        if (state.currentVideo == null || state.videoCasting || state.videoUsingFfmpeg) return
        if (videoHoldSpeedPrevious != null) return
        videoHoldSpeedPrevious = state.videoSpeed
        val target = videoPlaybackRate(speed)
        videoPlayer.setSpeed(target)
        _state.value = state.copy(videoSpeed = target)
    }

    fun endVideoHoldSpeed() {
        val previous = videoHoldSpeedPrevious ?: return
        videoHoldSpeedPrevious = null
        val state = _state.value
        if (state.videoCasting || state.videoUsingFfmpeg) return
        videoPlayer.setSpeed(previous)
        _state.value = state.copy(videoSpeed = previous)
    }

    /** 视频定时播放：到点暂停当前视频（与音频睡眠定时相互独立）。minutes<=0 视为取消。 */
    fun startVideoSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelVideoSleepTimer()
            return
        }
        stopVideoSleepTimerJob()
        val generation = videoSleepTimerGeneration
        val totalMs = minutes.toLong() * 60_000L
        _state.value = _state.value.copy(videoSleepTimerTotalMs = totalMs, videoSleepTimerRemainingMs = totalMs)
        videoSleepTimerJob = viewModelScope.launch {
            val startedElapsedMs = SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive && generation == videoSleepTimerGeneration) {
                val remaining = remainingSleepTimerMs(totalMs, startedElapsedMs, SystemClock.elapsedRealtime())
                if (generation != videoSleepTimerGeneration) return@launch
                _state.value = _state.value.copy(videoSleepTimerRemainingMs = remaining)
                if (remaining <= 0) {
                    finishVideoSleepTimer()
                    return@launch
                }
                delay(minOf(500L, remaining))
            }
        }
    }

    fun cancelVideoSleepTimer() {
        stopVideoSleepTimerJob()
        if (_state.value.videoSleepTimerTotalMs == 0L) return
        _state.value = _state.value.copy(videoSleepTimerTotalMs = 0, videoSleepTimerRemainingMs = 0)
    }

    private fun stopVideoSleepTimerJob() {
        videoSleepTimerGeneration += 1
        videoSleepTimerJob?.cancel()
        videoSleepTimerJob = null
    }

    private fun finishVideoSleepTimer() {
        stopVideoSleepTimerJob()
        if (_state.value.currentVideo == null) {
            _state.value = _state.value.copy(videoSleepTimerTotalMs = 0, videoSleepTimerRemainingMs = 0)
            return
        }
        videoPlayer.pause()
        _state.value = _state.value.copy(
            videoSleepTimerTotalMs = 0,
            videoSleepTimerRemainingMs = 0,
            videoPlaying = false,
            videoBuffering = false,
        )
        updateMediaSession()
    }

    fun videoPrevious() {
        // 投送中也允许切集：playVideos 内部路由到当前投送会话（与音频 previous/next 同构）。
        val state = _state.value
        val index = videoPreviousIndex(state.videoIndex) ?: return
        playVideos(state.videoQueue, index)
    }

    fun videoNext() {
        val state = _state.value
        val index = videoNextIndex(state.videoIndex, state.videoQueue.size - 1) ?: return
        playVideos(state.videoQueue, index)
    }

    fun closeVideoPlayer() {
        // 关闭前把最后进度强制写入播放历史，保证断点续播位置尽量新。
        if (_state.value.currentVideo != null && !_state.value.videoCasting) {
            recordPlaybackProgress(force = true)
        }
        stopVideoSleepTimerJob()
        _state.value = _state.value.copy(videoSleepTimerTotalMs = 0, videoSleepTimerRemainingMs = 0)
        videoAlbumContext = null
        if (_state.value.videoCasting) {
            activeVideoDlna?.let { renderer -> viewModelScope.launch { runCatching { renderer.stop() } } }
            mediaServer.stop()
            activeVideoDlna?.dispose()
            activeVideoDlna = null
        }
        videoPanJob?.cancel()
        videoProgressJob?.cancel()
        videoPlayer.stopAndClear()
        videoPlayer.bindFallbackEngine(null)
        _state.value = _state.value.copy(
            videoQualityEntryVisible = false,
            videoQualityOptions = emptyList(),
            videoQueue = emptyList(),
            currentVideo = null,
            videoIndex = -1,
            videoPlaying = false,
            videoPositionMs = 0,
            videoDurationMs = 0,
            videoLoading = false,
            videoError = null,
            videoCasting = false,
            videoCastType = null,
            videoCastTarget = "",
            videoCastLoading = false,
            videoUsingFfmpeg = false,
        )
        videoBackgrounded = false
        videoWasPlayingBeforeBackground = false
        updateMediaSession()
    }

    /** 系统锁屏/切后台时保留播放状态，只关闭画面轨，让 MediaSession 继续输出音频。 */
    fun enterVideoBackground() {
        val state = _state.value
        if (state.currentVideo != null && !state.videoCasting) {
            videoWasPlayingBeforeBackground = shouldKeepVideoAudioPlaying(
                statePlaying = state.videoPlaying,
                enginePlaying = videoPlayer.isPlaying(),
            )
            videoBackgrounded = true
            videoPlayer.setVideoEnabled(false)
            if (videoWasPlayingBeforeBackground) videoPlayer.resume()
            _state.value = _state.value.copy(
                videoAudioOnly = true,
                videoPlaying = videoWasPlayingBeforeBackground,
                videoBuffering = videoPlayer.isBuffering(),
                videoPositionMs = videoPlayer.positionMs(),
            )
            recordPlaybackProgress(force = true)
            updateMediaSession(forceNotification = true)
        }
    }

    /** 用户主动返回列表时暂停视频（保留队列与进度）。 */
    fun pauseVideoForBackground() {
        if (_state.value.currentVideo != null) {
            videoBackgrounded = false
            videoWasPlayingBeforeBackground = false
            videoPlayer.pause()
            _state.value = _state.value.copy(videoPlaying = false)
            updateMediaSession(forceNotification = true)
        }
    }

    // ---------- 视频投送（本地与网盘视频 → DLNA） ----------

    /** 本地视频投送到 DLNA：暂停本机 → 临时 HTTP 服务 → 设备播放；云端视频受代理限制暂不支持。 */
    private suspend fun resolveVideoDlnaSource(video: NativeTrack, renderer: DlnaRendererController? = null): String {
        val lanAddress = LocalMediaServer.resolvePrivateLanAddress()
        // 视频格式兼容层（2026-09-28）：接收端不支持源容器（MKV/MOV 等）、但支持 MP4，
        // 且源编码（H.264/HEVC+AAC）可流复制时 → 先转封装为临时 MP4 再投送（seek/Range 完整）。
        val sinkMimes = if (renderer != null && android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching { renderer.receiverSinkMimes() }.getOrNull()
        } else null
        fun wantsRemux(containerMime: String): Boolean =
            sinkMimes != null && containerMime.startsWith("video/") &&
                !dlnaReceiverSupports(sinkMimes, containerMime, isHls = false) &&
                dlnaReceiverSupports(sinkMimes, "video/mp4", isHls = false)
        fun wantsTsStreaming(containerMime: String): Boolean =
            sinkMimes != null && containerMime.startsWith("video/") &&
                !dlnaReceiverSupports(sinkMimes, containerMime, isHls = false) &&
                dlnaReceiverSupports(sinkMimes, "video/mp2t", isHls = true)
        suspend fun remuxToFile(sourceUri: Uri, headers: Map<String, String>): Uri {
            val output = File(getApplication<MusicApplication>().cacheDir, "dlna_remux_${System.currentTimeMillis()}.mp4")
            runCatching { remuxTempFile?.delete() }
            _state.value = _state.value.copy(status = "视频容器不兼容，正在转封装（大文件需要一些时间）…")
            val outerContext = currentCoroutineContext()
            withContext(Dispatchers.IO) {
                val remuxer = DlnaVideoRemuxer(getApplication(), sourceUri, headers)
                remuxer.remuxTo(output) { !outerContext.isActive }
            }
            remuxTempFile = output
            AppErrorRecorder.event("DlnaCast", "视频已转封装 size=${output.length()}")
            return Uri.fromFile(output)
        }
        return when (video.uri.scheme) {
            "content" -> {
                // 流式 TS 优先：即转即推、零等待（H.264+AAC 源且设备支持 TS）
                if (wantsTsStreaming(video.mimeType.ifBlank { "video/mp4" }) &&
                    withContext(Dispatchers.IO) {
                        DlnaTsStreamPump(getApplication(), video.uri, emptyMap()).probePlayable()
                    }
                ) {
                    return mediaServer.startRemux(video, lanAddress, null).toString()
                }
                if (wantsRemux(video.mimeType.ifBlank { "video/mp4" })) {
                    val remuxer = DlnaVideoRemuxer(getApplication(), video.uri)
                    if (withContext(Dispatchers.IO) { remuxer.probe() }) {
                        val remuxedUri = remuxToFile(video.uri, emptyMap())
                        return mediaServer.start(
                            video.copy(uri = remuxedUri, mimeType = "video/mp4"), lanAddress,
                        ).toString()
                    }
                }
                mediaServer.start(video, lanAddress).toString()
            }
            "baidupan" -> {
                val file = video.uri.host?.toLongOrNull()?.let(panFileByFsId::get)
                    ?: throw IllegalStateException("网盘文件已失效，请重新打开")
                withContext(Dispatchers.IO) {
                    panAccessToken()
                    panStreamProxy.start(file, lanAddress).also { panStreamProxy.resolveDlink() }
                }
            }
            "quarkpan" -> {
                val file = video.uri.host?.let(quarkFileByFid::get)
                    ?: throw IllegalStateException("网盘文件已失效，请重新打开")
                withContext(Dispatchers.IO) {
                    quarkEnsureCookie()
                    quarkStreamProxy.start(file, lanAddress).also { quarkStreamProxy.resolveDownloadUrl() }
                }
            }
            "onlinevideo", "bilibili", "mv" -> {
                // 服务端在线剧集 / B 站 / MV 已随服务端体系下线：无直链可投。
                throw IllegalStateException("此视频来源已下线，暂不支持投送")
            }
            else -> throw IllegalStateException("此视频暂不支持投送")
        }
    }

    /**
     * 投送中切集：沿用当前投送会话把新一集解析后直接加载到设备（自动连播/上一集/下一集/选集共用），
     * 与音频投送 playFromQueue 的换曲分支同构——不回本机、不重建会话。
     * 切换期间 currentVideo 保持旧集：加载成功才落位新集，失败时旧集状态原样保留（自动连播闸也不连锁）。
     */
    private fun castSwitchVideo(videos: List<NativeTrack>, index: Int, startPositionMs: Long) {
        val renderer = activeVideoDlna ?: return
        if (_state.value.videoLoading) return
        val video = videos[index]
        viewModelScope.launch {
            _state.value = _state.value.copy(
                videoLoading = true,
                videoBuffering = false,
                videoError = null,
                status = "正在切换到 ${video.title}",
                // 字幕是本机画面叠加：投送画面由电视渲染，切集后清掉旧分集的字幕状态（不加载新字幕）。
                videoSubtitleCues = emptyList(),
                videoSubtitleName = null,
                videoSubtitleTracks = emptyList(),
                videoSubtitleSelectedTrackId = null,
                videoSecondarySubtitleTrackId = null,
                videoSecondarySubtitleCues = emptyList(),
                videoSubtitleLoadingTrackId = null,
                videoSecondarySubtitleLoadingTrackId = null,
                videoSubtitleAiAvailable = false,
                videoSubtitleAiLoading = false,
                videoSubtitleStatus = null,
            )
            videoSubtitleJob?.cancel()
            runCatching {
                videoPlayer.pause()
                val source = resolveVideoDlnaSource(video, renderer)
                renderer.load(source, video, isVideo = true)
                renderer.play(1.0)
                if (startPositionMs > 0) renderer.seekWhenReady(startPositionMs)
            }.onSuccess {
                val liveSource = isLiveVideoUri(video.uri)
                videoAutoAdvanceDoneId = -1L
                _state.value = _state.value.copy(
                    videoQueue = videos,
                    videoIndex = index,
                    currentVideo = video,
                    videoPlaying = true,
                    videoLoading = false,
                    videoBuffering = false,
                    videoLive = liveSource,
                    videoPositionMs = startPositionMs.coerceAtLeast(0),
                    videoDurationMs = video.durationMs,
                    videoError = null,
                    status = null,
                )
                startVideoCastPolling()
                updateMediaSession()
                syncVideoCastVolume(renderer)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    videoLoading = false,
                    videoPlaying = false,
                    status = null,
                    videoError = error.message?.takeIf { it.isNotBlank() } ?: "投送设备未响应换集操作，请重试或返回本机。",
                )
            }
        }
    }

    /** 投送起播/切集后把电视端音量读回音量条（与音频投送 syncDlnaVolume 同一口径）。 */
    private fun syncVideoCastVolume(renderer: CastRemote) {
        viewModelScope.launch {
            runCatching { renderer.volume() }.onSuccess { volume ->
                if (volume != null && activeVideoDlna === renderer) {
                    _state.value = _state.value.copy(volume = volume)
                }
            }
        }
    }

    fun videoStartDlna(device: DlnaDevice) {
        val video = _state.value.currentVideo ?: return
        if (_state.value.videoCastLoading || _state.value.videoCasting) return
        if (!isVideoCastSource(video.uri.scheme)) {
            _state.value = _state.value.copy(videoError = "此视频暂不支持投送")
            return
        }
        _state.value = _state.value.copy(videoCastLoading = true, videoCastTarget = device.name, videoError = null)
        viewModelScope.launch {
            runCatching {
                val position = videoPlayer.positionMs()
                videoPlayer.pause()
                val renderer = DlnaRendererController(device)
                val source = resolveVideoDlnaSource(video, renderer)
                renderer.load(source, video, isVideo = true)
                renderer.play(1.0)
                if (position > 0) renderer.seekWhenReady(position)
                activeVideoDlna = renderer
                attachCastEvents(renderer, isVideo = true)
            }.onSuccess {
                _state.value = _state.value.copy(
                    videoCasting = true, videoCastType = "dlna", videoCastTarget = device.name,
                    videoCastLoading = false, videoPlaying = true, videoPositionMs = 0, videoError = null,
                )
                startVideoCastPolling()
                activeVideoDlna?.let { syncVideoCastVolume(it) }
            }.onFailure {
                mediaServer.stop(); activeVideoDlna?.dispose(); activeVideoDlna = null
                videoPlayer.resume()
                _state.value = _state.value.copy(
                    videoCasting = false, videoCastType = null, videoCastTarget = "",
                    videoCastLoading = false, videoPlaying = false, videoError = "投送失败，请重试",
                )
            }
        }
    }

    /** 从投送设备返回本机播放，从远端位置续播。 */
    fun videoReturnToLocal() {
        val video = _state.value.currentVideo ?: return
        if (!_state.value.videoCasting || _state.value.videoCastLoading) return
        _state.value = _state.value.copy(videoCastLoading = true)
        activeVideoDlna?.let { renderer ->
            viewModelScope.launch {
                runCatching {
                    val position = renderer.positionMs().also { renderer.pause() }
                    renderer.stop()
                    mediaServer.stop()
                    renderer.dispose()
                    activeVideoDlna = null
                    position
                }.onSuccess { position -> resumeVideoLocal(video, position) }
                    .onFailure { _state.value = _state.value.copy(videoCastLoading = false, videoError = "返回本机失败，请重试") }
            }
            return
        }
    }

    private fun resumeVideoLocal(video: NativeTrack, positionMs: Long) {
        videoPlayer.play(video.uri, positionMs)
        _state.value = _state.value.copy(
            videoCasting = false, videoCastType = null, videoCastTarget = "",
            videoCastLoading = false, videoPlaying = true, videoPositionMs = positionMs, videoError = null,
        )
        startVideoProgressPolling()
    }

    /** 投送中暂停/继续：转发到投送设备。 */
    fun videoCastToggle() {
        val playing = _state.value.videoPlaying
        activeVideoDlna?.let { renderer ->
            viewModelScope.launch {
                runCatching { if (playing) renderer.pause() else renderer.play(1.0) }
                    .onSuccess {
                        _state.value = _state.value.copy(videoPlaying = !playing, videoError = null)
                        updateMediaSession()
                    }
                    .onFailure { _state.value = _state.value.copy(videoError = "投送设备未响应播放控制。") }
            }
            return
        }
    }

    fun videoCastSeekTo(ms: Long) {
        activeVideoDlna?.let { renderer ->
            viewModelScope.launch {
                runCatching { renderer.seek(ms.coerceAtLeast(0)) }
                    .onSuccess {
                        _state.value = _state.value.copy(videoPositionMs = ms.coerceAtLeast(0), videoError = null)
                        updateMediaSession()
                    }
                    .onFailure { _state.value = _state.value.copy(videoError = "投送设备未响应定位操作。") }
            }
            return
        }
    }

    /** 投送中进度轮询：从投送设备读取位置与播放状态；确认播完自动连播下一集（与音频投送一致）。 */
    private fun startVideoCastPolling() {
        videoProgressJob?.cancel()
        videoProgressJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val state = _state.value
                if (state.currentVideo == null || !state.videoCasting) break
                activeVideoDlna?.let { renderer ->
                    val positionResult = runCatching { renderer.positionMs() }
                    if (positionResult.isFailure) {
                        // 位置连续拿不到（~5 秒）：设备可能已掉线，宣告会话失败（与音频投送同一口径）
                        videoCastPollFailureCount++
                        if (videoCastPollFailureCount >= 10) {
                            videoCastPollFailureCount = 0
                            activeVideoDlna = null
                            renderer.dispose()
                            viewModelScope.launch { runCatching { renderer.stop() } }
                            mediaServer.stop()
                            _state.value = state.copy(
                                videoCasting = false, videoCastType = null, videoCastTarget = "",
                                videoPlaying = false, status = "投送设备无响应，已停止投送。",
                            )
                            updateMediaSession()
                        }
                        continue
                    }
                    videoCastPollFailureCount = 0
                    val position = positionResult.getOrNull()
                    val stopped = runCatching { renderer.isStopped() }.getOrDefault(false)
                    // 暂停状态归用户操作与 GENA 事件所有：轮询只确认「已停止」，
                    // 不能用 !stopped 把 PAUSED_PLAYBACK 打回播放中（否则暂停键永远在发暂停）。
                    val ended = stopped &&
                        isConfirmedRemoteCompletion(position ?: state.videoPositionMs, state.videoDurationMs, stopped = true)
                    when {
                        ended -> advanceVideoEpisodeIfEnded(state, reachedEnd = true)
                        stopped && !ended && state.videoPlaying && !state.videoCastLoading && !state.videoLoading ->
                            _state.value = state.copy(
                                videoPlaying = false,
                                status = "投送设备提前停止播放，请重试。",
                            )
                        else -> _state.value = state.copy(
                            videoPositionMs = position ?: state.videoPositionMs,
                            videoPlaying = if (stopped) false else state.videoPlaying,
                        )
                    }
                    updateMediaSession()
                    continue
                }
            }
        }
    }

    /** 视频自动连播（本机与投送共用）：确认播完且非直播流时切下一集；同一集只推进一次。 */
    private fun advanceVideoEpisodeIfEnded(state: MusicUiState, reachedEnd: Boolean) {
        val videoId = state.currentVideo?.id ?: return
        val next = videoNextIndex(state.videoIndex, state.videoQueue.size - 1) ?: return
        if (!shouldAutoAdvanceVideo(
                videoId = videoId,
                advancedVideoId = videoAutoAdvanceDoneId,
                reachedEnd = reachedEnd,
                isLive = state.videoLive,
                hasNextEpisode = true,
            )
        ) {
            // 直播断流或无下一集：只落到暂停态，不推进。
            if (reachedEnd) _state.value = _state.value.copy(videoPlaying = false)
            return
        }
        videoAutoAdvanceDoneId = videoId
        playVideos(state.videoQueue, next)
    }

    private fun videoErrorState(message: String) {
        if (_state.value.currentVideo == null) return
        _state.value = _state.value.copy(
            videoError = message,
            videoLoading = false,
            videoPlaying = false,
            videoBuffering = false,
        )
        updateMediaSession()
    }

    private fun startVideoProgressPolling() {
        videoProgressJob?.cancel()
        videoProgressJob = viewModelScope.launch {
            while (true) {
                val state = _state.value
                if (state.currentVideo == null) break
                val position = videoPlayer.positionMs()
                val duration = videoPlayer.durationMs()
                val playing = videoPlayer.isPlaying()
                val buffering = videoPlayer.isBuffering()
                _state.value = state.copy(
                    videoPositionMs = position,
                    videoDurationMs = if (duration > 0) duration else state.videoDurationMs,
                    videoPlaying = playing,
                    videoBuffering = buffering,
                    videoLoading = state.videoLoading && !buffering && !playing,
                )
                // 自动连播：播完且不在投送/直播时切下一集（state 为上一拍快照，播完瞬间 videoPlaying 仍为 true）。
                if (!state.videoCasting && state.videoPlaying && !buffering) {
                    advanceVideoEpisodeIfEnded(
                        state,
                        reachedEnd = hasReachedTrackEnd(position, if (duration > 0) duration else state.videoDurationMs),
                    )
                }
                followVideoSubtitleLine(position)
                val now = SystemClock.elapsedRealtime()
                if (playing != state.videoPlaying || now - lastVideoSessionUpdateAtMs >= 1_000) {
                    lastVideoSessionUpdateAtMs = now
                    updateMediaSession()
                }
                delay(videoProgressPollingDelayMs(playing))
            }
        }
    }

    /** 在线曲目播放：插件 enrichTrack → getMediaSource（按档位降档）→ playUrl（headers 透传）。 */
    private fun startOnlinePlayback(
        queue: List<NativeTrack>,
        index: Int,
        track: NativeTrack,
        onlineTrack: OnlineTrack,
        startPosition: Long,
    ) {
        if (onlineStartInProgress) return
        onlineStartInProgress = true
        pendingOnlineIndex = index
        _state.value = _state.value.copy(onlineResolving = true, onlineResolvingForward = pendingOnlineForward)
        val startedGeneration = playbackGeneration
        viewModelScope.launch {
            val resolution = runCatching {
                check(startedGeneration == playbackGeneration) { "播放内容已切换" }
                val media = resolveOnlineMedia(
                    onlineTrack,
                    onlineRawItemById[track.id],
                    _state.value.playbackQuality,
                )
                playOnlineSourceUrl(media, track.durationMs, startPosition)
                media
            }
            if (startedGeneration != playbackGeneration) return@launch
            val resolved = resolution.getOrNull()
            onlineStartInProgress = false
            if (resolved != null) {
                applyOnlineSuccess(queue, index, track, resolved, startPosition)
                return@launch
            }
            pendingOnlineIndex = null
            val wasAutoAdvancing = autoAdvanceInProgress
            autoAdvanceInProgress = false
            stopTrackSleepTimerAfterPlaybackFailure(wasAutoAdvancing)
            scheduleNetworkRecovery(track, onlineTrack)
            _state.value = _state.value.copy(
                isStreaming = false,
                playing = false,
                onlineResolving = false,
                status = resolution.exceptionOrNull()?.message ?: "暂时无法播放这首歌曲，请稍后重试",
            )
        }
    }

    /** 在线播放成功后的统一状态提交。 */
    private fun applyOnlineSuccess(
        queue: List<NativeTrack>,
        index: Int,
        track: NativeTrack,
        resolved: MediaSource,
        startPosition: Long,
    ) {
        player.setVolume(_state.value.volume)
        player.setSpeed(_state.value.playbackSpeed)
        onlineResolvedTrackId = track.id
        onlineResolvedUrl = resolved.url
        val currentTrack = track.copy(
            durationMs = resolvedOnlineDuration(null, player.durationMs(), track.durationMs),
        )
        val currentQueue = queue.map { item -> if (item.id == track.id) currentTrack else item }
        _state.value = _state.value.copy(
            currentTrack = currentTrack,
            queue = currentQueue,
            queueIndex = currentQueue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: index,
            isStreaming = true,
            currentTrackLive = false,
            playing = true,
            positionMs = startPosition,
            lyrics = null,
            onlineResolvedQuality = resolved.resolvedQuality,
            onlineResolving = false,
            status = null,
        )
        autoAdvanceInProgress = false
        pendingOnlineIndex = null
        rememberPlayback(track)
        saveQueue()
        updateMediaSession()
        startProgressPolling()
        ensureLyrics(currentTrack)
        // 历史/收藏续播：playUrl 对刚建立的网络流一次性 seek 可能未生效，复用 seek 的重试机制确保跳到目标位置。
        if (startPosition > 0) seek(startPosition)
    }

    /** 插件直链播放：headers（Referer/UA 等）必须透传给播放内核。 */
    private fun playOnlineSourceUrl(resolved: MediaSource, durationMs: Long, positionMs: Long) {
        player.playUrl(resolved.url, durationMs, positionMs, headers = resolved.headers, isLive = false)
    }

    // —— 网络恢复重试（2026-09-28）：弱网窗口起播失败不再要求用户手动重试 ——

    /** 因网络类失败起播中断、且仍是当前曲目的在线项；网络恢复后自动重试一次。 */
    private var networkRecoveryCandidate: Pair<NativeTrack, OnlineTrack>? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkRecovery() {
        if (networkCallback != null) return
        val manager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 回调在系统线程：切主线程再触碰 VM 状态
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    tryNetworkRecovery()
                    viewModelScope.launch { rebuildCastAfterNetworkChange() }
                }
            }
        }
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            networkCallback = callback
        }
    }

    /** 起播失败且仍是当前曲目的在线项：网络恢复后自动重试一次（仅传输类失败值得重试）。 */
    private fun scheduleNetworkRecovery(track: NativeTrack, onlineTrack: OnlineTrack) {
        networkRecoveryCandidate = track to onlineTrack
    }

    private fun tryNetworkRecovery() {
        val candidate = networkRecoveryCandidate ?: return
        val (track, onlineTrack) = candidate
        val state = _state.value
        when {
            // 用户已切走：候选作废
            state.currentTrack?.id != track.id -> networkRecoveryCandidate = null
            // 仍在播（或引擎自动重连已成功）：候选作废
            state.playing && !player.isStopped() -> networkRecoveryCandidate = null
            state.isCasting -> return
            else -> {
                networkRecoveryCandidate = null
                _state.value = state.copy(status = "网络已恢复，正在恢复播放…")
                startOnlinePlayback(state.queue, state.queueIndex, track, onlineTrack, state.positionMs)
            }
        }
    }

    // —— 网络切换投送会话迁移（2026-09-28 第一梯队 3） ——

    /** 当前投送的本机代理 IP 与上游源：Wi‑Fi 切换导致手机 IP 变化时据此重建会话。 */
    @Volatile private var castServedHost: String? = null
    private var castUpstream: Pair<NativeTrack, CastSource>? = null
    private var castMigrating = false

    /** Wi‑Fi 切换后投送会话迁移：本机代理改绑新 IP，重新加载并从远端位置续播；GENA 按新地址重订。 */
    private suspend fun rebuildCastAfterNetworkChange() {
        if (castMigrating || !_state.value.isCasting) return
        val remote = activeRemote ?: return
        val track = _state.value.currentTrack ?: return
        val upstream = castUpstream ?: return
        val lan = runCatching { LocalMediaServer.resolvePrivateLanAddress() }.getOrNull() ?: return
        if (lan.hostAddress == castServedHost) return  // 地址未变（蜂窝并起等），无需迁移
        castMigrating = true
        try {
            val position = runCatching { remote.positionMs() }.getOrDefault(0L)
            runCatching { remote.pause() }
            // 直投不经手机，IP 变化不影响；代理会话重建为当前 IP 的新地址
            val sessionUrl = mediaServer.startRemote(
                track,
                lan,
                LocalMediaServer.RemoteMediaSource(
                    url = upstream.second.url,
                    headers = upstream.second.headers,
                ),
            ).toString()
            castServedHost = lan.hostAddress
            remote.load(sessionUrl, castTrack(track, upstream.second.copy(url = sessionUrl)), isVideo = false)
            remote.play(_state.value.playbackSpeed)
            if (position > 0) runCatching { remote.seekWhenReady(position) }
            if (remote is DlnaRendererController) remote.dispose()  // 旧 GENA 回调指向旧 IP，按新地址重订
            attachCastEvents(remote, isVideo = false)
            _state.value = _state.value.copy(status = "网络已切换，投送已恢复。")
            AppErrorRecorder.event("DlnaCast", "网络切换后投送会话已迁移 host=${lan.hostAddress}")
        } finally {
            castMigrating = false
        }
    }

    /** 歌词按需兜底：仅当当前曲目歌词为空时经插件 getLyric 异步补取。 */
    private fun ensureLyrics(track: NativeTrack) {
        if (!_state.value.lyrics.isNullOrBlank()) return
        if (_state.value.isCasting) return
        val onlineTrack = onlineTrackById[track.id] ?: return
        val targetId = _state.value.currentTrack?.id ?: return
        viewModelScope.launch {
            val lyrics = runCatching {
                withContext(Dispatchers.IO) {
                    val plugin = pluginFor(onlineTrack.pluginId) ?: return@withContext null
                    plugin.getLyric(onlineTrackToStreamTrack(onlineTrack, onlineRawItemById[track.id]))
                }
            }.getOrNull()
            val clean = sanitizeLyrics(lyrics?.rawLrc ?: lyrics?.translation)
            if (!clean.isNullOrBlank() && _state.value.currentTrack?.id == targetId) {
                _state.value = _state.value.copy(lyrics = clean)
            }
        }
    }

    fun togglePlayback() {
        val current = _state.value.currentTrack ?: return
        if (_state.value.isCasting) {
            val remotes = castGroupAll()
            if (remotes.isNotEmpty()) {
                viewModelScope.launch {
                    val resume = !_state.value.playing
                    runCatching {
                        remotes.forEach { remote ->
                            if (resume) remote.play(_state.value.playbackSpeed) else remote.pause()
                        }
                    }
                    _state.value = _state.value.copy(playing = resume)
                    updateMediaSession()
                }
                return
            }
            _state.value = _state.value.copy(status = "投送会话已失效，请返回本机后重试。")
            return
        }
        if (_state.value.playing) {
            autoAdvanceInProgress = false
            localFadeTransitionInProgress = false
            pendingLocalPlayback = null
            recordPlaybackProgress(force = true)
            player.pause()
            stopLyricTts()
            _state.value = _state.value.copy(playing = false)
        } else {
            if (player.resume()) _state.value = _state.value.copy(playing = true)
            else playFromQueue(_state.value.queue, _state.value.queueIndex)
        }
        updateMediaSession()
    }

    fun seek(positionMs: Long) {
        if (_state.value.isStreaming && !shouldUseRemoteSeek(_state.value.isCasting)) {
            val trackId = _state.value.currentTrack?.id ?: return
            val duration = _state.value.currentTrack?.durationMs ?: 0
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            streamSeekJob?.cancel()
            streamSeekJob = viewModelScope.launch {
                var attempt = 0
                while (_state.value.currentTrack?.id == trackId && _state.value.isStreaming) {
                    if (player.seek(target)) {
                        if (_state.value.currentTrack?.id == trackId) {
                            _state.value = _state.value.copy(positionMs = target, status = null)
                            recordPlaybackProgress(force = true)
                            updateMediaSession()
                        }
                        break
                    }
                    val waitMs = networkSeekRetryDelayMs(attempt++)
                    if (waitMs == null) {
                        if (_state.value.currentTrack?.id == trackId) {
                            _state.value = _state.value.copy(status = "网络缓冲不足，暂时无法定位。")
                        }
                        break
                    }
                    delay(waitMs)
                }
            }
            return
        }
        if (_state.value.isCasting) {
            val remotes = castGroupAll()
            if (remotes.isNotEmpty()) {
                viewModelScope.launch {
                    val target = (_state.value.currentTrack?.cueStartMs ?: 0) + positionMs
                    runCatching { remotes.forEach { it.seek(target) } }
                    _state.value = _state.value.copy(positionMs = positionMs)
                }
                return
            }
            _state.value = _state.value.copy(status = "投送会话已失效，请返回本机后重试。")
            return
        }
        if (player.seek(positionMs)) {
            _state.value = _state.value.copy(positionMs = positionMs)
            recordPlaybackProgress(force = true)
            updateMediaSession()
        }
    }

    private fun seekStep(forward: Boolean) {
        val state = _state.value
        val track = state.currentTrack ?: return
        seek(seekStepPosition(state.positionMs, MEDIA_SEEK_STEP_MS, track.durationMs, forward))
    }

    private fun seekSessionStep(forward: Boolean) {
        val state = _state.value
        val video = state.currentVideo
        if (video != null) {
            videoSeekTo(seekStepPosition(state.videoPositionMs, MEDIA_SEEK_STEP_MS, state.videoDurationMs, forward))
        } else {
            seekStep(forward)
        }
    }

    /** 手动切歌：单曲循环下也换到相邻条目（自动续播才重放本曲）。
     *  在途解析未提交 queueIndex 时以 [pendingOnlineIndex] 为基准继续前进——
     *  连点下一首前进多首，而不是对同一目标反复重启解析。
     *  目标已在队列端点时直接忽略（不打断在途解析，避免残留转圈与白付重试）。 */
    fun previous() {
        pendingOnlineForward = false
        val target = manualPreviousIndex(from = pendingOnlineIndex) ?: return
        interruptStalledOnlineStart()
        playAt(target)
    }

    fun next() {
        pendingOnlineForward = true
        val target = manualNextIndex(from = pendingOnlineIndex) ?: return
        interruptStalledOnlineStart()
        playAt(target)
    }

    /** 显式切歌打断在途起播：作废其代际与占位标志，新曲目立即起播（卡在慢解析上的旧协程会被代际检查丢弃）。 */
    private fun interruptStalledOnlineStart() {
        if (onlineStartInProgress) {
            playbackGeneration++
            onlineStartInProgress = false
        }
    }

    fun setQueueMode(mode: NativeQueueMode) {
        _state.value = _state.value.copy(queueMode = mode)
        settingsPreferences.edit().putString("queue_mode", mode.name).apply()
    }

    fun moveQueueItem(index: Int, offset: Int) {
        val current = _state.value
        val target = index + offset
        if (index !in current.queue.indices || target !in current.queue.indices || index == target) return
        val queue = current.queue.toMutableList().apply {
            val item = removeAt(index)
            add(target, item)
        }
        val queueIndex = when (current.queueIndex) {
            index -> target
            in minOf(index, target)..maxOf(index, target) -> if (index < target) current.queueIndex - 1 else current.queueIndex + 1
            else -> current.queueIndex
        }
        _state.value = current.copy(queue = queue, queueIndex = queueIndex)
        saveQueue()
    }

    fun removeQueueItem(index: Int) {
        val current = _state.value
        if (index !in current.queue.indices) return
        if (index == current.queueIndex) {
            _state.value = current.copy(status = "正在播放的歌曲不能从队列中移除。")
            return
        }
        val queue = current.queue.toMutableList().apply { removeAt(index) }
        val queueIndex = if (index < current.queueIndex) current.queueIndex - 1 else current.queueIndex
        _state.value = current.copy(queue = queue, queueIndex = queueIndex)
        saveQueue()
    }

    fun saveQueueAsPlaylist(name: String) {
        val title = name.trim()
        if (title.isBlank()) {
            _state.value = _state.value.copy(status = "请输入播放列表名称。")
            return
        }
        val queue = _state.value.queue
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(status = "播放队列为空，无法保存。")
            return
        }
        val persistableQueue = queue.filter {
            shouldPersistQueueTrack(it.id, onlineTrackById.keys)
        }
        if (persistableQueue.isEmpty()) {
            _state.value = _state.value.copy(status = "在线歌曲不能保存到播放列表")
            return
        }
        val trackIds = persistableQueue.map { it.id }.filter { it >= 0 }.distinct()
        val externalTracks = persistableQueue.filter { it.id < 0 }.map(NativeTrack::toImportedAlbumTrack)
        val playlists = _state.value.playlists +
            SavedPlaylist(System.currentTimeMillis().toString(), title, trackIds, externalTracks = externalTracks)
        _state.value = _state.value.copy(playlists = playlists, status = "已保存播放列表：$title")
        savePlaylists(playlists)
    }

    fun deletePlaylist(id: String) {
        val playlists = _state.value.playlists.filterNot { it.id == id }
        _state.value = _state.value.copy(playlists = playlists, status = "已删除播放列表。")
        savePlaylists(playlists)
    }

    /** 通过兼容的 MusicFree 插件导入平台歌单，落入在线歌单域。 */
    fun importOnlinePlaylist(sourceId: String, urlLike: String, name: String) {
        val source = enabledSources().firstOrNull { it.id == sourceId }
        val url = urlLike.trim()
        val title = name.trim()
        if (source == null || url.isBlank() || title.isBlank()) {
            _state.value = _state.value.copy(status = "请选择音源并填写歌单链接和名称。")
            return
        }
        _state.value = _state.value.copy(status = "正在导入在线歌单……")
        viewModelScope.launch {
            val tracks = runCatching {
                withContext(Dispatchers.IO) { pluginManager.getPlugin(source).importMusicSheet(url) }
            }.getOrElse { error ->
                _state.value = _state.value.copy(status = "导入歌单失败：${error.message ?: "插件未返回内容"}")
                return@launch
            }
            if (tracks.isEmpty()) {
                _state.value = _state.value.copy(status = "导入歌单失败：插件没有返回曲目。")
                return@launch
            }
            val onlineTracks = tracks.map(OnlineTrack::fromStreamTrack)
            val folder = FavoriteFolder(
                id = "online-playlist-${System.currentTimeMillis()}",
                name = title,
                createdAtMs = System.currentTimeMillis(),
                scope = MediaScope.Music,
                items = onlineTracks.map(::mediaReferenceForOnlineTrack),
            )
            val folders = (_state.value.favoriteFolders.ifEmpty { loadFavoriteFolders() } + folder)
            saveFavoriteFolders(folders)
            _state.value = _state.value.copy(
                favoriteFolders = folders,
                status = "已导入在线歌单“$title”（${onlineTracks.size} 首）。",
            )
        }
    }

    fun playPlaylist(id: String) {
        val playlist = _state.value.playlists.firstOrNull { it.id == id } ?: return
        if (playlist.onlineTracks.isNotEmpty()) {
            playOnline(playlist.onlineTracks, 0)
            return
        }
        val tracksById = _state.value.tracks.associateBy { it.id }
        val queue = applyMetadataOverrides(
            playlist.trackIds.mapNotNull(tracksById::get) +
                playlist.externalTracks.map(ImportedAlbumTrack::toNativeTrack) +
                playlist.externalUris
                    .filterNot { uri -> playlist.externalTracks.any { it.uri == uri } }
                    .mapNotNull(::externalTrackFromUriString),
        )
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(status = "播放列表中的歌曲已不可用。")
            return
        }
        playFromQueue(queue, 0)
    }

    fun setAbStart() {
        if (_state.value.isCasting || _state.value.currentTrack == null) {
            _state.value = _state.value.copy(status = "A-B 循环仅支持本机播放。")
            return
        }
        val start = _state.value.positionMs
        _state.value = _state.value.copy(
            abStartMs = start,
            abEndMs = _state.value.abEndMs?.takeIf { it > start },
            status = "已设定 A 点：${formatTimestamp(start)}",
        )
    }

    fun setAbEnd() {
        val start = _state.value.abStartMs
        val end = _state.value.positionMs
        if (_state.value.isCasting || start == null) {
            _state.value = _state.value.copy(status = if (_state.value.isCasting) "A-B 循环仅支持本机播放。" else "请先设定 A 点。")
            return
        }
        if (end <= start + 500) {
            _state.value = _state.value.copy(status = "B 点必须在 A 点之后。")
            return
        }
        _state.value = _state.value.copy(abEndMs = end, status = "A-B 循环已开启。")
    }

    fun clearAbLoop() {
        _state.value = _state.value.copy(abStartMs = null, abEndMs = null, status = "已清除 A-B 循环。")
    }

    fun clearPlaybackHistory() {
        _state.value = _state.value.copy(
            playbackHistory = emptyList(),
            singlePlaybackHistory = emptyList(),
            albumPlaybackHistory = emptyList(),
            status = "已清除播放历史。",
        )
        settingsPreferences.edit()
            .remove("playback_history")
            .remove(SINGLE_PLAYBACK_HISTORY_KEY)
            .remove(ALBUM_PLAYBACK_HISTORY_KEY)
            .apply()
    }

    fun removeSinglePlaybackHistory(keys: Set<String>) {
        if (keys.isEmpty()) return
        val updated = _state.value.singlePlaybackHistory.filterNot { favoriteReferenceKey(it.reference) in keys }
        _state.value = _state.value.copy(singlePlaybackHistory = updated, status = "已删除播放历史")
        saveSinglePlaybackHistory(updated)
    }

    fun removeAlbumPlaybackHistory(keys: Set<String>) {
        if (keys.isEmpty()) return
        val updated = _state.value.albumPlaybackHistory.filterNot { it.albumKey in keys }
        _state.value = _state.value.copy(albumPlaybackHistory = updated, status = "已删除播放历史")
        saveAlbumPlaybackHistory(updated)
    }

    fun setLibrarySort(sort: LibrarySort) {
        val state = _state.value
        val ascending = if (state.librarySort == sort) !state.librarySortAscending else true
        _state.value = state.copy(librarySort = sort, librarySortAscending = ascending)
        settingsPreferences.edit()
            .putString("library_sort", sort.name)
            .putBoolean("library_sort_ascending", ascending)
            .apply()
    }

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    fun toggleFavoritesFilter() {
        _state.value = _state.value.copy(onlyFavorites = !_state.value.onlyFavorites)
    }

    fun toggleFavorite(track: NativeTrack) {
        val reference = mediaReferenceForTrack(track) ?: return
        // 收藏目标落在该条目内容域的默认收藏夹（本地曲目→月播库，网盘→月播云，在线→各自模块）。
        val folderId = favoriteDefaultFolderId(mediaScopeForSingle(reference))
        val folders = _state.value.favoriteFolders.ifEmpty { loadFavoriteFolders() }
        val updated = folders.map { folder ->
            if (folder.id != folderId) folder else {
                val exists = folder.items.any { favoriteReferenceKey(it) == favoriteReferenceKey(reference) }
                folder.copy(
                    items = if (exists) folder.items.filterNot { favoriteReferenceKey(it) == favoriteReferenceKey(reference) }
                    else (listOf(reference) + folder.items).distinctBy(::favoriteReferenceKey),
                )
            }
        }
        val added = updated.firstOrNull { it.id == folderId }?.items?.any { favoriteReferenceKey(it) == favoriteReferenceKey(reference) } == true
        saveFavoriteFolders(updated)
        _state.value = _state.value.copy(status = if (added) "已收藏 " + track.title else "已取消收藏 " + track.title)
    }

    fun createFavoriteFolder(scope: MediaScope, name: String) {
        val title = name.trim().ifBlank { return }
        val folders = _state.value.favoriteFolders.ifEmpty { loadFavoriteFolders() }
        if (folders.any { it.scope == scope && it.name == title }) {
            _state.value = _state.value.copy(status = "已存在同名收藏夹")
            return
        }
        saveFavoriteFolders(
            folders + FavoriteFolder(System.currentTimeMillis().toString(), title, System.currentTimeMillis(), scope = scope),
        )
        _state.value = _state.value.copy(status = "已新建收藏夹 " + title)
    }

    private fun mediaReferenceForLibraryAlbum(album: LibraryAlbum<NativeTrack>): MediaReference = MediaReference(
        source = LOCAL_ALBUM_SOURCE,
        key = album.key,
        title = album.name,
        artist = album.artist,
        album = album.name,
        durationMs = 0,
        format = "",
        mimeType = "application/x-local-album",
        artworkUrl = null,
    )

    // —— 一键收藏（不选收藏夹）：月听音乐/月播有声/月赏视频/月tv/月电台统一长按即收藏 ——

    /** 一键收藏/取消收藏在线曲目（歌曲/听书节目/影视分集）：落在该条目内容域的默认收藏夹。 */
    fun toggleOnlineTrackFavorite(track: OnlineTrack) {
        val reference = mediaReferenceForOnlineTrack(track)
        applyFavoriteToggle(reference, mediaScopeForSingle(reference), track.title)
    }

    /** 一键收藏/取消收藏在线合集（专辑/歌单/榜单整张一条）。 */
    fun toggleOnlineCollectionFavorite(collection: OnlineCollection) {
        val reference = mediaReferenceForOnlineCollection(collection)
        applyFavoriteToggle(reference, MediaScope.Music, collection.name)
    }

    /** 一键收藏/取消收藏本地/导入专辑（整张一条）。 */
    internal fun toggleLibraryAlbumFavorite(album: LibraryAlbum<NativeTrack>) {
        applyFavoriteToggle(mediaReferenceForLibraryAlbum(album), MediaScope.Local, album.name)
    }

    /** 一键收藏/取消收藏网盘音频文件。 */
    fun toggleCloudFileFavorite(file: CloudFile) {
        val reference = mediaReferenceForCloudFile(file) ?: return
        applyFavoriteToggle(reference, mediaScopeForSingle(reference), reference.title)
    }

    private fun applyFavoriteToggle(reference: MediaReference, scope: MediaScope, title: String) {
        val folders = _state.value.favoriteFolders.ifEmpty { loadFavoriteFolders() }
        val (updated, added) = toggleReferenceInFavorites(folders, reference, scope)
        saveFavoriteFolders(updated)
        _state.value = _state.value.copy(status = if (added) "已收藏 " + title else "已取消收藏 " + title)
    }

    fun isOnlineTrackFavorite(track: OnlineTrack): Boolean {
        val reference = mediaReferenceForOnlineTrack(track)
        return isReferenceFavorited(_state.value.favoriteFolders, reference.source, reference.key)
    }

    fun isOnlineCollectionFavorite(collection: OnlineCollection): Boolean {
        val reference = mediaReferenceForOnlineCollection(collection)
        return isReferenceFavorited(_state.value.favoriteFolders, reference.source, reference.key)
    }

    internal fun isLibraryAlbumFavorite(album: LibraryAlbum<NativeTrack>): Boolean {
        val reference = mediaReferenceForLibraryAlbum(album)
        return isReferenceFavorited(_state.value.favoriteFolders, reference.source, reference.key)
    }

    fun isCloudFileFavorite(file: CloudFile): Boolean {
        val reference = mediaReferenceForCloudFile(file) ?: return false
        return isReferenceFavorited(_state.value.favoriteFolders, reference.source, reference.key)
    }

    fun removeFavoriteItems(folderId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        val updated = _state.value.favoriteFolders.map { folder ->
            if (folder.id != folderId) folder else folder.copy(items = folder.items.filterNot { favoriteReferenceKey(it) in keys })
        }
        saveFavoriteFolders(updated)
        _state.value = _state.value.copy(status = "已删除收藏项")
    }

    fun deleteFavoriteFolder(folderId: String) {
        if (isDefaultFavoriteFolderId(folderId)) return
        val folder = _state.value.favoriteFolders.firstOrNull { it.id == folderId } ?: return
        saveFavoriteFolders(_state.value.favoriteFolders.filterNot { it.id == folderId })
        _state.value = _state.value.copy(status = "已删除收藏夹 " + folder.name)
    }

    /**
     * 播放收藏项。传 [folderId] 时把整个收藏夹建成播放队列（当前项为起点），
     * 使「下一首/上一首」能在收藏夹内连续播放；否则退化为单曲播放。
     *
     * 收藏项本身可能是「整张合集」：[onOpenOnlineCollection] / [onOpenLocalAlbum] 由界面提供，
     * 用于把用户带到对应的合集/专辑页面。
     */
    fun playFavoriteItem(
        reference: MediaReference,
        folderId: String? = null,
        onOpenOnlineCollection: ((OnlineCollection) -> Unit)? = null,
        onOpenLocalAlbum: ((String) -> Unit)? = null,
    ) {
        // 收藏的在线合集：交给界面打开合集详情（键即合集 key）。
        onlineCollectionForFavorite(reference)?.let { collection ->
            onOpenOnlineCollection?.invoke(collection) ?: openOnlineCollection(collection)
            return
        }
        // 收藏的本地/导入专辑：交给界面打开专辑详情页（键即专辑 key）。
        localAlbumKeyForFavorite(reference)?.let { albumKey ->
            onOpenLocalAlbum?.invoke(albumKey)
            return
        }
        if (folderId != null) {
            val folder = _state.value.favoriteFolders.ifEmpty { loadFavoriteFolders() }.firstOrNull { it.id == folderId }
            if (folder != null) {
                // 收藏夹可以同时收音频与视频：队列只取**与目标项同形态**的条目
                // （跨形态排在一条队列里会让音频内核去播视频流，且下标在两个内核间对不上）。
                val sameKind = folder.items.filter { referenceIsVideo(it) == referenceIsVideo(reference) }
                val resolved = sameKind.mapNotNull { item -> nativeTrackFromReference(item)?.let { item to it } }
                val index = resolved.indexOfFirst { favoriteReferenceKey(it.first) == favoriteReferenceKey(reference) }
                if (index >= 0) {
                    // 视频轨走视频内核；其余按音频队列连续播放。
                    val tracks = resolved.map { it.second }
                    if (tracks[index].isVideo) {
                        albumPlaybackContext = null
                        videoAlbumContext = null
                        playVideos(tracks, index)
                        return
                    }
                    albumPlaybackContext = null
                    playFromQueueAtPosition(tracks, index, 0L)
                    return
                }
            }
        }
        playMediaReference(reference, 0L)
    }

    /** 播放整个收藏夹：按收藏顺序建队列，从第一项开始（同形态条目才进同一条队列）。 */
    fun playFavoriteFolder(folderId: String) {
        val folder = _state.value.favoriteFolders.ifEmpty { loadFavoriteFolders() }.firstOrNull { it.id == folderId } ?: return
        onlineTracksForFavoriteFolder(folder)?.let { tracks ->
            playOnline(tracks, 0)
            return
        }
        val playable = folder.items.filterNot {
            onlineCollectionForFavorite(it) != null || localAlbumKeyForFavorite(it) != null
        }
        val wantVideo = playable.firstOrNull()?.let(::referenceIsVideo) ?: false
        val queue = playable.filter { referenceIsVideo(it) == wantVideo }.mapNotNull(::nativeTrackFromReference)
        if (queue.isEmpty()) {
            _state.value = _state.value.copy(status = "这个收藏夹还没有可播放的内容。")
            return
        }
        albumPlaybackContext = null
        if (wantVideo) {
            videoAlbumContext = null
            playVideos(queue, 0)
            return
        }
        playFromQueue(queue, 0)
    }

    /**
     * 播放一条单曲历史。
     *
     * [siblings] 为当前列表里**可见的全部单曲条目**（含目标项，顺序与界面一致）：传进来时整条列表
     * 会被建成播放队列，从目标项开始播——这样历史页点一条也能「下一首/上一首」连续往下走
     * （旧实现只播这一条，队列长度为 1，按钮形同虚设）。
     *
     * 队列只收**与目标项同形态**的条目：音频与视频走不同播放内核，混在一条队列里会让音频内核去播视频流。
     */
    fun playSingleHistory(entry: SinglePlaybackHistoryEntry, siblings: List<SinglePlaybackHistoryEntry> = emptyList()) {
        val target = nativeTrackFromReference(entry.reference) ?: return
        val sameKind = if (siblings.isEmpty()) {
            listOf(entry)
        } else {
            siblings.filter { referenceIsVideo(it.reference) == target.isVideo }
        }
        // 逐条解析并保留 (引用, 曲目) 配对：解析失败的条目会被跳过，用「引用列表」的下标去索引
        // 「曲目列表」会错位（少一条就整体前移，起播的就成了别的条目）。
        val resolved = sameKind.mapNotNull { item -> nativeTrackFromReference(item.reference)?.let { item.reference to it } }
        val index = resolved.indexOfFirst { favoriteReferenceKey(it.first) == favoriteReferenceKey(entry.reference) }
        if (index < 0) {
            playMediaReference(entry.reference, entry.positionMs)
            return
        }
        val queue = resolved.map { it.second }
        albumPlaybackContext = null
        if (target.isVideo) {
            videoAlbumContext = null
            playVideos(queue, index, entry.positionMs)
            return
        }
        playFromQueueAtPosition(queue, index, entry.positionMs)
    }

    fun playAlbumHistory(entry: AlbumPlaybackHistoryEntry) {
        val track = nativeTrackFromReference(entry.trackReference)
        // 在线合集专辑历史：按合集键恢复（pluginId/kind 编码在专辑键里），重拉失败再退化为单条续播。
        if (entry.trackReference.source == ONLINE_ALBUM_SOURCE) {
            resumeOnlineCollectionFromHistory(entry, fallbackTrack = track)
            return
        }
        if (track == null) return
        if (track.isVideo) {
            videoAlbumContext = AlbumPlaybackContext(
                key = entry.albumKey,
                title = entry.albumTitle,
                artist = entry.artist,
                source = entry.source,
                albumId = entry.albumId,
                query = entry.query,
                queueKey = queueHistoryKey(listOf(track)),
            )
            videoQueueAlbumKey = entry.albumKey
            playVideos(listOf(track), 0, entry.positionMs)
            return
        }
        // 本地/网盘等其它音频来源：单条续播（旧行为），专辑上下文按单条队列记。
        playAlbumHistorySingleFallback(entry, track)
    }

    /** 单曲引用对应的专辑历史条目（同曲既记单曲又记专辑历史）；没有则 null。 */
    private fun albumHistoryEntryForTrack(reference: MediaReference): AlbumPlaybackHistoryEntry? =
        _state.value.albumPlaybackHistory.firstOrNull { favoriteReferenceKey(it.trackReference) == favoriteReferenceKey(reference) }

    /**
     * 历史续播的合集曲目拉取：顺序翻页直到落点可判定（键/平台 ID 命中或记录下标可达），
     * 封顶 12 页；某页失败就用已取到的部分（可能为空，调用方兜底）。
     */
    private suspend fun fetchCollectionTracksForResume(
        collection: OnlineCollection,
        entry: AlbumPlaybackHistoryEntry,
    ): List<StreamTrack> {
        val online = entry.trackReference.onlineTrack
        val exactKey = online?.key
        val platformId = online?.platformId?.takeIf(String::isNotBlank)
        var tracks = emptyList<StreamTrack>()
        for (page in 1..12) {
            val result = runCatching { fetchCollectionPage(collection, page) }.getOrNull() ?: return tracks
            tracks = (tracks + result.items).distinctBy { it.pluginId + ":" + it.key }
            val covered = entry.trackIndex < tracks.size ||
                (exactKey != null && tracks.any { it.pluginId + ":" + it.key == exactKey || it.key == exactKey }) ||
                (platformId != null && tracks.any { it.key == platformId })
            if (covered || result.isEnd) return tracks
        }
        return tracks
    }

    /** 专辑历史续播的兜底：退化为单条续播（旧行为），专辑上下文按单条队列记，进度继续入历史。 */
    private fun playAlbumHistorySingleFallback(entry: AlbumPlaybackHistoryEntry, track: NativeTrack) {
        albumPlaybackContext = AlbumPlaybackContext(
            key = entry.albumKey,
            title = entry.albumTitle,
            artist = entry.artist,
            source = entry.source,
            albumId = entry.albumId,
            query = entry.query,
            queueKey = queueHistoryKey(listOf(track)),
        )
        playFromQueueAtPosition(listOf(track), 0, entry.positionMs)
    }

    /** 在线合集历史续播：重拉整张曲目入队后从记录的曲目与进度继续；恢复不了退化为单条续播。 */
    private fun resumeOnlineCollectionFromHistory(entry: AlbumPlaybackHistoryEntry, fallbackTrack: NativeTrack?) {
        val collection = onlineCollectionForHistoryEntry(entry)
        if (collection == null) {
            fallbackTrack?.let { playAlbumHistorySingleFallback(entry, it) }
            return
        }
        val generation = playbackGeneration
        viewModelScope.launch {
            val tracks = fetchCollectionTracksForResume(collection, entry)
            // 等待期间用户已另起播放：静默放弃本次续播。
            if (generation != playbackGeneration) return@launch
            val onlineTracks = tracks.map(OnlineTrack::fromStreamTrack)
            val landing = historyResumeLanding(onlineTracks, entry)
            if (tracks.isEmpty() || landing == null) {
                fallbackTrack?.let { playAlbumHistorySingleFallback(entry, it) }
                return@launch
            }
            playStreamCollectionTracks(collection, tracks, landing.first, forcedStartPositionMs = landing.second)
        }
    }

    /** 合集历史条目 → 可重新拉取的在线合集：专辑键（kind:pluginId:collectionId）+ 曲目引用的源信息恢复。 */
    private fun onlineCollectionForHistoryEntry(entry: AlbumPlaybackHistoryEntry): OnlineCollection? {
        val online = entry.trackReference.onlineTrack ?: return null
        if (online.pluginId.isBlank()) return null
        val parts = entry.albumKey.split(':', limit = 3)
        if (parts.size != 3) return null
        val kind = when (parts[0]) {
            "Sheet" -> StreamCollectionKind.Sheet
            "TopList" -> StreamCollectionKind.TopList
            "Album" -> StreamCollectionKind.Album
            else -> return null
        }
        return OnlineCollection(
            pluginId = online.pluginId,
            sourceName = online.sourceName,
            kind = kind,
            collectionId = parts[2],
            name = entry.albumTitle,
            artist = entry.artist,
            artworkUrl = online.artworkUrl,
            description = null,
            worksNum = null,
        )
    }

    private fun playMediaReference(reference: MediaReference, positionMs: Long) {
        val track = nativeTrackFromReference(reference) ?: return
        albumPlaybackContext = null
        if (track.isVideo) {
            // 直播电视台（视频轨）：走视频内核，无进度概念。
            videoAlbumContext = null
            playVideos(listOf(track), 0, positionMs)
            return
        }
        playFromQueueAtPosition(listOf(track), 0, positionMs)
    }

    private fun nativeTrackFromReference(reference: MediaReference): NativeTrack? = when (reference.source) {
        // 在线合集与本地专辑：不是单条曲目，由 playFavoriteItem 单独处理，队列构建时跳过。
        ONLINE_ALBUM_SOURCE, LOCAL_ALBUM_SOURCE -> null
        "video" -> {
            // 播放历史里的视频条目：URI 原样重建曲目（本地/网盘视频）。
            val uri = reference.uri?.let { android.net.Uri.parse(it) } ?: return null
            NativeTrack(
                id = externalTrackId(uri),
                uri = uri,
                title = reference.title,
                artist = reference.artist,
                album = reference.album,
                durationMs = reference.durationMs,
                format = reference.format,
                mimeType = reference.mimeType,
                folderPath = "",
                sizeBytes = reference.sizeBytes,
                artworkUrl = reference.artworkUrl,
                isVideo = true,
            ).also { track ->
                if (uri.scheme == "baidupan") {
                    val fsId = uri.host?.toLongOrNull()
                    if (fsId != null && panFileByFsId[fsId] == null && reference.cloudPath != null) {
                        panFileByFsId[fsId] = BaiduPanFile(
                            fsId = fsId,
                            path = reference.cloudPath ?: "/${reference.title}.${reference.format.lowercase()}",
                            isDir = false,
                            serverFilename = reference.title,
                            size = reference.sizeBytes,
                            category = 2,
                            serverMtime = 0,
                        )
                    }
                }
            }
        }
        "online" -> {
            val online = reference.onlineTrack ?: return null
            val track = onlineTrackToNativeTrack(online)
            onlineTrackById[track.id] = online
            track
        }
        "baidupan" -> {
            val fsId = reference.key.toLongOrNull() ?: return null
            val file = BaiduPanFile(
                fsId = fsId,
                path = reference.cloudPath ?: "/" + reference.title + "." + reference.format.lowercase(),
                isDir = false,
                serverFilename = reference.title + "." + reference.format.lowercase(),
                size = reference.sizeBytes,
                category = 2,
                serverMtime = 0,
            )
            panFileByFsId[fsId] = file
            baiduPanTrack(file).copy(durationMs = reference.durationMs, mimeType = reference.mimeType)
        }
        "quarkpan" -> {
            val file = QuarkPanFile(
                fid = reference.key,
                pdirFid = reference.cloudParentId ?: "0",
                fileName = reference.title + "." + reference.format.lowercase(),
                isDir = false,
                size = reference.sizeBytes,
                category = 2,
                updatedAt = 0,
            )
            quarkFileByFid[file.fid] = file
            quarkTrack(file).copy(durationMs = reference.durationMs, mimeType = reference.mimeType)
        }
        else -> {
            val uriString = reference.uri ?: return null
            val uri = Uri.parse(uriString)
            val existing = _state.value.tracks.firstOrNull { it.uri.toString() == uriString && it.cueStartMs == reference.cueStartMs }
            if (existing != null) return existing
            if (!canOpenLocalReference(uri)) {
                _state.value = _state.value.copy(status = "本地文件找不到或无读取权限，请重新导入。")
                return null
            }
            NativeTrack(
                id = externalTrackId(uri),
                uri = uri,
                title = reference.title,
                artist = reference.artist,
                album = reference.album,
                durationMs = reference.durationMs,
                format = reference.format,
                mimeType = reference.mimeType,
                folderPath = "",
                sizeBytes = reference.sizeBytes,
                cueStartMs = reference.cueStartMs,
                artworkUrl = reference.artworkUrl,
            )
        }
    }

    private fun canOpenLocalReference(uri: Uri): Boolean = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    private fun playFromQueueAtPosition(queue: List<NativeTrack>, index: Int, positionMs: Long) {
        if (index !in queue.indices) return
        forcedStartPositionMs = positionMs.coerceAtLeast(0)
        _state.value = _state.value.copy(currentTrack = queue[index], queue = queue, queueIndex = index, positionMs = forcedStartPositionMs ?: 0L)
        playFromQueue(queue, index)
    }

    fun setVolume(volume: Float) {
        val target = volume.coerceIn(0f, 1f)
        settingsPreferences.edit().putFloat("volume", target).apply()
        if (_state.value.isCasting) {
            val remotes = castGroupAll()
            if (remotes.isNotEmpty()) {
                viewModelScope.launch {
                    var supported = false
                    runCatching {
                        remotes.forEach { remote -> supported = remote.setVolume(target) || supported }
                    }
                    if (supported) _state.value = _state.value.copy(volume = target)
                    else _state.value = _state.value.copy(status = "此播放设备不支持音量控制。")
                }
                return
            }
            _state.value = _state.value.copy(status = "投送会话已失效，请返回本机后重试。")
            return
        }
        if (player.setVolume(target)) _state.value = _state.value.copy(volume = target)
    }

    fun setSpeed(speed: Double) {
        val target = remotePlaybackRate(speed)
        fun applySpeed() {
            // 倍速只在播放页调节、作用于当前播放会话：不再持久化为全局默认（最终版行为）。
            _state.value = _state.value.copy(playbackSpeed = target)
            updateMediaSession()
        }
        if (_state.value.isCasting) {
            activeRemote?.let { renderer ->
                if (!_state.value.playing) {
                    applySpeed()
                } else {
                    viewModelScope.launch {
                        runCatching { renderer.play(target) }
                            .onSuccess { applySpeed() }
                            .onFailure { _state.value = _state.value.copy(status = "操作失败，请重试。") }
                    }
                }
                return
            }
            _state.value = _state.value.copy(status = "投送会话已失效，请返回本机后重试。")
            return
        }
        if (player.setSpeed(target)) {
            applySpeed()
        }
    }

    fun setLibraryBrowse(browse: LibraryBrowse) {
        _state.value = _state.value.copy(libraryBrowse = browse)
        settingsPreferences.edit().putString("library_browse", browse.name).apply()
    }

    fun setFadeTransitions(enabled: Boolean) {
        player.setFadeEnabled(enabled)
        _state.value = _state.value.copy(fadeTransitions = enabled)
        settingsPreferences.edit().putBoolean("fade_transitions", enabled).apply()
        if (!enabled && localFadeTransitionInProgress) {
            localFadeTransitionInProgress = false
            pendingLocalPlayback?.also { (queue, index) ->
                pendingLocalPlayback = null
                startLocalPlaybackNow(queue, index)
            }
        }
    }

    /** 设置是否自动从在线媒体匹配本地歌曲歌词（默认开启）。 */
    fun setAutoMatchLocalLyrics(enabled: Boolean) {
        _state.value = _state.value.copy(autoMatchLocalLyrics = enabled)
        settingsPreferences.edit().putBoolean("auto_match_local_lyrics", enabled).apply()
    }

    /** 播放音频时是否自动进入正在播放页：只影响「起播自动跳转」，用户点迷你播放器仍可随时进入。 */
    fun setAutoOpenPlayingPage(enabled: Boolean) {
        _state.value = _state.value.copy(autoOpenPlayingPage = enabled)
        settingsPreferences.edit().putBoolean(AUTO_OPEN_PLAYING_PAGE_KEY, enabled).apply()
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        val mode = SilenceSkipMode.LongerThan
        val threshold = if (enabled) 500L else 0L
        player.setSilenceSkipping(enabled, mode, threshold)
        _state.value = _state.value.copy(
            skipSilenceEnabled = enabled,
            silenceSkipMode = mode,
            silenceSkipThresholdMs = threshold,
        )
        settingsPreferences.edit().apply {
            if (enabled) {
                putBoolean("skip_silence_enabled", true)
                putString("skip_silence_mode", mode.name)
                putLong("skip_silence_threshold_ms", threshold)
            } else {
                remove("skip_silence_enabled")
                remove("skip_silence_mode")
                remove("skip_silence_threshold_ms")
            }
            remove("skip_leading_silence")
            remove("skip_trailing_silence")
            apply()
        }
    }

    fun setSilenceSkipMode(mode: SilenceSkipMode) {
        if (!_state.value.skipSilenceEnabled) return
        updateSilenceSkipping(mode, _state.value.silenceSkipThresholdMs)
    }

    fun setSilenceSkipThresholdMs(thresholdMs: Long) {
        if (!_state.value.skipSilenceEnabled) return
        updateSilenceSkipping(_state.value.silenceSkipMode, thresholdMs.coerceIn(1L, 60_000L))
    }

    private fun updateSilenceSkipping(mode: SilenceSkipMode, thresholdMs: Long) {
        player.setSilenceSkipping(true, mode, thresholdMs)
        _state.value = _state.value.copy(silenceSkipMode = mode, silenceSkipThresholdMs = thresholdMs)
        settingsPreferences.edit()
            .putBoolean("skip_silence_enabled", true)
            .putString("skip_silence_mode", mode.name)
            .putLong("skip_silence_threshold_ms", thresholdMs)
            .apply()
    }

    fun setThemeMode(mode: AppThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
        settingsPreferences.edit().putString("theme_mode", mode.name).apply()
    }

    fun setGridDensity(density: GridDensity) {
        _state.value = _state.value.copy(gridDensity = density)
        settingsPreferences.edit().putString("grid_density", density.name).apply()
    }

    fun scanDlnaDevices() {
        if (_state.value.scanningDevices || _state.value.switchingDevice) return
        _state.value = _state.value.copy(scanningDevices = true, status = "正在搜索局域网投送设备。")
        viewModelScope.launch {
            val dlnaResult = runCatching { dlnaDiscovery.scan() }
            val dlna = dlnaResult.getOrDefault(emptyList())
            // Chromecast 路由与 DLNA 同屏列出（无 Google 服务的设备自动隐藏）
            val chromecast = withContext(Dispatchers.IO) {
                runCatching { ChromecastDiscovery(getApplication()).routes() }.getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(
                dlnaDevices = dlna,
                chromecastDevices = chromecast,
                chromecastAvailable = true,
                scanningDevices = false,
                status = if (dlnaResult.isFailure) "未能搜索投送设备，请检查 Wi‑Fi 后重试。" else "已找到 ${dlna.size + chromecast.size} 个新设备。",
            )
        }
    }

    /** 多房间：把 DLNA 设备加入当前投送组，从主会话当前位置同步播放。 */
    private fun addToCastGroup(device: DlnaDevice) {
        val track = _state.value.currentTrack ?: return
        val servedUrl = castServedUrl
        if (servedUrl.isNullOrEmpty()) {
            _state.value = _state.value.copy(status = "当前投送暂不支持加入设备，请重试。")
            return
        }
        _state.value = _state.value.copy(status = "正在加入 ${device.name}…")
        viewModelScope.launch {
            runCatching {
                val renderer = DlnaRendererController(device)
                renderer.load(servedUrl, track, isVideo = false)
                renderer.play(_state.value.playbackSpeed)
                val target = _state.value.positionMs + track.cueStartMs.coerceAtLeast(0)
                if (target > 0) renderer.seekWhenReady(target)
                castGroupList.add(renderer)
                _state.value = _state.value.copy(
                    castGroupNames = _state.value.castGroupNames + device.name,
                    status = "已加入 ${device.name}，正在同步播放",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(status = error.message ?: "加入 ${device.name} 失败，请重试。")
            }
        }
    }

    /** 音频投送到 Chromecast（默认接收端；视频暂限 DLNA）。 */
    fun startChromecast(deviceName: String) {
        val track = _state.value.currentTrack ?: return
        if (_state.value.switchingDevice) return
        // 多房间：投送中再选 Chromecast 设备同样加入同播组
        if (_state.value.isCasting) {
            addChromecastToGroup(deviceName)
            return
        }
        _state.value = _state.value.copy(switchingDevice = true, status = "正在连接 $deviceName")
        viewModelScope.launch {
            runCatching {
                val remote = ChromecastRemote.connect(getApplication(), deviceName)
                    ?: error("未能连接 $deviceName，请确认设备在同一 Wi‑Fi")
                val source = resolveCastSource(track, renderer = remote)
                val position = player.positionMs()
                player.pause()
                loadDlnaSource(remote, track, position, source).also { activeRemote = remote }
            }.onSuccess {
                _state.value = _state.value.copy(isCasting = true, targetName = deviceName, playing = true, switchingDevice = false, status = "已连接 $deviceName")
                startProgressPolling()
            }.onFailure { error ->
                mediaServer.stop(); activeRemote?.dispose(); activeRemote = null; player.resume()
                _state.value = _state.value.copy(switchingDevice = false, status = error.message ?: "操作失败，请重试。")
            }
        }
    }

    /** 多房间：把 Chromecast 设备加入当前投送组。 */
    private fun addChromecastToGroup(deviceName: String) {
        val track = _state.value.currentTrack ?: return
        val servedUrl = castServedUrl
        if (servedUrl.isNullOrEmpty()) {
            _state.value = _state.value.copy(status = "当前投送暂不支持加入设备，请重试。")
            return
        }
        _state.value = _state.value.copy(status = "正在加入 $deviceName…")
        viewModelScope.launch {
            runCatching {
                val remote = ChromecastRemote.connect(getApplication(), deviceName)
                    ?: error("未能连接 $deviceName，请确认设备在同一 Wi‑Fi")
                remote.load(servedUrl, track, isVideo = false)
                remote.play(_state.value.playbackSpeed)
                val target = _state.value.positionMs + track.cueStartMs.coerceAtLeast(0)
                if (target > 0) remote.seekWhenReady(target)
                castGroupList.add(remote)
                _state.value = _state.value.copy(
                    castGroupNames = _state.value.castGroupNames + deviceName,
                    status = "已加入 $deviceName，正在同步播放",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(status = error.message ?: "加入 $deviceName 失败，请重试。")
            }
        }
    }

    fun refreshAudioOutputs() {
        _state.value = _state.value.copy(audioOutputs = audioOutputController.outputs())
    }

    fun selectAudioOutput(output: AudioOutput) {
        val device = output.device
        if (device == null) {
            _state.value = _state.value.copy(status = "系统暂未提供 ${output.name} 的媒体输出，无法切换。")
            return
        }
        if (player.setPreferredDevice(device)) {
            if (_state.value.isCasting) {
                returnToLocal()
                return
            }
            _state.value = _state.value.copy(
                status = if (player.hasAudioTrack()) {
                    "正在请求切换至 ${output.name}，以系统实际输出为准。"
                } else {
                    "已选择 ${output.name}，开始播放后确认系统输出。"
                },
            )
        } else _state.value = _state.value.copy(status = "系统无法切换至 ${output.name}。")
    }

    fun startDlna(device: DlnaDevice) {
        val track = _state.value.currentTrack ?: return
        if (!isDlnaCastSource(track.uri.scheme, onlineTrackById.containsKey(track.id))) {
            _state.value = _state.value.copy(status = "此媒体暂不支持投送")
            return
        }
        if (_state.value.switchingDevice) return
        // 多房间语义（2026-09-28）：投送中再选设备 = 加入同播组；切换请先「返回本机」
        if (_state.value.isCasting) {
            addToCastGroup(device)
            return
        }
        _state.value = _state.value.copy(switchingDevice = true, status = "正在连接 ${device.name}")
        viewModelScope.launch {
            runCatching {
                val renderer = DlnaRendererController(device)
                val source = resolveCastSource(track, renderer = renderer)
                val position = player.positionMs()
                player.pause()
                loadDlnaSource(renderer, track, position, source).also { activeRemote = renderer }
            }.onSuccess {
                _state.value = _state.value.copy(isCasting = true, targetName = device.name, playing = true, switchingDevice = false, status = "已连接 ${device.name}")
                updateMediaSession()
                rememberHistory("dlna", device.id, device.name, device.location, device.avTransportControlUrl, device.renderingControlUrl, device.renderingControlServiceType)
                syncDlnaVolume()
                startProgressPolling()
            }.onFailure { error ->
                Log.w("DlnaCast", "投送失败 device=${device.name} track=${track.title}", error)
                mediaServer.stop(); activeRemote?.dispose(); activeRemote = null; player.resume(); _state.value = _state.value.copy(switchingDevice = false, status = "操作失败，请重试。")
            }
        }
    }

    private suspend fun playLocalFromCast(track: NativeTrack, positionMs: Long): NativeTrack {
        val remote = isRemoteCastSource(track.uri.scheme, onlineTrackById.containsKey(track.id)) ||
            track.uri.scheme in setOf("baidupan", "quarkpan")
        if (!remote) {
            player.play(track, positionMs)
            return track
        }
        // 回本机优先复用投送起播时的解析结果：返回本机/投送中切输出不应再被上游解析链卡住
        // （上游抖动时重新解析要数秒甚至失败，表现为「无法回到本机」）。
        // 地址失效由用户重播兜底——重播走完整解析。
        val cached = castUpstream?.takeIf { it.first.id == track.id }?.second
        var source = cached ?: resolveCastSource(track)
        try {
            player.playUrl(source.url, source.durationMs, positionMs, headers = source.headers)
        } catch (error: Throwable) {
            if (cached != null) {
                // 缓存地址起播失败（签名过期等）：回落完整解析一次。
                source = resolveCastSource(track)
                player.playUrl(source.url, source.durationMs, positionMs, headers = source.headers)
            } else {
                if (!shouldRefreshCastSource(source.refreshable, 0)) throw error
                source = resolveCastSource(track, forceRefresh = true)
                player.playUrl(source.url, source.durationMs, positionMs, headers = source.headers)
            }
        }
        player.setVolume(_state.value.volume)
        player.setSpeed(_state.value.playbackSpeed)
        return castTrack(track, source)
    }

    fun returnToLocal() {
        val track = _state.value.currentTrack ?: return
        if (!_state.value.isCasting || _state.value.switchingDevice) return
        activeRemote?.let { renderer ->
            _state.value = _state.value.copy(switchingDevice = true, status = "正在返回本机输出")
            viewModelScope.launch {
                runCatching {
                    val position = renderer.positionMs().also { renderer.pause() }
                    val localPosition = remoteResumePosition(position, track.cueStartMs, track.durationMs)
                    val localTrack = playLocalFromCast(track, localPosition)
                    renderer.stop()
                    mediaServer.stop()
                    renderer.dispose()
                    preloadedCast = null
                    castUpstream = null
                    castServedHost = null
                    // 返回本机：整组停止（多房间语义 = 结束广播）
                    castGroupList.forEach { extra ->
                        runCatching { extra.stop() }
                        runCatching { extra.dispose() }
                    }
                    castGroupList.clear()
                    castServedUrl = null
                    activeRemote = null
                    localTrack to localPosition
                }
                    .onSuccess { (localTrack, localPosition) ->
                        _state.value = localPlaybackState(localPosition).copy(
                            currentTrack = localTrack,
                            queue = _state.value.queue.map { if (it.id == localTrack.id) localTrack else it },
                        )
                        updateMediaSession()
                        startProgressPolling()
                    }.onFailure { error ->
                        Log.w("DlnaCast", "返回本机失败 track=${track.title}", error)
                        _state.value = _state.value.copy(switchingDevice = false, status = "操作失败，请重试。")
                    }
            }
            return
        }
        _state.value = _state.value.copy(switchingDevice = false, status = "投送会话已失效，请重试。")
    }

    fun startHistory(entry: CastHistoryEntry) {
        if (_state.value.scanningDevices || _state.value.switchingDevice) return
        if (entry.protocol == "dlna") {
            val location = entry.location
            val controlUrl = entry.controlUrl
            if (location == null || controlUrl == null) {
                _state.value = _state.value.copy(scanningDevices = true, status = "正在补全 ${entry.name} 的连接信息。")
                viewModelScope.launch {
                    val device = runCatching { dlnaDiscovery.scan() }.getOrDefault(emptyList()).firstOrNull { it.id == entry.id }
                    _state.value = _state.value.copy(scanningDevices = false)
                    if (device != null) startDlna(device) else _state.value = _state.value.copy(status = "未找到 ${entry.name}。")
                }
            } else startDlna(DlnaDevice(entry.id, entry.name, location, controlUrl, entry.renderingControlUrl, entry.renderingControlServiceType))
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(if (_state.value.isCasting) 500 else 100)
                if (_state.value.isCasting) {
                    val observedGeneration = playbackGeneration
                    activeRemote?.let { renderer ->
                        var positionFailed = true
                        runCatching { renderer.positionMs() }
                            .onSuccess {
                                positionFailed = false
                                castPollFailureCount = 0
                                if (!isCurrentPlaybackCompletion(observedGeneration, playbackGeneration)) return@onSuccess
                                val position = (it - (_state.value.currentTrack?.cueStartMs ?: 0)).coerceAtLeast(0)
                                _state.value = _state.value.copy(positionMs = position)
                                if (isConfirmedRemoteCompletion(position, _state.value.currentTrack?.durationMs ?: 0, stopped = false)) {
                                    advanceAfterStop(observedGeneration)
                                }
                            }
                        if (positionFailed) castPollFailureCount++
                        // 设备掉线主动判定：连续 ~5 秒无响应即宣告会话失败（原先静默冻结在旧进度）
                        if (castPollFailureCount >= 10) {
                            castPollFailureCount = 0
                            val wasCasting = _state.value.isCasting
                            renderer.dispose()
                            preloadedCast = null
                            castUpstream = null
                            castServedHost = null
                            // 掉线判定基于主会话：整组一并收尾，避免附加设备悬空播放
                            castGroupList.forEach { extra ->
                                runCatching { extra.stop() }
                                runCatching { extra.dispose() }
                            }
                            castGroupList.clear()
                            castServedUrl = null
                            activeRemote = null
                            mediaServer.stop()
                            viewModelScope.launch { runCatching { renderer.stop() } }
                            if (wasCasting) {
                                _state.value = _state.value.copy(
                                    isCasting = false, playing = false, castGroupNames = emptyList(),
                                    status = "投送设备无响应，已停止投送。",
                                )
                            }
                            return@let
                        }
                        if (
                            isCurrentPlaybackCompletion(observedGeneration, playbackGeneration) &&
                            runCatching { renderer.isStopped() }.getOrDefault(false)
                        ) {
                            val state = _state.value
                            if (isConfirmedRemoteCompletion(state.positionMs, state.currentTrack?.durationMs ?: 0, stopped = true)) {
                                advanceAfterStop(observedGeneration)
                            } else {
                                _state.value = state.copy(playing = false, status = "投送设备提前停止播放，请重试。")
                                updateMediaSession()
                            }
                        }
                        syncDlnaVolume(renderer)
                    }
                    continue
                }
                if (_state.value.streamLoading) continue
                val stopped = player.isStopped()
                if (stopped && _state.value.playing) {
                    advanceAfterStop()
                    continue
                }
                val position = player.positionMs()
                val playing = player.isPlaying()
                if (position != _state.value.positionMs || playing != _state.value.playing) {
                    _state.value = _state.value.copy(positionMs = position, playing = playing)
                }
                // 网络流时长后知后觉（网盘有声等站点的清单不带总长）：起播后从播放内核回填一次，
                // 进度条、快进快退钳制与断点续播记录都依赖它。
                val playingTrack = _state.value.currentTrack
                if (playingTrack != null && playingTrack.durationMs <= 0 && !stopped) {
                    val reported = player.durationMs()
                    if (reported > 0) {
                        val updated = playingTrack.copy(durationMs = reported)
                        _state.value = _state.value.copy(
                            currentTrack = updated,
                            queue = _state.value.queue.map { if (it.id == updated.id) updated else it },
                        )
                        saveQueue()
                        updateMediaSession()
                    }
                }
                if (playing) {
                    followLyricLine(position)
                }
                val loopStart = _state.value.abStartMs
                val loopEnd = _state.value.abEndMs
                if (_state.value.playing && loopStart != null && loopEnd != null && _state.value.positionMs >= loopEnd) {
                    seek(loopStart)
                    continue
                }
                // 片尾跳过：本机播放进入尾部门槛时自动跳下一首（投送由远端自然结束驱动，不在此触发）
                val skipTailSec = skipConfigFor(_state.value.currentTrack).second
                if (skipTailSec > 0 && !_state.value.isCasting) {
                    val duration = _state.value.currentTrack?.durationMs ?: 0L
                    if (duration > 0 && _state.value.playing && _state.value.positionMs >= duration - (skipTailSec * 1000).toLong()) {
                        advanceAfterStop()
                    }
                }
                recordPlaybackProgress()
                updateMediaSession()
                val next = nextIndex()
                if (
                    _state.value.fadeTransitions &&
                    _state.value.playing &&
                    !autoAdvanceInProgress &&
                    !localFadeTransitionInProgress &&
                    next != null &&
                    _state.value.currentTrack != null &&
                    _state.value.currentTrack!!.durationMs - _state.value.positionMs <= 500
                ) {
                    if (
                        _state.value.sleepTimerMode == SleepTimerMode.Tracks &&
                        _state.value.sleepTimerRemainingTracks <= 1
                    ) {
                        continue
                    }
                    autoAdvanceInProgress = true
                    playFromQueue(_state.value.queue, next)
                    continue
                }
            }
        }
    }

    private fun syncDlnaVolume(renderer: CastRemote? = activeRemote) {
        val active = renderer ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDlnaVolumeReadAtMs < 2_000) return
        lastDlnaVolumeReadAtMs = now
        viewModelScope.launch {
            runCatching { active.volume() }.onSuccess { volume ->
                // 与投送 setVolume 的线性直传一致，读回不再做 perceptual 逆变换。
                if (volume != null && activeRemote === active) _state.value = _state.value.copy(volume = volume)
            }
        }
    }

    private fun advanceAfterStop(observedGeneration: Long = playbackGeneration) {
        if (!isCurrentPlaybackCompletion(observedGeneration, playbackGeneration)) return
        val current = _state.value
        if (onlineStartInProgress || autoAdvanceInProgress || _state.value.streamLoading || !current.playing || current.currentTrack == null) return
        if (!shouldAdvanceAfterRemoteStop(current.isCasting, current.currentTrack.durationMs)) {
            _state.value = current.copy(playing = false, status = "投送设备已停止播放，请重试")
            return
        }
        // 直播流（电台/电视）没有「自然播完」：会话提前终止即断流（死链/上游不可达/网络切换），
        // 必须向用户明示，否则表现成「解析成功却无声停止」。
        if (current.currentTrackLive) {
            _state.value = current.copy(playing = false, status = "直播信号已中断，请稍后重试")
            return
        }
        if (isPrematureRemoteStop(current.isCasting, current.currentTrack.durationMs, current.positionMs)) {
            _state.value = current.copy(playing = false, status = "投送设备已停止播放，请重试。")
            return
        }
        if (consumeTrackSleepTimerAtCompletion()) return
        val next = nextIndex()
        if (next == null) {
            val status = if (_state.value.sleepTimerMode == SleepTimerMode.Tracks) {
                "播放队列已结束，睡眠定时已停止。"
            } else {
                _state.value.sleepTimerStatus
            }
            if (_state.value.sleepTimerMode == SleepTimerMode.Tracks) clearSleepTimerState(status)
            _state.value = _state.value.copy(playing = false, sleepTimerStatus = status)
            return
        }
        autoAdvanceInProgress = true
        playFromQueue(current.queue, next)
    }

    private fun reachedTrackEnd(positionMs: Long): Boolean = _state.value.currentTrack
        ?.let { hasReachedTrackEnd(positionMs, it.durationMs) }
        ?: false

    private fun markPlaybackFailure(message: String = "操作失败，请重试。") {
        val wasAutoAdvancing = autoAdvanceInProgress
        autoAdvanceInProgress = false
        stopTrackSleepTimerAfterPlaybackFailure(wasAutoAdvancing)
        _state.value = _state.value.copy(
            playing = if (wasAutoAdvancing) false else _state.value.playing,
            status = message,
        )
    }

    private fun stopTrackSleepTimerAfterPlaybackFailure(wasAutoAdvancing: Boolean) {
        if (shouldStopTrackSleepTimerAfterPlaybackFailure(wasAutoAdvancing, _state.value.sleepTimerMode)) {
            clearSleepTimerState("自动切歌失败，睡眠定时已停止。")
        }
    }

    private fun localPlaybackState(position: Long): MusicUiState {
        val output = player.routedDevice()?.let(audioOutputController::describe)
        return _state.value.copy(
            isCasting = false,
            targetName = output?.name ?: "本机输出待确认",
            positionMs = position,
            playing = true,
            switchingDevice = false,
            status = output?.let { "系统输出：${it.name}" } ?: "已返回本机播放，正在确认系统输出。",
        )
    }

    private fun loadHistory(): List<CastHistoryEntry> = runCatching {
        val values = JSONArray(historyPreferences.getString("devices", "[]"))
        List(values.length()) { index -> values.getJSONObject(index) }.map {
            CastHistoryEntry(
                it.getString("protocol"), it.getString("id"), it.getString("name"),
                it.optString("location").ifBlank { null }, it.optString("controlUrl").ifBlank { null },
                it.optString("renderingControlUrl").ifBlank { null },
                it.optString("renderingControlServiceType").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    private fun loadQueueMode(): NativeQueueMode = settingsPreferences
        .getString("queue_mode", NativeQueueMode.RepeatAll.name)
        ?.let { runCatching { NativeQueueMode.valueOf(it) }.getOrNull() }
        ?: NativeQueueMode.RepeatAll

    private fun loadThemeMode(): AppThemeMode = settingsPreferences
        .getString("theme_mode", AppThemeMode.System.name)
        ?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
        ?: AppThemeMode.System

    private fun loadGridDensity(): GridDensity = settingsPreferences
        .getString("grid_density", GridDensity.Columns3.name)
        ?.let { runCatching { GridDensity.valueOf(it) }.getOrNull() }
        ?: GridDensity.Columns3

    private fun loadLibrarySort(): LibrarySort = settingsPreferences
        .getString("library_sort", LibrarySort.Name.name)
        ?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() }
        ?: LibrarySort.Name

    private fun loadLibraryBrowse(): LibraryBrowse = settingsPreferences
        .getString("library_browse", LibraryBrowse.Songs.name)
        ?.let { runCatching { LibraryBrowse.valueOf(it) }.getOrNull() }
        ?: LibraryBrowse.Songs

    private fun loadFavoriteIds(): Set<Long> = settingsPreferences
        .getStringSet("favorite_ids", emptySet())
        .orEmpty()
        .mapNotNull(String::toLongOrNull)
        .toSet()

    private fun defaultFavoriteFolder(scope: MediaScope = MediaScope.Music) = FavoriteFolder(
        id = favoriteDefaultFolderId(scope),
        name = "默认收藏夹",
        createdAtMs = 0L,
        scope = scope,
    )

    private fun loadFavoriteFolders(): List<FavoriteFolder> = runCatching {
        val saved = settingsPreferences.getString(FAVORITE_FOLDERS_KEY, null)
        if (saved.isNullOrBlank()) return@runCatching ensureDefaultFavoriteFolders(listOf(defaultFavoriteFolder()))
        val values = JSONArray(saved)
        val folders = List(values.length()) { index -> values.getJSONObject(index) }.mapNotNull { value ->
            val id = value.optString("id").ifBlank { return@mapNotNull null }
            val scope = value.optString("scope").takeIf(String::isNotBlank)
                ?.let { name -> MediaScope.entries.firstOrNull { it.name == name } }
                ?: scopeOfFavoriteFolderId(id)
            FavoriteFolder(
                id = id,
                name = value.optString("name").ifBlank { if (isDefaultFavoriteFolderId(id)) "默认收藏夹" else "收藏夹" },
                createdAtMs = value.optLong("createdAtMs", 0L),
                items = decodeMediaReferenceArray(value.optJSONArray("items")),
                scope = scope,
            )
        }
        ensureDefaultFavoriteFolders(normalizeFavoriteFolderScopes(folders))
    }.getOrDefault(ensureDefaultFavoriteFolders(listOf(defaultFavoriteFolder())))

    /** 每个内容域至少有一个「默认收藏夹」，否则该模块的收藏页无落点。 */
    private fun ensureDefaultFavoriteFolders(folders: List<FavoriteFolder>): List<FavoriteFolder> {
        val missing = MediaScope.entries.filter { scope -> folders.none { it.scope == scope && isDefaultFavoriteFolderId(it.id) } }
        return folders + missing.map { defaultFavoriteFolder(it) }
    }

    /**
     * 旧版本收藏夹全局混放：按条目内容域拆分——单一域的文件夹原样归属；混合域的按域拆成同名夹。
     * 空文件夹归音乐（旧版「默认收藏夹」语义）。已在加载/保存两条路径上幂等执行。
     */
    private fun normalizeFavoriteFolderScopes(folders: List<FavoriteFolder>): List<FavoriteFolder> {
        val result = mutableListOf<FavoriteFolder>()
        folders.forEach { folder ->
            if (folder.items.isEmpty()) {
                result += folder
                return@forEach
            }
            folder.items.groupBy(::mediaScopeForSingle).forEach { (scope, items) ->
                val sameScope = result.firstOrNull { it.id == folder.id && it.scope == scope }
                if (sameScope != null) {
                    result[result.indexOf(sameScope)] = sameScope.copy(items = sameScope.items + items)
                    return@forEach
                }
                val id = if (result.none { it.id == folder.id } && folder.items.all { mediaScopeForSingle(it) == scope }) {
                    folder.id
                } else {
                    // 混合夹拆分：保留原名，用「原名-域」作新 id 避免与主夹冲突（默认夹 id 由 ensure 补齐）。
                    "${folder.id}:${scope.name}"
                }
                result += folder.copy(id = id, scope = scope, items = items)
            }
        }
        // 拆分后若有默认夹落在非音乐域，改名回「默认收藏夹」以保持一致。
        return ensureDefaultFavoriteFolders(result.distinctBy { it.id })
    }

    private fun saveFavoriteFolders(folders: List<FavoriteFolder>) {
        val normalized = ensureDefaultFavoriteFolders(normalizeFavoriteFolderScopes(folders))
        val values = JSONArray().apply {
            normalized.forEach { folder ->
                put(JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("createdAtMs", folder.createdAtMs)
                    put("scope", folder.scope.name)
                    put("items", JSONArray().apply { folder.items.forEach { put(encodeMediaReference(it)) } })
                })
            }
        }
        settingsPreferences.edit().putString(FAVORITE_FOLDERS_KEY, values.toString()).apply()
        _state.value = _state.value.copy(
            favoriteFolders = normalized,
            favoriteIds = favoriteTrackIds(normalized, _state.value.tracks),
        )
    }

    private fun favoriteTrackIds(folders: List<FavoriteFolder>, tracks: List<NativeTrack>): Set<Long> {
        val keys = folders.flatMap { it.items }.map { favoriteReferenceKey(it) }.toSet()
        return tracks.mapNotNull { track ->
            val reference = mediaReferenceForTrack(track)
            if (reference != null && favoriteReferenceKey(reference) in keys) track.id else null
        }.toSet()
    }

    private fun favoriteReferenceKey(reference: MediaReference): String = reference.source + ":" + reference.key

    private fun migrateLegacyFavorites(tracks: List<NativeTrack>) {
        if (settingsPreferences.getBoolean("favorite_folders_migrated", false)) return
        val legacyIds = loadFavoriteIds()
        if (legacyIds.isEmpty()) {
            if (settingsPreferences.getString(FAVORITE_FOLDERS_KEY, null).isNullOrBlank()) saveFavoriteFolders(loadFavoriteFolders())
            settingsPreferences.edit().putBoolean("favorite_folders_migrated", true).apply()
            return
        }
        val legacyItems = tracks.filter { it.id in legacyIds }.mapNotNull(::mediaReferenceForTrack)
        if (legacyItems.isNotEmpty()) {
            // 旧本地曲目收藏 → 月播库默认夹（saveFavoriteFolders 会按域归一）。
            val folderId = favoriteDefaultFolderId(MediaScope.Local)
            val folders = loadFavoriteFolders().map { folder ->
                if (folder.id != folderId) folder else folder.copy(
                    items = (legacyItems + folder.items).distinctBy(::favoriteReferenceKey),
                )
            }
            saveFavoriteFolders(folders)
        }
        settingsPreferences.edit().putBoolean("favorite_folders_migrated", true).apply()
    }

    private fun loadPlaylists(): List<SavedPlaylist> = runCatching {
        val values = JSONArray(settingsPreferences.getString("playlists", "[]"))
        List(values.length()) { index -> values.getJSONObject(index) }.map { value ->
            SavedPlaylist(
                id = value.getString("id"),
                name = value.getString("name"),
                trackIds = value.getJSONArray("trackIds").let { ids -> List(ids.length()) { ids.getLong(it) } },
                externalUris = value.optJSONArray("externalUris")
                    ?.let { uris -> List(uris.length()) { uris.getString(it) } }
                    ?: emptyList(),
                externalTracks = ImportedAlbumJson.decodeTracks(
                    if (value.has("externalTracks")) value.optString("externalTracks") else null,
                ),
                onlineTracks = value.optJSONArray("onlineTracks")?.let { tracks ->
                    List(tracks.length()) { index -> OnlineTrack.fromJson(tracks.getJSONObject(index)) }
                        .filterNotNull()
                } ?: emptyList(),
            )
        }
    }.getOrDefault(emptyList())

    private fun savePlaylists(playlists: List<SavedPlaylist>) {
        val values = JSONArray().apply {
            playlists.forEach { playlist ->
                put(JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("trackIds", JSONArray().apply { playlist.trackIds.forEach(::put) })
                    put("externalUris", JSONArray().apply { playlist.externalUris.forEach(::put) })
                    put("externalTracks", ImportedAlbumJson.encodeTracks(playlist.externalTracks))
                    put("onlineTracks", JSONArray().apply { playlist.onlineTracks.forEach { put(it.toJson()) } })
                })
            }
        }
        settingsPreferences.edit().putString("playlists", values.toString()).apply()
    }

    private fun loadImportedAlbums(): List<ImportedAlbum> =
        ImportedAlbumJson.decode(settingsPreferences.getString("imported_albums", null))

    private fun saveImportedAlbums(albums: List<ImportedAlbum>) {
        settingsPreferences.edit().putString("imported_albums", ImportedAlbumJson.encode(albums)).apply()
    }

    private fun loadHiddenAlbumKeys(): Set<String> =
        settingsPreferences.getStringSet("hidden_album_keys", emptySet()).orEmpty().toSet()

    private fun saveHiddenAlbumKeys(keys: Set<String>) {
        settingsPreferences.edit().putStringSet("hidden_album_keys", keys).apply()
    }

    private fun loadTrackMetadataOverrides(): Map<String, TrackMetadataOverride> =
        decodeTrackMetadataOverrides(settingsPreferences.getString("track_metadata_overrides", null))

    private fun saveTrackMetadataOverrides(overrides: Map<String, TrackMetadataOverride>) {
        settingsPreferences.edit()
            .putString("track_metadata_overrides", encodeTrackMetadataOverrides(overrides))
            .apply()
    }

    private fun encodeMediaReference(reference: MediaReference): JSONObject = JSONObject().apply {
        put("source", reference.source)
        put("key", reference.key)
        put("title", reference.title)
        put("artist", reference.artist)
        put("album", reference.album)
        put("durationMs", reference.durationMs)
        put("format", reference.format)
        put("mimeType", reference.mimeType)
        put("uri", reference.uri)
        put("cueStartMs", reference.cueStartMs)
        put("sizeBytes", reference.sizeBytes)
        put("artworkUrl", reference.artworkUrl)
        put("cloudPath", reference.cloudPath)
        put("cloudParentId", reference.cloudParentId)
        reference.onlineTrack?.let { put("onlineTrack", JSONObject(encodeOnlineResumeTrack(it))) }
    }

    private fun decodeMediaReference(value: JSONObject): MediaReference? {
        val source = value.optString("source").ifBlank { return null }
        val key = value.optString("key").ifBlank { return null }
        val online = value.optJSONObject("onlineTrack")?.let { decodeOnlineResumeTrack(it.toString()) }
        return MediaReference(
            source = source,
            key = key,
            title = value.optString("title").ifBlank { "未知音频" },
            artist = value.optString("artist"),
            album = value.optString("album"),
            durationMs = value.optLong("durationMs", 0L).coerceAtLeast(0),
            format = value.optString("format"),
            mimeType = value.optString("mimeType").ifBlank { "audio/*" },
            uri = value.optString("uri").ifBlank { null },
            cueStartMs = value.optLong("cueStartMs", 0L).coerceAtLeast(0),
            sizeBytes = value.optLong("sizeBytes", 0L).coerceAtLeast(0),
            artworkUrl = value.optString("artworkUrl").ifBlank { null },
            onlineTrack = online,
            cloudPath = value.optString("cloudPath").ifBlank { null },
            cloudParentId = value.optString("cloudParentId").ifBlank { null },
        )
    }

    private fun decodeMediaReferenceArray(values: JSONArray?): List<MediaReference> {
        if (values == null) return emptyList()
        return List(values.length()) { index -> values.optJSONObject(index) }.mapNotNull { value ->
            value?.let(::decodeMediaReference)
        }
    }

    private fun mediaReferenceForTrack(track: NativeTrack): MediaReference? {
        val online = onlineTrackById[track.id]
        if (!track.isVideo) {
            if (online != null) return mediaReferenceForOnlineTrack(online)
        } else {
            // 视频（本地/网盘）统一以 URI 为键进播放历史。
            val cloudPath = if (track.uri.scheme == "baidupan") {
                track.uri.host?.toLongOrNull()?.let { panFileByFsId[it]?.path }
            } else {
                null
            }
            return MediaReference(
                source = "video",
                key = track.uri.toString(),
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                format = track.format,
                mimeType = track.mimeType,
                uri = track.uri.toString(),
                sizeBytes = track.sizeBytes,
                artworkUrl = track.artworkUrl,
                cloudPath = cloudPath,
            )
        }
        return when (track.uri.scheme) {
            "baidupan" -> {
                val fsId = track.uri.host ?: return null
                val file = fsId.toLongOrNull()?.let { panFileByFsId[it] }
                MediaReference(
                    source = "baidupan",
                    key = fsId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    format = track.format,
                    mimeType = track.mimeType,
                    uri = track.uri.toString(),
                    sizeBytes = track.sizeBytes,
                    cloudPath = file?.path,
                )
            }
            "quarkpan" -> {
                val fid = track.uri.host ?: return null
                val file = quarkFileByFid[fid]
                MediaReference(
                    source = "quarkpan",
                    key = fid,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    format = track.format,
                    mimeType = track.mimeType,
                    uri = track.uri.toString(),
                    sizeBytes = track.sizeBytes,
                    cloudParentId = file?.pdirFid,
                )
            }
            else -> MediaReference(
                source = "local",
                key = track.uri.toString() + "#" + track.cueStartMs,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                format = track.format,
                mimeType = track.mimeType,
                uri = track.uri.toString(),
                cueStartMs = track.cueStartMs,
                sizeBytes = track.sizeBytes,
                artworkUrl = track.artworkUrl,
            )
        }
    }

    /** 在线曲目 → 收藏/历史引用：source="online"，插件与平台标识随 onlineTrack 落盘。 */
    private fun mediaReferenceForOnlineTrack(track: OnlineTrack, uri: String? = null): MediaReference = MediaReference(
        source = "online",
        key = track.key,
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs ?: 0,
        format = "mp3",
        mimeType = "audio/mpeg",
        uri = uri,
        artworkUrl = track.artworkUrl,
        onlineTrack = track,
    )

    /** 在线合集（专辑/歌单/榜单）→ 收藏引用：source="onlinealbum"，kind 编码在 onlineTrack.platform 里落盘，
     *  播放时按 key 重新打开合集详情，故合集本身可被收藏。 */
    private fun mediaReferenceForOnlineCollection(collection: OnlineCollection): MediaReference = MediaReference(
        source = ONLINE_ALBUM_SOURCE,
        key = collection.key,
        title = collection.name,
        artist = collection.artist,
        album = collection.name,
        durationMs = 0,
        format = "",
        mimeType = "application/x-online-collection",
        artworkUrl = collection.artworkUrl,
        onlineTrack = OnlineTrack(
            pluginId = collection.pluginId,
            platform = collection.kind.name.lowercase(),
            sourceName = collection.sourceName,
            platformId = collection.collectionId,
            title = collection.name,
            artist = collection.artist,
            album = collection.name,
            artworkUrl = collection.artworkUrl,
        ),
    )


    fun mediaReferenceForCloudFile(file: CloudFile): MediaReference? {
        if (file.isDir || !isPanAudioFileName(file.name)) return null
        val source = if (file.provider == CloudDisk.BAIDU) "baidupan" else "quarkpan"
        return MediaReference(
            source = source,
            key = file.id,
            title = file.name.substringBeforeLast(".", file.name),
            artist = file.provider.displayName,
            album = file.provider.displayName,
            durationMs = 0,
            format = file.name.substringAfterLast(".", "").uppercase(),
            mimeType = "audio/mpeg",
            uri = if (file.provider == CloudDisk.BAIDU) "baidupan://" + file.id else "quarkpan://" + file.id,
            sizeBytes = file.size,
            cloudPath = file.path,
            cloudParentId = file.parentId,
        )
    }

    private fun loadPlaybackHistory(): List<PlaybackHistoryEntry> = runCatching {
        val values = JSONArray(settingsPreferences.getString("playback_history", "[]"))
        List(values.length()) { index -> values.getJSONObject(index) }.map { value ->
            PlaybackHistoryEntry(value.getLong("trackId"), value.getLong("lastPlayedAtMs"), value.getInt("playCount"))
        }.sortedByDescending { it.lastPlayedAtMs }
    }.getOrDefault(emptyList())

    private fun loadSinglePlaybackHistory(): List<SinglePlaybackHistoryEntry> = runCatching {
        val values = JSONArray(settingsPreferences.getString(SINGLE_PLAYBACK_HISTORY_KEY, "[]"))
        List(values.length()) { index -> values.getJSONObject(index) }.mapNotNull { value ->
            val reference = value.optJSONObject("reference")?.let(::decodeMediaReference) ?: return@mapNotNull null
            SinglePlaybackHistoryEntry(
                reference = reference,
                positionMs = value.optLong("positionMs", 0L).coerceAtLeast(0),
                durationMs = value.optLong("durationMs", reference.durationMs).coerceAtLeast(0),
                lastPlayedAtMs = value.optLong("lastPlayedAtMs", 0L),
                playCount = value.optInt("playCount", 1).coerceAtLeast(1),
            )
        }.sortedByDescending { it.lastPlayedAtMs }
    }.getOrDefault(emptyList())

    private fun saveSinglePlaybackHistory(entries: List<SinglePlaybackHistoryEntry>) {
        val values = JSONArray().apply {
            entries.take(PLAYBACK_HISTORY_LIMIT).forEach { entry ->
                put(JSONObject().apply {
                    put("reference", encodeMediaReference(entry.reference))
                    put("positionMs", entry.positionMs)
                    put("durationMs", entry.durationMs)
                    put("lastPlayedAtMs", entry.lastPlayedAtMs)
                    put("playCount", entry.playCount)
                })
            }
        }
        settingsPreferences.edit().putString(SINGLE_PLAYBACK_HISTORY_KEY, values.toString()).apply()
    }

    private fun loadAlbumPlaybackHistory(): List<AlbumPlaybackHistoryEntry> = runCatching {
        val values = JSONArray(settingsPreferences.getString(ALBUM_PLAYBACK_HISTORY_KEY, "[]"))
        List(values.length()) { index -> values.getJSONObject(index) }.mapNotNull { value ->
            val reference = value.optJSONObject("trackReference")?.let(::decodeMediaReference) ?: return@mapNotNull null
            AlbumPlaybackHistoryEntry(
                albumKey = value.optString("albumKey").ifBlank { return@mapNotNull null },
                albumTitle = value.optString("albumTitle").ifBlank { reference.album.ifBlank { reference.title } },
                artist = value.optString("artist"),
                source = value.optString("source").ifBlank { reference.source },
                albumId = value.optString("albumId").ifBlank { null },
                query = value.optString("query").ifBlank { null },
                trackReference = reference,
                trackIndex = value.optInt("trackIndex", 0).coerceAtLeast(0),
                positionMs = value.optLong("positionMs", 0L).coerceAtLeast(0),
                durationMs = value.optLong("durationMs", reference.durationMs).coerceAtLeast(0),
                lastPlayedAtMs = value.optLong("lastPlayedAtMs", 0L),
            )
        }.sortedByDescending { it.lastPlayedAtMs }
    }.getOrDefault(emptyList())

    private fun saveAlbumPlaybackHistory(entries: List<AlbumPlaybackHistoryEntry>) {
        val values = JSONArray().apply {
            entries.take(PLAYBACK_HISTORY_LIMIT).forEach { entry ->
                put(JSONObject().apply {
                    put("albumKey", entry.albumKey)
                    put("albumTitle", entry.albumTitle)
                    put("artist", entry.artist)
                    put("source", entry.source)
                    put("albumId", entry.albumId)
                    put("query", entry.query)
                    put("trackReference", encodeMediaReference(entry.trackReference))
                    put("trackIndex", entry.trackIndex)
                    put("positionMs", entry.positionMs)
                    put("durationMs", entry.durationMs)
                    put("lastPlayedAtMs", entry.lastPlayedAtMs)
                })
            }
        }
        settingsPreferences.edit().putString(ALBUM_PLAYBACK_HISTORY_KEY, values.toString()).apply()
    }

    private fun migrateLegacyPlaybackHistory(tracks: List<NativeTrack>) {
        if (settingsPreferences.getBoolean("playback_history_v2_migrated", false)) return
        val byId = tracks.associateBy { it.id }
        val legacy = loadPlaybackHistory().mapNotNull { entry ->
            val track = byId[entry.trackId] ?: return@mapNotNull null
            val reference = mediaReferenceForTrack(track) ?: return@mapNotNull null
            SinglePlaybackHistoryEntry(reference, 0L, track.durationMs, entry.lastPlayedAtMs, entry.playCount)
        }
        if (legacy.isNotEmpty()) {
            val merged = (loadSinglePlaybackHistory() + legacy)
                .sortedByDescending { it.lastPlayedAtMs }
                .distinctBy { favoriteReferenceKey(it.reference) }
                .take(PLAYBACK_HISTORY_LIMIT)
            saveSinglePlaybackHistory(merged)
        }
        settingsPreferences.edit().putBoolean("playback_history_v2_migrated", true).apply()
    }

    private fun rememberPlayback(track: NativeTrack) {
        val now = System.currentTimeMillis()
        val previous = _state.value.playbackHistory.firstOrNull { it.trackId == track.id }
        val entries = (listOf(
            PlaybackHistoryEntry(track.id, now, (previous?.playCount ?: 0) + 1),
        ) + _state.value.playbackHistory.filterNot { it.trackId == track.id }).take(100)
        _state.value = _state.value.copy(playbackHistory = entries)
        val values = JSONArray().apply {
            entries.forEach { entry -> put(JSONObject().apply {
                put("trackId", entry.trackId)
                put("lastPlayedAtMs", entry.lastPlayedAtMs)
                put("playCount", entry.playCount)
            }) }
        }
        settingsPreferences.edit().putString("playback_history", values.toString()).apply()
        recordPlaybackProgress(force = true, incrementCount = true)
    }

    private fun recordPlaybackProgress(force: Boolean = false, incrementCount: Boolean = false) {
        val state = _state.value
        // 视频（本地/网盘/剧集/B站/MV）与音频共用同一份单曲/专辑历史；视频活跃时优先记录视频进度，
        // 此时 currentTrack 是上一首音频的残留状态，不能作为记录对象；投送中进度在远端，不入本地历史。
        val video = state.currentVideo
        val isVideo = video != null
        if (video == null && state.currentTrack == null) return
        if (video != null && state.videoCasting) return
        val track = video ?: state.currentTrack ?: return
        val reference = mediaReferenceForTrack(track) ?: return
        val now = System.currentTimeMillis()
        if (!force) {
            val last = settingsPreferences.getLong("playback_history_last_save_ms", 0L)
            if (now - last < PLAYBACK_PROGRESS_SAVE_INTERVAL_MS) return
        }
        settingsPreferences.edit().putLong("playback_history_last_save_ms", now).apply()
        val position = (if (isVideo) state.videoPositionMs else state.positionMs).coerceAtLeast(0)
        val duration = (if (isVideo) state.videoDurationMs.takeIf { it > 0 } else null)
            ?: track.durationMs.takeIf { it > 0 }
            ?: reference.durationMs
        val queueIndex = (if (isVideo) state.videoIndex else state.queueIndex).coerceAtLeast(0)
        val context = if (isVideo) {
            videoAlbumContext?.takeIf { videoQueueAlbumKey == it.key }
        } else {
            albumPlaybackContext?.takeIf { it.queueKey == queueHistoryKey(state.queue) }
        }
        val previousSingle = _state.value.singlePlaybackHistory.firstOrNull { favoriteReferenceKey(it.reference) == favoriteReferenceKey(reference) }
        val singles = (listOf(
            SinglePlaybackHistoryEntry(
                reference = reference.copy(durationMs = duration),
                positionMs = position,
                durationMs = duration,
                lastPlayedAtMs = now,
                playCount = (previousSingle?.playCount ?: 0) + if (incrementCount) 1 else 0,
            ),
        ) + _state.value.singlePlaybackHistory.filterNot { favoriteReferenceKey(it.reference) == favoriteReferenceKey(reference) })
            .take(PLAYBACK_HISTORY_LIMIT)
        val albums = if (context == null) {
            _state.value.albumPlaybackHistory
        } else {
            (listOf(
                AlbumPlaybackHistoryEntry(
                    albumKey = context.key,
                    albumTitle = context.title,
                    artist = context.artist,
                    source = context.source,
                    albumId = context.albumId,
                    query = context.query,
                    trackReference = reference.copy(durationMs = duration),
                    trackIndex = queueIndex,
                    positionMs = position,
                    durationMs = duration,
                    lastPlayedAtMs = now,
                ),
            ) + _state.value.albumPlaybackHistory.filterNot { it.albumKey == context.key }).take(PLAYBACK_HISTORY_LIMIT)
        }
        _state.value = _state.value.copy(singlePlaybackHistory = singles, albumPlaybackHistory = albums)
        saveSinglePlaybackHistory(singles)
        if (context != null) saveAlbumPlaybackHistory(albums)
    }

    private fun queueHistoryKey(queue: List<NativeTrack>): String = queue.joinToString("|") { it.id.toString() }

    private fun saveQueue() {
        val state = _state.value
        if (state.queue.any { it.id in onlineTrackById }) return
        val items = JSONArray().apply {
            state.queue.forEach { track ->
                if (track.id >= 0) {
                    put(JSONObject().put("id", track.id))
                } else {
                    put(encodeExternalQueueItem(track))
                }
            }
        }
        settingsPreferences.edit()
            .putString("queue_items", items.toString())
            .putInt("queue_index", state.queueIndex)
            .apply()
    }

    private fun restoreQueue(tracks: List<NativeTrack>): List<NativeTrack> = runCatching {
        val byId = tracks.associateBy { it.id }
        val saved = settingsPreferences.getString("queue_items", null)
        val queue = if (saved.isNullOrBlank()) {
            // 旧版本仅保存 MediaStore id 的队列
            JSONArray(settingsPreferences.getString("queue_ids", "[]"))
                .let { ids -> List(ids.length()) { ids.getLong(it) }.mapNotNull(byId::get) }
        } else {
            val items = JSONArray(saved)
            List(items.length()) { index -> items.getJSONObject(index) }.mapNotNull { item ->
                when {
                    item.has("externalTrack") -> decodeExternalQueueItem(item)?.toNativeTrack()
                    item.has("uri") -> externalTrackFromUriString(item.getString("uri"))
                    else -> byId[item.getLong("id")]
                }
            }
        }
        applyMetadataOverrides(queue)
    }.getOrDefault(emptyList())


    private fun restoreResume(tracks: List<NativeTrack>): MusicUiState {
        if (_state.value.currentTrack != null) return _state.value
        val restoredQueue = restoreQueue(tracks)
        val resumeId = settingsPreferences.getLong("resume_track_id", -1)
        val resumePosition = settingsPreferences.getLong("resume_position_ms", 0).coerceAtLeast(0)
        // 在线流媒体续播：仅恢复上一首在线曲目（含平台标识与进度），不恢复整个在线队列。
        settingsPreferences.getString("resume_online_track", null)
            ?.let { decodeOnlineResumeTrack(it) }
            ?.let { onlineTrack ->
                val nativeTrack = onlineTrackToNativeTrack(onlineTrack)
                if (nativeTrack.id == resumeId) {
                    onlineTrackById[nativeTrack.id] = onlineTrack
                    return _state.value.copy(
                        currentTrack = nativeTrack,
                        queue = listOf(nativeTrack),
                        queueIndex = 0,
                        positionMs = resumePosition,
                        status = "已恢复上次播放进度",
                    )
                }
            }
        val track = restoredQueue.firstOrNull { it.id == resumeId }
            ?: tracks.firstOrNull { it.id == resumeId }
            ?: return _state.value
        val queue = if (restoredQueue.any { it.id == track.id }) restoredQueue else restoredQueue + track
        val position = resumePosition.coerceIn(0, (track.durationMs - 1_000).coerceAtLeast(0))
        return _state.value.copy(
            currentTrack = track,
            queue = queue,
            queueIndex = queue.indexOf(track),
            positionMs = position,
            status = "已恢复上次播放进度",
        )
    }

    private fun resumePositionFor(track: NativeTrack): Long = _state.value
        .takeIf {
            it.currentTrack?.id == track.id &&
                (track.durationMs <= 0 || it.positionMs < track.durationMs - 1_000)
        }
        ?.positionMs
        ?.coerceAtLeast(0)
        ?: 0

    private fun saveResume() {
        val state = _state.value
        val track = state.currentTrack ?: return
        val position = state.positionMs.coerceAtLeast(0)
        if (!shouldSaveResume(position, track.durationMs)) return
        val onlineTrack = onlineTrackById[track.id]
        settingsPreferences.edit()
            .putLong("resume_track_id", track.id)
            .putLong("resume_position_ms", position)
            .apply {
                if (onlineTrack != null) putString("resume_online_track", encodeOnlineResumeTrack(onlineTrack))
                else remove("resume_online_track")
            }
            .apply()
    }

    private fun rememberHistory(
        protocol: String,
        id: String,
        name: String,
        location: String? = null,
        controlUrl: String? = null,
        renderingControlUrl: String? = null,
        renderingControlServiceType: String? = null,
    ) {
        val entries = (listOf(CastHistoryEntry(protocol, id, name, location, controlUrl, renderingControlUrl, renderingControlServiceType)) + _state.value.history)
            .distinctBy { "${it.protocol}:${it.id}" }.take(8)
        historyPreferences.edit().putString("devices", JSONArray().apply {
            entries.forEach { device -> put(JSONObject().put("protocol", device.protocol).put("id", device.id).put("name", device.name).put("location", device.location).put("controlUrl", device.controlUrl).put("renderingControlUrl", device.renderingControlUrl).put("renderingControlServiceType", device.renderingControlServiceType)) }
        }.toString()).apply()
        _state.value = _state.value.copy(history = entries)
    }

    private fun playAt(index: Int?) {
        val queue = _state.value.queue
        if (index != null) playFromQueue(queue, index)
    }

    /**
     * 手动「下一首」目标：与自动续播（[nextIndex]）分开。
     *
     * 区别只有一处——单曲循环下自动续播要重放本曲，而**手动**点下一首应该真的换一首
     * （重放本曲在用户看来就是按钮坏了）；因此这里按「回绕/顺序」两种手动语义处理。
     */
    private fun manualNextIndex(from: Int? = null): Int? {
        val state = _state.value
        if (state.queue.isEmpty()) return null
        val base = from?.takeIf { it in state.queue.indices } ?: state.queueIndex
        if (state.queueMode == NativeQueueMode.Shuffle && state.queue.size > 1) {
            var next = base
            while (next == base) next = Random.nextInt(state.queue.size)
            return next
        }
        return skipTargetIndex(base, state.queue.size, 1, wraps = queueModeWraps(state.queueMode))
    }

    private fun manualPreviousIndex(from: Int? = null): Int? {
        val state = _state.value
        if (state.queue.isEmpty()) return null
        val base = from?.takeIf { it in state.queue.indices } ?: state.queueIndex
        return skipTargetIndex(base, state.queue.size, -1, wraps = queueModeWraps(state.queueMode))
    }

    private fun previousIndex(): Int? {
        val state = _state.value
        if (state.queue.isEmpty()) return null
        return if (state.queueIndex > 0) state.queueIndex - 1
        else if (state.queueMode == NativeQueueMode.RepeatAll) state.queue.lastIndex else null
    }

    private fun nextIndex(): Int? {
        val state = _state.value
        if (state.queue.isEmpty()) return null
        if (state.queueMode == NativeQueueMode.RepeatOne) return state.queueIndex
        if (state.queueMode == NativeQueueMode.Shuffle && state.queue.size > 1) {
            var next = state.queueIndex
            while (next == state.queueIndex) next = Random.nextInt(state.queue.size)
            return next
        }
        if (state.queueIndex < state.queue.lastIndex) return state.queueIndex + 1
        return if (state.queueMode == NativeQueueMode.RepeatAll) 0 else null
    }

    /** 「正在播放」标记的最近一次输入（曲目、队列、上下文、换台态）：每秒一次的进度刷新里没变就跳过重算。 */
    private var playingMarkersKey: List<Any?>? = null

    /**
     * 刷新「正在播放」标记。当前播放条目一变就更新，供列表标注与按钮置灰判据使用。
     */
    private fun syncPlayingMarkers() {
        val state = _state.value
        val video = state.currentVideo
        val track = video ?: state.currentTrack
        // 队列身份用 Context 本身当键：进度每秒刷新时队列对象不变，避免每次 join 整条队列的 id。
        val contextIdentity: Any? = if (video != null) videoAlbumContext else albumPlaybackContext
        val cacheKey = listOf(track?.id, state.queue.size, contextIdentity)
        if (cacheKey == playingMarkersKey) return
        playingMarkersKey = cacheKey
        val referenceKey = track?.let { mediaReferenceForTrack(it) }?.let(::favoriteReferenceKey)
        val albumKey = when {
            video != null -> videoAlbumContext?.takeIf { videoQueueAlbumKey == it.key }?.key
            else -> albumPlaybackContext?.takeIf { it.queueKey == queueHistoryKey(state.queue) }?.key
        }
        _state.value = state.copy(
            playingReferenceKey = referenceKey,
            playingAlbumKey = albumKey,
        )
    }

    private fun updateMediaSession(forceNotification: Boolean = false) {
        syncPlayingMarkers()
        val current = _state.value
        val video = current.currentVideo
        val track = video ?: current.currentTrack
        val isVideo = video != null
        val durationMs = if (isVideo) current.videoDurationMs else track?.durationMs ?: 0
        val positionMs = if (isVideo) current.videoPositionMs else current.positionMs
        val playing = if (isVideo) current.videoPlaying else current.playing
        mediaSession.isActive = track != null
        if (track == null) {
            mediaSessionTrackId = null
            mediaSessionDurationMs = -1L
            notificationState = null
            return
        }
        if (mediaSessionTrackId != track.id || mediaSessionDurationMs != durationMs || mediaSessionIsVideo != isVideo) {
            mediaSession.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                    .build(),
            )
            mediaSessionTrackId = track.id
            mediaSessionDurationMs = durationMs
            mediaSessionIsVideo = isVideo
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs,
                    if (playing) if (isVideo) 1f else current.playbackSpeed.toFloat() else 0f,
                )
                .build(),
        )
        val nextNotificationState = Triple(track.id, playing, isVideo)
        if (forceNotification || notificationState != nextNotificationState) {
            notificationState = nextNotificationState
            MediaPlaybackService.show(
                getApplication(),
                mediaSession,
                track,
                playing,
            )
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        val bands = _state.value.eqBands.toFloatArray()
        player.setEq(bands, _state.value.eqPreamp, enabled)
        _state.value = _state.value.copy(eqEnabled = enabled)
        settingsPreferences.edit().putBoolean("eq_enabled", enabled).apply()
    }

    fun setEqBand(index: Int, gain: Float) {
        val bands = _state.value.eqBands.toMutableList()
        if (index in bands.indices) {
            bands[index] = gain.coerceIn(-15f, 15f)
            if (_state.value.eqEnabled) player.setEq(bands.toFloatArray(), _state.value.eqPreamp, true)
            _state.value = _state.value.copy(eqBands = bands, eqPreset = "自定义")
            settingsPreferences.edit().apply {
                bands.forEachIndexed { i, v -> putFloat("eq_band_$i", v) }
                putString("eq_preset", "自定义")
                apply()
            }
        }
    }

    fun setEqPreamp(preamp: Float) {
        val p = preamp.coerceIn(-12f, 12f)
        if (_state.value.eqEnabled) player.setEq(_state.value.eqBands.toFloatArray(), p, true)
        _state.value = _state.value.copy(eqPreamp = p, eqPreset = "自定义")
        settingsPreferences.edit().apply {
            putFloat("eq_preamp", p)
            putString("eq_preset", "自定义")
            apply()
        }
    }

    fun applyEqPreset(preset: EqPreset) {
        if (_state.value.eqEnabled) player.setEq(preset.bands, preset.preamp, true)
        _state.value = _state.value.copy(eqBands = preset.bands.toList(), eqPreamp = preset.preamp, eqPreset = preset.name)
        settingsPreferences.edit().apply {
            preset.bands.forEachIndexed { i, v -> putFloat("eq_band_$i", v) }
            putFloat("eq_preamp", preset.preamp)
            putString("eq_preset", preset.name)
            apply()
        }
    }

    fun setReverb(value: Int) = setAudioEffect { copy(reverb = value) }

    fun setChorus(value: Int) = setAudioEffect { copy(chorus = value) }

    fun setEcho(value: Int) = setAudioEffect { copy(echo = value) }

    fun setFlanger(value: Int) = setAudioEffect { copy(flanger = value) }

    fun setLoudness(value: Int) = setAudioEffect { copy(loudness = value) }

    fun setLoudnessCompress(on: Boolean) = setAudioEffect { copy(loudnessCompress = on) }

    private fun setAudioEffect(update: AudioEffects.() -> AudioEffects) {
        val effects = _state.value.audioEffects.update()
            .let { clamped ->
                clamped.copy(
                    reverb = clamped.reverb.coerceIn(0, 100),
                    chorus = clamped.chorus.coerceIn(0, 100),
                    echo = clamped.echo.coerceIn(0, 100),
                    flanger = clamped.flanger.coerceIn(0, 100),
                    loudness = clamped.loudness.coerceIn(-200, 0),
                )
            }
        _state.value = _state.value.copy(audioEffects = effects)
        saveAudioEffects(effects)
        player.setEffects(effects)
    }

    private fun loadAudioEffects(): AudioEffects {
        val alreadyLufs = settingsPreferences.getBoolean("audio_effect_loudness_lufs", false)
        val loudness = migrateLoudnessTarget(
            settingsPreferences.getInt("audio_effect_loudness", 0),
            alreadyLufs,
        )
        if (!alreadyLufs) settingsPreferences.edit()
            .putInt("audio_effect_loudness", loudness)
            .putBoolean("audio_effect_loudness_lufs", true)
            .apply()
        return AudioEffects(
            reverb = settingsPreferences.getInt("audio_effect_reverb", 0),
            chorus = settingsPreferences.getInt("audio_effect_chorus", 0),
            echo = settingsPreferences.getInt("audio_effect_echo", 0),
            flanger = settingsPreferences.getInt("audio_effect_flanger", 0),
            loudness = loudness,
            loudnessCompress = settingsPreferences.getBoolean("audio_effect_loudness_compress", false),
        ).let { e ->
        e.copy(
            reverb = e.reverb.coerceIn(0, 100),
            chorus = e.chorus.coerceIn(0, 100),
            echo = e.echo.coerceIn(0, 100),
            flanger = e.flanger.coerceIn(0, 100),
            loudness = e.loudness.coerceIn(-200, 0),
        )
        }
    }

    private fun saveAudioEffects(effects: AudioEffects) {
        settingsPreferences.edit().apply {
            putInt("audio_effect_reverb", effects.reverb)
            putInt("audio_effect_chorus", effects.chorus)
            putInt("audio_effect_echo", effects.echo)
            putInt("audio_effect_flanger", effects.flanger)
            putInt("audio_effect_loudness", effects.loudness)
            putBoolean("audio_effect_loudness_lufs", true)
            putBoolean("audio_effect_loudness_compress", effects.loudnessCompress)
            apply()
        }
    }

    /** 跳过片头片尾：按专辑优先、文件夹次之读取当前歌曲的跳过秒数（0 表示不跳过，支持 0.5 精度）。
     *  总开关关闭时一律返回 0——即使历史配置里存有秒数也不生效。 */
    fun skipConfigFor(track: NativeTrack?): Pair<Float, Float> {
        if (track == null) return 0f to 0f
        val albumKey = track.album.takeIf { it.isNotBlank() }?.let { "skip_album_$it" }
        val folderKey = track.folderPath.takeIf { it.isNotBlank() }?.let { "skip_folder_$it" }
        val albumPair = albumKey?.let { parseSkip(settingsPreferences.getString(it, null)) }
        val stored = albumPair
            ?: folderKey?.let { parseSkip(settingsPreferences.getString(it, null)) }
            ?: (0f to 0f)
        return gatedSkipSeconds(_state.value.skipIntroOutroEnabled, stored.first, stored.second)
    }

    /**
     * 跳过片头片尾总开关。开启时设置项才可见、跳过才生效；
     * 关闭时把当前歌曲所属范围的已存秒数清零（面板清零）并让 UI 隐藏设置项。
     */
    fun setSkipIntroOutroEnabled(enabled: Boolean) {
        settingsPreferences.edit().putBoolean(SKIP_INTRO_OUTRO_ENABLED_KEY, enabled).apply()
        _state.value = _state.value.copy(
            skipIntroOutroEnabled = enabled,
            status = if (enabled) "已开启跳过片头片尾。" else "已关闭跳过片头片尾，设置已清零。",
        )
        if (!enabled) clearSkipConfigForCurrentTrack()
    }

    /** 清零当前歌曲所属专辑/文件夹的跳过秒数（关闭开关时调用）。 */
    private fun clearSkipConfigForCurrentTrack() {
        val track = _state.value.currentTrack ?: return
        val key = track.album.takeIf { it.isNotBlank() }?.let { "skip_album_$it" }
            ?: track.folderPath.takeIf { it.isNotBlank() }?.let { "skip_folder_$it" }
            ?: return
        settingsPreferences.edit().putString(key, "0.0,0.0").apply()
    }

    /** 针对当前歌曲的上下文保存跳过秒数。album 非空时按专辑保存，否则按文件夹保存。 */
    fun saveSkipConfig(track: NativeTrack?, headSeconds: Float, tailSeconds: Float) {
        if (track == null) return
        val key = track.album.takeIf { it.isNotBlank() }?.let { "skip_album_$it" }
            ?: track.folderPath.takeIf { it.isNotBlank() }?.let { "skip_folder_$it" }
            ?: return
        val head = headSeconds.coerceAtLeast(0f)
        val tail = tailSeconds.coerceAtLeast(0f)
        settingsPreferences.edit().putString(key, "$head,$tail").apply()
    }

    private fun parseSkip(raw: String?): Pair<Float, Float>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",")
        val head = parts.getOrNull(0)?.toFloatOrNull() ?: return null
        val tail = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        return head.coerceAtLeast(0f) to tail.coerceAtLeast(0f)
    }

    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) return
        stopSleepTimerJob()
        val generation = sleepTimerGeneration
        val totalMs = minutes.toLong() * 60_000L
        _state.value = _state.value.copy(
            sleepTimerMode = SleepTimerMode.Minutes,
            sleepTimerTotalMs = totalMs,
            sleepTimerRemainingMs = totalMs,
            sleepTimerTotalTracks = 0,
            sleepTimerRemainingTracks = 0,
            sleepTimerStatus = "睡眠定时已启动：$minutes 分钟。",
        )
        sleepTimerJob = viewModelScope.launch {
            val startedElapsedMs = SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive && generation == sleepTimerGeneration) {
                val remaining = remainingSleepTimerMs(totalMs, startedElapsedMs, SystemClock.elapsedRealtime())
                if (generation != sleepTimerGeneration) return@launch
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
                if (remaining <= 0) {
                    finishSleepTimer("睡眠定时已暂停播放。")
                    return@launch
                }
                delay(minOf(500L, remaining))
            }
        }
    }

    fun startTrackSleepTimer(count: Int) {
        if (count <= 0) return
        if (_state.value.currentTrack == null) {
            _state.value = _state.value.copy(sleepTimerStatus = "请先选择一首歌曲。")
            return
        }
        stopSleepTimerJob()
        _state.value = _state.value.copy(
            sleepTimerMode = SleepTimerMode.Tracks,
            sleepTimerTotalMs = 0,
            sleepTimerRemainingMs = 0,
            sleepTimerTotalTracks = count,
            sleepTimerRemainingTracks = count,
            sleepTimerStatus = if (count == 1) {
                "睡眠定时已启动：播完本曲。"
            } else {
                "睡眠定时已启动：播完 $count 曲。"
            },
        )
    }

    fun cancelSleepTimer() {
        if (_state.value.sleepTimerMode == SleepTimerMode.None) return
        stopSleepTimerJob()
        clearSleepTimerState("睡眠定时已取消。")
    }

    private fun stopSleepTimerJob() {
        sleepTimerGeneration += 1
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private fun clearSleepTimerState(status: String?) {
        _state.value = _state.value.copy(
            sleepTimerMode = SleepTimerMode.None,
            sleepTimerTotalMs = 0,
            sleepTimerRemainingMs = 0,
            sleepTimerTotalTracks = 0,
            sleepTimerRemainingTracks = 0,
            sleepTimerStatus = status,
        )
    }

    private fun consumeTrackSleepTimerAtCompletion(): Boolean {
        val current = _state.value
        if (current.sleepTimerMode != SleepTimerMode.Tracks) return false
        return when (val result = consumeTrackSleepTimer(current.sleepTimerRemainingTracks)) {
            TrackSleepResult.Stop -> {
                finishSleepTimer("已播完设定曲目，已暂停播放。")
                true
            }
            is TrackSleepResult.Continue -> {
                _state.value = current.copy(
                    sleepTimerRemainingTracks = result.remainingTracks,
                    sleepTimerStatus = "睡眠定时运行中，还剩 ${result.remainingTracks} 曲。",
                )
                false
            }
        }
    }

    private fun finishSleepTimer(message: String) {
        val playback = _state.value
        sleepTimerGeneration += 1
        sleepTimerJob = null
        autoAdvanceInProgress = false
        recordPlaybackProgress(force = true)
        clearSleepTimerState(message)
        stopLyricTts()
        _state.value = _state.value.copy(playing = false, status = message)
        updateMediaSession()
        pausePlaybackForSleepTimer(playback)
    }

    private fun pausePlaybackForSleepTimer(playback: MusicUiState) {
        if (!playback.playing) return
        val capturedGeneration = playbackGeneration
        if (playback.isCasting) {
            activeRemote?.let { renderer ->
                viewModelScope.launch {
                    if (!shouldPauseForSleepTimer(capturedGeneration, playbackGeneration, _state.value.playing)) return@launch
                    runCatching { renderer.pause() }.onFailure {
                        if (shouldPauseForSleepTimer(capturedGeneration, playbackGeneration, _state.value.playing)) {
                            reportSleepTimerPauseFailure()
                        }
                    }
                }
                return
            }
            reportSleepTimerPauseFailure()
            return
        }
        localFadeTransitionInProgress = false
        pendingLocalPlayback = null
        player.pause()
    }

    private fun reportSleepTimerPauseFailure() {
        val message = "睡眠定时已结束，但未能暂停投送设备。"
        _state.value = _state.value.copy(status = message, sleepTimerStatus = message)
    }

    /** 加载系统 TTS 引擎列表；仅在本机未投送时展示可选引擎。 */
    fun loadTtsEngines() {
        ensureLyricTtsReady()
        val engines = lyricTts?.engines().orEmpty()
        if (engines.isNotEmpty()) handleTtsEnginesReady(engines)
    }

    /** 打开/关闭歌词跟唱朗读；开启时按已选引擎初始化，关闭时停止并清空状态。 */
    fun toggleLyricTts() {
        val enable = !_state.value.lyricTtsEnabled
        settingsPreferences.edit().putBoolean("lyric_tts_enabled", enable).apply()
        if (enable) {
            _state.value = _state.value.copy(
                lyricTtsEnabled = true,
                lyricTtsStatus = "正在读取系统语音引擎…",
            )
            lastSpokenLineIndex = -1
            lastSpokenTrackId = null
            loadTtsEngines()
        } else {
            stopLyricTts()
            _state.value = _state.value.copy(
                lyricTtsEnabled = false,
                ttsEngines = emptyList(),
                lyricTtsStatus = null,
            )
        }
    }

    /** 选择 TTS 引擎并持久化。 */
    fun setLyricTtsEngine(packageName: String) {
        ensureLyricTtsReady()
        val engine = _state.value.ttsEngines.firstOrNull { it.packageName == packageName } ?: return
        stopLyricTts()
        settingsPreferences.edit().putString("lyric_tts_engine", packageName).apply()
        _state.value = _state.value.copy(
            lyricTtsEngine = packageName,
            lyricTtsStatus = "正在切换到${engine.label}…",
        )
        lyricTts?.selectEngine(packageName)
    }

    /** 设置歌词播报偏移量（毫秒）：正值提前朗读，负值延后。 */
    fun setLyricTtsOffset(offsetMs: Long) {
        val clamped = normalizeLyricOffset(offsetMs)
        stopLyricTts()
        settingsPreferences.edit().putLong("lyric_tts_offset_ms", clamped).apply()
        _state.value = _state.value.copy(lyricTtsOffsetMs = clamped)
    }

    /** 设置 TTS 朗读输出通道（媒体/铃声/无障碍）；即时生效。 */
    fun setLyricTtsChannel(channel: LyricTtsChannel) {
        settingsPreferences.edit().putString("lyric_tts_channel", channel.value).apply()
        _state.value = _state.value.copy(lyricTtsChannel = channel)
        lyricTts?.channel = channel
    }

    private fun ensureLyricTtsReady() {
        if (lyricTts == null) {
            lyricTts = LyricTtsEngine(
                context = getApplication(),
                onEnginesReady = ::handleTtsEnginesReady,
                onEngineReady = ::handleTtsEngineReady,
                onUtteranceResult = { id, success ->
                    viewModelScope.launch { handleLyricTtsUtterance(id, success) }
                },
            )
            lyricTts?.channel = _state.value.lyricTtsChannel
        }
    }

    private fun handleTtsEnginesReady(engines: List<TtsEngineInfo>) {
        if (!_state.value.lyricTtsEnabled && !_state.value.videoSubtitleTtsEnabled) return
        val savedPackage = _state.value.lyricTtsEngine
        val selectedPackage = chooseTtsEnginePackage(savedPackage, engines)
        if (selectedPackage == null) {
            settingsPreferences.edit().remove("lyric_tts_engine").apply()
            lyricTts?.selectEngine(null)
            _state.value = _state.value.copy(
                ttsEngines = emptyList(),
                lyricTtsEngine = null,
                lyricTtsStatus = "未找到系统语音引擎，请在系统设置中安装语音。",
            )
            return
        }
        val selected = engines.first { it.packageName == selectedPackage }
        val fellBack = savedPackage != null && savedPackage != selectedPackage
        settingsPreferences.edit().putString("lyric_tts_engine", selectedPackage).apply()
        _state.value = _state.value.copy(
            ttsEngines = engines,
            lyricTtsEngine = selectedPackage,
            lyricTtsStatus = if (fellBack) {
                "原语音引擎不可用，已切换到${selected.label}。"
            } else {
                "正在初始化${selected.label}…"
            },
        )
        lyricTts?.selectEngine(selectedPackage)
    }

    private fun handleTtsEngineReady(packageName: String, ready: Boolean) {
        if ((!_state.value.lyricTtsEnabled && !_state.value.videoSubtitleTtsEnabled) || _state.value.lyricTtsEngine != packageName) return
        val label = _state.value.ttsEngines.firstOrNull { it.packageName == packageName }?.label ?: packageName
        val fallbackStatus = _state.value.lyricTtsStatus?.takeIf { it.startsWith("原语音引擎不可用") }
        _state.value = _state.value.copy(
            lyricTtsStatus = when {
                !ready -> "语音引擎初始化失败，请选择其他引擎。"
                fallbackStatus != null -> fallbackStatus
                else -> "语音引擎已就绪：$label。"
            },
        )
    }

    /** 歌词跟唱：在进度轮询中调用；本机播放时，当位置进入某句时间窗（叠加偏移量）即朗读该句。 */
    private fun followLyricLine(positionMs: Long) {
        if (!_state.value.lyricTtsEnabled) return
        val engine = lyricTts ?: return
        if (_state.value.lyricTtsEngine == null) return
        if (!engine.isReady()) return
        val currentTrack = _state.value.currentTrack ?: return
        // 仅当歌词文本变化时重新解析，避免轮询中反复解析 LRC。
        val lyricsText = _state.value.lyrics
        if (lyricsText != cachedLyricsText) {
            cachedLyricsText = lyricsText
            cachedLyricLines = parseLyrics(lyricsText)
        }
        val lines = cachedLyricLines
        if (lines.isEmpty()) return
        val target = if (_state.value.lyricTtsOffsetMs != 0L) {
            activeLyricIndex(lines, positionMs + _state.value.lyricTtsOffsetMs)
        } else {
            activeLyricIndex(lines, positionMs)
        }
        if (target < 0) return
        // 仅在切句时朗读；同一句不重复。
        if (target == lastSpokenLineIndex && currentTrack.id == lastSpokenTrackId) return
        if (pendingLyricUtterance?.let { it.trackId == currentTrack.id && it.lineIndex == target } == true) return
        if (lines[target].text.isBlank()) return
        enqueueLyricTtsLine(currentTrack.id, target, lines[target].text, 0)
    }

    private fun enqueueLyricTtsLine(trackId: Long, lineIndex: Int, text: String, attempt: Int, source: String = "lyric") {
        val engine = lyricTts ?: return
        val utteranceId = "${source}_${++lyricTtsUtteranceSequence}"
        pendingLyricUtterance = PendingLyricUtterance(utteranceId, trackId, lineIndex, text, attempt, source)
        when (engine.speak(text, utteranceId)) {
            LyricTtsSpeakResult.Queued -> Unit
            LyricTtsSpeakResult.NotReady -> pendingLyricUtterance = null
            LyricTtsSpeakResult.Failed -> handleLyricTtsUtterance(utteranceId, false)
        }
    }

    private fun handleLyricTtsUtterance(utteranceId: String, success: Boolean) {
        val pending = pendingLyricUtterance?.takeIf { it.id == utteranceId } ?: return
        pendingLyricUtterance = null
        val isVideoSubtitle = pending.source == VIDEO_SUBTITLE_TTS_SOURCE
        val currentId = if (isVideoSubtitle) _state.value.currentVideo?.id else _state.value.currentTrack?.id
        val enabled = if (isVideoSubtitle) _state.value.videoSubtitleTtsEnabled else _state.value.lyricTtsEnabled
        if (!enabled || currentId != pending.trackId) return
        if (success) {
            if (isVideoSubtitle) {
                lastSpokenVideoLineIndex = pending.lineIndex
                lastSpokenVideoId = pending.trackId
            } else {
                lastSpokenLineIndex = pending.lineIndex
                lastSpokenTrackId = pending.trackId
            }
            if (_state.value.lyricTtsStatus?.startsWith("歌词朗读失败") == true) {
                _state.value = _state.value.copy(lyricTtsStatus = "歌词朗读已恢复。")
            }
            return
        }
        if (shouldRetryLyricTtsFailure(pending.attempt)) {
            _state.value = _state.value.copy(lyricTtsStatus = "语音朗读失败，正在重试本句。")
            enqueueLyricTtsLine(pending.trackId, pending.lineIndex, pending.text, pending.attempt + 1, pending.source)
        } else {
            if (isVideoSubtitle) {
                lastSpokenVideoLineIndex = pending.lineIndex
                lastSpokenVideoId = pending.trackId
            } else {
                lastSpokenLineIndex = pending.lineIndex
                lastSpokenTrackId = pending.trackId
            }
            _state.value = _state.value.copy(lyricTtsStatus = "语音朗读失败，已跳过本句。")
        }
    }

    /** 停止跟唱朗读（切歌/暂停/退出时调用），并清空已朗读句记录。 */
    private fun stopLyricTts() {
        pendingLyricUtterance = null
        lyricTts?.stop()
        lastSpokenLineIndex = -1
        lastSpokenTrackId = null
    }

    override fun onCleared() {
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        streamSeekJob?.cancel()
        networkCallback?.let { callback ->
            (getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.runCatching { unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        audioOutputController.stopObserving(audioDeviceCallback)
        mediaSession.release()
        stopLyricTts()
        lyricTts?.shutdown()
        lyricTts = null
        super.onCleared()
    }

    /** 导出应用日志与系统日志，生成文本文件后弹出系统分享面板。
     *  @param clearBefore 为 true 时先清空旧日志，导出的内容只包含清空后产生的新日志。 */
    fun exportLogs(clearBefore: Boolean = false) {
        if (_state.value.logExporting) return
        _state.value = _state.value.copy(logExporting = true, logExportAnnouncement = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { LogExporter.collect(getApplication(), clearBefore) }
                .onSuccess { file ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        val uri = LogExporter.shareUri(getApplication(), file)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(resolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching {
                        getApplication<Application>().startActivity(Intent.createChooser(intent, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onSuccess {
                        _state.value = _state.value.copy(logExporting = false, logExportAnnouncement = "日志已生成，请选择保存或分享位置")
                    }.onFailure {
                        _state.value = _state.value.copy(logExporting = false, logExportAnnouncement = "日志导出失败：没有可用的分享应用")
                    }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(logExporting = false, logExportAnnouncement = "日志导出失败：${e.message}")
                }
        }
    }

    fun clearLogExportAnnouncement() {
        _state.value = _state.value.copy(logExportAnnouncement = null)
    }

    /** 切换「导出前清空旧日志」开关。 */
    fun toggleLogExportClearBefore() {
        _state.value = _state.value.copy(logExportClearBefore = !_state.value.logExportClearBefore)
    }

    /** 清空系统日志缓冲与应用此前导出的日志文件，便于后续导出更精简的日志。 */
    fun clearLogs() {
        if (_state.value.logClearing) return
        _state.value = _state.value.copy(logClearing = true, logClearAnnouncement = null)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { LogExporter.clear(getApplication()) }.getOrDefault(false)
            _state.value = _state.value.copy(
                logClearing = false,
                logClearAnnouncement = if (ok) "日志已清空，可重新操作以收集最新日志" else "日志清空失败",
            )
        }
    }

    fun clearLogClearAnnouncement() {
        _state.value = _state.value.copy(logClearAnnouncement = null)
    }

    private fun queryTracks(): List<NativeTrack> {
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Audio.AudioColumns.BITRATE,
            folderColumn,
        )
        val audioTracks = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            buildList {
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val bitrate = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.BITRATE)
                val folder = cursor.getColumnIndexOrThrow(folderColumn)
                while (cursor.moveToNext()) {
                    val trackId = cursor.getLong(id)
                    val displayName = cursor.getString(name) ?: "未知歌曲"
                    add(
                        NativeTrack(
                            id = trackId,
                            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId),
                            title = displayName,
                            artist = cursor.getString(artist).orEmpty().ifBlank { "未知艺术家" },
                            album = cursor.getString(album).orEmpty(),
                            durationMs = cursor.getLong(duration).coerceAtLeast(0),
                            format = displayName.substringAfterLast('.', "音频").uppercase(),
                            mimeType = cursor.getString(mime).orEmpty().ifBlank { "audio/*" },
                            folderPath = cursor.getString(folder).orEmpty().trim('/').let { path -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) path else path.substringBeforeLast('/', "") },
                            sizeBytes = cursor.getLong(size).coerceAtLeast(0),
                            modifiedTimeMs = cursor.getLong(modified).coerceAtLeast(0) * 1_000,
                            bitrateKbps = cursor.getInt(bitrate).coerceAtLeast(0) / 1_000,
                        ),
                    )
                }
            }
        } ?: emptyList()
        return expandCueTracks(audioTracks)
    }

    private fun queryVideos(): List<NativeTrack> {
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            folderColumn,
        )
        val videos = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val folder = cursor.getColumnIndexOrThrow(folderColumn)
            buildList {
                while (cursor.moveToNext()) {
                    val videoId = cursor.getLong(id)
                    val displayName = cursor.getString(name) ?: "未知视频"
                    val folderValue = cursor.getString(folder).orEmpty().trim('/')
                    add(
                        NativeTrack(
                            id = videoId,
                            uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId),
                            title = displayName,
                            artist = "",
                            album = "",
                            durationMs = cursor.getLong(duration).coerceAtLeast(0),
                            format = displayName.substringAfterLast('.', "视频").uppercase(),
                            mimeType = cursor.getString(mime).orEmpty().ifBlank { "video/*" },
                            folderPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folderValue else folderValue.substringBeforeLast('/', ""),
                            sizeBytes = cursor.getLong(size).coerceAtLeast(0),
                            modifiedTimeMs = cursor.getLong(modified).coerceAtLeast(0) * 1_000,
                            isVideo = true,
                        ),
                    )
                }
            }
        } ?: emptyList()
        // 补充 MediaStore 未按视频索引的 RealMedia（.rm/.rmvb/.rmm），走 FFmpeg 兜底引擎播放。
        val rmvb = queryRmvbTracks(folderColumn)
        val seen = videos.mapTo(mutableSetOf()) { it.folderPath to it.title }
        return videos + rmvb.filter { (it.folderPath to it.title) !in seen }
    }

    /** 查询 MediaStore.Files 中扩展名为 .rm/.rmvb/.rmm 的 RealMedia 文件，作为视频条目。 */
    private fun queryRmvbTracks(folderColumn: String): List<NativeTrack> = runCatching {
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                folderColumn,
            ),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.rm' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.rmvb' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.rmm'",
            null,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val folder = cursor.getColumnIndexOrThrow(folderColumn)
            buildList {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(name).orEmpty()
                    if (!isRmvbLikeVideoFileName(displayName)) continue
                    val rowId = cursor.getLong(id)
                    val folderValue = cursor.getString(folder).orEmpty().trim('/')
                    add(
                        NativeTrack(
                            id = rowId,
                            uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), rowId),
                            title = displayName,
                            artist = "",
                            album = "",
                            durationMs = 0,
                            format = displayName.substringAfterLast('.', "视频").uppercase(),
                            mimeType = "video/x-pn-realvideo",
                            folderPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folderValue else folderValue.substringBeforeLast('/', ""),
                            sizeBytes = cursor.getLong(size).coerceAtLeast(0),
                            modifiedTimeMs = cursor.getLong(modified).coerceAtLeast(0) * 1_000,
                            isVideo = true,
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun expandCueTracks(
        audioTracks: List<NativeTrack>,
        cueSheets: List<CueSheet>? = null,
    ): List<NativeTrack> {
        if (audioTracks.isEmpty()) return audioTracks
        val sources = audioTracks.associateBy { cueSourceKey(it.folderPath, it.title) }
        val replacedSourceIds = mutableSetOf<Long>()
        val cueTracks = buildList {
            (cueSheets ?: queryCueSheets(audioTracks)).forEach { sheet ->
                val parsed = parseCue(sheet.contents) ?: return@forEach
                parsed.files.forEach { file ->
                    val source = sources[cueSourceKey(sheet.folderPath, file.sourceFileName)]
                        ?: sheet.takeIf { it.folderPath.isBlank() }?.let {
                            audioTracks.singleOrNull { track -> track.title.equals(file.sourceFileName, true) }
                        }
                        ?: return@forEach
                    if (!replacedSourceIds.add(source.id)) return@forEach
                    val entries = file.entries.sortedBy { it.startMs }
                    val splitTracks = entries.mapIndexedNotNull { index, entry ->
                        val duration = cueSegmentDurationMs(
                            source.durationMs,
                            entry.startMs,
                            entries.getOrNull(index + 1)?.startMs,
                        )
                        if (duration <= 0) null else source.copy(
                            id = source.id xor (entry.number.toLong() shl 56),
                            title = entry.title.ifBlank { "${source.title.substringBeforeLast('.') } ${entry.number}" },
                            artist = entry.performer.ifBlank { parsed.performer.ifBlank { source.artist } },
                            album = parsed.album.ifBlank { source.album },
                            durationMs = duration,
                            sizeBytes = 0,
                            cueStartMs = entry.startMs,
                            isCueTrack = true,
                        )
                    }
                    if (splitTracks.isEmpty()) replacedSourceIds.remove(source.id) else addAll(splitTracks)
                }
            }
        }
        return if (cueTracks.isEmpty()) audioTracks else audioTracks.filterNot { it.id in replacedSourceIds } + cueTracks
    }

    /** CUE 内容缓存（按 路径+修改时间 失效），避免每次扫描都重读全部 CUE 文件。 */
    private val cueSheetCache = SimpleLruCache<String, Pair<Long, String>>(
        maxEntries = 512,
        maxWeight = 16L * 1024 * 1024,
        weightOf = { it.second.length.toLong() },
    )

    private fun readCueCached(cacheKey: String, modifiedMs: Long, read: () -> String?): String? {
        cueSheetCache.get(cacheKey)?.takeIf { it.first == modifiedMs }?.let { return it.second }
        val contents = read() ?: return null
        cueSheetCache.put(cacheKey, modifiedMs to contents)
        return contents
    }

    private fun queryCueSheets(audioTracks: List<NativeTrack>): List<CueSheet> {
        val directSheets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            audioTracks.asSequence()
                .map { it.folderPath }
                .distinct()
                .flatMap { folderPath ->
                    File(Environment.getExternalStorageDirectory(), folderPath)
                        .listFiles { _, name -> isCueSheetName(name) }
                        .orEmpty()
                        .asSequence()
                        .mapNotNull { file ->
                            runCatching {
                                val contents = readCueCached("dir:${file.absolutePath}", file.lastModified()) {
                                    decodeText(file.readBytes())
                                }
                                contents?.let { CueSheet(folderPath, file.name, it) }
                            }.getOrNull()
                        }
                }
                .toList()
        } else emptyList()
        val mediaStoreSheets = runCatching {
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, folderColumn),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.cue' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.cue.txt'",
            null,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val folder = cursor.getColumnIndexOrThrow(folderColumn)
            buildList {
                while (cursor.moveToNext()) {
                    if (!isCueSheetName(cursor.getString(name).orEmpty())) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cursor.getLong(id))
                    val contents = readCueCached("uri:$uri", cursor.getLong(modified).coerceAtLeast(0) * 1_000) {
                        resolver.openInputStream(uri)?.use { decodeText(it.readBytes()) }
                    } ?: continue
                    add(CueSheet(
                        folderPath = cursor.getString(folder).orEmpty().trim('/').let { path -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) path else path.substringBeforeLast('/', "") },
                        fileName = cursor.getString(name).orEmpty(),
                        contents = contents,
                    ))
                }
            }
        }.orEmpty()
        }.getOrDefault(emptyList())
        return (directSheets + mediaStoreSheets).distinctBy { it.folderPath to it.contents }
    }

    private fun parseCue(contents: String): ParsedCue? {
        var album = ""
        var performer = ""
        var currentFile: String? = null
        var current: CueEntry? = null
        val pendingEntries = mutableListOf<CueEntry>()
        val files = mutableListOf<CueFile>()
        val trackPattern = Regex("^\\s*TRACK\\s+(\\d+)\\s+AUDIO\\s*$", RegexOption.IGNORE_CASE)
        val indexPattern = Regex("^\\s*INDEX\\s+01\\s+(\\d+):(\\d+):(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        contents.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("FILE ", true) -> {
                    current?.let(pendingEntries::add)
                    current = null
                    flushCueFile(currentFile, pendingEntries, files)
                    currentFile = line.substringAfter(' ').substringBeforeLast(' ').trim()
                        .trim('"').substringAfterLast('\\').substringAfterLast('/')
                }
                trackPattern.matches(line) -> {
                    current?.let(pendingEntries::add)
                    current = CueEntry(trackPattern.matchEntire(line)!!.groupValues[1].toInt())
                }
                indexPattern.matches(line) && current != null -> {
                    val values = indexPattern.matchEntire(line)!!.groupValues
                    current!!.startMs = cueTimeMs(values[1], values[2], values[3])
                }
                line.startsWith("TITLE ", true) -> {
                    if (current == null) album = cueValue(line.substringAfter(' '))
                    else current!!.title = cueValue(line.substringAfter(' '))
                }
                line.startsWith("PERFORMER ", true) -> {
                    if (current == null) performer = cueValue(line.substringAfter(' '))
                    else current!!.performer = cueValue(line.substringAfter(' '))
                }
            }
        }
        current?.let(pendingEntries::add)
        flushCueFile(currentFile, pendingEntries, files)
        val parsed = files.map { CueFile(it.sourceFileName, it.entries.filter { e -> e.startMs >= 0 }) }
            .filter { it.entries.isNotEmpty() }
        return if (parsed.isEmpty()) null else ParsedCue(parsed, album, performer)
    }

    private fun flushCueFile(
        sourceFileName: String?,
        pendingEntries: MutableList<CueEntry>,
        files: MutableList<CueFile>,
    ) {
        sourceFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
            if (pendingEntries.isNotEmpty()) files.add(CueFile(fileName, pendingEntries.toList()))
        }
        pendingEntries.clear()
    }

    private fun cueSourceKey(folderPath: String, fileName: String): String = "${folderPath.trim('/')}/${fileName.trim()}".lowercase()

    private fun isCueSheetName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".cue") || lower.endsWith(".cue.txt")
    }

    private fun cueValue(value: String): String = value.trim().removeSurrounding("\"").trim()

    private fun loadExternalLyrics(track: NativeTrack): String? =
        mediaStoreLyrics(track) ?: directFileLyrics(track)

    private fun mediaStoreLyrics(track: NativeTrack): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val title = track.title.substringBeforeLast('.', track.title).trim()
        if (title.isBlank()) return null
        return runCatching {
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.lrc'",
            null,
            null,
        )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val folderCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    if (!cursor.getString(folderCol).orEmpty().trim('/').equals(track.folderPath, true)) continue
                    if (!name.endsWith(".lrc", true)) continue
                    if (!name.substringBeforeLast('.').equals(title, true)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cursor.getLong(id))
                    resolver.openInputStream(uri)?.use { input ->
                        val text = decodeText(input.readBytes()).removePrefix("\uFEFF").trim()
                        if (text.isNotBlank()) return text
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun directFileLyrics(track: NativeTrack): String? {
        val canDirectRead = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        return if (!canDirectRead) null else runCatching {
            val title = track.title.substringBeforeLast('.', track.title).trim()
            if (title.isBlank()) null else {
                val folder = File(Environment.getExternalStorageDirectory(), track.folderPath)
                val lyricFile = folder.listFiles()?.firstOrNull {
                    it.isFile && it.extension.equals("lrc", true) && it.nameWithoutExtension.equals(title, true)
                }
                lyricFile?.let { decodeText(it.readBytes()).removePrefix("\uFEFF").trim().takeIf(String::isNotBlank) }
            }
        }.getOrNull()
    }

    private fun decodeText(bytes: ByteArray): String {
        var offset = 0
        var charset: Charset = Charsets.UTF_8
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                offset = 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                offset = 2
                charset = Charsets.UTF_16LE
            }
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                offset = 2
                charset = Charsets.UTF_16BE
            }
        }
        val decoded = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        }
        if (decoded.isSuccess) return decoded.getOrThrow()
        return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun cueTimeMs(minutes: String, seconds: String, frames: String): Long = (
        (minutes.toLongOrNull() ?: 0) * 60_000 +
            (seconds.toLongOrNull() ?: 0) * 1_000 +
            (frames.toLongOrNull() ?: 0) * 1_000 / 75
        )

    private data class FolderImport(
        val audioTracks: List<NativeTrack>,
        val cueSheets: List<CueSheet>,
        val frontCoverUri: String?,
        val backCoverUri: String?,
        val description: String?,
    )
    private data class CueSheet(val folderPath: String, val fileName: String, val contents: String)
    private data class CueFile(val sourceFileName: String, val entries: List<CueEntry>)
    private data class ParsedCue(val files: List<CueFile>, val album: String, val performer: String)
    private data class CueEntry(val number: Int, var title: String = "", var performer: String = "", var startMs: Long = -1)
}

/**
 * 从本地曲目推导在线歌词搜索标题（匹配规则：歌曲名 + 歌手）。
 * 本地 title 来自 MediaStore DISPLAY_NAME，含扩展名与常见曲号/歌手前缀（如 “08 - 周杰伦 - Mojito.flac”），
 * 直接拿去「歌名+歌手」精确匹配永远失败；这里去掉扩展名、曲号前缀与已知歌手前缀。
 */
internal fun localLyricSearchTitle(title: String, artist: String = ""): String {
    var search = title.substringBeforeLast('.', title).trim()
    // 去掉曲号前缀："08 - "、"002."、"01-"、"11 - "。
    search = search.replace(Regex("""^\d+\s*[-–—.、]\s*"""), "").trim()
    // 若已知歌手以 "歌手 - "（或 "歌手-"、"歌手 "）开头，去掉该前缀，避免带进搜索与精确匹配。
    val known = artist.trim()
    if (known.isNotBlank() && !known.equals("未知艺术家", true) && !known.equals("<unknown>", true)) {
        search = search.removePrefix("$known - ").removePrefix("$known-").removePrefix("$known ").trim()
    }
    return search
}

/**
 * 推导本地歌词搜索标题候选：先完整归一化名；若歌手未知而文件名形如 “歌手 - 标题”，
 * 再补 “标题” 作为候选，供宽松匹配逐步尝试，提高命中率。
 */
internal fun localLyricSearchTitles(title: String, artist: String): List<String> {
    val primary = localLyricSearchTitle(title, artist)
    val candidates = mutableListOf<String>()
    if (primary.isNotBlank()) candidates.add(primary)
    if (primary.contains(" - ")) {
        val last = primary.substringAfterLast(" - ").trim()
        if (last.isNotBlank() && last != primary) candidates.add(last)
    }
    return candidates.distinct()
}
