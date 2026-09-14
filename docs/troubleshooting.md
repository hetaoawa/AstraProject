# Troubleshooting

## Callback timed out

The default is 60 seconds of RUNNING time per handler. Increase it with `taskTimeout` or explicitly disable it with `disableTaskTimeout()`. Queue time is not counted.

Timeout sends `Thread.interrupt()`. Ensure blocking calls react to interruption and loops check the interrupt flag. Code that ignores interruption may continue consuming resources; Java provides no safe forced termination and the framework never uses `Thread.stop()`.

## RejectedExecutionException

The plugin reached both `maxConcurrentTasks` and `maxPendingTasks`. Reduce incoming work, speed up callbacks, or carefully raise bounded limits. The callback is not executed on the Gateway/Webhook thread.

## Plugin or bot close is slow

`pluginShutdownTimeout` controls only shutdown waiting. Close first rejects new tasks and cancels queued tasks, then waits for running callbacks before interrupting them. It is separate from normal task timeout.

## Process exits after READY

`startWebSocket().join()` returns when READY arrives. Keep the process alive with the hosting application's lifecycle.

## Synchronous API failure

Message and OpenAPI calls throw directly. Handle a known business error locally when appropriate, or let it leave the callback so the framework logs and reports it.

## Missing completion tracking

If plugin code starts a third-party fire-and-forget asynchronous task and returns, the framework cannot discover it. Use synchronous calls or explicitly await the result.
