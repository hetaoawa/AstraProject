package top.hetaoawa.astra.api;

public record MessageEvent(EventData data, Message message) implements Event {}
