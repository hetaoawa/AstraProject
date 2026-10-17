package top.hetaoawa.astra.api;

public record ApiOperation(
    String id, String method, String pathTemplate, String title, String documentation) {}
