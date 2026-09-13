package com.incubator4.dynamic.tiktok

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.event.SubscriptionChangedEvent
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.plugin.PublisherSourcePlugin
import top.colter.dynamic.core.task.TaskScheduler

public class TiktokPublisherPlugin private constructor(
    private val runtime: TiktokPublisherRuntime,
) :
    PublisherSourcePlugin,
    PublisherLoginProvider,
    ConfigurablePlugin<TiktokPublisherConfig> {

    public constructor() : this(TiktokPublisherRuntime())

    internal constructor(
        loadConfig: (String) -> TiktokPublisherConfig,
        gatewayFactory: (TiktokPublisherConfig) -> TiktokGateway,
        saveConfig: (String, TiktokPublisherConfig) -> Unit = { _, _ -> },
        taskScheduler: TaskScheduler,
        liveStatusStoreFactory: (() -> TiktokLiveStatusStore)? = null,
    ) : this(
        TiktokPublisherRuntime(
            loadConfig = loadConfig,
            gatewayFactory = gatewayFactory,
            saveConfig = saveConfig,
            taskScheduler = taskScheduler,
            liveStatusStoreFactory = liveStatusStoreFactory,
        ),
    )

    override val platformId: PlatformId
        get() = runtime.platformId

    override val configId: String
        get() = runtime.configId
    override val configName: String
        get() = runtime.configName
    override val configDescription: String
        get() = runtime.configDescription
    override val configClass = TiktokPublisherConfig::class
    override val configFormSpec = TiktokPublisherConfigForm.spec

    override val supportedLoginMethods: Set<PublisherLoginMethod>
        get() = runtime.supportedLoginMethods
    override val supportsCookieExport: Boolean
        get() = runtime.supportsCookieExport

    override suspend fun onLoad(context: PluginContext) {
        runtime.onLoad(context)
    }

    override suspend fun onStart() {
        runtime.onStart()
    }

    override suspend fun onStop() {
        runtime.onStop()
    }

    override suspend fun onUnload() {
        runtime.onUnload()
    }

    override fun currentConfig(): TiktokPublisherConfig {
        return runtime.currentConfig()
    }

    override fun applyConfig(next: TiktokPublisherConfig): ConfigApplyResult {
        return runtime.applyConfig(next)
    }

    override suspend fun checkLoginState(): PublisherLoginResult {
        return runtime.checkLoginState()
    }

    override suspend fun loginByCookie(cookie: String): PublisherLoginResult {
        return runtime.loginByCookie(cookie)
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): PublisherLoginResult {
        return runtime.loginByQrCode(onQrCode, onStatusChanged)
    }

    override suspend fun exportCookie(): String? {
        return runtime.exportCookie()
    }

    override suspend fun onSubscriptionChanged(event: SubscriptionChangedEvent) {
        runtime.onSubscriptionChanged(event)
    }
}
