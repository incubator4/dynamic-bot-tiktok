# Decisions

按时间追加。改方向时先改本文件，再改代码。状态：`Accepted` / `Superseded` / `Proposed`。

## ADR-0001: 本仓库只做单一来源插件

- Status: Accepted
- Date: 2026-09-13

`dynamic-bot-tiktok` 是一个 Kotlin fatJar 产品，不是宿主、不是出口插件、不是多插件单体仓。交付物是放入主程序 `plugins/` 的 `*-all.jar`。

## ADR-0002: 骨架对齐微博官方插件与小红书仓库

- Status: Accepted
- Date: 2026-09-13

一期以 [dynamic-bot-weibo](https://github.com/Colter23/dynamic-bot-weibo) 为模板：`Plugin` 薄入口 + `Runtime` 编排 + `Gateway` + `Mapper` + `CursorStore` + `RequestFailureHandler`。Bilibili 的搜索、二维码、直播、批量关注留到后续 ADR。

产品文档、Gradle 骨架和 Cursor 规则对齐 [dynamic-bot-rednote](https://github.com/incubator4/dynamic-bot-rednote)。

只依赖 [dynamic-bot-core](https://github.com/Colter23/dynamic-bot-core) 公开 API，不依赖主程序内部实现。

## ADR-0003: 平台标识用 `tiktok`

- Status: Accepted
- Date: 2026-09-13

| 项 | 值 |
| --- | --- |
| 仓库 / 产品名 | `dynamic-bot-tiktok` |
| `plugin.yml` `id` | `tiktok-publisher` |
| `PlatformId` | `tiktok` |
| 包名 | `com.incubator4.dynamic.tiktok` |

对外文案用「抖音」。用户可见配置和错误信息用中文。标识符不用 `douyin`，避免和仓库名分裂。一期范围只覆盖国内抖音，不覆盖国际版 TikTok。

Kotlin 包名必须跟随 Gradle `group`，见 ADR-0008。

## ADR-0004: 登录以 Cookie 为先

- Status: Accepted
- Date: 2026-09-13

`PublisherLoginProvider` 一期只保证 Cookie 登录、登录态检查、登录失效暂停轮询。二维码登录、Cookie 自动刷新、导出 Cookie 都不是 MVP。

Cookie 只存在用户本机的 `config/`，不进 git，不写进文档示例的真实值。

## ADR-0005: 轮询默认保守

- Status: Accepted
- Date: 2026-09-13

抖音风控敏感，默认对齐微博 / 小红书：

- `pollingEnabled` 默认 `false`，需要推送时再打开。
- 默认轮询间隔不少于 60 秒；默认请求间隔不少于 1 秒。
- 连续未登录或疑似风控时暂停轮询，而不是缩短间隔重试。
- 补发窗口默认 0，首次启动只记游标、不推旧作品。

检测策略优先「少请求、可暂停」，不要默认对每个订阅用户高频直打个人主页。若后续有更稳的时间线/关注流，再单开 ADR 切换。

## ADR-0006: 一期能力边界

- Status: Accepted
- Date: 2026-09-13

In：Cookie 登录、用户 ID 查资料、作品轮询、`DynamicPayload` 映射、游标、风控暂停、配置表单；宜做链接解析。

Out：用户名搜索、自动关注、直播、视频无水印下载、国际版 TikTok、插件独立后台页、出口协议、第二套推送通道。

订阅键使用抖音用户 ID。用户名 / 抖音号搜索另开 ADR。

## ADR-0007: 构建与 API 版本

- Status: Accepted
- Date: 2026-09-13

- `compileOnly` `top.colter.dynamic:dynamic-bot-core:0.0.4`
- `plugin.yml` `apiVersion: 3.0.0`（与当前 `CORE_PLUGIN_API_VERSION` 对齐）
- Java 17 字节码，Gradle toolchain 21
- 官方 fatJar 脚本：不打包宿主已提供的 logging / core
- 本地若有 `../dynamic-bot-core`，composite build

升级 core 时同步改本 ADR 的版本号，并跑官方插件同风格的边界测试（禁止 import 宿主内部包）。

## ADR-0008: Kotlin 包名跟随 Gradle group

- Status: Accepted
- Date: 2026-09-13

本仓库是 incubator4 维护的插件，Kotlin 源码包名必须挂在 Gradle `group` 下面，不要沿用官方插件的 `top.colter.dynamic.*`。

| 项 | 值 |
| --- | --- |
| Gradle `group` | `com.incubator4.dynamic` |
| 本插件源码 / 测试包 | `com.incubator4.dynamic.tiktok` |
| 生成代码（如 `GitVersion`） | `com.incubator4.dynamic`（直接用 `project.group`） |

约束：

- 手写 Kotlin 的 `package` 必须是 `com.incubator4.dynamic` 或其子包。
- 目录与 `package` 一致：`src/main/kotlin/com/incubator4/dynamic/tiktok/`。
- `plugin.yml` 的 `mainClass` 与入口类全名一致。
- 改 `group` 时同步改包名、目录和本 ADR；不要只改一边。
- 依赖的官方坐标仍是 `top.colter.dynamic:dynamic-bot-core`，那是别人的制品，不表示本仓库包名跟官方走。
