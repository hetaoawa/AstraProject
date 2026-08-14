# 插件开发快速上手

本文面向拿到 AstraQQBot 后，希望开发自己功能插件的 Java 用户。完成本文后，你会得到一个可运行的机器人宿主，以及一个独立的 `HelloPlugin`。

> AstraQQBot 当前提供事件注册和 OpenAPI，不包含 JAR 自动扫描、插件市场或热加载器。本文中的“插件”是一个独立 Java 类：它负责向 `QQBot` 注册监听器，宿主程序负责创建和装配插件。

## 1. 准备机器人和开发环境

你需要：

- JDK 17 或更高版本；
- Maven 3.8 或更高版本；
- 已在 QQ 开放平台创建机器人；
- 机器人的 AppID 和 AppSecret；
- 已把 AstraQQBot 安装到本地 Maven 仓库，或已从制品仓库取得框架依赖。

如果拿到的是框架源码，先在框架目录执行：

```bash
mvn clean install
```

该命令会安装主 JAR、源码 JAR 和 Javadoc JAR。将框架作为 Maven 依赖重新加载后，在 IDEA 中调用 API 时即可通过悬停查看中文用法、参数和返回值说明。

这会把以下制品安装到本地 Maven 仓库：

```text
top.hetaoawa:AstraQQBot:0.1.0-SNAPSHOT
```

## 2. 创建插件项目

新建一个普通 Maven 项目，例如 `my-astra-bot`：

```text
my-astra-bot/
├─ pom.xml
└─ src/main/java/com/example/mybot/
   ├─ Main.java
   ├─ BotPlugin.java
   └─ HelloPlugin.java
```

`pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-astra-bot</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>top.hetaoawa</groupId>
            <artifactId>AstraQQBot</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <configuration>
                    <mainClass>com.example.mybot.Main</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## 3. 定义插件契约

在自己的项目中创建 `BotPlugin.java`：

```java
package com.example.mybot;

import top.hetaoawa.qqbot.QQBot;

public interface BotPlugin extends AutoCloseable {
    String name();

    void register(QQBot bot);

    @Override
    default void close() {
        // 插件持有线程池、数据库连接等资源时在实现类中覆盖。
    }
}
```

这个接口属于你的业务工程，不是框架强制要求。它让多个功能模块拥有一致的注册和关闭方式。

## 4. 编写第一个插件

创建 `HelloPlugin.java`。当用户发送 `/hello` 时，插件回复欢迎语：

```java
package com.example.mybot;

import top.hetaoawa.qqbot.QQBot;

import java.util.concurrent.CompletableFuture;

public final class HelloPlugin implements BotPlugin {
    @Override
    public String name() {
        return "hello";
    }

    @Override
    public void register(QQBot bot) {
        QQBot.Plugin plugin = bot.plugin(name());
        plugin.onMessageAsync(message -> {
            String content = message.content();
            if (content == null || !content.trim().equalsIgnoreCase("/hello")) {
                return CompletableFuture.completedFuture(null);
            }

            return message.replyText("你好，我的第一个 AstraQQBot 插件已运行！");
        });
    }
}
```

监听器会收到单聊和群聊消息。插件应先判断命令是否匹配，再执行业务逻辑，避免回复所有消息。

## 5. 创建宿主并加载插件

创建 `Main.java`：

```java
package com.example.mybot;

import top.hetaoawa.qqbot.BotConfig;
import top.hetaoawa.qqbot.BotLogLevel;
import top.hetaoawa.qqbot.Intents;
import top.hetaoawa.qqbot.QQBot;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        BotConfig config = BotConfig.builder()
                .appId(requiredEnv("QQ_BOT_APP_ID"))
                .clientSecret(requiredEnv("QQ_BOT_CLIENT_SECRET"))
                .intents(Intents.GROUP_AND_C2C_EVENT)
                .logLevel(BotLogLevel.DEBUG)
                .build();

        QQBot bot = QQBot.create(config)
                .onEvent("READY", event -> System.out.println("Bot READY"))
                .onError("main-error-handler", Throwable::printStackTrace);

        List<BotPlugin> plugins = List.of(
                new HelloPlugin()
        );

        for (BotPlugin plugin : plugins) {
            plugin.register(bot);
            System.out.println("Loaded plugin: " + plugin.name());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            plugins.forEach(plugin -> {
                try {
                    plugin.close();
                } catch (Exception error) {
                    error.printStackTrace();
                }
            });
            bot.close();
        }));

        bot.startWebSocket().join();
        new CountDownLatch(1).await();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }
}
```

`startWebSocket().join()` 只等待 Gateway 返回 `READY`，随后就会结束等待。因此示例使用 `CountDownLatch` 保持进程运行。

示例启用了 DEBUG 日志。因为插件使用 `onMessageAsync(name(), ...)` 注册，控制台会显示事件被 `hello` 捕获，以及回复 Future 真正完成或失败的时间。

## 6. 配置凭证并运行

不要把 AppSecret 写入源码、配置示例或 Git。

Linux/macOS：

```bash
export QQ_BOT_APP_ID="你的 AppID"
export QQ_BOT_CLIENT_SECRET="你的 AppSecret"
mvn compile exec:java
```

PowerShell：

```powershell
$env:QQ_BOT_APP_ID="你的 AppID"
$env:QQ_BOT_CLIENT_SECRET="你的 AppSecret"
mvn compile exec:java
```

看到以下日志后，在测试环境向机器人发送 `/hello`：

```text
Loaded plugin: hello
Bot READY
```

## 7. 在插件中使用更多能力

### 监听指定事件

```java
bot.onEvent("FRIEND_ADD", event -> {
    System.out.println("新增好友：" + event.data());
});
```

标准化监听器还包括：

- `onInteraction`：按钮互动和 Reaction；
- `onRelationship`：好友、群成员和机器人关系变化；
- `onMessageStatus`：订阅消息及审核状态；
- `onResource`：Guild、Channel、论坛和音频资源事件。

### 主动发送消息

```java
bot.sendPrivateMessage(userOpenId, "主动单聊消息");
bot.sendGroupMessage(groupOpenId, "主动群聊消息");
```

### 使用管理 OpenAPI

```java
bot.api().getGroupInfo(groupOpenId)
        .thenAccept(info -> System.out.println(info.toPrettyString()))
        .exceptionally(error -> {
            error.printStackTrace();
            return null;
        });
```

更多调用见 [扩展 OpenAPI 与管理能力](open-api.md)。

## 8. 插件拆分建议

每个插件只处理一个业务域，例如：

```text
plugins/
├─ HelpPlugin.java       # /help
├─ WelcomePlugin.java    # 好友或成员加入欢迎
├─ ModerationPlugin.java # 禁言、审批和管理命令
└─ MediaPlugin.java      # 图片、文件和富媒体
```

建议遵循以下规则：

1. `register` 只注册监听器，不执行长时间阻塞操作。
2. 命令解析、权限判断和业务服务放在插件自己的方法或服务类中。
3. 数据库连接、线程池等资源由插件持有，并在 `close()` 中释放。
4. 所有 `CompletableFuture` 都要处理异常，不要静默丢弃发送失败。
5. QQ 事件可能重复投递；涉及签到、积分、审批等写操作时，以消息 ID 或事件 ID 做幂等。
6. 不要在监听器线程中执行慢查询、文件处理或外部网络调用，应转交业务线程池。
7. 使用带名称的监听器注册方法；需要跟踪异步完成时使用 `onMessageAsync` 或 `onEventAsync`。

## 9. 选择正确的 Intents

默认的插件示例只需要：

```java
.intents(Intents.GROUP_AND_C2C_EVENT)
```

使用其他事件时按需组合：

```java
.intents(Intents.GROUP_AND_C2C_EVENT | Intents.INTERACTION)
```

只订阅插件真正需要且机器人已经获得权限的 Intent。订阅未授权事件可能导致 Gateway 拒绝连接。

## 10. 开发和发布前检查

开发过程中至少完成：

```bash
mvn clean test
mvn package
```

发布或部署前确认：

- AppID/AppSecret 从环境变量或密钥服务读取；
- 使用 QQ 开放平台测试环境验证命令和权限；
- 监听器中的耗时任务已转交线程池；
- 主动消息和管理 API 的 Future 均处理异常；
- 写操作具备幂等策略；
- 进程关闭时会执行插件 `close()` 和 `QQBot.close()`；
- 业务没有使用当前不支持的频道消息或频道私信能力。

## 常见问题

### 能否把插件单独打成 JAR 后自动加载？

当前框架不会扫描插件目录，也没有热加载协议。可以先把插件作为同一 Maven 工程中的独立类或模块，由宿主显式创建并注册。若自行实现 JAR 加载器，需要同时设计类加载隔离、配置、依赖冲突和卸载生命周期。

### 为什么机器人 READY 后程序立即退出？

`startWebSocket()` 返回的 Future 在 READY 时完成，不代表机器人会一直阻塞主线程。使用应用服务器生命周期、`CountDownLatch` 或其他合适机制保持进程运行。

### 插件为什么收不到事件？

依次检查机器人权限、`Intents`、测试环境配置和事件名称。详见 [故障排查](troubleshooting.md)。

## 下一步

- [配置项](configuration.md)
- [日志与调试](logging.md)
- [事件与消息模型](events.md)
- [发送与回复消息](messages.md)
- [扩展 OpenAPI 与管理能力](open-api.md)
- [框架支持功能列表](support-matrix.md)
