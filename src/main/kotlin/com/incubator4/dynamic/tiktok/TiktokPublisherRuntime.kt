package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.event.SubscriptionChangeType
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PLUGIN_ID: String = "tiktok-publisher"

private val logger = loggerFor<TiktokPublisherRuntime>()

internal class TiktokPublisherRuntime() :
    PublisherSourcePlugin,
    PublisherLoginProvider,
    ConfigurablePlugin<TiktokPublisherConfig> {

    private var pluginId: String = DEFAULT_PLUGIN_ID
    private val detectTaskId: String = "tiktok-detect"

    override val platformId: PlatformId = PlatformId.of(TIKTOK_PLATFORM_ID)

    override val configId: String
        get() = pluginId
    override val configName: String = "抖音动态源"
    override val configDescription: String = "抖音动态与直播轮询、登录配置。"
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
    private var liveStatusStoreFactory: () -> TiktokLiveStatusStore = {
        error("抖音直播状态存储尚未初始化")
    }

    private var useContextConfigService: Boolean = true
    private var useContextTaskScheduler: Boolean = true
    private var useContextLiveStore: Boolean = true

    private lateinit var taskScheduler: TaskScheduler
    private lateinit var sourceUpdatePublisher: SourceUpdatePublisher
    private lateinit var subscriptionQueryService: SubscriptionQueryService
    private lateinit var config: TiktokPublisherConfig
    private lateinit var gateway: TiktokGateway
    private lateinit var requestFailureHandler: TiktokRequestFailureHandler
    private lateinit var liveStatusStore: TiktokLiveStatusStore
    private lateinit var detectTask: TaskDefinition

    private val detectMutex: Mutex = Mutex()
    private val publisherLock: Any = Any()

    @Volatile
    private var livePublishers: Map<Int, Publisher> = emptyMap()

    @Volatile
    private var pendingDetection: Boolean = false

    internal constructor(
        loadConfig: (String) -> TiktokPublisherConfig,
        gatewayFactory: (TiktokPublisherConfig) -> TiktokGateway,
        saveConfig: (String, TiktokPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
        liveStatusStoreFactory: (() -> TiktokLiveStatusStore)? = null,
    ) : this() {
        this.loadConfig = loadConfig
        this.gatewayFactory = gatewayFactory
        this.saveConfig = saveConfig
        this.taskScheduler = taskScheduler
        useContextConfigService = false
        useContextTaskScheduler = false
        if (liveStatusStoreFactory != null) {
            this.liveStatusStoreFactory = liveStatusStoreFactory
            useContextLiveStore = false
        }
    }

    override val supportedLoginMethods: Set<PublisherLoginMethod> = setOf(
        PublisherLoginMethod.COOKIE,
        PublisherLoginMethod.QR_CODE,
    )
    override val supportsCookieExport: Boolean = true

    override suspend fun onLoad(context: PluginContext) {
        pluginId = context.pluginId
        sourceUpdatePublisher = context.sourceUpdatePublisher
        subscriptionQueryService = context.subscriptionQueryService
        if (useContextTaskScheduler) {
            taskScheduler = context.taskScheduler
        }
        if (useContextLiveStore) {
            liveStatusStoreFactory = { SourceStateTiktokLiveStatusStore(context.sourceStateStore) }
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
        liveStatusStore = liveStatusStoreFactory()
        detectTask = TaskDefinition(
            id = detectTaskId,
            name = "抖音直播检测",
            description = "按配置间隔检测已订阅抖音用户的直播状态，并发布到主项目。",
            schedule = TaskSchedule.FixedDelay(config.pollingIntervalSeconds.seconds, runImmediately = true),
            action = { detectAndPublish() },
        )
        loadActivePublishers()
        logger.info {
            "抖音插件已加载：pluginId=$pluginId，轮询启用=${config.pollingEnabled}，直播检测=${config.liveDetectionEnabled}"
        }
    }

    override suspend fun onStart() {
        val initial = checkLoginState()
        if (initial.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("抖音启动登录状态检查")
            logger.info {
                "抖音登录状态可用：账号=${initial.account?.name ?: initial.account?.userId ?: "未知"}"
            }
            if (config.pollingEnabled) {
                val started = bootstrapLoggedInState()
                logger.info { "抖音轮询已就绪：任务新启动=$started" }
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
            taskScheduler.stop(detectTaskId)
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
            if (config.pollingEnabled && ::taskScheduler.isInitialized && ::detectTask.isInitialized) {
                bootstrapLoggedInState()
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
        if (!::gateway.isInitialized) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "抖音插件尚未加载，无法扫码登录",
            )
        }
        val outcome = try {
            gateway.loginByQrCode(onQrCode, onStatusChanged)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "抖音扫码登录失败",
            )
        }
        val result = outcome.result
        if (result.status != PublisherLoginStatus.SUCCESS) {
            return result
        }
        val cookieHeader = outcome.cookieHeader?.trim().orEmpty()
        if (cookieHeader.isBlank()) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "扫码登录成功但未返回 Cookie",
            )
        }
        val previous = currentConfig()
        val next = previous.copy(cookie = cookieHeader)
        config = next
        gateway = gatewayFactory(next)
        val verified = checkLoginState()
        if (verified.status == PublisherLoginStatus.SUCCESS) {
            requestFailureHandler.recordSuccess("二维码登录")
            if (!persistRuntimeCookieIfChanged()) {
                saveConfig(pluginId, config)
            }
            if (config.pollingEnabled && ::taskScheduler.isInitialized && ::detectTask.isInitialized) {
                bootstrapLoggedInState()
            }
            return verified
        }
        config = previous
        gateway = gatewayFactory(previous)
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = verified.message.ifBlank { "扫码登录后账号状态校验失败" },
        )
    }

    override suspend fun exportCookie(): String? {
        return currentConfig().cookie.trim().takeIf { it.isNotBlank() }
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        if (event.publisher.platformId != platformId) return

        when (event.changeType) {
            SubscriptionChangeType.SUBSCRIBED -> handleSubscribed(event)
            SubscriptionChangeType.UPDATED -> handleSubscribed(event)
            SubscriptionChangeType.UNSUBSCRIBED -> handleUnsubscribed(event)
        }
    }

    internal fun isPollingPaused(): Boolean {
        return ::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()
    }

    private suspend fun detectAndPublish(skipLiveDetection: Boolean = false) {
        if (!config.pollingEnabled) return
        if (::requestFailureHandler.isInitialized && requestFailureHandler.isPollingPaused()) {
            logger.debug { "抖音检测跳过：登录状态失效或疑似风控，轮询请求已暂停" }
            return
        }
        if (!detectMutex.tryLock()) {
            pendingDetection = true
            logger.debug { "抖音检测仍在执行，本轮已标记为补跑" }
            return
        }

        try {
            do {
                pendingDetection = false
                detectAndPublishLocked(skipLiveDetection)
            } while (pendingDetection)
            persistRuntimeCookieIfChanged()
        } finally {
            detectMutex.unlock()
        }
    }

    private suspend fun detectAndPublishLocked(skipLiveDetection: Boolean) {
        loadActivePublishers(logSummary = false)
        val livePublisherSnapshot = livePublishers
        if (livePublisherSnapshot.isEmpty()) {
            logger.debug { "抖音检测跳过：没有活跃直播订阅发布者" }
            return
        }
        if (skipLiveDetection) return

        val now = System.currentTimeMillis() / 1000
        detectLiveStatusChanges(livePublisherSnapshot, now)
    }

    private suspend fun detectLiveStatusChanges(publisherSnapshot: Map<Int, Publisher>, now: Long) {
        if (!config.liveDetectionEnabled || publisherSnapshot.isEmpty()) return

        for (publisher in publisherSnapshot.values) {
            if (requestFailureHandler.isPollingPaused()) {
                logger.debug { "抖音直播检测中止：轮询已暂停" }
                return
            }
            val userId = normalizeUserId(publisher.externalId) ?: continue
            val snapshot = runTiktokRequest("直播状态拉取 uid=$userId") {
                gateway.fetchLiveSnapshot(userId)
            }.getOrNull() ?: continue
            val previous = liveStatusStore.get(publisher.id)
            val current = buildLiveState(publisher, snapshot, previous, now)
            val update = buildLiveUpdate(publisher, previous, current, now, snapshot)
            if (update != null) {
                logger.info {
                    "抖音检测到直播状态变化：publisher=${publisher.displayLabel()}，event=${update.eventType.value}，roomId=${current.roomId}"
                }
            }
            if (update == null || publishSourceUpdate(update)) {
                liveStatusStore.save(current)
            } else {
                logger.warn {
                    "抖音直播状态发布失败，已保留旧状态：publisher=${publisher.displayLabel()}，roomId=${current.roomId}"
                }
            }
        }
    }

    private suspend fun ensureLiveBaseline(publisher: Publisher): Boolean {
        if (!config.liveDetectionEnabled || liveStatusStore.get(publisher.id) != null) return false
        val userId = normalizeUserId(publisher.externalId) ?: return false
        val now = System.currentTimeMillis() / 1000
        val snapshot = runTiktokRequest("直播状态基线 uid=$userId") {
            gateway.fetchLiveSnapshot(userId)
        }.getOrNull() ?: return true
        liveStatusStore.save(buildLiveState(publisher, snapshot, previous = null, observedAt = now))
        return true
    }

    private fun buildLiveState(
        publisher: Publisher,
        snapshot: TiktokLiveSnapshot,
        previous: PublisherLiveStatus?,
        observedAt: Long,
    ): PublisherLiveStatus {
        val status = when (snapshot.status) {
            LiveStatus.OPEN -> LiveStatus.OPEN
            LiveStatus.CLOSE, LiveStatus.ROUND -> LiveStatus.CLOSE
        }
        val roomId = firstNonBlank(snapshot.webRid, snapshot.roomId, previous?.roomId).orEmpty()
        val title = snapshot.title.takeIf { it.isNotBlank() }
            ?: previous?.title?.takeIf { it.isNotBlank() }
            ?: publisher.name
        val cover = snapshot.coverUrl?.takeIf { it.isNotBlank() }?.let { MediaRef(it, MediaKind.COVER) }
            ?: previous?.cover
        val area = snapshot.area?.takeIf { it.isNotBlank() } ?: previous?.area
        val startedAt = if (status == LiveStatus.OPEN) {
            snapshot.startedAtEpochSeconds
                ?: previous?.takeIf { it.status == LiveStatus.OPEN }?.startedAtEpochSeconds
                ?: observedAt
        } else {
            previous?.startedAtEpochSeconds ?: snapshot.startedAtEpochSeconds
        }
        return PublisherLiveStatus(
            publisherId = publisher.id,
            roomId = roomId,
            status = status,
            title = title,
            cover = cover,
            area = area,
            startedAtEpochSeconds = startedAt,
            lastObservedAtEpochSeconds = observedAt,
        )
    }

    private fun buildLiveUpdate(
        publisher: Publisher,
        previous: PublisherLiveStatus?,
        current: PublisherLiveStatus,
        observedAt: Long,
        snapshot: TiktokLiveSnapshot,
    ): SourceUpdate? {
        if (previous == null) return null

        val previousOpen = previous.status == LiveStatus.OPEN
        val currentOpen = current.status == LiveStatus.OPEN
        if (previousOpen == currentOpen) return null

        val eventType = if (currentOpen) SourceEventType.LIVE_STARTED else SourceEventType.LIVE_ENDED
        val startedAt = if (eventType == SourceEventType.LIVE_STARTED) {
            current.startedAtEpochSeconds ?: observedAt
        } else {
            previous.startedAtEpochSeconds ?: current.startedAtEpochSeconds
        }
        val endedAt = if (eventType == SourceEventType.LIVE_ENDED) observedAt else null
        val eventTime = when (eventType) {
            SourceEventType.LIVE_STARTED -> startedAt ?: observedAt
            SourceEventType.LIVE_ENDED -> endedAt ?: observedAt
            else -> observedAt
        }
        val roomId = current.roomId.ifBlank { previous.roomId }
        val title = current.title.ifBlank { previous.title }
        return SourceUpdate(
            key = UpdateKey(
                publisherKey = publisher.key,
                eventType = eventType,
                externalId = "$roomId:$eventTime",
            ),
            publisher = publisher.toInfo(),
            occurredAtEpochSeconds = eventTime,
            observedAtEpochSeconds = observedAt,
            link = liveRoomLink(roomId, snapshot.userId.ifBlank { publisher.externalId }),
            payload = LivePayload(
                roomId = roomId,
                title = title,
                area = current.area ?: previous.area,
                cover = current.cover ?: previous.cover,
                status = current.status,
                previousStatus = previous.status,
                startedAtEpochSeconds = startedAt,
                endedAtEpochSeconds = endedAt,
            ),
        )
    }

    private suspend fun bootstrapLoggedInState(): Boolean {
        loadActivePublishers()
        return startDetectionTask()
    }

    private suspend fun handleSubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshots(publisherId)
            return
        }

        val interests = applyPublisherSnapshot(snapshot)
        if (config.pollingEnabled && ::taskScheduler.isInitialized && taskScheduler.isRunning(detectTaskId) && interests.hasAnyInterest) {
            val liveBaselineRequested = interests.becameLivePresent && ensureLiveBaseline(snapshot.publisher)
            if (!liveBaselineRequested) {
                detectAndPublish()
            }
        }
    }

    private fun handleUnsubscribed(event: SubscriptionChangedEvent) {
        val publisherId = event.publisher.id
        val snapshot = subscriptionQueryService.findActivePublisherWithSubscribersById(publisherId)
        if (snapshot == null || snapshot.publisher.platformId != platformId) {
            removePublisherFromSnapshots(publisherId)
            liveStatusStore.evict(publisherId)
        } else {
            applyPublisherSnapshot(snapshot)
        }
    }

    private fun applyPublisherSnapshot(snapshot: PublisherSubscribers): PublisherInterests {
        val publisherId = snapshot.publisher.id
        val hasLive = config.liveDetectionEnabled && snapshot.hasLiveEventSubscription()
        val interests = synchronized(publisherLock) {
            val wasLivePresent = livePublishers.containsKey(publisherId)
            livePublishers = if (hasLive) {
                livePublishers + (publisherId to snapshot.publisher)
            } else {
                livePublishers - publisherId
            }
            PublisherInterests(
                hasLive = hasLive,
                becameLivePresent = hasLive && !wasLivePresent,
            )
        }
        if (!interests.hasLive) {
            liveStatusStore.evict(publisherId)
        }
        return interests
    }

    private fun removePublisherFromSnapshots(publisherId: Int) {
        synchronized(publisherLock) {
            livePublishers = livePublishers - publisherId
        }
    }

    private fun loadActivePublishers(logSummary: Boolean = true) {
        val loadedLive = subscriptionQueryService
            .findActivePublishersWithSubscribersBySourcePlatform(platformId.value)
            .filter { config.liveDetectionEnabled && it.hasLiveEventSubscription() }
            .map { it.publisher }
            .associateBy { it.id }
        synchronized(publisherLock) {
            livePublishers = loadedLive
        }
        if (logSummary) {
            logger.info { "抖音订阅发布者已加载：直播=${loadedLive.size}" }
        }
    }

    private suspend fun <T> runTiktokRequest(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return requestFailureHandler.run(operation, block)
    }

    private suspend fun publishSourceUpdate(update: SourceUpdate): Boolean {
        logger.debug {
            "抖音提交来源更新到主项目：event=${update.eventType.value}，update=${update.key.stableValue()}，publisher=${update.publisher.name}"
        }
        val result = sourceUpdatePublisher.publish(
            SourceUpdatePublishRequest(
                sourcePlugin = pluginId,
                update = update,
            )
        )
        if (result.accepted) {
            logger.debug {
                "抖音来源更新已进入主项目：update=${update.key.stableValue()}，结果=${result.message}"
            }
        } else {
            logger.warn {
                "抖音来源更新发布失败，状态暂不推进：update=${update.key.stableValue()}，原因=${result.message}"
            }
        }
        return result.accepted
    }

    private fun startDetectionTask(): Boolean {
        val started = taskScheduler.start(detectTask)
        if (started) {
            logger.info { "抖音检测任务已启动：taskId=$detectTaskId" }
        } else {
            logger.debug { "抖音检测任务已在运行：taskId=$detectTaskId" }
        }
        return started
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

    private fun normalizeUserId(userId: String): String? {
        return userId.trim().takeIf { it.isNotBlank() }
    }

    private fun PublisherSubscribers.hasLiveEventSubscription(): Boolean {
        return hasEnabledEvent(SubscriptionEventKind.LIVE_STARTED) ||
            hasEnabledEvent(SubscriptionEventKind.LIVE_ENDED)
    }

    private fun PublisherSubscribers.hasEnabledEvent(kind: SubscriptionEventKind): Boolean {
        return subscriptions.any { item ->
            item.subscription.state == EntityState.ACTIVE &&
                item.subscriber.state.allowsActiveDelivery &&
                kind in item.subscription.policy.enabledEvents
        }
    }

    private fun Publisher.displayLabel(): String {
        return name.takeIf { it.isNotBlank() } ?: externalId
    }

    private data class PublisherInterests(
        val hasLive: Boolean,
        val becameLivePresent: Boolean,
    ) {
        val hasAnyInterest: Boolean
            get() = hasLive
    }
}
