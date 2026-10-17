# 开发与发布

当前版本为 `0.1.0`，仍在测试阶段。插件工程和框架源码均要求 Java 21 JDK；运行发行包只要求 Java 21 运行环境。

新版插件 API 使用新的 Maven 坐标 `top.hetaoawa:astra-api:0.1.0`；`astra-framework` 是源码仓库的父工程，不向 Central 发布。旧版 `astra-qqbot` SDK 保留原有发布历史。

## 插件开发路径一：在框架仓库中开发

拉取仓库，在根目录执行：

```powershell
./scripts/new-plugin.ps1
```

Linux/macOS 使用 `sh scripts/new-plugin.sh`。填写插件 ID、名称、Maven group、Java 包名和插件版本后，工程默认生成到 `plugins-src/<插件ID>/`。可用 `-Directory` / `--directory` 指定其他目录。插件目录被外层 Git 忽略，可以在其中单独创建 Git 仓库。

进入插件目录：

```powershell
./scripts/plugin.ps1 setup
./scripts/plugin.ps1 package
./scripts/plugin.ps1 dev
```

`setup` 从本地框架源码构建运行包并安装 API；`package` 生成发布用插件 JAR；`dev` 同时完成构建、安装插件和启动。此时框架源码版本必须与插件锁定版本一致。

## 插件开发路径二：独立插件仓库

从对应框架版本的 GitHub Release 下载 `init.ps1` 或 `init.sh`，放在空目录（允许已存在 `.git`）并执行：

```powershell
./init.ps1 -Version 0.1.0
```

```sh
sh init.sh --version 0.1.0
```

脚本将询问插件信息，并从同一 Release 获取 `astra-release.properties`，按清单下载模板并校验 SHA-256，再下载固定版本的运行包。此时不会拉取框架源码。运行脚本时若省略版本号会要求交互选择版本；回车将默认选择最新稳定 Release，如需使用未发布的测试版，需明确指定版本号。

Windows 可用 `-Directory`、`-PluginId`、`-Name`、`-GroupId`、`-Package`、`-PluginVersion` 非交互初始化。Shell 对应选项为 `--directory`、`--id`、`--name`、`--group`、`--package`、`--plugin-version`；框架选项须放在插件信息选项之前。

初始化后的工程包含：

```text
my-plugin/
  pom.xml                         Maven API 依赖、插件 Manifest、Shade 打包
  astra-project.properties        固定的框架/API 版本、插件 ID 和 GitHub 仓库
  src/main/java/                  插件源码
  scripts/plugin.ps1、plugin.sh   setup / package / dev
  .astra-tools/AstraDev.java      随模板固定版本的开发工具，需提交 Git
  mvnw、mvnw.cmd、.mvn/           Maven Wrapper，需提交 Git
  .astra/runtime/                 本地运行包、配置、数据库、插件，Git 忽略
  dist/                           可发布插件 JAR，Git 忽略
```

API 将从 Maven 仓库获取，使用 `provided`；框架运行包将从 GitHub Release 获取。首次构建前，对应 API 必须已发布。第三方库由 Maven 下载并通过 Shade 合并；框架 API、PF4J 和日志实现排除在插件 JAR 之外。

在 `.astra/runtime/config/application.yml` 中填写机器人配置，设置 `QQ_APP_ID` / `QQ_BOT_SECRET`，再执行 `dev`。配置与数据库不会被后续 `setup` 覆盖。修改插件后重新执行 `dev`，需要先停止旧进程；不支持热更新。

如果工程文件已经生成，但运行包下载中断，进入工程执行 `setup` 重试即可；不要重新初始化或覆盖已有源码。

开源时提交插件源码、构建配置、开发脚本和版本文件，忽略 `.astra/` 及必要的敏感信息。别人克隆后执行 `setup` 即可恢复相同版本的运行环境。发布时分发 `dist/` 中的插件 JAR，并注明适配框架版本、配置、平台权限及插件依赖。
