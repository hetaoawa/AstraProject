# 配置与部署

普通用户下载 `astra-runtime-0.1.0.zip` 并解压，运行 `start.ps1` 或 `sh start.sh`，首次会生成配置文件。填写配置、放入插件后再次启动。发行包只需要 Java 21；仓库源码构建和独立插件开发见 [开发与发布](development.md)。

本页用于部署和维护 Astra 服务。第一次运行可按 [快速开始](../README.md#快速开始) 操作，插件开发见 [插件开发](plugins.md)。

## 运行目录

运行包需要 Java 21。以项目根目录作为运行目录时，文件布局如下：

```text
config/
  application.yml
plugins/
  astra-example-plugin-0.1.0.jar
release/
  astra-runtime-0.1.0.jar
data/
  astra-v1.db
  astra-v1.lock
```

`config/application.yml` 由 [配置示例](../config/application.example.yml) 复制并修改。`data/` 中的数据库和锁文件在运行时创建。路径均相对于启动时的工作目录；更换目录时应一并检查配置、插件和数据路径。

```powershell
java -jar release/astra-runtime-0.1.0.jar
```

默认读取当前目录的 `config/application.yml`。其他配置目录可通过 `--spring.config.additional-location=file:其他配置目录/` 指定。

SQLite 默认使用 `data/astra-v1.db`。使用 PostgreSQL 或 MySQL 时，为框架建立独立数据库和账号，再填写 `dialect`、`url`、`username`、`password`。

## 接入 QQ

### Webhook

设置 `astra.bots[].transport: WEBHOOK`，回调地址为：

```text
https://你的域名/qq/webhook/{environment}/{appId}
```

环境和 AppID 必须与配置完全一致。HTTPS 反向代理转发到应用端口，默认是 8080。代理应保留原始请求体和以下请求头，并将请求体上限设为 2 MiB：

- `X-Bot-Appid`。
- `X-Signature-Timestamp`。
- `X-Signature-Ed25519`。

截止本文档发布，平台支持配置的 Webhook 端口为 80、443、8080、8443，部署反代项目时请注意端口及防火墙问题。

### WebSocket

设置 `astra.bots[].transport: WEBSOCKET`，按机器人的实际权限填写 `intents`。`shards` 表示本进程创建的分片总数，特殊事件订阅需要先取得平台权限。

生产 `api-base` 默认是 `https://api.bot.qq.com`。`environment` 用于区分本地数据；使用沙箱时还需要配置平台规定的沙箱 API 地址。

### Redis

每组独立运行数据使用不同的 `namespace`，并为 Redis 配置 `noeviction` 和所需的 AOF 或 RDB 持久化。启动时若 Redis 不可用，框架会使用内存队列。

运行中的 Redis 故障会暂停处理，恢复后继续。Redis 数据被清除，或使用内存队列的进程退出时，尚未完成的队列任务可能丢失，框架无法补回。详细恢复规则见 [队列与恢复](architecture.md#队列与恢复)。

## 配置参考

以下是内置默认值，完整配置见 [application.example.yml](../config/application.example.yml)。配置示例中的 `astra.console` 为 `true`，内置默认值为 `false`。

### 框架配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `astra.database.dialect` | `sqlite` | 可选 `sqlite`、`postgresql`、`mysql` |
| `astra.database.url` | `jdbc:sqlite:data/astra-v1.db` | 框架使用的独立数据库 |
| `astra.database.username`、`astra.database.password` | 空 | PostgreSQL、MySQL 连接账号 |
| `astra.lock-file` | `data/astra-v1.lock` | 本地进程锁文件 |
| `astra.redis.enabled` | `true` | 启动时尝试连接 Redis |
| `astra.redis.uri` | `redis://localhost:6379/0` | Redis 连接地址 |
| `astra.redis.namespace` | `astra:v1` | 每组独立数据使用不同名称 |
| `astra.history.enabled` | `false` | 保存消息历史 |
| `astra.history.retention-days` | `30` | `0` 表示长期保留 |
| `astra.event-archive.enabled` | `false` | 单独保存完整平台报文 |
| `astra.event-archive.retention-days` | `30` | 按接收时间清理，`0` 表示长期保留 |
| `astra.plugins.directory` | `plugins` | 插件 JAR 目录 |
| `astra.plugins.enabled-by-default` | `true` | 新插件对各机器人的初始状态；数据库已保存的状态优先 |
| `astra.plugins.configuration` | 空映射 | 按插件 ID 提供配置 |
| `astra.console` | `false` | 开启标准输入管理控制台 |
| `astra.bots` | `[]` | 机器人列表；空列表可用于部署检查 |

### 机器人配置

下表配置项均位于 `astra.bots[]` 下。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `app-id`、`secret` | 必填 | AppID 和机器人密钥 |
| `environment` | `production` | 区分运行环境和数据 |
| `transport` | `WEBHOOK` | `WEBHOOK` 或 `WEBSOCKET` |
| `api-base` | `https://api.bot.qq.com` | 平台 API 地址 |
| `queue-capacity` | `10000` | 接收队列容量，包含未完成任务 |
| `concurrency` | `8` | 并行处理的聊天数 |
| `outbound-capacity` | `1000` | 发送队列容量 |
| `rate-limits.certification` | `UNVERIFIED` | 按后台认证状态设置，可选 `PERSONAL`、`ENTERPRISE` |
| `rate-limits.concurrency` | `8` | 尚未结束的 HTTP 请求上限；等待配额的任务不占用 |
| `rate-limits.day-zone` | `Asia/Shanghai` | 每日额度使用的日期与时区 |
| `rate-limits.cooldown` | `1s` | 收到平台限流反馈后的最短冷却时间 |
| `rate-limits.budgets` | `[]` | 后台额外分配的全局或接口额度 |
| `request-timeout` | `30s` | 单次平台请求超时 |
| `signature-tolerance` | `5m` | 接收签名允许的时间偏差 |
| `intents` | `33554435` | WebSocket 事件订阅位集 |
| `shards` | `1` | 本进程创建的 WebSocket 分片数 |
| `union-enabled` | `false` | 开启已获平台授权的 union 关联 |
| `union-scope` | 空 | union 共享范围，开启关联后必填 |

示例配置通过 `QQ_APP_ID` 和 `QQ_BOT_SECRET` 环境变量读取凭据，占位符没有默认值。请在启动前设置。限速配置的含义和生效范围见 [接口限速与配额](rate-limits.md)。

## 控制台与健康检查

设置 `astra.console: true` 后，可以在标准输入中执行：

```text
status
plugins list
plugins enable astra-example
plugins disable astra-example
plugins enable astra-example APPID production
plugins disable astra-example APPID production
```

不带机器人参数时，对所有已配置机器人生效。框架没有远程插件管理接口；生产服务可关闭控制台，修改配置后重启。

`/astra/health` 检查队列、数据库执行权、已配置的 WebSocket 连接和事件消费异常。状态为 `DOWN` 时返回 HTTP 503。

Actuator 默认只监听本机 `127.0.0.1:8081`，公开 `health`、`info`，不公开密钥或配置值。

## 常见故障

| 现象 | 检查与处理 |
| --- | --- |
| Webhook 返回 401 | 检查 URL 中的 AppID、`X-Bot-Appid`、密钥、原始请求字节、签名头和服务器时间 |
| Webhook 返回 503 | 查看 `/astra/health`；队列满时检查长时间阻塞的插件并调整容量或并发；Redis 故障时恢复原实例 |
| 启动后使用 `memory` | Redis 启动探测未通过；服务恢复后重启框架才能改用 Redis |
| WebSocket 状态为 `DOWN` | 检查平台关闭码、`intents` 权限、分片数和剩余登录额度；恢复序号只记录已成功入队的位置 |
| 平台返回 11253 等权限错误 | 接口尚未向机器人开放；申请能力后再由业务发起请求，框架不会自动重发 |
| 插件加载失败 | 检查 Manifest、API 版本、`Plugin-Requires`、必需依赖和打包内容；不要重复打包 `astra-api` |
| 数据库或 Redis namespace 已占用 | 确认只有一个进程使用这组数据；异常退出后数据库租约最多等待约 30 秒，Redis 租约约 45 秒 |
| 数据库租约失效 | 停止旧进程，确认这组数据只由一个进程使用，再重启；不要在运行中删除租约或 Streams 条目 |
| 事件尚未识别 | 通过 `@OnEvent` 和 `RawEvent` 读取原始 `d` 字段 |
| 历史查询抛出关闭异常 | 检查 `astra.history.enabled`；开启后只记录新消息 |
| 事件归档查询抛出关闭异常 | 检查 `astra.event-archive.enabled`，它与历史开关分别设置 |
| HTTP 路由注册冲突 | 为不同插件和机器人设置不同路径；参数名称不同但模式等价的路由也会冲突，启用失败会撤销注册资源 |
| 插件 HTTP 接口返回 504 | 处理超过 30 秒；请求不重试，停用仍需等待实际处理结束 |

正常停止 JVM 时，留出约 30 秒供接收和请求清理。异常退出后，已开始但结果不明的处理器和平台请求不会重跑。插件应按业务需要实现幂等处理。

## 构建与发布

Windows 下运行 [scripts/build.ps1](../scripts/build.ps1)。脚本可通过 `-JavaHome` 或 `JAVA_HOME` 指定 Java 21，默认将构建中间文件放在项目的 `.astra/cache/`，可通过 `-CacheRoot` 或 `ASTRA_CACHE_ROOT` 指定缓存位置。发行文件生成到 `release/`，包含运行 ZIP、模板 ZIP、JAR、版本清单、初始化脚本和 SHA-256 校验文件。

其他环境可使用 Java 21 执行 Maven：

```text
mvn verify
```

直接执行 Maven 时，默认输出在 `target/` 下的各模块目录。可用 `-Dastra.buildRoot=构建目录`、`-Dmaven.repo.local=依赖缓存目录` 指定其他位置。各发布文件的用途见 [发布目录](../release/README.md)。

CI 使用 Java 21 验证，并组装运行 ZIP、模板 ZIP、API 包、版本清单和初始化脚本。标签发布工作流生成草稿测试版 Release；API 通过 astra-api 的 central-release Profile 使用 Maven deploy 发布，维护者需配置 Central 凭据与 GPG 签名。具体发布顺序与 Maven Central 的额外要求见 [开发与发布](development.md#发布-api-和-github-release)。项目自有代码采用 EPL-2.0，第三方依赖仍适用各自的许可证。
