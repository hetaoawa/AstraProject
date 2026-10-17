# 插件 HTTP 路由

插件通过 `context.httpRoutes()` 注册 HTTP 接口，与应用共享 `server.port`，无需另开端口。每个机器人启用插件时都会调用一次 `onEnable`，路径应包含环境和 AppID，避免多个机器人注册到同一地址。

## 注册路由

```java
public void onEnable(PluginContext context) {
    String base = "/plugins/demo/" + context.bot().environment()
        + "/" + context.bot().appId();
    context.httpRoutes().register("GET", base + "/echo/{value}", request ->
        CompletableFuture.completedFuture(HttpRoutes.Response.text(
            200, request.pathVariables().get("value"))));
    context.httpRoutes().register("POST", base + "/echo", request ->
        CompletableFuture.completedFuture(HttpRoutes.Response.text(
            200, request.text())));
}
```

支持 GET、POST、PUT、PATCH、DELETE、OPTIONS、HEAD，按注册的方法精确匹配。路径使用 Spring PathPattern 的参数和通配语法。相同方法下，等价的参数路径不能重复注册；匹配时优先选择更具体的路径。

`/qq`、`/astra`、`/actuator`、`/error` 由框架使用。路由表在整个进程内共享，插件需要自行区分各机器人的路径，并处理接口认证和业务权限。

## 请求与响应

`HttpRoutes.Request` 提供请求方法、路径变量、查询参数、请求头、原始字节和远端地址。`text()` 按 UTF-8 读取正文。`Response` 指定状态码、响应头和正文，可以使用 `text()`、`json()` 工厂方法。

处理函数返回 `CompletionStage<Response>`。请求体最大为 2 MiB；处理失败返回 500，并调用插件的 `onError`。

## 启用、超时与停用

初始化期间路由尚未开放。`onEnable` 完成后，框架提交注册；提交中途失败会撤销已注册的入口。完整生命周期见 [插件开发](plugins.md#生命周期与依赖)。

HTTP 响应最多等待 30 秒，超过后返回 504。插件处理仍会继续，请求不会自动重试。停用时先注销路由，再等待尚未结束的处理。

注册返回的 `Handle.close()` 可提前注销单个路由；插件停用时会自动注销它的全部路由。
