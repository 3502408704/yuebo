package com.example.local_music_player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 自研多连接下载器回归：分段组装、断点续传、If-Range 校验、单流退化与失败路径。 */
class ChunkedDownloaderTest {

    private val tempDir: File = Files.createTempDirectory("chunked-dl").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    // —— 测试脚手架 ——

    private class RecordingListener : TransferListener {
        val progress = ConcurrentLinkedQueue<Triple<Long, Long, Long>>()
        var completedBytes: Long? = null
        var failure: String? = null

        override fun onProgress(tag: String, downloadedBytes: Long, totalBytes: Long, speedBps: Long, etag: String?) {
            progress.add(Triple(downloadedBytes, totalBytes, speedBps))
        }

        override fun onCompleted(tag: String, totalBytes: Long) {
            completedBytes = totalBytes
        }

        override fun onFailed(tag: String, message: String) {
            failure = message
        }
    }

    /** 按字节区间切片的伪源站：记录全部区间请求，可注入 500、截断、If-Range 失配等行为。 */
    private class SliceDispatcher(private val content: ByteArray, private val etag: String = "\"entity-1\"") : Dispatcher() {
        val rangeValues = ConcurrentLinkedQueue<String>()
        @Volatile var respond500 = 0
        @Volatile var truncateBodyTo = -1
        @Volatile var alwaysFull = false
        @Volatile var htmlErrorPage = false
        @Volatile var forceFullAfterProbe = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader(HEADER_RANGE)
            if (range != null) rangeValues.add(range)
            if (htmlErrorPage) {
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html>blocked</html>")
            }
            if (respond500 > 0) {
                respond500 -= 1
                return MockResponse().setResponseCode(500)
            }
            if (range == null || alwaysFull) return fullResponse()
            val match = Regex("bytes=(\\d+)-(\\d+)").find(range)
                ?: return MockResponse().setResponseCode(400)
            val start = match.groupValues[1].toLong()
            val end = match.groupValues[2].toLong()
            val ifRange = request.getHeader(HEADER_IF_RANGE)
            if (ifRange != null && ifRange != etag) return fullResponse()
            if (forceFullAfterProbe && range != PROBE_RANGE_VALUE) return fullResponse()
            return partialResponse(start, end)
        }

        private fun fullResponse(): MockResponse = MockResponse()
            .setResponseCode(200)
            .setHeader(HEADER_ETAG, etag)
            .setHeader("Content-Type", "application/octet-stream")
            .setBody(okio.Buffer().write(content))

        private fun partialResponse(start: Long, end: Long): MockResponse {
            val safeEnd = end.coerceAtMost(content.size - 1L).coerceAtLeast(start)
            val body = content.copyOfRange(start.toInt(), (safeEnd + 1).toInt())
            val response = MockResponse()
                .setResponseCode(206)
                .setHeader(HEADER_ETAG, etag)
                .setHeader(HEADER_CONTENT_RANGE, "bytes %d-%d/%d".format(start, safeEnd, content.size))
                .setHeader("Content-Type", "application/octet-stream")
            if (truncateBodyTo in 1 until body.size) {
                response.setBody(okio.Buffer().write(body.copyOf(truncateBodyTo)))
                response.setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
            } else {
                response.setBody(okio.Buffer().write(body))
            }
            return response
        }

        private companion object {
            const val HEADER_RANGE = "Range"
            const val HEADER_IF_RANGE = "If-Range"
            const val HEADER_ETAG = "ETag"
            const val HEADER_CONTENT_RANGE = "Content-Range"
            const val PROBE_RANGE_VALUE = "bytes=0-0"
        }
    }

    private fun testDownloader(attempts: Int = 3, backoffMs: Long = 10) = ChunkedDownloader(
        isOnline = { true },
        networkSignal = MutableSharedFlow(),
        retryAttempts = attempts,
        retryBackoffBaseMs = backoffMs,
    )

    private fun transferSpec(url: String, target: File) = HttpTransferSpec(
        tag = "test",
        url = url,
        target = target,
        maxConnections = 4,
        minChunkBytes = 64_000,
    )

    private fun pseudoRandomContent(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }

    private fun seededProgress(target: File, total: Long, etag: String, vararg chunks: LongArray) {
        val array = JSONArray()
        chunks.forEach { chunk -> array.put(JSONArray().put(chunk[0]).put(chunk[1]).put(chunk[2])) }
        target.parentFile?.mkdirs()
        downloadProgressFile(target).writeText(
            JSONObject().put("total_bytes", total).put("etag", etag).put("chunks", array).toString(),
        )
    }

    // —— 用例 ——

    @Test
    fun multi_connection_download_assembles_exact_content() {
        val content = pseudoRandomContent(300_000)
        val source = SliceDispatcher(content)
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val target = File(tempDir, "multi.part")
            val listener = RecordingListener()
            runBlocking { testDownloader().run(transferSpec(server.url("/f").toString(), target), listener) }
            assertNull(listener.failure)
            assertEquals(content.size.toLong(), listener.completedBytes)
            assertTrue(target.readBytes().contentEquals(content))
            // 除探测（bytes=0-0）外应出现多个互不重叠的分段起点
            val starts = source.rangeValues
                .mapNotNull { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLong() }
                .filter { it > 0L }
                .distinct()
            assertTrue(starts.size >= 3, "应有多连接分段，实际起点: $starts")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resume_continues_from_meta_without_refetching_completed_bytes() {
        val content = pseudoRandomContent(200)
        val source = SliceDispatcher(content)
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val target = File(tempDir, "resume.part")
            target.writeBytes(content.copyOf(60))
            seededProgress(
                target, total = 200, etag = "\"entity-1\"",
                longArrayOf(0, 99, 60), longArrayOf(100, 199, 0),
            )
            val listener = RecordingListener()
            runBlocking { testDownloader().run(transferSpec(server.url("/f").toString(), target), listener) }
            assertNull(listener.failure)
            assertTrue(target.readBytes().contentEquals(content))
            val requested = source.rangeValues.filter { it != "bytes=0-0" }
            assertTrue("bytes=60-99" in requested, "应从断点60继续: $requested")
            assertTrue("bytes=100-199" in requested, requested.toString())
            assertTrue(requested.none { it.startsWith("bytes=0-") }, "不得重下已完成前缀: $requested")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun etag_mismatch_discards_stale_progress_and_redownloads() {
        val content = pseudoRandomContent(200)
        val source = SliceDispatcher(content)
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val target = File(tempDir, "stale.part")
            target.writeBytes(content.copyOf(60))
            seededProgress(
                target, total = 200, etag = "\"stale-entity\"",
                longArrayOf(0, 99, 60), longArrayOf(100, 199, 0),
            )
            val listener = RecordingListener()
            runBlocking { testDownloader().run(transferSpec(server.url("/f").toString(), target), listener) }
            assertNull(listener.failure)
            assertTrue(target.readBytes().contentEquals(content))
            assertFalse(source.rangeValues.any { it == "bytes=60-99" }, "过期断点必须整体重下")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun server_without_range_support_downloads_single_stream() {
        val content = pseudoRandomContent(120_000)
        val source = SliceDispatcher(content).apply { alwaysFull = true }
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val target = File(tempDir, "single.part")
            val listener = RecordingListener()
            runBlocking { testDownloader().run(transferSpec(server.url("/f").toString(), target), listener) }
            assertNull(listener.failure)
            assertTrue(target.readBytes().contentEquals(content))
            assertFalse(downloadProgressFile(target).exists(), "单流模式不应产生断点文件")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun html_error_page_fails_with_invalid_address() {
        val source = SliceDispatcher(pseudoRandomContent(100)).apply { htmlErrorPage = true }
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val listener = RecordingListener()
            runBlocking { testDownloader().run(transferSpec(server.url("/f").toString(), File(tempDir, "h.part")), listener) }
            assertEquals("下载地址已失效，请重试", listener.failure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun truncated_chunk_fails_with_network_error_after_retries() {
        val content = pseudoRandomContent(100_000)
        val source = SliceDispatcher(content).apply { truncateBodyTo = 10 }
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val listener = RecordingListener()
            runBlocking { testDownloader(attempts = 1).run(transferSpec(server.url("/f").toString(), File(tempDir, "t.part")), listener) }
            assertEquals("网络错误，请稍后重试", listener.failure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun transient_500_recovers_within_retry_budget() {
        val content = pseudoRandomContent(80_000)
        val source = SliceDispatcher(content).apply { respond500 = 2 }
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val target = File(tempDir, "r500.part")
            val listener = RecordingListener()
            runBlocking { testDownloader(attempts = 3).run(transferSpec(server.url("/f").toString(), target), listener) }
            assertNull(listener.failure)
            assertTrue(target.readBytes().contentEquals(content))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun flaky_range_server_fails_fast_after_one_restart() {
        val content = pseudoRandomContent(300_000)
        val source = SliceDispatcher(content).apply { forceFullAfterProbe = true }
        val server = MockWebServer().apply { dispatcher = source; start() }
        try {
            val listener = RecordingListener()
            runBlocking { testDownloader(attempts = 1).run(transferSpec(server.url("/f").toString(), File(tempDir, "f.part")), listener) }
            assertEquals("下载内容已变化，请重试", listener.failure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun content_range_total_parsing() {
        assertEquals(123L, parseContentRangeTotal("bytes 0-0/123"))
        assertNull(parseContentRangeTotal("bytes 0-0/*"))
        assertNull(parseContentRangeTotal(null))
        assertNull(parseContentRangeTotal("garbage"))
    }
}
