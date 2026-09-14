# Commands

```java
plugin.onCommand("ban", 100, false, command -> {
    String member = command.arguments().getFirst();
    moderation.ban(member);
    command.replyText("done");
});
```

Arguments are command name, priority, `continuePropagation`, and a synchronous `EventHandler<QQCommandEvent>`. Higher priority runs first. Matching handlers are serial: the next handler is submitted only after the prior managed task returns successfully. Each handler receives its own default 60-second timeout.

`continuePropagation=false` stops later command handlers after success. Failure, rejection, cancellation, or timeout also stops the current command chain. Ordinary `onMessage` listeners are routed independently and are not suppressed by command propagation.
