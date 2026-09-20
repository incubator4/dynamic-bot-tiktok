package com.incubator4.dynamic.tiktok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.config.ConfigNumberKind
import top.colter.dynamic.core.plugin.PublisherLoginStatus

class TiktokAuthTest {
    @Test
    fun `cookie header and json inputs are normalized`() {
        val header = parseTiktokCookieInput("sessionid=abc; ttwid=def; Path=/; domain=.douyin.com")
        assertEquals("sessionid=abc; ttwid=def", header.header)
        assertTrue(header.hasLoginSession())

        val array = parseTiktokCookieInput(
            """[{"name":"sessionid","value":"from-json"},{"name":"ttwid","value":"token"}]""",
        )
        assertEquals("sessionid=from-json; ttwid=token", array.header)

        val obj = parseTiktokCookieInput("""{"sessionid":"map-session","sid_tt":"map-sid"}""")
        assertEquals("sessionid=map-session; sid_tt=map-sid", obj.header)
        assertTrue(parseTiktokCookieInput("   ").isEmpty())
        assertFalse(parseTiktokCookieInput("ttwid=device-only").hasLoginSession())
    }

    @Test
    fun `account info success maps account and rejects empty session`() {
        val loggedIn = parseTiktokAccountInfo(
            """
            {
              "message": "success",
              "data": {
                "user_id": 123456,
                "user_id_str": "123456",
                "screen_name": "测试用户",
                "avatar_url": "https://example.com/avatar.png"
              }
            }
            """.trimIndent(),
        ).toLoginResult()

        assertEquals(PublisherLoginStatus.SUCCESS, loggedIn.status)
        assertEquals("123456", loggedIn.account?.userId)
        assertEquals("测试用户", loggedIn.account?.name)
        assertEquals("https://example.com/avatar.png", loggedIn.account?.avatar?.uri)

        val empty = parseTiktokAccountInfo(
            """{"message":"success","data":{}}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, empty.status)
        assertTrue(empty.message.contains("未登录") || empty.message.contains("失效"))
        assertNull(empty.account)
    }

    @Test
    fun `account info login expiry and risk control are classified`() {
        val expired = parseTiktokAccountInfo(
            """{"message":"error","data":{"error_code":7,"description":"登录已过期"}}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, expired.status)
        assertTrue(expired.message.contains("登录"))

        val blocked = parseTiktokAccountInfo(
            """{"message":"error","data":{"error_code":461,"description":"请求被风控拦截"}}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, blocked.status)
        assertTrue(blocked.message.contains("风控"))
        assertTrue(looksLikeRiskControl(461, "请求被风控拦截", httpStatus = 200))
        assertTrue(looksLikeRiskControl(null, "", httpStatus = 403))
        assertTrue(looksLikeRiskControl(4031, "您正在尝试访问的网站存在安全风险"))
        assertTrue(looksLikeHtml("<!doctype html><html><head><script></script></head></html>"))
    }

    @Test
    fun `account info missing user is treated as cookie login failure`() {
        assertTrue(TIKTOK_ACCOUNT_INFO_URL.contains("/aweme/v1/passport/account/info/v2/"))
        assertTrue(TIKTOK_ACCOUNT_INFO_URL.contains("aid=$TIKTOK_PASSPORT_AID"))
        assertFalse(TIKTOK_ACCOUNT_INFO_URL.contains("aid=$TIKTOK_WEB_AID"))

        val missing = parseTiktokAccountInfo(
            """{"message":"error","data":{"error_code":1041,"description":"用户不存在"}}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, missing.status)
        assertTrue(missing.message.contains("Cookie"))
        assertTrue(missing.message.contains("sessionid"))
        assertNull(missing.account)
    }

    @Test
    fun `account info app denied is not treated as missing cookie`() {
        val denied = parseTiktokAccountInfo(
            """{"message":"error","data":{"error_code":1105,"description":"该应用无权限"}}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.FAILED, denied.status)
        assertTrue(denied.message.contains("无权限"))
        assertTrue(denied.message.contains("扫码"))
        assertNull(denied.account)
    }

    @Test
    fun `passport v2 user payload maps logged in account`() {
        val loggedIn = parseTiktokAccountInfo(
            """{"status_code":0,"user":{"sec_uid":"MS4wSelf","nickname":"网页账号","uid":123456}}""",
        ).toLoginResult()
        assertEquals(PublisherLoginStatus.SUCCESS, loggedIn.status)
        assertEquals("MS4wSelf", loggedIn.account?.userId)
        assertEquals("网页账号", loggedIn.account?.name)
    }

    @Test
    fun `invalid cookie json fails with chinese message`() {
        val error = assertFailsWith<TiktokLoginException> {
            parseTiktokCookieInput("[not-json")
        }
        assertTrue(error.message!!.contains("Cookie JSON"))
    }
}

class TiktokPublisherConfigFormTest {
    @Test
    fun `form should group visible settings and hide cookie`() {
        val fields = TiktokPublisherConfigForm.spec.fields
        val sections = fields.groupBy { it.section }

        assertEquals(setOf("轮询与风控", "作品与直播"), sections.keys)
        assertEquals(
            listOf(
                "pollingEnabled",
                "pollingIntervalSeconds",
                "requestIntervalSeconds",
                "replayWindowMinutes",
                "maxConsecutiveLoginFailures",
            ),
            sections.getValue("轮询与风控").map { it.path },
        )
        assertEquals(listOf("liveDetectionEnabled"), sections.getValue("作品与直播").map { it.path })
        assertFalse(fields.any { it.path == "cookie" })
        assertTrue(fields.all { it.label.any { ch -> ch in '\u4e00'..'\u9fff' } })
    }

    @Test
    fun `form should keep restart and number constraints`() {
        val fields = TiktokPublisherConfigForm.spec.fields.associateBy { it.path }

        assertTrue(fields.getValue("pollingEnabled").restartRequired)
        assertTrue(fields.getValue("pollingIntervalSeconds").restartRequired)
        assertTrue(fields.getValue("requestIntervalSeconds").restartRequired)
        assertEquals(60L, fields.getValue("pollingIntervalSeconds").min)
        assertEquals(1L, fields.getValue("requestIntervalSeconds").min)
        assertEquals(ConfigNumberKind.INTEGER, fields.getValue("replayWindowMinutes").numberKind)
        assertEquals(ConfigNumberKind.INTEGER, fields.getValue("maxConsecutiveLoginFailures").numberKind)
    }

    @Test
    fun `validator should reject invalid values`() {
        TiktokPublisherConfigForm.validate(TiktokPublisherConfig())

        assertFailsWith<IllegalArgumentException> {
            TiktokPublisherConfigForm.validate(TiktokPublisherConfig(pollingIntervalSeconds = 59.0))
        }
        assertFailsWith<IllegalArgumentException> {
            TiktokPublisherConfigForm.validate(TiktokPublisherConfig(requestIntervalSeconds = 0.9))
        }
        assertFailsWith<IllegalArgumentException> {
            TiktokPublisherConfigForm.validate(TiktokPublisherConfig(replayWindowMinutes = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            TiktokPublisherConfigForm.validate(TiktokPublisherConfig(maxConsecutiveLoginFailures = -1))
        }
    }

    @Test
    fun `qr create and check payloads are parsed`() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val session = parseTiktokQrCodeCreate(
            """{"error_code":0,"data":{"token":"tok-1","qrcode":"$png","qrcode_index_url":"https://example.com/qr"}}""",
        )
        assertEquals("tok-1", session.token)
        assertEquals("https://example.com/qr", session.qrContent)
        assertTrue(session.qrImageBytes != null && session.qrImageBytes!!.isNotEmpty())

        val waiting = parseTiktokQrCodeCheck("""{"error_code":0,"data":{"status":"1"}}""")
        assertEquals(TiktokQrCheckStatus.WAITING, waiting.status)

        val scanned = parseTiktokQrCodeCheck("""{"error_code":0,"data":{"status":"2"}}""")
        assertEquals(TiktokQrCheckStatus.SCANNED, scanned.status)

        val confirmed = parseTiktokQrCodeCheck(
            """{"error_code":0,"data":{"status":"3","redirect_url":"https://www.douyin.com/login/callback"}}""",
        )
        assertEquals(TiktokQrCheckStatus.CONFIRMED, confirmed.status)
        assertEquals("https://www.douyin.com/login/callback", confirmed.redirectUrl)

        val expired = parseTiktokQrCodeCheck("""{"error_code":0,"data":{"status":"5"}}""")
        assertEquals(TiktokQrCheckStatus.EXPIRED, expired.status)

        val confirmedByStatusFour = parseTiktokQrCodeCheck(
            """{"error_code":0,"data":{"status":"4","redirect_url":"https://www.douyin.com/login/callback"}}""",
        )
        assertEquals(TiktokQrCheckStatus.CONFIRMED, confirmedByStatusFour.status)

        val blocked = parseTiktokQrCodeCheck(
            """{"error_code":461,"data":{"description":"请求被风控拦截"}}""",
        )
        assertEquals(TiktokQrCheckStatus.ERROR, blocked.status)
        assertTrue(blocked.message.contains("风控"))

        val htmlCreate = assertFailsWith<TiktokBlockedException> {
            parseTiktokQrCodeCreate("<!doctype html><html><head><script></script></head></html>")
        }
        assertTrue(htmlCreate.message!!.contains("风控") || htmlCreate.message!!.contains("网页"))

        val riskCreate = assertFailsWith<TiktokBlockedException> {
            parseTiktokQrCodeCreate(
                """{"data":{"captcha":"","description":"您正在尝试访问的网站存在安全风险","error_code":4031},"message":"error"}""",
            )
        }
        assertTrue(riskCreate.message!!.contains("安全风险"))

        val frontendQr = parseTiktokQrCodeCreate(
            """{"error_code":0,"data":{"token":"tok-front","frontend_show_qrcode":"https://example.com/front-qr"}}""",
        )
        assertEquals("https://example.com/front-qr", frontendQr.qrContent)
    }

    @Test
    fun `qr create rejects missing content and maps challenge`() {
        val missingToken = assertFailsWith<TiktokLoginException> {
            parseTiktokQrCodeCreate("""{"error_code":0,"data":{}}""")
        }
        assertTrue(missingToken.message!!.contains("token"))

        val missingQr = assertFailsWith<TiktokLoginException> {
            parseTiktokQrCodeCreate("""{"error_code":0,"data":{"token":"tok-only"}}""")
        }
        assertTrue(missingQr.message!!.contains("二维码"))

        val blocked = assertFailsWith<TiktokBlockedException> {
            parseTiktokQrCodeCreate("""{"error_code":471,"data":{"description":"人机验证"}}""")
        }
        assertTrue(blocked.message!!.contains("人机验证") || blocked.message!!.contains("风控"))

        val session = parseTiktokQrCodeCreate(
            """{"error_code":0,"data":{"token":"tok-2","qrcode_index_url":"https://example.com/qr"}}""",
        )
        val challenge = session.toChallenge()
        assertEquals("https://example.com/qr", challenge.qrContent)
        assertTrue(challenge.message.orEmpty().contains("抖音"))
        assertTrue(challenge.instruction.orEmpty().contains("扫一扫"))
        assertEquals(TIKTOK_QR_POLL_INTERVAL_MS, challenge.statusPollIntervalMillis)
    }

}
