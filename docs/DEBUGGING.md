# AI 调试日志

bridge-9 增加模型请求重试：每次模型轮最多3次尝试（额外重试2次，间隔1秒/2秒），针对网络I/O、超时、HTTP408/429/5xx、空响应、断流和损坏工具参数。认证/权限/格式HTTP4xx、明确失败或输出截断通常不重试。已执行游戏工具、效果未知操作不重放，也不重启整段agent会话。独立API在客户端重试，共享API在服务器内重试且仍占用同一玩家的并发名额。取消会撤销待重试任务；日志搜索model_attempt/model_retry查看尝试次数。重试可能增加模型计费；不是游戏操作回滚机制。

共享API总等待上限300秒，客户端共享请求310秒，Pi模型RPC330秒；单次HTTP请求90秒，整轮agent仍10分钟。工具RPC仍110秒。红石skill和模组接口见 `.agents/skills/minecraft-redstone/SKILL.md` 与 `docs/MOD_EXTENSIONS.md`。

从 bridge-8 起，调试信息默认写入 Minecraft 实例的 `logs/latest.log`（日志配置启用时也会进入 `debug.log`）。无需额外设置。每次 AI 请求生成独立诊断编号，发生错误时显示在聊天窗口；搜索该编号可以按顺序查看整个请求。共享 API 错误可能另外返回“服务端诊断编号”，需在服务器日志搜索。

常见阶段：

| 阶段 | 检查范围 |
| --- | --- |
| runtime_start / runtime_handshake | 内嵌或自定义Pi程序启动、就绪握手 |
| credentials / model_catalog | 授权状态、刷新令牌、模型目录；目录查询失败时记录接口回退 |
| model_request_encode / model_http | 实际模型、渠道、选择的协议、提供商主机、消息和工具数量 |
| http_json_parse / model_response_decode | HTTP状态、内容类型、响应字节数、可用请求ID，以及choices/output/error等字段类型 |
| responses_stream_decode | Responses事件解析；responses_stream事件记录事件数、已完成输出数、是否从完成项恢复output |
| runtime_model_conversion / pi/model_response_shape | Java模型消息转换到Pi消息的过程 |
| pi/tool_arguments_json | 模型工具调用参数的JSON解析，例如SyntaxError |
| tool/工具名 | 工具开始、结果状态；服务端工具调用ID可与服务器日志对应 |
| shared_http_response / shared_json_parse / shared_response_decode | 共享API的服务端HTTP及响应解析 |

每条 `AI debug [编号]` 带请求开始后的累计毫秒数。失败记录保留异常类型、最多8层原因链和每层最多10个堆栈位置；不序列化异常原始消息，因为JSON解析器和上游库可能在消息中回显模型正文。每个诊断范围最多512条事件，防止异常循环刷屏。子进程退出前记录是否存活及可取得的退出码。

Pi的stderr持续读取以免堵塞进程；仅保留字符计数和已知异常类别，扫描样本最多8192字符。Pi自身的解析错误通过结构化诊断事件报告阶段与异常类别。stderr原文、异常原始消息、完整请求/响应、聊天正文、工具参数、容器NBT、图片/Base64、Authorization和OAuth令牌均不新增到调试日志。服务器既有工具拒绝说明和游戏本身的聊天日志仍按原行为记录。

复现错误时，保留游戏提示中的诊断编号、发生时间，以及该编号在客户端和服务端日志中的完整事件。HTTP状态为200但在 `http_json_parse` 失败，表示响应无法按JSON对象解析；在 `model_response_decode` 失败，应查看响应字段类型/状态，不能直接推断模型不支持工具。`pi/tool_arguments_json + SyntaxError` 表示工具参数字符串不是有效JSON。只有证据支持时才归因，不自动重试有副作用的工具。

更新需要同时替换客户端和服务端模组，并重新启动。已有错误没有保存的异常细节无法追溯补回；新版日志用于下一次复现。开发实例请重新运行runClient，避免依赖热替换新增类/字段。

2026-11-14 已确认一类兼容问题：gpt-6.1-terra/default 的非流式响应返回 completed 但 output=[]；流式响应有 response.output_item.done，最终 response.completed 的 output 仍为空。模组现对 Responses 请求启用 stream，并在最终响应为空时按 output_index 还原已完成输出。不会执行 delta 或 added 中的半成品调用；只有成功完成事件到达后才允许交给工具链。缺少完成事件、上游失败或真正空输出仍报错，不自动重试。普通 JSON 响应保持兼容，Chat Completions 请求方式不变。
