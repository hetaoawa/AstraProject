# Architecture

## Event path

1. Gateway/Webhook receives and parses an event.
2. Dispatch matches typed, generic, message, command, interaction, relationship, status and resource handlers.
3. Each match becomes a managed plugin task and is submitted without blocking the receive thread.
4. A per-plugin `Executors.newThreadPerTaskExecutor` starts a named Java 21 virtual thread when concurrency permits.
5. The callback returns, throws, times out, or is cancelled; exactly one atomic terminal transition performs logging, error reporting, bookkeeping and queue advancement.

Tasks carry plugin name, handler name, event type/id, queued/start/end times, queue duration, execution duration and final state. States are `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`, and `CANCELLED`.

## Backpressure and isolation

Each plugin has independent active-task and bounded-pending limits. Defaults are 256 concurrent and 512 pending. Queue admission uses only a short state lock; receive threads never wait on a semaphore. A full queue is rejected immediately and never uses caller-runs behavior. One plugin's saturation or failure does not execute in, or stop, another plugin.

## Timeout and command ordering

Task timeout starts immediately before invoking the handler, not while queued. Timeout atomically wins the terminal state, reports a detailed `TimeoutException`, and interrupts the virtual thread. Late success/failure is ignored.

Matching command handlers form a priority-ordered asynchronous chain. Each handler is still a normal managed task with its own timeout. Propagation is decided only after that handler returns successfully. Failure or timeout stops the chain. Ordinary message listeners continue to follow their independent routing path.

## Shutdown

Plugin close removes all of that runtime's listeners, rejects new work, cancels queued work, waits for running callbacks, then interrupts survivors after `pluginShutdownTimeout`. Bot close first stops transports, then closes all runtimes under one shutdown deadline, their timeout schedulers, and HTTP resources. Both operations are idempotent.

Cancellation is cooperative. `Thread.interrupt()` cannot safely terminate code that ignores interruption; `Thread.stop()` is never used.

## HTTP and tokens

Public business APIs block synchronously, which is appropriate on virtual threads. The internal HTTP executor remains bounded for Java HTTP request isolation and Gateway lifecycle work. Access-token refresh uses explicit lock/condition single-flight coordination: shared state is checked under lock, the network request runs outside it, and concurrent callers share the completed refresh.
