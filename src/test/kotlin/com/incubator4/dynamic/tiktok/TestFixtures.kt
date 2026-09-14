package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberState
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.SubscriptionSubscriber
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass

internal fun testContext(
    updates: SourceUpdatePublisher = SourceUpdatePublisher {
        SourceUpdatePublishResult.ignored("test")
    },
    subscriptions: SubscriptionQueryService = DummySubscriptionQueryService,
    notificationPublisher: SystemNotificationPublisher = SystemNotificationPublisher {
        SystemNotificationPublishResult.accepted()
    },
    taskScheduler: TaskScheduler = ManualTaskScheduler(),
    sourceStateStore: SourceStateStore = DummySourceStateStore,
): PluginContext {
    return PluginContext(
        pluginId = "tiktok-publisher",
        descriptor = PluginDescriptor(
            id = "tiktok-publisher",
            name = "抖音动态源",
            version = "0.0.1",
            mainClass = "com.incubator4.dynamic.tiktok.TiktokPublisherPlugin",
        ),
        configService = DummyConfigService,
        dataStore = DummyPluginDataStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        taskScheduler = taskScheduler,
        sourceUpdatePublisher = updates,
        sourceStateStore = sourceStateStore,
        subscriptionQueryService = subscriptions,
        notificationPublisher = notificationPublisher,
    )
}

internal fun testPublisher(id: Int, userId: String): Publisher {
    return Publisher(
        id = id,
        key = PublisherKey.of(TIKTOK_PLATFORM_ID, PublisherKind.USER, userId),
        name = "抖音用户 $userId",
        avatar = MediaRef("https://example.com/avatar.png", MediaKind.AVATAR),
        createTime = 1,
        createUser = 1,
    )
}

internal fun testLiveSnapshot(
    userId: String,
    status: LiveStatus = LiveStatus.CLOSE,
    roomId: String = "room-$userId",
    webRid: String? = "rid-$userId",
    title: String = "直播 $userId",
    coverUrl: String? = "https://example.com/cover.jpg",
    startedAt: Long? = null,
): TiktokLiveSnapshot {
    return TiktokLiveSnapshot(
        userId = userId,
        roomId = roomId,
        webRid = webRid,
        status = status,
        title = title,
        coverUrl = coverUrl,
        startedAtEpochSeconds = startedAt,
    )
}

internal open class RecordingTiktokGateway(
    var loginResult: PublisherLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "登录成功"),
    private val exportedCookie: String = "",
    private val liveSnapshots: MutableMap<String, MutableList<TiktokLiveSnapshot>> = mutableMapOf(),
    var qrLoginOutcome: TiktokQrLoginOutcome = TiktokQrLoginOutcome(
        result = PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "测试网关未配置抖音二维码登录",
        ),
    ),
) : TiktokGateway {
    var loginCheckCount: Int = 0
        private set
    val fetchedLiveUserIds: MutableList<String> = mutableListOf()

    override fun exportCookie(): String = exportedCookie

    override suspend fun checkLoginState(): PublisherLoginResult {
        loginCheckCount += 1
        return loginResult
    }

    override suspend fun fetchLiveSnapshot(userId: String): TiktokLiveSnapshot {
        fetchedLiveUserIds += userId
        val queue = liveSnapshots[userId] ?: return TiktokLiveSnapshot(userId = userId)
        return if (queue.isEmpty()) {
            TiktokLiveSnapshot(userId = userId)
        } else {
            queue.removeAt(0)
        }
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): TiktokQrLoginOutcome {
        return qrLoginOutcome
    }

    fun enqueueLive(userId: String, vararg snapshots: TiktokLiveSnapshot) {
        liveSnapshots.getOrPut(userId) { mutableListOf() }.addAll(snapshots)
    }
}

internal class InMemoryTiktokLiveStatusStore(
    initial: Map<Int, PublisherLiveStatus> = emptyMap(),
) : TiktokLiveStatusStore {
    private val states: MutableMap<Int, PublisherLiveStatus> = initial.toMutableMap()

    override fun get(publisherId: Int): PublisherLiveStatus? = states[publisherId]

    override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
        states[state.publisherId] = state
        return state
    }

    override fun evict(publisherId: Int) {
        states.remove(publisherId)
    }
}

internal class RecordingSourceUpdatePublisher : SourceUpdatePublisher {
    val requests: MutableList<SourceUpdatePublishRequest> = mutableListOf()
    var nextResult: SourceUpdatePublishResult = SourceUpdatePublishResult.enqueued(1)

    override suspend fun publish(request: SourceUpdatePublishRequest): SourceUpdatePublishResult {
        requests += request
        return nextResult
    }
}

internal class FixedSubscriptionQueryService(
    publishers: List<Publisher>,
    private val enabledEvents: Set<SubscriptionEventKind> = setOf(
        SubscriptionEventKind.LIVE_STARTED,
        SubscriptionEventKind.LIVE_ENDED,
    ),
) : SubscriptionQueryService {
    var snapshots: List<PublisherSubscribers> = publishers.mapIndexed { index, publisher ->
        publisherSnapshot(publisher, index + 1, enabledEvents)
    }

    override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? {
        return snapshots.firstOrNull { it.publisher.id == publisherId }
    }

    override fun findActivePublishersWithSubscribersBySourcePlatform(platformId: String): List<PublisherSubscribers> {
        return snapshots.filter { it.publisher.platformId.value == platformId }
    }
}

internal fun publisherSnapshot(
    publisher: Publisher,
    index: Int,
    enabledEvents: Set<SubscriptionEventKind>,
): PublisherSubscribers {
    val subscriber = Subscriber(
        id = index,
        address = TargetAddress.of("onebot", TargetKind.GROUP, "1000"),
        name = "测试群",
        state = SubscriberState.ACTIVE,
        createTime = 1,
        createUser = 1,
    )
    return PublisherSubscribers(
        publisher = publisher,
        subscriptions = listOf(
            SubscriptionSubscriber(
                subscription = Subscription(
                    id = index,
                    subscriberId = subscriber.id,
                    publisherId = publisher.id,
                    createdAtEpochSeconds = 1,
                    updatedAtEpochSeconds = 1,
                    policy = SubscriptionPolicy(enabledEvents = enabledEvents),
                ),
                subscriber = subscriber,
            )
        ),
    )
}

internal class ManualTaskScheduler : TaskScheduler {
    private val tasks: MutableMap<String, TaskDefinition> = linkedMapOf()
    private val running: MutableSet<String> = linkedSetOf()

    override fun start(task: TaskDefinition): Boolean {
        tasks[task.id] = task
        return running.add(task.id)
    }

    suspend fun runOnce(id: String) {
        require(id in running) { "任务未运行：$id" }
        val task = tasks[id] ?: error("任务不存在：$id")
        task.action()
    }

    override fun start(id: String): Boolean = if (id in tasks) running.add(id) else false

    override suspend fun stop(id: String): Boolean = running.remove(id)

    override suspend fun restart(id: String): Boolean {
        stop(id)
        return start(id)
    }

    override suspend fun stopAll() {
        running.clear()
    }

    override suspend fun shutdown() {
        running.clear()
        tasks.clear()
    }

    override fun isRunning(id: String): Boolean = id in running

    override fun snapshot(id: String): TaskSnapshot? = null

    override fun snapshots(): List<TaskSnapshot> = emptyList()
}

internal object DummyConfigService : ConfigService {
    override fun <T : Any> loadOrCreate(
        pluginId: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
        defaultProvider: () -> T,
    ): T = defaultProvider()

    override fun <T : Any> save(pluginId: String, config: T) = Unit

    override fun <T : Any> reload(
        pluginId: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
    ): T = error("未配置测试配置：$pluginId")

    override fun exists(pluginId: String): Boolean = false

    override fun delete(pluginId: String): Boolean = false

    override fun resolvePath(pluginId: String): Path = createTempDirectory("tiktok-config").resolve("$pluginId.yml")
}

internal object DummyPluginDataStore : PluginDataStore {
    override val dataDir: Path = createTempDirectory("tiktok-data")

    override fun <T : Any> loadOrCreate(
        name: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
        defaultProvider: () -> T,
    ): T = defaultProvider()

    override fun <T : Any> save(name: String, value: T) = Unit

    override fun <T : Any> reload(
        name: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
    ): T = error("未配置测试数据：$name")

    override fun exists(name: String): Boolean = false

    override fun delete(name: String): Boolean = false

    override fun resolvePath(name: String): Path = dataDir.resolve("$name.yml")
}

internal object DummySourceStateStore : SourceStateStore {
    override fun findCursor(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
    ): SourceCursor? = null

    override fun ensureCursorBaseline(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        timestamp: Long,
    ): SourceCursor = SourceCursor(publisherId, sourceKey, eventType, "__baseline__$timestamp", timestamp)

    override fun markCursorSeen(
        publisherId: Int,
        sourceKey: String,
        eventType: SourceEventType,
        updateKey: String,
        timestamp: Long,
    ): SourceCursor = SourceCursor(publisherId, sourceKey, eventType, updateKey, timestamp, listOf(updateKey))

    override fun findLatestLiveStatus(publisherId: Int): PublisherLiveStatus? = null

    override fun saveLiveStatus(state: PublisherLiveStatus): PublisherLiveStatus = state
}

internal object DummySubscriptionQueryService : SubscriptionQueryService {
    override fun findActivePublisherWithSubscribersById(publisherId: Int): PublisherSubscribers? = null

    override fun findActivePublishersWithSubscribersBySourcePlatform(platformId: String): List<PublisherSubscribers> {
        return emptyList()
    }
}
