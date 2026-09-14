/**
 * 面向 Java 21+ 的 QQ 官方机器人开发框架。
 *
 * <p>插件通过同步回调处理事件，可以直接执行数据库、文件和 HTTP 操作，也可以抛出受检异常。
 * 普通回调可能并发运行；共享状态需要保证线程安全，耗时操作需要设置超时并响应线程中断。</p>
 *
 * <p>消息发送和 OpenAPI 方法同步返回结果。WebSocket 启动方法返回 Future，并在首次 READY 时完成。
 * 应用停止时应调用 {@link top.hetaoawa.qqbot.QQBot#close()} 释放连接和插件资源。</p>
 */
package top.hetaoawa.qqbot;
