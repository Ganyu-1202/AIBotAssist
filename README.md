> 最新源码新增多材料、英简中设置界面、按模式方块黑名单及门/床/液体/受控容器NBT，见 [BLOCK_POLICY.md](BLOCK_POLICY.md)。这些改动已包含在0.2.0。

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

发布产物：fabric/build/libs/aibotassist-fabric-0.2.0.jar、neoforge/build/libs/aibotassist-neoforge-0.2.0.jar。0.2.0为本次完整构建，安装前移除旧版本JAR。

验证记录见 [VERIFICATION.md](VERIFICATION.md)。

## 0.2.0 本地凭据

API密钥及TokenNest access/refresh token保存在玩家电脑客户端 `config/aibotassist/private/`，采用AES-256-GCM加密及世界绑定AAD；`local.key`为同机解密密钥，密文以哈希文件名隔离，不含可读世界路径。单人绑定存档绝对路径，联机绑定连接地址，再结合登录玩家UUID。不同维度共享同世界记录；多人服务器内部更换世界无法由客户端可靠识别，仍按服务器地址区分。

进入世界加载，离开清理内存，刷新令牌后更新保存；修改API地址或OAuth client_id清除当前内存授权，不能把旧服务商令牌带到新端点。设置中“清除当前世界凭据”删除该世界本地记录，不影响其它记录。退出游戏不会删除已保存记录。OAuth浏览器回调绑定发起世界，切换世界后不接受旧登录结果。

密钥从不传到Minecraft服务器、Pi进程或世界存档。不要分享整个config目录及local.key。加密密钥与密文都在本机，因此不能抵抗同系统账号恶意程序/文件窃取；Windows使用系统继承ACL，Unix尽力设为仅所有者权限，尚未使用系统凭据管理器。保存失败会报错，不宣称已持久保存。存档移动或服务器换地址会视为新作用域，需重新填写。
