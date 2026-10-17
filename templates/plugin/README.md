# 插件工程模板

本目录是初始化脚本的输入，包含占位符，不能直接复制后构建。

- 独立仓库：从 GitHub Release 下载 `init.ps1` 或 `init.sh`，指定框架版本并运行。
- 框架仓库内：执行 `scripts/new-plugin.ps1` 或 `sh scripts/new-plugin.sh`。

脚本生成插件工程、Maven Wrapper、固定版本信息、打包及调试入口。插件 ID、名称、group、包名和插件版本都可以填写。API 依赖为 `provided`，第三方依赖默认通过 Shade 打包。

进入生成的工程执行 `scripts/plugin.ps1 package` / `dev`，或 `sh scripts/plugin.sh package` / `dev`。`setup` 用于克隆后恢复本地运行环境。发布插件 JAR 位于 `dist/`；配置、运行包和数据库位于被 Git 忽略的 `.astra/runtime/`。

修改本模板、开发工具或 Wrapper 后，提升 `templates/template.properties` 中的模板版本。模板与框架通过每个 Release 的版本清单关联。

完整说明见 [开发与发布](../../docs/development.md)，插件接口见 [插件开发](../../docs/plugins.md)。

框架提供的开发工具与示例代码采用 EPL-2.0。初始化脚本会把许可证和声明放入生成工程的 `.astra-tools/`，并随插件 JAR 保留；插件作者应为自己的原创代码选择适当许可证。
