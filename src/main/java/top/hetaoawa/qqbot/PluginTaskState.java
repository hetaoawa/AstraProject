package top.hetaoawa.qqbot;

/** Lifecycle state of one framework-managed plugin invocation. */
public enum PluginTaskState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED
}
