package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.event.SourceUpdatePublishResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiktokPublisherRuntimeLiveTest {
    @Test
    fun `startup with polling enabled starts detect task after login`() = runBlocking {
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = TiktokPublisherConfig(pollingEnabled = true, cookie = "sessionid=ok"),
            gateway = RecordingTiktokGateway(),
            scheduler = scheduler,
        )
        runtime.onLoad(testContext(taskScheduler = scheduler))
        runtime.onStart()

        assertTrue(scheduler.isRunning("tiktok-detect"))
    }

    @Test
    fun `first poll records live baseline without publishing`() = runBlocking {
        val publisher = testPublisher(1, "MS4wLjABAAAAtest")
        val liveStore = InMemoryTiktokLiveStatusStore()
        val gateway = RecordingTiktokGateway()
        gateway.enqueueLive(
            publisher.externalId,
            testLiveSnapshot(publisher.externalId, status = LiveStatus.OPEN, title = "已在播"),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = TiktokPublisherConfig(pollingEnabled = true),
            gateway = gateway,
            scheduler = scheduler,
            liveStore = liveStore,
        )
        runtime.onLoad(
            testContext(
                updates = updates,
                subscriptions = FixedSubscriptionQueryService(listOf(publisher)),
                taskScheduler = scheduler,
            ),
        )
        runtime.onStart()
        scheduler.runOnce("tiktok-detect")

        assertEquals(emptyList(), updates.requests)
        assertEquals(LiveStatus.OPEN, liveStore.get(publisher.id)?.status)
        assertEquals("已在播", liveStore.get(publisher.id)?.title)
        assertEquals(listOf(publisher.externalId), gateway.fetchedLiveUserIds)
    }

    @Test
    fun `later poll publishes live started and ended`() = runBlocking {
        val publisher = testPublisher(1, "MS4wLjABAAAAtest")
        val liveStore = InMemoryTiktokLiveStatusStore()
        val gateway = RecordingTiktokGateway()
        gateway.enqueueLive(
            publisher.externalId,
            testLiveSnapshot(publisher.externalId, status = LiveStatus.CLOSE),
            testLiveSnapshot(publisher.externalId, status = LiveStatus.OPEN, title = "开播了", webRid = "host1"),
            testLiveSnapshot(publisher.externalId, status = LiveStatus.CLOSE, title = "已下播", webRid = "host1"),
        )
        val updates = RecordingSourceUpdatePublisher()
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = TiktokPublisherConfig(pollingEnabled = true),
            gateway = gateway,
            scheduler = scheduler,
            liveStore = liveStore,
        )
        runtime.onLoad(
            testContext(
                updates = updates,
                subscriptions = FixedSubscriptionQueryService(listOf(publisher)),
                taskScheduler = scheduler,
            ),
        )
        runtime.onStart()
        scheduler.runOnce("tiktok-detect")
        scheduler.runOnce("tiktok-detect")
        scheduler.runOnce("tiktok-detect")

        assertEquals(
            listOf(SourceEventType.LIVE_STARTED, SourceEventType.LIVE_ENDED),
            updates.requests.map { it.update.eventType },
        )
        val started = updates.requests[0].update.payload as LivePayload
        assertEquals(LiveStatus.OPEN, started.status)
        assertEquals(LiveStatus.CLOSE, started.previousStatus)
        assertEquals("开播了", started.title)
        assertEquals("https://live.douyin.com/host1", updates.requests[0].update.link)
        val ended = updates.requests[1].update.payload as LivePayload
        assertEquals(LiveStatus.CLOSE, ended.status)
        assertEquals(LiveStatus.OPEN, ended.previousStatus)
        assertEquals(LiveStatus.CLOSE, liveStore.get(publisher.id)?.status)
    }

    @Test
    fun `failed live publish does not overwrite previous status`() = runBlocking {
        val publisher = testPublisher(1, "MS4wLjABAAAAtest")
        val liveStore = InMemoryTiktokLiveStatusStore()
        val gateway = RecordingTiktokGateway()
        gateway.enqueueLive(
            publisher.externalId,
            testLiveSnapshot(publisher.externalId, status = LiveStatus.OPEN),
            testLiveSnapshot(publisher.externalId, status = LiveStatus.CLOSE),
        )
        val updates = RecordingSourceUpdatePublisher().apply {
            nextResult = SourceUpdatePublishResult.failed("主程序未收下")
        }
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = TiktokPublisherConfig(pollingEnabled = true),
            gateway = gateway,
            scheduler = scheduler,
            liveStore = liveStore,
        )
        runtime.onLoad(
            testContext(
                updates = updates,
                subscriptions = FixedSubscriptionQueryService(listOf(publisher)),
                taskScheduler = scheduler,
            ),
        )
        runtime.onStart()
        scheduler.runOnce("tiktok-detect")
        updates.nextResult = SourceUpdatePublishResult.failed("主程序未收下")
        scheduler.runOnce("tiktok-detect")

        assertEquals(1, updates.requests.size)
        assertEquals(SourceEventType.LIVE_ENDED, updates.requests.single().update.eventType)
        assertEquals(LiveStatus.OPEN, liveStore.get(publisher.id)?.status)
    }

    @Test
    fun `dynamic-only subscription is not live-polled`() = runBlocking {
        val publisher = testPublisher(1, "MS4wLjABAAAAtest")
        val gateway = RecordingTiktokGateway()
        gateway.enqueueLive(publisher.externalId, testLiveSnapshot(publisher.externalId, status = LiveStatus.OPEN))
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = TiktokPublisherConfig(pollingEnabled = true),
            gateway = gateway,
            scheduler = scheduler,
        )
        runtime.onLoad(
            testContext(
                subscriptions = FixedSubscriptionQueryService(
                    listOf(publisher),
                    enabledEvents = setOf(SubscriptionEventKind.DYNAMIC),
                ),
                taskScheduler = scheduler,
            ),
        )
        runtime.onStart()
        scheduler.runOnce("tiktok-detect")

        assertEquals(emptyList(), gateway.fetchedLiveUserIds)
        assertEquals(false, runtime.isPollingPaused())
    }

    @Test
    fun `live detection can be disabled`() = runBlocking {
        val publisher = testPublisher(1, "MS4wLjABAAAAtest")
        val gateway = RecordingTiktokGateway()
        gateway.enqueueLive(publisher.externalId, testLiveSnapshot(publisher.externalId, status = LiveStatus.OPEN))
        val scheduler = ManualTaskScheduler()
        val runtime = runtime(
            config = TiktokPublisherConfig(pollingEnabled = true, liveDetectionEnabled = false),
            gateway = gateway,
            scheduler = scheduler,
        )
        runtime.onLoad(
            testContext(
                subscriptions = FixedSubscriptionQueryService(listOf(publisher)),
                taskScheduler = scheduler,
            ),
        )
        runtime.onStart()
        scheduler.runOnce("tiktok-detect")

        assertEquals(emptyList(), gateway.fetchedLiveUserIds)
    }

    private fun runtime(
        config: TiktokPublisherConfig,
        gateway: RecordingTiktokGateway,
        scheduler: ManualTaskScheduler,
        liveStore: TiktokLiveStatusStore = InMemoryTiktokLiveStatusStore(),
    ): TiktokPublisherRuntime {
        return TiktokPublisherRuntime(
            loadConfig = { config },
            gatewayFactory = { gateway },
            taskScheduler = scheduler,
            liveStatusStoreFactory = { liveStore },
        )
    }
}
