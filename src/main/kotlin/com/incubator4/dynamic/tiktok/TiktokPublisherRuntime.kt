package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor

private const val DEFAULT_PLUGIN_ID: String = "tiktok-publisher"

private val logger = loggerFor<TiktokPublisherRuntime>()

internal class TiktokPublisherRuntime() :
    PublisherSourcePlugin,
    PublisherLoginProvider,
    ConfigurablePlugin<TiktokPublisherConfig> {

    private var pluginId: String = DEFAULT_PLUGIN_ID

    override val platformId: PlatformId = PlatformId.of(TIKTOK_PLATFORM_ID)

    override val configId: String
        get() = pluginId
    override val configName: String = "抖音动态源"
    override val configDescription: String = "抖音动态轮询与登录配置。"
    override val configClass = TiktokPublisherConfig::class
    override val configFormSpec = TiktokPublisherConfigForm.spec

    private var loadConfig: (String) -> TiktokPublisherConfig = { id ->
        error("抖音插件配置服务尚未初始化：$id")
    }
    private var saveConfig: (String, TiktokPublisherConfig) -> Unit = { _, _ -> }
    private var gatewayFactory: (TiktokPublisherConfig) -> TiktokGateway = { config ->
        TiktokHttpGateway(
            client = TiktokClient(config),
            requestIntervalMs = secondsToMillis(config.requestIntervalSeconds, minimumMillis = 1_000),
        )
    }

    private var useContextConfigService: Boolean = true
    private var useContextTaskScheduler: Boolean = true

    private lateinit var taskScheduler: TaskScheduler
    private lateinit var config: TiktokPublisherConfig
    private lateinit var gateway: TiktokGateway
    private lateinit var requestFailureHandler: TiktokRequestFailureHandler

    internal constructor(
        loadConfig: (String) -> TiktokPublisherConfig,
        gatewayFactory: (TiktokPublisherConfig) -> TiktokGateway,
        saveConfig: (String, TiktokPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
    ) : this() {
        this.loadConfig = loadConfig
        this.gatewayFactory = gatewayFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
        useContextConfigService = false
        useContextTaskScheduler = false
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(PublisherLoginMethod.COOKIE)
    override val supportsCookieExport: Boolean = true

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        if (useContextTaskScheduler) {
            taskScheduler = context.taskScheduler
        }
        if (useContextConfigService) {
            loadConfig = { id -> context.configService.loadOrCreate(id) { TiktokPublisherConfig() } }
            saveConfig = { id, next -> context.configService.save(id, next) }
        }

        config = loadConfig(pluginId)
        TiktokPublisherConfigForm.validate(config)
        gateway = gatewayFactory(config)
        requestFailureHandler = TiktokRequestFailureHandler(
            configProvider = { config },
            notificationPublisher = context.notificationPublisher,
        )
        logger.info { "抖音插件已加载：pluginId=$pluginId，轮询启用=${config.pollingEnabled}" }
    }

    override suspend fun onStart() {
        val initial = checkLoginState()
        if (initial.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("抖音启动登录状态检查")
            logger.info {
                "抖音登录状态可用：账号=${initial.account?.name ?: initial.account?.userId ?: "未知"}"
            }
            if (config.pollingEnabled) {
                logger.info { "抖音轮询已配置启用；作品检测将在后续接入后生效" }
            }
            return
        }
        if (config.pollingEnabled) {
            logger.warn {
                "抖音轮询暂不启动：登录状态=${initial.status}，原因=${initial.message}"
            }
        } else {
            logger.info {
                "抖音轮询未启用；当前登录状态=${initial.status}，原因=${initial.message}"
            }
        }
    }

    override suspend fun onStop() {
        if (::taskScheduler.isInitialized) {
            taskScheduler.stop("tiktok-detect")
        }
        runCatching { persistRuntimeCookieIfChanged() }
            .onFailure { logger.warn(it) { "停止抖音插件前回存 Cookie 失败" } }
        logger.info { "抖音插件已停止" }
    }

    override suspend fun onUnload() {
        runCatching { persistRuntimeCookieIfChanged() }
            .onFailure { logger.warn(it) { "卸载抖音插件前回存 Cookie 失败" } }
    }

    override fun currentConfig(): TiktokPublisherConfig {
        return if (::config.isInitialized) config else loadConfig(pluginId)
    }

    override fun applyConfig(next: TiktokPublisherConfig): ConfigApplyResult {
        TiktokPublisherConfigForm.validate(next)
        val previous = currentConfig()
        if (previous == next) {
            return ConfigApplyResult(changed = false, message = "抖音配置未变化")
        }

        config = next
        if (::gateway.isInitialized) {
            gateway = gatewayFactory(next)
        }

        val restartRequired = previous.pollingEnabled != next.pollingEnabled ||
            previous.pollingIntervalSeconds != next.pollingIntervalSeconds ||
            previous.requestIntervalSeconds != next.requestIntervalSeconds ||
            previous.cookie != next.cookie

        return ConfigApplyResult(
            changed = true,
            restartRequired = restartRequired,
            restartTargets = if (restartRequired) listOf("抖音插件") else emptyList(),
            message = if (restartRequired) {
                "抖音配置已保存；需要重启抖音插件以重建轮询服务"
            } else {
                "抖音配置已保存并生效"
            },
        )
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        val result = try {
            gateway.checkLoginState()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "抖音登录状态检查失败",
            )
        }
        if (result.status == PublisherLoginStatus.SUCCESS) {
            persistRuntimeCookieIfChanged()
        }
        return result
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        val cookies = try {
            parseTiktokCookieInput(cookie)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "抖音 Cookie 无法解析",
            )
        }
        if (cookies.isEmpty()) {
            return PublisherLoginResult(PublisherLoginStatus.FAILED, "抖音 Cookie 不能为空")
        }
        if (!cookies.hasLoginSession()) {
            return PublisherLoginResult(
                PublisherLoginStatus.FAILED,
                "抖音 Cookie 缺少登录会话，请从已登录的浏览器导入包含 sessionid 的完整 Cookie",
            )
        }

        val previous = currentConfig()
        val next = previous.copy(cookie = cookies.header)
        config = next
        gateway = gatewayFactory(next)
        val result = checkLoginState()
        if (result.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("Cookie 登录")
            if (!persistRuntimeCookieIfChanged()) {
                saveConfig(pluginId, config)
            }
        } else {
            config = previous
            gateway = gatewayFactory(previous)
        }
        return result
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "一期不支持抖音二维码登录，请使用 Cookie 登录",
        )
    }

    override suspend fun exportCookie(): String? {
        return currentConfig().cookie.trim().takeIf { it.isNotBlank() }
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        if (event.publisher.platformId != platformId) return
    }

    internal fun isPollingPaused(): Boolean {
        return ::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()
    }

    private fun persistRuntimeCookieIfChanged(): Boolean {
        if (!::gateway.isInitialized) return false
        val latest = gateway.exportCookie().trim().takeIf { it.isNotBlank() } ?: return false
        if (latest == config.cookie.trim()) return false
        config = config.copy(cookie = latest)
        runCatching {
            saveConfig(pluginId, config)
        }.onFailure {
            logger.warn(it) { "回存抖音运行期 Cookie 失败" }
        }
        logger.debug { "抖音运行期 Cookie 已回存配置" }
        return true
    }
}
