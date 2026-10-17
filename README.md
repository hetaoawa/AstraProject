# Astra Framework

Astra 当前版本为 **0.1.0，仍处于测试阶段**。框架、插件 API 和配置可能继续调整，插件应明确标注适配版本。

Astra 是一个基于 Java 21 的 QQ 机器人框架。一个进程可以运行多个机器人，通过独立的插件 JAR 扩展功能，支持 Webhook 和 WebSocket 接入。

项目使用 Maven、Spring Boot 4.1.1 和 PF4J 3.16.0，自行实现 QQ 协议，不依赖 QQ 官方 SDK。数据库可选 SQLite、PostgreSQL 或 MySQL；事件队列在启动时选择 Redis Streams 或内存队列。

## 快速开始

普通用户从 [GitHub Releases](https://github.com/hetaoawa/AstraProject/releases) 下载 `astra-runtime-0.1.0.zip`，解压后执行 `start.ps1` 或 `sh start.sh`。首次执行会创建 `config/application.yml`；填写配置、设置 `QQ_APP_ID` / `QQ_BOT_SECRET`，将插件 JAR 放入 `plugins/` 后再次启动。运行只需要 Java 21，不需要 Git 或 Maven。示例插件位于 `examples/`，需要手动放入 `plugins/`。

发行配置默认使用 SQLite 并关闭 Redis。Webhook 的 HTTPS 回调或 WebSocket 权限仍需按 [配置与部署](docs/deployment.md) 准备。

### 插件开发

两条开发路径共用插件模板和打包规则，均需要 Java 21 **JDK**。Maven Wrapper 会自动准备 Maven。

- **独立插件仓库**：下载 [init.ps1](https://raw.githubusercontent.com/hetaoawa/AstraProject/main/scripts/init.ps1)，在空目录执行，按提示选择框架版本和插件信息。Linux/macOS 使用 [init.sh](https://raw.githubusercontent.com/hetaoawa/AstraProject/main/scripts/init.sh)。也可从指定版本 Release 下载脚本；测试版应明确选择 `0.1.0`。
- **框架源码仓库内**：拉取本仓库，执行 `./scripts/new-plugin.ps1` 或 `sh scripts/new-plugin.sh`，在 `plugins-src/` 中创建插件工程。

进入生成的插件目录后，执行 `./scripts/plugin.ps1 package` 打包，执行 `./scripts/plugin.ps1 dev` 打包、安装并启动本地框架。Linux/macOS 使用 `sh scripts/plugin.sh package` / `dev`。发布文件位于 `dist/`。

独立工程通过 Maven 获取 `astra-api`，从 GitHub 下载固定版本的运行包；源码仓库内的工程使用本地框架源码。完整步骤见 [开发与发布](docs/development.md)。

### 从框架源码构建

准备 Java 21 JDK。在项目根目录执行 Windows 构建脚本：

```powershell
.\scripts\build.ps1 -JavaHome '你的 Java 21 JDK 目录'
```

Windows 构建脚本默认将依赖缓存、编译文件和测试输出放在项目的 `.astra/cache/`，可通过 `-CacheRoot` 或 `ASTRA_CACHE_ROOT` 指定其他位置；发行文件生成到 `release/`。Linux/macOS 使用 `sh scripts/build.sh`。其他构建方式见 [开发与发布](docs/development.md)。

1. 将 [配置示例](config/application.example.yml) 复制为运行目录下的 `config/application.yml`。
2. 设置 `QQ_APP_ID` 和 `QQ_BOT_SECRET` 环境变量，或按部署环境修改配置中的占位符。
3. 将 `release/astra-example-plugin-0.1.0.jar` 复制到运行目录下的 `plugins/`。
4. 在运行目录启动：

```powershell
java -jar release/astra-runtime-0.1.0.jar
```

默认应用端口为 8080。Webhook 地址为 `https://你的域名/qq/webhook/{environment}/{appId}`，其中环境和 AppID 必须与配置一致。HTTPS 代理和 WebSocket 配置见 [接入 QQ](docs/deployment.md#接入-qq)。

访问 `/astra/health` 可查看运行状态。示例插件提供 `/ping`、`/info`、`/ask`、`/history` 命令，以及 `/plugins/example/{environment}/{appId}/status` 和 GET、POST echo 路由。`/history` 需要开启消息历史。

未配置机器人时，应用也能启动并检查部署。内置配置的机器人列表为空；复制配置示例后，需要填写其中的机器人信息。

## 使用前需要了解

- 同一聊天的事件依次处理，不同聊天可以并行；每个机器人分别设置容量和并发数。
- 插件处理失败和平台请求失败都不会自动重试。重启后，已开始但结果不明的调用会被跳过。
- 用户、群和其他平台标识均保留为字符串，按 AppID、环境和标识类型区分。跨机器人关联需要显式配置 union 范围，并收到非空的 union 值。
- 资料库只保存框架观察到的信息；成员查询返回本地记录，可能缺少平台上的其他成员。
- 消息历史和完整事件归档默认关闭，可以分别开启和设置保留期。
- 会话只保存在内存中，重启后结束。替换插件 JAR 后也需要重启。
- 插件以可信代码运行。类加载器用于隔离依赖，框架没有进程级插件沙箱。授权规则由权限插件提供。

当前版本的 API、插件接口和数据库结构均已重写。旧插件需要改用新 API；项目不提供旧数据库迁移工具。默认数据库为 `data/astra-v1.db`，部署时请使用独立的数据目录或数据库。

## 文档

完整目录见 [文档导航](docs/README.md)。常用入口：

| 要做的事 | 文档 |
| --- | --- |
| 配置机器人、部署服务、排查故障 | [配置与部署](docs/deployment.md) |
| 开发插件、处理事件、等待回复、注册任务 | [插件开发](docs/plugins.md) |
| 使用消息、历史、事件归档和 HTTP 扩展 | [功能索引](docs/features.md) |
| 查看接口频率、消息额度和等待规则 | [接口限速与配额](docs/rate-limits.md) |
| 了解队列恢复、数据隔离和执行状态 | [架构与执行规则](docs/architecture.md) |
| 查看 QQ 接口清单和已有验证范围 | [协议覆盖](docs/protocol-coverage.md)、[验收记录](docs/validation.md) |
| 创建独立插件工程 | [插件模板](templates/plugin/README.md) |
| 初始化插件仓库、调试、构建发行包和发布 API | [开发与发布](docs/development.md) |
| 查看发布文件 | [发布目录](release/README.md) |

## 项目模块

| 模块 | 用途 |
| --- | --- |
| `astra-api` | 事件、注解、消息、插件上下文和服务接口，不依赖 Spring |
| `astra-core` | 事件匹配、聊天调度、执行状态、内存会话和定时任务 |
| `astra-qq` | QQ 协议、签名、鉴权、Webhook、WebSocket 和平台请求调度 |
| `astra-storage` | 三种数据库的存储实现，以及 Redis Streams 和内存队列 |
| `astra-runtime` | Spring Boot 应用、配置、插件管理、控制台和健康检查 |
| `astra-example-plugin` | 可以独立加载的示例插件 |

QQ 协议实现参考 [QQ 官方文档](https://bot.q.qq.com/wiki/develop/api-v2/)，事件匹配分类参考 [NoneBot 响应器](https://nonebot.dev/docs/api/plugin/on)。

## 许可证

项目自有代码采用 [Eclipse Public License 2.0（EPL-2.0）](LICENSE)，允许符合条款的商业使用，无需另行取得商业授权。分发框架或其修改版本时，应遵守 EPL 关于许可证、声明和对应源码提供的要求；第三方组件保留各自许可证。源码与版本标签见本仓库，相关声明见 [NOTICE](NOTICE)。本项目未指定 EPL 的 Secondary Licenses。
