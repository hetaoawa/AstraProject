# Logging

Managed task logs include plugin, handler, kind, event type, event ID, virtual-thread name, queued/start/end timestamps, queue duration, execution duration and final state. Payload content is omitted unless TRACE payload logging is explicitly enabled; secrets and access tokens are never logged.

```java
bot.plugin("hello").onMessage(message -> message.replyText("hello"));

bot.onError(error -> {
    // Central reporting. This callback is itself managed and failures are logged once.
});
```

Failures distinguish normal callback exceptions, `TimeoutException`, `RejectedExecutionException`, and `CancellationException`. One plugin failure does not prevent other plugins from receiving the event. Error-handler failure is logged but not recursively sent back through the same channel.

Log levels are configured with `logLevel`; raw event payload logging additionally requires `logEventPayloads(true)`.
