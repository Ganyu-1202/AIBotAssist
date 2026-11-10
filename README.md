> 最新源码新增多材料、英简中设置界面、按模式方块黑名单及门/床/液体/受控容器NBT，见 [BLOCK_POLICY.md](BLOCK_POLICY.md)。这些改动尚未重打发布JAR。

# AI Bot Assist — 一体化依赖构建

当前构建会把Pi SDK、Bun运行环境及前置模组内嵌进JAR，玩家无需另行安装Bun/npm、下载Pi依赖或填写运行时路径。

## 玩家安装

1. 启动器安装Minecraft 1.21.1、Java21和对应Fabric或NeoForge加载器。加载器/游戏/Java本身不打入模组。
2. 只放入对应平台的一份模组JAR，不要同时安装两份。Architectury已内嵌；Fabric版额外内嵌Fabric API。
3. 游戏内执行 `/aibot settings`，填写API地址、模型和密钥或完成TokenNest授权。**自定义Pi路径留空使用内嵌运行时**；旧默认agent-runtime/index.mjs自动迁移到内嵌模式。
4. 首次调用自动校验并解包当前平台程序到config/aibotassist/runtime/<内容哈希>/，随后启动，不进行玩家侧网络依赖下载。磁盘必须可写且允许执行本地程序。

支持内嵌Windows x64、Linux x64/ARM64、macOS x64/ARM64。Windows ARM64及其它架构暂不内嵌。五平台一起提供会增大JAR：当前Fabric约163MiB，NeoForge约161MiB；首次解包还需一份本机程序磁盘空间。Linux/macOS仅交叉编译，仍待本机验证；系统运行库、执行权限或安全策略可能影响启动。

## 工具与功能

真实Pi Agent核心0.85.1驱动19个游戏工具；详见 [工具参数及安全边界](agent-runtime/TOOLS.md)。客户端保存凭据并发送模型请求，Pi不接收密钥，服务器裁决权限、模式、材料与世界操作。熔炼/酿造遵循原版tick，任务恢复限制见工具文档。

## 开发构建

构建机仍需Java21和Bun（不是玩家依赖），Gradle自动运行bun install --frozen-lockfile及五平台独立编译，将资源嵌入两平台JAR。可通过`-PbunExecutable=绝对路径`指定构建用Bun。首次构建需要下载依赖及跨平台Bun目标；项目内已有锁文件、依赖目录和已生成运行时，分发JAR不要求玩家下载。SDK源码通过固定包和锁文件提供，不将node_modules提交Git。

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-21'
.\gradlew.bat build
```

发布产物：fabric/build/libs/aibotassist-fabric-0.1.1.jar、neoforge/build/libs/aibotassist-neoforge-0.1.1.jar。本轮为内嵌验证构建，版本号未递增，请勿与之前同名旧包混淆。

验证记录见 [VERIFICATION.md](VERIFICATION.md)。
