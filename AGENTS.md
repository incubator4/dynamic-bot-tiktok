# Agent instructions

This repository is **one plugin product**: a Kotlin fatJar content-source plugin for [dynamic-bot](https://github.com/Colter23/dynamic-bot).

It is **not** the host app, **not** a message sink, and **not** a monorepo of multiple plugins.

Read this file first. Longer rationale lives in `docs/`. Cursor also loads `.cursor/rules/*.mdc`.

## Product

Subscribe to [Douyin / 抖音](https://www.douyin.com) publishers (users) and push new videos into dynamic-bot as `DynamicPayload` updates, plus live start/end as `LivePayload`.

Official reference plugins:

- [dynamic-bot-weibo](https://github.com/Colter23/dynamic-bot-weibo) — closest template (Cookie login, conservative polling)
- [dynamic-bot-bilibili](https://github.com/Colter23/dynamic-bot-bilibili) — richer source plugin (search, QR login, live)
- [PLUGIN_DEVELOPMENT.md](https://github.com/Colter23/dynamic-bot-core/blob/main/PLUGIN_DEVELOPMENT.md) — public plugin contract
- Sibling [dynamic-bot-rednote](https://github.com/incubator4/dynamic-bot-rednote) — same product skeleton for Xiaohongshu

## Docs map

| File | Read when |
| --- | --- |
| [docs/product.md](docs/product.md) | scope, MVP, non-goals |
| [docs/architecture.md](docs/architecture.md) | package layout and runtime split |
| [docs/plugin-contract.md](docs/plugin-contract.md) | core APIs this plugin must implement |
| [docs/decisions.md](docs/decisions.md) | accepted ADRs; update when changing direction |

## Hard rules

1. Depend only on `dynamic-bot-core` **public** APIs (`compileOnly` in production). Never import host runtime, repository, table, loader, or event-bus packages.
2. Publish videos through `PluginContext.sourceUpdatePublisher`. Advance cursors only when the result is not `FAILED`.
3. Hang I/O, polling, and long work on the context `CoroutineScope` / `TaskScheduler`.
4. Treat Douyin as risk-sensitive: conservative intervals, pause on login loss or风控, never hammer per-user APIs. Support Cookie and QR login (ADR-0010).
5. Never commit cookies, tokens, or harvested session files. Config form labels and user-visible errors stay in 中文.
6. Record architecture and product-scope changes in `docs/decisions.md`. Do not invent core APIs; look them up in `dynamic-bot-core`.
7. Kotlin packages follow the Gradle `group` (`com.incubator4.dynamic`). Plugin source lives in `com.incubator4.dynamic.tiktok`, not `top.colter.dynamic.*`.
