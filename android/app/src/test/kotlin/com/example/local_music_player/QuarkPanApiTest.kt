package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuarkPanApiTest {
    @Test
    fun parseFileListReadsFieldsAndDirFlag() {
        val list = parseQuarkPanFileList(
            """{"status":200,"data":{"metadata":{"total":3},"list":[
                {"fid":"f1","pdir_fid":"0","file_name":"音乐","file_type":0,"dir":true,"size":0,"category":0,"updated_at":1700000000000},
                {"fid":"f2","pdir_fid":"0","file_name":"song.mp3","file_type":1,"size":2048,"category":2,"updated_at":1700000001000}
            ]}}""",
        )
        assertEquals(2, list.files.size)
        val folder = list.files[0]
        assertEquals("f1", folder.fid)
        assertEquals("0", folder.pdirFid)
        assertEquals("音乐", folder.fileName)
        assertTrue(folder.isDir)
        assertEquals(1700000000000, folder.updatedAt)
        val file = list.files[1]
        assertEquals("f2", file.fid)
        assertEquals("song.mp3", file.fileName)
        assertEquals(false, file.isDir)
        assertEquals(2048L, file.size)
        assertEquals(2, file.category)
        assertTrue(list.hasMore)
        assertEquals(3, list.total)
    }

    @Test
    fun parseFileListHasMoreFalseWhenTotalMet() {
        val list = parseQuarkPanFileList(
            """{"status":200,"data":{"metadata":{"total":1},"list":[
                {"fid":"f1","pdir_fid":"0","file_name":"a.mp3","file_type":1,"size":1,"category":0,"updated_at":0}
            ]}}""",
        )
        assertEquals(false, list.hasMore)
    }

    @Test
    fun parseFileListEmptyWithoutList() {
        val list = parseQuarkPanFileList("""{"status":200,"data":{}}""")
        assertTrue(list.files.isEmpty())
        assertEquals(false, list.hasMore)
    }

    @Test
    fun downloadUrlReadsFirstDataEntry() {
        assertEquals(
            "https://cd.example/song.mp3?sign=abc",
            quarkDownloadUrlFromJson(
                """{"status":200,"data":[{"fid":"f2","download_url":"https://cd.example/song.mp3?sign=abc"}]}""",
            ),
        )
        assertNull(quarkDownloadUrlFromJson("""{"status":200,"data":[]}"""))
        assertNull(quarkDownloadUrlFromJson("not json"))
    }

    @Test
    fun qrTokenFromJsonReadsMembersToken() {
        assertEquals(
            "sta123",
            quarkQrTokenFromJson(
                """{"status":2000000,"message":"ok","data":{"members":{"token":"sta123"}}}""",
            ),
        )
        assertNull(quarkQrTokenFromJson("""{"status":5000000,"message":"err"}"""))
    }

    @Test
    fun qrPollResultStates() {
        val ok = quarkQrPollResult(
            """{"status":2000000,"message":"ok","data":{"members":{"service_ticket":"ticket-1"}}}""",
        )
        assertIs<QuarkPanQrPollResult.Ok>(ok)
        assertEquals("ticket-1", (ok as QuarkPanQrPollResult.Ok).serviceTicket)
        assertIs<QuarkPanQrPollResult.Pending>(
            quarkQrPollResult("""{"status":50004001,"message":"Query result is empty"}"""),
        )
        assertIs<QuarkPanQrPollResult.Expired>(quarkQrPollResult("""{"status":50004002}"""))
        assertIs<QuarkPanQrPollResult.Expired>(quarkQrPollResult("""{"status":50004003}"""))
        assertIs<QuarkPanQrPollResult.Expired>(quarkQrPollResult("""{"status":50004004}"""))
        val failed = quarkQrPollResult("""{"status":50005000,"message":"boom"}""")
        assertIs<QuarkPanQrPollResult.Failed>(failed)
        assertEquals("boom", (failed as QuarkPanQrPollResult.Failed).message)
    }

    @Test
    fun qrPollResultOkWithoutTicketIsFailed() {
        val result = quarkQrPollResult("""{"status":2000000,"message":"ok","data":{"members":{}}}""")
        assertIs<QuarkPanQrPollResult.Failed>(result)
    }

    @Test
    fun parseUserReadsNickname() {
        val user = parseQuarkPanUser("""{"data":{"nickname":"阿夸","avatar":"http://a/b.png"}}""")
        assertEquals("阿夸", user?.name)
        assertEquals("http://a/b.png", user?.avatarUrl)
        assertNull(parseQuarkPanUser("""{"data":{}}"""))
        assertNull(parseQuarkPanUser("not json"))
    }

    @Test
    fun apiErrorMessageFallsBackToStatus() {
        assertEquals("请求失败", quarkApiErrorMessage("""{"message":"请求失败"}"""))
        assertTrue(quarkApiErrorMessage("""{"status":500}""").contains("500"))
        assertEquals("夸克网盘请求失败", quarkApiErrorMessage("not json"))
    }

    @Test
    fun fileSortUrlBuildsQuery() {
        val url = quarkFileSortUrl("0", 1, 100, "file_type:asc,file_name:asc")
        assertTrue(url.startsWith("$QUARK_API_BASE/file/sort?pr=ucpro&fr=pc"))
        assertTrue(url.contains("pdir_fid=0"))
        assertTrue(url.contains("_page=1"))
        assertTrue(url.contains("_size=100"))
        assertTrue(url.contains("_sort="))
    }

    @Test
    fun searchUrlEncodesKeyword() {
        val url = quarkSearchUrl("周杰伦 七里香", 1, 50)
        assertTrue(url.startsWith("$QUARK_API_BASE/file/search?pr=ucpro&fr=pc"))
        assertTrue(url.contains("q="))
        assertTrue(url.contains("_page=1"))
        assertTrue(url.contains("_size=50"))
    }

    @Test
    fun deleteBodyUsesActionTypeTwo() {
        val body = quarkDeleteBody(listOf("f1", "f2"))
        assertTrue(body.contains("\"action_type\":2"))
        assertTrue(body.contains("\"f1\""))
        assertTrue(body.contains("\"f2\""))
        assertTrue(body.contains("\"exclude_fids\""))
    }

    @Test
    fun renameBodyReadsFidAndName() {
        val body = quarkRenameBody("f1", "新名字.mp3")
        assertTrue(body.contains("\"fid\":\"f1\""))
        assertTrue(body.contains("\"file_name\":\"新名字.mp3\""))
    }

    @Test
    fun copyMoveBodyActionTypes() {
        assertTrue(quarkCopyMoveBody(0, listOf("f1"), "0").contains("\"action_type\":0"))
        assertTrue(quarkCopyMoveBody(1, listOf("f1"), "0").contains("\"action_type\":1"))
        assertTrue(quarkCopyMoveBody(1, listOf("f1"), "0").contains("\"to_pdir_fid\":\"0\""))
    }

    @Test
    fun qrUrlContainsTokenAndClientId() {
        val url = quarkQrUrl("sta123")
        assertTrue(url.startsWith("$QUARK_QR_LANDING_URL?"))
        assertTrue(url.contains("token=sta123"))
        assertTrue(url.contains("client_id=532"))
        assertTrue(url.contains("ssb=weblogin"))
    }

    @Test
    fun mergeCookiesHandlesLeadingSpacesWithoutCrash() {
        // 回归：旧实现用未 trim 的 '=' 偏移对 trim 后字符串做 substring 会越界
        val merged = mergeQuarkCookies("a=b;  x=y", listOf("c=1; Path=/", "a=2"))
        assertEquals("a=2; x=y; c=1", merged)
    }

    @Test
    fun mergeCookiesTrailingEqualsIsSafe() {
        // " abcdefg=" 这种带前导空格、'=' 在末尾的部分不能抛异常
        val merged = mergeQuarkCookies(" abcdefg=", emptyList())
        assertTrue(merged.contains("abcdefg="))
    }

    @Test
    fun mergeCookiesEmptySetCookieKeepsCurrent() {
        assertEquals("k=v", mergeQuarkCookies("k=v", emptyList()))
        assertEquals("", mergeQuarkCookies("", emptyList()))
    }

    @Test
    fun parseCookieStringHandlesLeadingSpaceAndTrailingEquals() {
        // 回归：旧实现用未 trim 的 '=' 偏移对 trim 后字符串做 substring 会越界（闪退）
        // 例如 " abcdefghijklm=" -> trim 后 13 字符，未 trim 的 '=' 在 index 14
        val map = parseQuarkCookieString(" abcdefghijklm=")
        assertEquals("", map["abcdefghijklm"])
    }

    @Test
    fun parseCookieStringNormalPairs() {
        val map = parseQuarkCookieString("a=b;  x=y; k=")
        assertEquals("b", map["a"])
        assertEquals("y", map["x"])
        assertEquals("", map["k"])
    }
}
