package com.example.local_music_player

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceView
import android.graphics.Bitmap
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Platform-only device tests: generated media, actual playback clocks, no test dependencies. */
class PlaybackEngineInstrumentation : Instrumentation() {
    private lateinit var outputView: SurfaceView
    private var cases: Set<String>? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cases = arguments?.getString("cases")?.split(',')?.toSet()
        start()
    }

    override fun onStart() {
        val results = mutableListOf<String>()
        var failures = 0
        var activity: Activity? = null
        val originalProxy = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = if (uri.host == "127.0.0.1") listOf(Proxy.NO_PROXY)
                else originalProxy?.select(uri) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
                originalProxy?.connectFailed(uri, address, error)
            }
        })
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = activity
            outputView = main { SurfaceView(host).also { host.setContentView(it) } }
            await { main { outputView.holder.surface.isValid } }
            val root = File(targetContext.cacheDir, "playback-qa").apply { mkdirs() }
            copyAssets("", root)
            FixtureServer(root).use { server ->
                fun test(name: String, block: () -> Unit) {
                    if (cases != null && name !in cases!!) return
                    val start = SystemClock.elapsedRealtime()
                    try {
                        block()
                        results += "PASS $name ${SystemClock.elapsedRealtime() - start}ms"
                    } catch (e: Throwable) {
                        failures++
                        results += "FAIL $name ${e.stackTraceToString()}"
                    }
                    sendStatus(0, Bundle().apply { putString("stream", results.last() + "\n") })
                }
                test("ffmpeg_formats_pause_seek") {
                    for (name in listOf("tone.wv", "tone.tta", "tone.wma", "tone.m4a", "cover.flac", "silence.dsf")) {
                        withFfmpeg { player, error ->
                            val started = SystemClock.elapsedRealtime()
                            main { player.play(Uri.fromFile(File(root, name)), 0) }
                            await(error) { main { player.positionMs() } > 0 }
                            val firstPcm = SystemClock.elapsedRealtime() - started
                            await(error) { main { player.positionMs() } >= 300 }
                            main { player.pause() }
                            val paused = main { player.positionMs() }
                            SystemClock.sleep(250)
                            check(kotlin.math.abs(main { player.positionMs() } - paused) < 60) { "$name pause advanced" }
                            main { player.seekTo(2_000); player.resume() }
                            await(error) { main { player.positionMs() } >= 2_400 }
                            results += "PCM $name firstPlaybackHeadMs=$firstPcm advanced / paused / seeked"
                        }
                    }
                }
                test("ffmpeg_tail_and_replay") {
                    withFfmpeg { player, error ->
                        val ended = AtomicReference<Long?>()
                        main { player.onComplete = { ended.set(player.positionMs()) }; player.play(Uri.fromFile(File(root, "tone.wv")), 4_500) }
                        await(error) { ended.get() != null }
                        check(ended.get()!! >= 5_950) { "PCM tail truncated at ${ended.get()}" }
                        main { player.resume() }
                        await(error) { main { player.positionMs() } in 200..1_500 }
                    }
                }
                test("ffmpeg_video_surface") {
                    withFfmpeg(requireVideo = true) { player, error ->
                        main { player.setSurface(outputView.holder.surface); player.play(Uri.fromFile(File(root, "legacy.wmv")), 0) }
                        await(error) { main { player.positionMs() } > 500 }
                        capture("playback-qa-ffmpeg.png")
                        main { player.pause() }
                        val paused = main { player.positionMs() }
                        SystemClock.sleep(200)
                        check(main { player.positionMs() } == paused)
                        main { player.seekTo(2_000); player.resume() }
                        await(error) { main { player.positionMs() } > 2_500 }
                    }
                }
                test("ffmpeg_video_realtime") {
                    for (uri in listOf(Uri.fromFile(File(root, "legacy.wmv")), Uri.parse(server.url + "/legacy.wmv"))) {
                        val host = outputView.context as Activity
                        outputView = main { SurfaceView(host).also { host.setContentView(it) } }
                        await { main { outputView.holder.surface.isValid } }
                        withFfmpeg(requireVideo = true) { player, error ->
                            main { player.setSurface(outputView.holder.surface); player.play(uri, 0) }
                            await(error) { main { player.positionMs() } >= 300 }
                            val position = main { player.positionMs() }
                            val started = SystemClock.elapsedRealtime()
                            SystemClock.sleep(3_000)
                            error.get()?.let { error(it) }
                            val advanced = main { player.positionMs() } - position
                            val elapsed = SystemClock.elapsedRealtime() - started
                            results += "video_realtime scheme=${uri.scheme} advancedMs=$advanced elapsedMs=$elapsed"
                            check(advanced in (elapsed - 400)..(elapsed + 300)) { "video/audio stalled: $advanced media ms / $elapsed wall ms" }
                        }
                    }
                }
                test("ffmpeg_cancel_open_and_pause_before_ready") {
                    withFfmpeg { player, error ->
                        main { player.play(Uri.parse(server.url + "/stall"), 0) }
                        await { server.hits("/stall") > 0 }
                        val start = SystemClock.elapsedRealtime()
                        main { player.stopAndClear(); player.play(Uri.fromFile(File(root, "tone.wv")), 0); player.pause() }
                        val stopUiMs = SystemClock.elapsedRealtime() - start
                        results += "cancel_open_ui_ms=$stopUiMs"
                        check(stopUiMs < 300) { "stop blocked UI" }
                        SystemClock.sleep(700)
                        check(main { player.positionMs() } == 0L && !main { player.isPlaying() }) { "paused open started output" }
                        main { player.resume() }
                        await(error, 4_000) { main { player.positionMs() } > 300 }
                    }
                }
                test("media3_hls_no_extension_headers_and_aes128") {
                    playStream(server, "/hls/manifest", "application/vnd.apple.mpegurl", seek = true)
                    playStream(server, "/encrypted/manifest", "audio/x-mpegurl; charset=utf-8", seek = false)
                    check(server.hits("/encrypted/key") > 0) { "AES key not requested" }
                    check(server.missingHeaders == 0) { "request headers lost on child request" }
                }
                test("media3_hls_live_manifest_refresh") {
                    val before = server.hits("/hls/live")
                    playStream(server, "/hls/live", "hls", seek = false)
                    check(server.hits("/hls/live") - before >= 2) { "live manifest did not refresh" }
                }
                test("media3_hls_event_manifest_refresh") { playStream(server, "/hls/event", "hls", seek = false) }
                test("media3_dash") { playStream(server, "/dash/index.mpd", "application/dash+xml", seek = true) }
                test("hybrid_switch_and_cue") {
                    val engine = main { HybridAudioEngine(Media3AudioEngine(targetContext), FfmpegAudioEngine(targetContext)) }
                    try {
                        main { engine.playUrl(Uri.fromFile(File(root, "tone.wv")).toString(), 6_000, 0) }
                        await { main { engine.positionMs() } > 200 }
                        main { engine.playUrl(Uri.fromFile(File(root, "tone.wav")).toString(), 6_000, 0) }
                        await { main { engine.positionMs() } > 200 }
                        main { engine.playUrl(Uri.fromFile(File(root, "tone.tta")).toString(), 6_000, 0); engine.pause() }
                        SystemClock.sleep(500)
                        check(!main { engine.isPlaying() })
                        main { engine.resume() }
                        await { main { engine.positionMs() } > 200 }
                        main { engine.play(NativeTrack(
                            id = 1, uri = Uri.fromFile(File(root, "tone.wv")), title = "CUE QA", artist = "", album = "",
                            durationMs = 700, format = "wv", mimeType = "audio/wavpack", folderPath = root.path,
                            cueStartMs = 2_000, isCueTrack = true,
                        ), 0) }
                        await { main { engine.positionMs() } > 100 }
                        check(main { engine.durationMs() } == 700L)
                        await { main { engine.isStopped() } }
                    } finally { main { engine.release() } }
                }
            }
        } catch (e: Throwable) {
            failures++
            results += "FAIL setup ${e.stackTraceToString()}"
        } finally {
            ProxySelector.setDefault(originalProxy)
            activity?.let { current -> main { current.finish() } }
        }
        File(targetContext.filesDir, "playback-qa.txt").writeText(results.joinToString("\n"))
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", results.joinToString("\n") + "\nfailures=$failures\n") })
    }

    private fun withFfmpeg(requireVideo: Boolean = false, block: (FfmpegFallbackPlayer, AtomicReference<String?>) -> Unit) {
        val error = AtomicReference<String?>()
        val player = main { FfmpegFallbackPlayer(targetContext, requireVideoTrack = requireVideo).apply { onError = { error.set(it) } } }
        try { block(player, error) } finally { main { player.release() } }
    }

    private fun playStream(server: FixtureServer, path: String, mime: String, seek: Boolean, minimumPosition: Long = 600) {
        // CPU/FFmpeg 与 MediaCodec 是不同 Surface producer；每个场景使用独立窗口，模拟正式 UI 的切换。
        val host = outputView.context as Activity
        outputView = main { SurfaceView(host).also { host.setContentView(it) } }
        await { main { outputView.holder.surface.isValid } }
        val error = AtomicReference<String?>()
        val engine = main { ExoPlayerVideoEngine(targetContext).apply {
            onError = { error.set(it) }; onUnsupported = { error.set(it) }
            player.setVideoSurfaceView(outputView)
            play(Uri.parse(server.url + path), 0, mapOf("X-Playback-Test" to "fixture"), mime)
        } }
        try {
            await(error, 20_000) { main { engine.isPlaying() && engine.positionMs() > minimumPosition } }
            if (path.endsWith("/live") || path.endsWith("/event")) {
                await(error, 12_000) { server.hits(path) >= 3 && main { engine.isPlaying() } }
            }
            check(main { (engine.player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 }) { "No video frames rendered" }
            if (path == "/hls/manifest") capture("playback-qa-hls.png")
            main { engine.pause() }
            await(error) { !main { engine.isPlaying() } }
            // 等内部播放线程确认 pause，而不是只读立即更新的应用线程 playWhenReady。
            var paused = main { engine.positionMs() }
            var stableSince = SystemClock.elapsedRealtime()
            val pauseDeadline = stableSince + 2_000
            while (SystemClock.elapsedRealtime() - stableSince < 300) {
                SystemClock.sleep(50)
                val current = main { engine.positionMs() }
                if (kotlin.math.abs(current - paused) > 20) { paused = current; stableSince = SystemClock.elapsedRealtime() }
                check(SystemClock.elapsedRealtime() < pauseDeadline) { "pause did not settle" }
            }
            SystemClock.sleep(200)
            val afterPause = main { engine.positionMs() }
            check(kotlin.math.abs(afterPause - paused) < 60) { "$path paused clock moved $paused -> $afterPause" }
            if (seek) {
                main { engine.seekTo(5_000); engine.resume() }
                await(error) { main { engine.positionMs() } > 5_500 }
            }
        } catch (failure: Throwable) {
            throw IllegalStateException("$path requests=${server.hits(path)} " + main {
                "state=${engine.player.playbackState} position=${engine.positionMs()} buffered=${engine.player.bufferedPosition} " +
                    "playWhenReady=${engine.player.playWhenReady} suppression=${engine.player.playbackSuppressionReason} " +
                    "frames=${engine.player.videoDecoderCounters?.renderedOutputBufferCount}"
            }, failure)
        } finally { main { engine.release() } }
    }

    private fun capture(name: String) {
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun await(error: AtomicReference<String?> = AtomicReference(), timeout: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            error.get()?.let { error(it) }
            check(SystemClock.elapsedRealtime() < deadline) { "playback condition timed out" }
            SystemClock.sleep(30)
        }
    }

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun copyAssets(path: String, target: File) {
        for (name in context.assets.list(path).orEmpty()) {
            val child = if (path.isEmpty()) name else "$path/$name"
            val file = File(target, name)
            if (context.assets.list(child).orEmpty().isNotEmpty()) {
                file.mkdirs(); copyAssets(child, file)
            } else context.assets.open(child).use { input -> file.outputStream().use(input::copyTo) }
        }
    }
}

private class FixtureServer(private val root: File) : AutoCloseable {
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val counts = ConcurrentHashMap<String, Int>()
    private var liveBorn = 0L
    @Volatile var missingHeaders = 0
    val url = "http://127.0.0.1:${server.localPort}"
    fun hits(path: String) = counts[path] ?: 0

    init {
        pool.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { runCatching { socket.use(::respond) } }
            }
        }
    }

    private fun respond(socket: Socket) {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader()
        val first = reader.readLine() ?: return
        val path = first.split(' ')[1].substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        counts.merge(path, 1, Int::plus)
        if (path == "/stall") { Thread.sleep(20_000); return }
        if (path.startsWith("/hls/") || path.startsWith("/encrypted/") || path.startsWith("/dash/")) {
            if (headers["x-playback-test"] != "fixture") missingHeaders++
        }
        val live = path == "/hls/live" || path == "/hls/event"
        val assetPath = if (path.endsWith("/manifest")) path.substringBeforeLast('/') + "/index.m3u8" else path
        val bytes = if (live) {
            if (liveBorn == 0L) liveBorn = SystemClock.elapsedRealtime()
            val sequence = ((SystemClock.elapsedRealtime() - liveBorn) / 2_000).toInt()
            buildString {
                if (path.endsWith("/event")) {
                    append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n")
                    repeat(sequence + 3) { i ->
                        if (i > 0 && i % 6 == 0) append("#EXT-X-DISCONTINUITY\n")
                        append("#EXTINF:2.0,\nseg${i % 6}.ts?seq=$i\n")
                    }
                    return@buildString
                }
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$sequence\n")
                // 连续 TS 分片，只在合成片段循环、PTS 归零处标记 discontinuity。
                // 保留 12 秒滑动窗口，给模拟器解码器初始化留出余量。
                append("#EXT-X-DISCONTINUITY-SEQUENCE:${((sequence - 1) / 6).coerceAtLeast(0)}\n")
                repeat(6) { offset ->
                    val index = sequence + offset
                    if (index > 0 && index % 6 == 0) append("#EXT-X-DISCONTINUITY\n")
                    append("#EXTINF:2.0,\nseg${index % 6}.ts?seq=$index\n")
                }
            }.toByteArray()
        } else File(root, assetPath.removePrefix("/")).readBytes()
        val start = headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
        val requestedEnd = headers["range"]?.substringAfter('-')?.toIntOrNull() ?: (bytes.size - 1)
        val end = requestedEnd.coerceAtMost(bytes.size - 1)
        val ranged = headers.containsKey("range")
        val contentType = when {
            live || assetPath.endsWith("m3u8") -> "application/vnd.apple.mpegurl"
            assetPath.endsWith("mpd") -> "application/dash+xml"
            else -> "application/octet-stream"
        }
        val response = buildString {
            append("HTTP/1.1 ${if (ranged) "206 Partial Content" else "200 OK"}\r\n")
            append("Content-Type: $contentType\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
            if (ranged) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply { write(response.toByteArray()); write(bytes, start, end - start + 1); flush() }
    }

    override fun close() { server.close(); pool.shutdownNow() }
}
