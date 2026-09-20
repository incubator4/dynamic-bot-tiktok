package com.incubator4.dynamic.tiktok

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigNumberKind

public data class TiktokPublisherConfig(
    val pollingEnabled: Boolean = false,
    val pollingIntervalSeconds: Double = 60.0,
    val requestIntervalSeconds: Double = 1.0,
    val replayWindowMinutes: Int = 0,
    val liveDetectionEnabled: Boolean = true,
    val maxConsecutiveLoginFailures: Int = 3,
    val cookie: String = "",
)

public object TiktokPublisherConfigForm {
    public val spec: ConfigFormSpec = ConfigFormSpec(
        title = "抖音动态源",
        description = "抖音账号最新作品与直播轮询、登录状态与请求风控配置。",
        fields = listOf(
            ConfigFieldSpec(
                path = "pollingEnabled",
                label = "启用轮询",
                type = ConfigFieldType.BOOLEAN,
                section = "轮询与风控",
                description = "开启后按配置间隔检测已订阅抖音用户的新作品和直播状态；关闭时插件仍可用于登录、链接解析和后续资料查询。",
                restartRequired = true,
                restartTarget = "抖音插件",
            ),
            ConfigFieldSpec(
                path = "pollingIntervalSeconds",
                label = "轮询间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "多久检查一次已订阅抖音用户的新作品和直播状态。建议不要低于 60 秒。",
                min = 60,
                restartRequired = true,
                restartTarget = "抖音插件",
            ),
            ConfigFieldSpec(
                path = "requestIntervalSeconds",
                label = "请求间隔（秒）",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "连续请求抖音接口之间等待多久，用于降低触发风控的概率。",
                min = 1,
                restartRequired = true,
                restartTarget = "抖音插件",
            ),
            ConfigFieldSpec(
                path = "replayWindowMinutes",
                label = "补发时间窗口（分钟）",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "启动后补发最近一段时间的新作品；设为 0 时只记录当前位置，避免首次推送旧内容。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
            ConfigFieldSpec(
                path = "liveDetectionEnabled",
                label = "直播检测",
                type = ConfigFieldType.BOOLEAN,
                section = "作品与直播",
                description = "是否检测开播和下播。关闭后只检测作品，不再产生直播开始和直播结束事件。",
            ),
            ConfigFieldSpec(
                path = "maxConsecutiveLoginFailures",
                label = "未登录暂停阈值",
                type = ConfigFieldType.NUMBER,
                section = "轮询与风控",
                description = "连续几次检测到未登录后暂停轮询。设为 0 表示不自动暂停；更新 Cookie 或登录恢复后会继续。",
                min = 0,
                numberKind = ConfigNumberKind.INTEGER,
            ),
        ),
    )

    public fun validate(config: TiktokPublisherConfig) {
        require(config.pollingIntervalSeconds >= 60.0) { "抖音轮询间隔不能小于 60 秒" }
        require(config.requestIntervalSeconds >= 1.0) { "抖音请求间隔不能小于 1 秒" }
        require(config.replayWindowMinutes >= 0) { "抖音补发时间窗口不能为负数" }
        require(config.maxConsecutiveLoginFailures >= 0) { "抖音未登录暂停阈值不能为负数" }
    }
}
