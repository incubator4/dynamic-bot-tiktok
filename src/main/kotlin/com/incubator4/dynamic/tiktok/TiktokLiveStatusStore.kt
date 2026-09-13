package com.incubator4.dynamic.tiktok

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.core.plugin.SourceStateStore

internal interface TiktokLiveStatusStore {
    fun get(publisherId: Int): PublisherLiveStatus?

    fun save(state: PublisherLiveStatus): PublisherLiveStatus

    fun evict(publisherId: Int)
}

internal class SourceStateTiktokLiveStatusStore(
    private val stateStore: SourceStateStore,
) : TiktokLiveStatusStore {
    private val cache: MutableMap<Int, PublisherLiveStatus> = ConcurrentHashMap()

    override fun get(publisherId: Int): PublisherLiveStatus? {
        cache[publisherId]?.let { return it }
        return stateStore.findLatestLiveStatus(publisherId)?.also { cache[publisherId] = it }
    }

    override fun save(state: PublisherLiveStatus): PublisherLiveStatus {
        val updated = stateStore.saveLiveStatus(state)
        cache[state.publisherId] = updated
        return updated
    }

    override fun evict(publisherId: Int) {
        cache.remove(publisherId)
    }
}
