package top.hetaoawa.astra.api;

public record HandlerFailure(
    String pluginId, String handlerId, Event event, Throwable cause, boolean interrupted) {}
