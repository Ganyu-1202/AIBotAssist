# 内嵌Pi运行时

**玩家无需安装本目录依赖或Bun。** Gradle通过build.mjs把Pi核心及Bun编译为五平台独立程序，随后内嵌JAR。游戏自动解包校验启动；自定义入口只是高级覆盖。

开发者：`bun install --frozen-lockfile`、`bun test`、`bun build.mjs`。版本由bun.lock固定；build.mjs生成SHA256清单。可用AIBOT_RUNTIME_TARGETS指定开发编译目标，但完整发行默认五平台齐全。

基于真实@earendil-works/pi-agent-core 0.85.1，非自写Agent循环，无coding/文件/Bash工具。工具说明见TOOLS.md。模型请求和凭据在Java端处理。

独立Windows程序已从JAR解出，在不含node_modules的空目录执行通过2轮模型/1工具的离线协议测试；Linux/macOS只有交叉编译证据。

`capture_view` 是客户端视野工具，返回内存中的 JPEG 图片；Pi 保留 image 内容块并适配为模型请求的 user/image_url，而不是把 Base64 塞进工具文本。读取视野无需服务器模组，修改世界仍需服务器裁决。详细规格及模型要求见 TOOLS.md。

`pi-0.85.1-bridge-9` 支持30个服务器工具和5个客户端工具，包括假人控制及服务器协助的远程区域图像。建造完成后补做结构复核，并明确标示机器运行尚未验收。新增结构化诊断事件，报告运行阶段与异常类别；Java端以独立诊断编号关联模型HTTP、响应解析和工具调用。详见 [调试日志](../docs/DEBUGGING.md)。会话由 Java 客户端按存档及实际玩家 UUID 加密保存，Pi 每轮读取恢复的近期聊天背景，不自动继续旧工具操作。
