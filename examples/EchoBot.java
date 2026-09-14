import top.hetaoawa.qqbot.BotConfig;
import top.hetaoawa.qqbot.Intents;
import top.hetaoawa.qqbot.QQBot;

import java.util.concurrent.CountDownLatch;

/** Minimal WebSocket echo bot. This file is documentation and is not compiled by Maven. */
public final class EchoBot {
    private EchoBot() {
    }

    public static void main(String[] args) throws InterruptedException {
        BotConfig config = BotConfig.builder()
                .appId(System.getenv("QQ_BOT_APP_ID"))
                .clientSecret(System.getenv("QQ_BOT_CLIENT_SECRET"))
                .intents(Intents.GROUP_AND_C2C_EVENT)
                .build();

        QQBot bot = QQBot.create(config)
                .onError(Throwable::printStackTrace);
        QQBot.Plugin echo = bot.plugin("echo");
        echo.onMessage(message -> message.replyText("收到：" + message.content()));
        echo.onEvent("READY", event -> System.out.println("Bot READY"));

        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        bot.startWebSocket().join();

        new CountDownLatch(1).await();
    }
}
