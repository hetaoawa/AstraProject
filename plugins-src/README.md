# 仓库内插件开发

在框架根目录执行 `./scripts/new-plugin.ps1`，或 `sh scripts/new-plugin.sh`。
脚本会在这里生成独立插件工程。用户插件默认不纳入框架 Git 仓库；可以在各插件目录单独初始化 Git。

进入生成的插件目录，执行 `./scripts/plugin.ps1 dev` 或 `sh scripts/plugin.sh dev`。
首次使用本地框架源码构建并安装 API，后续命令仍通过 Maven 进行增量构建。
配置、数据库和运行插件位于插件工程的 `.astra/runtime/`，发布用插件 JAR 位于 `dist/`。
