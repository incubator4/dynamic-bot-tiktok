package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.event.NoopSystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.tools.loggerFor

private val requestFailureLogger = loggerFor<TiktokRequestFailureHandler>()

internal class TiktokRequestFailureHandler(
    private val configProvider: () -> TiktokPublisherConfig,
    private val notificationPublisher: SystemNotificationPublisher = NoopSystemNotificationPublisher,
) {
    private var consecutiveLoginFailures: Int = 0
    private var pollingPausedByLoginFailure: Boolean = false
    private var pollingPausedByRiskControl: Boolean = false

    fun isPollingPaused(): Boolean = pollingPausedByLoginFailure || pollingPausedByRiskControl

    suspend fun <T> run(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return try {
            val result = block()
            recordSuccess(operation)
            Result.success(result)
        } catch (error: Throwable) {
            recordFailure(operation, error)
            Result.failure(error)
        }
    }

    suspend fun recordSuccess(operation: String) {
        val wasPaused = isPollingPaused()
        if (consecutiveLoginFailures > 0 || wasPaused) {
            requestFailureLogger.info {
                "抖音请求已恢复：operation=$operation，之前连续未登录失败=$consecutiveLoginFailures，之前风控暂停=$pollingPausedByRiskControl"
            }
        }
        consecutiveLoginFailures = 0
        pollingPausedByLoginFailure = false
        pollingPausedByRiskControl = false
        if (wasPaused) {
            publishNotification(
                SystemNotificationPublishRequest(
                    type = "tiktok.login_recovered",
                    severity = SystemNotificationSeverity.INFO,
                    title = "抖音登录状态已恢复",
                    content = "抖音请求已恢复，轮询可以继续执行。",
                    dedupeKey = "tiktok.login_recovered",
                    details = mapOf("operation" to operation),
                ),
            )
        }
    }

    suspend fun recordFailure(operation: String, error: Throwable) {
        if (error is CancellationException) throw error

        when (error) {
            is TiktokBlockedException -> recordRiskControlFailure(
                operation = operation,
                message = error.message ?: "抖音请求疑似被风控",
                cause = error,
            )
            is TiktokLoginException -> recordLoginFailure(
                operation = operation,
                message = error.message ?: "抖音登录状态不可用",
                cause = error,
            )
            is TiktokApiException -> recordApiFailure(operation, error)
            else -> recordUnknownFailure(operation, error)
        }
    }

    private suspend fun recordLoginFailure(
        operation: String,
        message: String,
        cause: Throwable?,
    ) {
        consecutiveLoginFailures += 1
        val threshold = configProvider().maxConsecutiveLoginFailures
        if (threshold > 0 && consecutiveLoginFailures >= threshold) {
            if (!pollingPausedByLoginFailure) {
                pollingPausedByLoginFailure = true
                logError(cause) {
                    "抖音登录状态失效，已暂停轮询请求：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold。请重新登录或更新 Cookie。"
                }
                publishNotification(
                    SystemNotificationPublishRequest(
                        type = "tiktok.login_paused",
                        severity = SystemNotificationSeverity.ERROR,
                        title = "抖音登录状态失效",
                        content = "抖音连续请求未登录，已暂停轮询请求。请重新登录或更新 Cookie。",
                        dedupeKey = "tiktok.login_paused",
                        details = mapOf(
                            "operation" to operation,
                            "consecutiveLoginFailures" to consecutiveLoginFailures.toString(),
                            "threshold" to threshold.toString(),
                            "error" to message,
                        ),
                    ),
                )
            } else {
                logWarn(cause) {
                    "抖音轮询仍处于未登录暂停状态：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold"
                }
            }
            return
        }

        val logMessage = if (threshold > 0) {
            "抖音请求未登录：operation=$operation，连续未登录失败=$consecutiveLoginFailures/$threshold，原因=$message"
        } else {
            "抖音请求未登录：operation=$operation，自动暂停已关闭，原因=$message"
        }
        logWarn(cause) { logMessage }
    }

    private suspend fun recordRiskControlFailure(
        operation: String,
        message: String,
        cause: Throwable?,
    ) {
        consecutiveLoginFailures = 0
        if (!pollingPausedByRiskControl) {
            pollingPausedByRiskControl = true
            logError(cause) {
                "抖音请求疑似被风控，已暂停轮询：operation=$operation。请稍后再试，不要继续高频请求。"
            }
            publishNotification(
                SystemNotificationPublishRequest(
                    type = "tiktok.risk_paused",
                    severity = SystemNotificationSeverity.ERROR,
                    title = "抖音请求疑似被风控",
                    content = "抖音请求疑似被风控，已暂停轮询。请稍后再试或更新 Cookie，不要继续高频请求。",
                    dedupeKey = "tiktok.risk_paused",
                    details = mapOf(
                        "operation" to operation,
                        "error" to message,
                    ),
                ),
            )
        } else {
            logWarn(cause) {
                "抖音轮询仍处于风控暂停状态：operation=$operation，原因=$message"
            }
        }
    }

    private fun recordApiFailure(operation: String, error: TiktokApiException) {
        consecutiveLoginFailures = 0
        requestFailureLogger.warn(error) {
            "抖音接口请求失败：operation=$operation，原因=${error.message ?: "未知"}"
        }
    }

    private fun recordUnknownFailure(operation: String, error: Throwable) {
        consecutiveLoginFailures = 0
        requestFailureLogger.warn(error) {
            "抖音请求出现未知异常：operation=$operation，类型=${error::class.qualifiedName ?: error::class.simpleName ?: "未知"}，原因=${error.message ?: "未知"}"
        }
    }

    private suspend fun publishNotification(request: SystemNotificationPublishRequest) {
        runCatching { notificationPublisher.publish(request) }
            .onFailure {
                requestFailureLogger.warn(it) { "抖音系统通知发布失败：type=${request.type}" }
            }
    }

    private fun logWarn(cause: Throwable?, message: () -> String) {
        if (cause == null) {
            requestFailureLogger.warn(message)
        } else {
            requestFailureLogger.warn(cause, message)
        }
    }

    private fun logError(cause: Throwable?, message: () -> String) {
        if (cause == null) {
            requestFailureLogger.error(message)
        } else {
            requestFailureLogger.error(cause, message)
        }
    }
}
