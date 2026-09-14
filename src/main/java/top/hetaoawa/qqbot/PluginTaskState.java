package top.hetaoawa.qqbot;

/** 日志中使用的插件回调生命周期状态。 */
public enum PluginTaskState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED
}
