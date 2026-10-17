# 发布目录

当前版本 **0.1.0，测试中**。运行 `scripts/build.ps1` 或 `sh scripts/build.sh` 后生成以下文件；只打包已有构建结果可使用 `scripts/package-release.ps1` / `package-release.sh`。

| 文件 | 用途 |
| --- | --- |
| `astra-runtime-0.1.0.zip` | 普通用户运行发行包，包含启动脚本、配置示例和示例插件 |
| `astra-plugin-template-0.1.0.zip` | 初始化脚本使用的工程模板及开发工具 |
| `astra-release.properties` | 固定框架、API、模板版本及 ZIP 摘要 |
| `init.ps1` / `init.sh` | 独立插件仓库的单脚本初始化入口 |
| `astra-api-0.1.0.jar` / `.pom` | Maven 发布所需的 API 和独立 POM |
| `astra-api-0.1.0-sources.jar` / `-javadoc.jar` | 使用 `-Papi-artifacts` 时生成的 API 源码和文档 |
| `astra-runtime-0.1.0.jar` | 可执行框架 JAR，ZIP 中同一文件命名为 `astra.jar` |
| `astra-example-plugin-0.1.0.jar` | 示例插件 |
| `SHA256SUMS` | 本次发行文件的 SHA-256 校验值 |

运行 ZIP 不包含用户配置、凭据或数据库。示例插件放在 `examples/`，需要用户自行安装。ZIP 和 API 发布顺序见 [开发与发布](../docs/development.md)。历史构建遗留且未列入当前 `SHA256SUMS` 的文件不属于本次发行。

## 2026-10-12 历史构建记录

当日功能和数据库 V4 的验证记录保留在 [验收记录](../docs/validation.md)。这不是 0.1.0 的线上 QQ 平台验收。
