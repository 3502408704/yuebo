package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaiduPanApiTest {
    @Test
    fun parseTokenResponseReadsFields() {
        val token = parseBaiduPanToken("""{"access_token":"AT","refresh_token":"RT","expires_in":2592000}""")
        assertEquals("AT", token?.accessToken)
        assertEquals("RT", token?.refreshToken)
        assertEquals(2_592_000_000L, token?.expiresInMs)
    }

    @Test
    fun parseTokenResponseNullWithoutAccessToken() {
        assertNull(parseBaiduPanToken("""{"error":"invalid_grant","error_description":"bad code"}"""))
    }

    @Test
    fun parseDeviceCodeReadsFields() {
        val code = parseBaiduPanDeviceCode(
            """{"device_code":"dc123","user_code":"q92autjg","verification_url":"https://openapi.baidu.com/device","qrcode_url":"https://openapi.baidu.com/device/qrcode/abc/q92autjg","expires_in":300,"interval":5}""",
        )
        assertEquals("dc123", code?.deviceCode)
        assertEquals("q92autjg", code?.userCode)
        assertEquals("https://openapi.baidu.com/device", code?.verificationUrl)
        assertEquals("https://openapi.baidu.com/device/qrcode/abc/q92autjg", code?.qrcodeUrl)
        assertEquals(300_000L, code?.expiresInMs)
        assertEquals(5_000L, code?.intervalMs)
    }

    @Test
    fun parseDeviceCodeNullWithoutDeviceCode() {
        assertNull(parseBaiduPanDeviceCode("""{"error":"invalid_client"}"""))
        assertNull(parseBaiduPanDeviceCode("not json"))
    }

    @Test
    fun parseDeviceTokenResultPendingWhileWaiting() {
        val result = parseBaiduPanDeviceTokenResult("""{"error":"authorization_pending"}""")
        assertIs<BaiduPanDeviceTokenResult.Pending>(result)
        assertIs<BaiduPanDeviceTokenResult.Pending>(
            parseBaiduPanDeviceTokenResult("""{"error":"slow_down"}"""),
        )
    }

    @Test
    fun parseDeviceTokenResultOkWhenAuthorized() {
        val result = parseBaiduPanDeviceTokenResult(
            """{"access_token":"AT","refresh_token":"RT","expires_in":2592000}""",
        )
        val ok = assertIs<BaiduPanDeviceTokenResult.Ok>(result)
        assertEquals("AT", ok.token.accessToken)
        assertEquals("RT", ok.token.refreshToken)
    }

    @Test
    fun parseDeviceTokenResultExpiredWhenInvalidGrant() {
        val result = parseBaiduPanDeviceTokenResult("""{"error":"invalid_grant","error_description":"expired"}""")
        assertIs<BaiduPanDeviceTokenResult.Expired>(result)
    }

    @Test
    fun parseDeviceTokenResultExpiredWhenDeviceCodeExpired() {
        val result = parseBaiduPanDeviceTokenResult("""{"error":"expired_token"}""")
        assertIs<BaiduPanDeviceTokenResult.Expired>(result)
    }

    @Test
    fun parseDeviceTokenResultFailedOnOtherErrors() {
        val result = parseBaiduPanDeviceTokenResult("""{"error":"server_error","error_description":"内部错误"}""")
        val failed = assertIs<BaiduPanDeviceTokenResult.Failed>(result)
        assertTrue(failed.message.contains("内部错误"))
    }

    @Test
    fun oauthErrorMessageReportsDescription() {
        assertTrue(
            oauthErrorMessage("""{"error":"invalid_grant","error_description":"授权码已失效"}""")
                .contains("授权码已失效"),
        )
        assertTrue(oauthErrorMessage("not json").isNotBlank())
    }

    @Test
    fun parseUserInfoReadsProfile() {
        val user = parseBaiduPanUser(
            """{"errno":0,"baidu_name":"小明","netdisk_name":"小明","vip_type":2,"avatar_url":"http://a"}""",
        )
        assertEquals("小明", user?.name)
        assertEquals("小明", user?.netdiskName)
        assertEquals(2, user?.vipType)
        assertEquals("http://a", user?.avatarUrl)
    }

    @Test
    fun parseUserInfoNullWhenErrnoNotZero() {
        assertNull(parseBaiduPanUser("""{"errno":-6}"""))
    }

    @Test
    fun parseFileListReadsFilesAndDirs() {
        val result = parseBaiduPanFileList(
            """{"errno":0,"list":[
                {"fs_id":1,"path":"/a.mp3","isdir":0,"server_filename":"a.mp3","size":100,"category":2,"server_mtime":123},
                {"fs_id":2,"path":"/dir","isdir":1,"server_filename":"dir","size":0,"category":0,"server_mtime":0}
            ]}""",
        )
        assertEquals(0, result.errno)
        assertEquals(2, result.files.size)
        assertEquals("/a.mp3", result.files[0].path)
        assertEquals(false, result.files[0].isDir)
        assertEquals(2, result.files[0].category)
        assertEquals(100L, result.files[0].size)
        assertEquals(true, result.files[1].isDir)
    }

    @Test
    fun filelistJsonBuildsCopyMoveEntries() {
        val json = baiduPanFilelistJson(
            listOf(
                BaiduPanFileManagerEntry("/a.mp3", "/music", "b.mp3"),
                BaiduPanFileManagerEntry("/dir", "/target"),
            ),
        )
        val array = org.json.JSONArray(json)
        assertEquals(2, array.length())
        val first = array.getJSONObject(0)
        assertEquals("/a.mp3", first.getString("path"))
        assertEquals("/music", first.getString("dest"))
        assertEquals("b.mp3", first.getString("newname"))
        val second = array.getJSONObject(1)
        assertEquals("/dir", second.getString("path"))
        assertEquals("/target", second.getString("dest"))
        assertTrue(!second.has("newname"))
    }

    @Test
    fun parseFileListEmptyWhenNoList() {
        val result = parseBaiduPanFileList("""{"errno":-9}""")
        assertEquals(-9, result.errno)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun parseFileMetasReadsDlink() {
        val metas = parseBaiduPanFileMetas(
            """{"errno":0,"list":[{"fs_id":7,"path":"/x.mp3","dlink":"https://d.pcs.baidu.com/file/x"}]}""",
        )
        assertEquals(1, metas.size)
        assertEquals(7L, metas[0].fsId)
        assertEquals("https://d.pcs.baidu.com/file/x", metas[0].dlink)
    }

    @Test
    fun panErrorMessageMapsTokenExpired() {
        assertTrue(panErrorMessage(-6).contains("重新登录"))
        assertTrue(panErrorMessage(0).isBlank())
        assertTrue(panErrorMessage(999).contains("999"))
    }

    @Test
    fun dlinkWithTokenAppendsToQuery() {
        assertEquals(
            "https://d.pcs.baidu.com/file/x?fid=1&access_token=AT",
            baiduPanDlinkWithToken("https://d.pcs.baidu.com/file/x?fid=1", "AT"),
        )
    }

    @Test
    fun dlinkWithTokenFallsBackToQuestionMark() {
        assertEquals(
            "https://d.pcs.baidu.com/file/x?access_token=AT",
            baiduPanDlinkWithToken("https://d.pcs.baidu.com/file/x", "AT"),
        )
    }

    @Test
    fun verificationUrlWithCodeAppendsCodeQuery() {
        val code = BaiduPanDeviceCode(
            deviceCode = "dc123",
            userCode = "q92autjg",
            verificationUrl = "https://openapi.baidu.com/device",
            qrcodeUrl = "https://openapi.baidu.com/device/qrcode/abc/q92autjg",
            expiresInMs = 300_000,
            intervalMs = 5_000,
        )
        assertEquals(
            "https://openapi.baidu.com/device?display=mobile&code=q92autjg",
            baiduPanVerificationUrlWithCode(code),
        )
    }

    @Test
    fun verificationUrlWithCodeKeepsExistingQuery() {
        val code = BaiduPanDeviceCode(
            deviceCode = "dc123",
            userCode = "a1b2c3d4",
            verificationUrl = "https://openapi.baidu.com/device?from=test",
            qrcodeUrl = "https://openapi.baidu.com/device/qrcode/abc/a1b2c3d4",
            expiresInMs = 300_000,
            intervalMs = 5_000,
        )
        assertEquals(
            "https://openapi.baidu.com/device?from=test&display=mobile&code=a1b2c3d4",
            baiduPanVerificationUrlWithCode(code),
        )
    }

    @Test
    fun verificationUrlWithCodeEncodesUserCode() {
        val code = BaiduPanDeviceCode(
            deviceCode = "dc123",
            userCode = "ab cd",
            verificationUrl = "https://openapi.baidu.com/device",
            qrcodeUrl = "",
            expiresInMs = 300_000,
            intervalMs = 5_000,
        )
        assertEquals(
            "https://openapi.baidu.com/device?display=mobile&code=ab+cd",
            baiduPanVerificationUrlWithCode(code),
        )
    }

    @Test
    fun dlinkUserAgentIsPanDotBaiduCom() {
        assertEquals("pan.baidu.com", BAIDU_PAN_DLINK_UA)
    }

    @Test
    fun authorizePageUrlReturnsNullWithoutRedirectUri() {
        assertNull(BaiduPanApi("ak", "sk").authorizePageUrl())
        assertNull(BaiduPanApi("ak", "sk", " ").authorizePageUrl())
    }

    @Test
    fun authorizePageUrlBuildsAuthorizeQuery() {
        val url = BaiduPanApi("ak1", "sk1", "https://yuebo.example/baidupan/oauth/callback")
            .authorizePageUrl()
        assertEquals(
            "https://openapi.baidu.com/oauth/2.0/authorize?response_type=code" +
                "&client_id=ak1&redirect_uri=https%3A%2F%2Fyuebo.example%2Fbaidupan%2Foauth%2Fcallback" +
                "&scope=basic,netdisk&display=mobile",
            url,
        )
    }

    @Test
    fun oAuthRedirectMatchAcceptsAppendedQueryAndRejectsOthers() {
        val redirect = "https://yuebo.example/baidupan/oauth/callback"
        assertTrue(
            isBaiduPanOAuthRedirect(
                "https://yuebo.example/baidupan/oauth/callback?code=abc123",
                redirect,
            ),
        )
        assertTrue(isBaiduPanOAuthRedirect(redirect, redirect))
        assertTrue(!isBaiduPanOAuthRedirect("https://yuebo.example/other?code=abc123", redirect))
        assertTrue(!isBaiduPanOAuthRedirect("https://evil.example/baidupan/oauth/callback?code=x", redirect))
        assertTrue(!isBaiduPanOAuthRedirect("not a url", redirect))
    }

    @Test
    fun queryParameterExtractsAndDecodesValues() {
        assertEquals(
            "ab+cd=",
            baiduPanQueryParameter("https://a.example/cb?state=x%26y&code=ab%2Bcd%3D", "code"),
        )
        assertNull(baiduPanQueryParameter("https://a.example/cb?state=x", "code"))
        assertNull(baiduPanQueryParameter("https://a.example/cb", "code"))
    }

    @Test
    fun searchUrlBuildsSearchQuery() {
        val url = baiduPanSearchUrl("TOKEN", "周杰伦", page = 2)
        assertTrue(url.startsWith("https://pan.baidu.com/rest/2.0/xpan/file?method=search&"))
        assertTrue(url.contains("key=%E5%91%A8%E6%9D%B0%E4%BC%A6"))
        assertTrue(url.contains("dir=%2F"))
        assertTrue(url.contains("web=1"))
        assertTrue(url.contains("recursion=1"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("num=100"))
        assertTrue(url.contains("access_token=TOKEN"))
    }

    @Test
    fun searchUrlDefaultsPageAndNumAndEncodesKeyword() {
        val url = baiduPanSearchUrl("T", "ab cd")
        assertTrue(url.contains("page=1"))
        assertTrue(url.contains("num=100"))
        assertTrue(url.contains("key=ab+cd"))
    }
}
