package top.hetaoawa.astra.api;

import java.time.Duration;
import java.time.ZoneId;

public interface Tasks {
  Handle delay(Duration delay, Runnable task);

  Handle interval(Duration initialDelay, Duration interval, Runnable task);

  Handle cron(String expression, ZoneId zone, Runnable task);

  interface Handle {
    void cancel();
  }
}
