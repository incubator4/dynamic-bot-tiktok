package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.data.PublisherSubscribers
import top.colter.dynamic.core.data.SourceCursor
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.SourceStateStore
import top.colter.dynamic.core.plugin.SubscriptionQueryService
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass

internal fun testContext(
    notificationPublisher: SystemNotificationPublisher = SystemNotificationPublisher {
        SystemNotificationPublishResult.accepted()
    },
    taskScheduler: TaskScheduler = ManualTaskScheduler(),
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
        sourceUpdatePublisher = SourceUpdatePublisher {
            SourceUpdatePublishResult.ignored("test")
        },
        sourceStateStore = DummySourceStateStore,
        subscriptionQueryService = DummySubscriptionQueryService,
        notificationPublisher = notificationPublisher,
    )
}

internal open class RecordingTiktokGateway(
    var loginResult: PublisherLoginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "登录成功"),
    private val exportedCookie: String = "",
) : TiktokGateway {
    var loginCheckCount: Int = 0
        private set

    override fun exportCookie(): String = exportedCookie

    override suspend fun checkLoginState(): PublisherLoginResult {
        loginCheckCount += 1
        return loginResult
    }
}

internal class ManualTaskScheduler : TaskScheduler {
    private val tasks: MutableMap<String, TaskDefinition> = linkedMapOf()
    private val running: MutableSet<String> = linkedSetOf()

    override fun start(task: TaskDefinition): Boolean {
        tasks[task.id] = task
        return running.add(task.id)
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
