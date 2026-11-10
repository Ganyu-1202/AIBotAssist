# 内嵌Pi运行时

**玩家无需安装本目录依赖或Bun。** Gradle通过build.mjs把Pi核心及Bun编译为五平台独立程序，随后内嵌JAR。游戏自动解包校验启动；自定义入口只是高级覆盖。

开发者：`bun install --frozen-lockfile`、`bun test`、`bun build.mjs`。版本由bun.lock固定；build.mjs生成SHA256清单。可用AIBOT_RUNTIME_TARGETS指定开发编译目标，但完整发行默认五平台齐全。

基于真实@earendil-works/pi-agent-core 0.85.1，非自写Agent循环，无coding/文件/Bash工具。工具说明见TOOLS.md。模型请求和凭据在Java端处理。

独立Windows程序已从JAR解出，在不含node_modules的空目录执行通过2轮模型/1工具的离线协议测试；Linux/macOS只有交叉编译证据。
