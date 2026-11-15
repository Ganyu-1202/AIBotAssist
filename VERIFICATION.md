# 验证记录 — 0.1.0

## 构建环境

- Java：用户提供的 `D:\Program Files\Java\jdk-21`。
- Gradle Wrapper 8.10.2；Architectury Plugin 3.4.164（3.4-SNAPSHOT 当前解析版本）；Architectury Loom 1.7.416。
- Minecraft 1.21.1；Architectury API 13.0.8。
- Fabric Loader 0.16.10 / Fabric API 0.115.1+1.21.1。
- NeoForge 21.1.172。
- 网络下载使用用户指定的 HTTP 代理 `http://127.0.0.1:7897`。代理未硬编码为模组运行默认值。

## 已观察到的结果

1. `gradlew build --no-daemon`：BUILD SUCCESSFUL，Fabric 与 NeoForge 均生成 remapped 发布 JAR。
2. `PlanTest`：10 tests、0 failures、0 errors、0 skipped。
   - 聊天无方块任务；材料统计；命令方块拒绝；TNT/空气/熔岩/箱子拒绝；重复坐标拒绝；小数/溢出/越界拒绝；合法边界；数量上限；无效/过长响应；最大方案小于32700字节上传限制。
3. Fabric 开发客户端：成功注册 C2S `aibotassist:up`、S2C `aibotassist:down`，完成音频及纹理图集初始化，无观察到的加载崩溃。
4. NeoForge 开发客户端：发现 `aibotassist`、`architectury`，完成音频及纹理图集初始化，无观察到的加载崩溃。
5. Fabric 开发专用服务器：加载模组、成功注册 C2S receiver；原版 EULA 检查处停止，未主动更改 EULA 文件。
6. NeoForge 开发专用服务器：发现模组及依赖、创建开发世界，启动至 `Done (...)! For help, type "help"`。开发环境日志显示 offline/insecure mode，仅属开发运行配置，不能用作公开生产服务器配置。
7. 启动检查创建的 Java/Gradle 子进程已按本次测试 PID 关闭；没有保留公开服务器进程。
8. TokenNest OAuth 发现文档和公共 API 信息已读取，确认 PKCE S256、授权码/刷新 grant、公共客户端 token endpoint auth `none`、`api`/`offline_access` 范围和 `/v1` API 地址。

原始构建/启动日志位于被 Git 忽略的 `.dev/`；JUnit XML/HTML 位于 `common/build/test-results/test/` 与 `common/build/reports/tests/test/`。

## 未验收，不应宣称通过

- 未提供真实服务商 API 凭据；没有付费模型调用、真实回复或端到端 AI 建造证据。
- 未注册本模组专属 TokenNest client_id；没有真实用户浏览器授权、令牌刷新或云端撤销证据。
- 没有真实双玩家联机、库存扣除、创造/生存/旁观切换实测。
- 没有使用正式启动器安装最终发布 JAR 的生产映射运行检查；当前启动检查使用 Loom 开发环境。
- 没有第三方领地保护模组兼容实测。
- 没有实际截图验证设置页不同分辨率布局或游戏内按钮交互。

## 下一次人工/凭据环境验收

请在临时测试世界而非正式存档中验证：

1. 设置名称、有效模型和密钥；输入 `@名称 你好`，应本人可见，不进入普通公共聊天。
2. 生存背包准备足量橡木木板，在空地请求小屋；确认前不扣料、不放块；确认后库存数量与成功方块数一致。
3. 生存材料不足时方案被拒绝；创造没有材料仍可放置；旁观请求建造被拒绝而聊天可用。
4. 执行中取消/缺料/移动超过32格/换维度/死亡/切旁观，剩余部分不执行不扣料；已经完成部分保留。
5. 两人分别配置不同账户，检查聊天和模型回复不互相出现；服务器网络负载不含密钥；服务商错误正文不显示。
6. 按注册策略配置 OAuth client_id，浏览器完成授权、取消、错误 state、超时和刷新测试。
7. 正式加载器安装发布 JAR 再进行一次单人/多人验收，分别覆盖 Fabric、NeoForge。

## 追加修改：受限命令与模式感知批量方块操作

- 新增 CommandPolicy、命令预览/确认、玩家权限解析及执行；禁止 kill/gamemode 和间接调用。
- 新增 removals 协议、同 tick 服务器生成 setblock/fill 执行、整批材料预扣与未放置退款；创造允许负硬度拆除，生存拒绝。
- 新增7项命令策略测试、5项批量指令/拆除协议测试，与原10项合计22项。双平台 build 成功。
- 上述改动未进行真实游戏世界端到端测试；以前客户端/服务器启动记录针对修改前版本，不能视为此次命令执行和材料结算的实测证据。
- 需特别实测：生存缺料整批拒绝、部分失败退款、创造基岩拆除、生存基岩拒绝、掉落规则、领地保护、非OP普通命令权限和禁止命令变体。

## 本次源码修改（按用户要求未编译）

- 两平台统一注册客户端 `/aibot settings`；开屏延迟至后续客户端 tick，避免发送聊天后原版 ChatScreen 关闭覆盖新设置界面。退出世界会清除延迟请求，返回不恢复旧聊天界面。
- `@AI` 输入在本人聊天 HUD 本地回显，不广播给他人。
- 删除手动确认命令/按钮和待确认队列；方案经服务器校验通过直接进入任务队列，执行前安全校验保留。保留 cancel。
- 更新模型提示和 README。
- 仅做源码路径及残留引用静态检查；未运行 Gradle、未启动游戏、未编译或替换 JAR。前述构建/测试/启动结果属于旧版本，不覆盖本次改动。
- 待后续验证：Fabric/NeoForge 首次进入世界、重复 `/aibot settings`、命令补全与服务器 cancel 共存、聊天本地回显、自动任务执行与无效方案拒绝。

## 草地建造拒绝与 settings 补全修复（源码，未编译）

- 截图的旧错误提示合并了全部原因，无法仅凭截图确证具体失败点。源码发现空气限定会拒绝草/高草等可替换植物，实体查询还会因掉落物误拒绝。
- 放置允许空气/原版可替换的无液体、无方块实体目标；仍拒绝实心已有方块，不因创造模式绕过区域保护。实体碰撞只阻止玩家/生物。
- 受控 setblock/fill 改为 replace，并在指令前执行完整目标检查；不会为模型开放任意原始方块指令。测试预期同步修改，未运行。
- 可控校验失败显示目标坐标、占用方块/保护/实体/材料等具体原因，不输出未知异常原文。
- 服务端 aibot 命令树同步声明 settings 子节点，以匹配客户端命令并提供补全；界面仍由客户端执行。
- 按用户要求未运行 Gradle、未生成 JAR、未启动游戏；需要后续在两加载器验证 Tab 补全、草地房屋、实心占用拒绝以及掉落物/玩家碰撞行为。

## 设置页完成按钮（源码，未编译）

- 保存按钮更名“完成”；仅当 save() 返回 true 时关闭并在聊天栏提示本地设置已保存。失败停留并显示错误。TokenNest 登录仍调用 save() 而不触发关闭。
- 静态检查完成；未运行构建、网络验证或界面实测，现有 JAR 未更新。

## 0.1.1 发布构建

- 用户授权后，将 Gradle、Fabric 和 NeoForge 元数据版本统一更新为0.1.1。
- Java21 执行 `gradlew build --no-daemon` 成功；额外执行 `gradlew :common:test --rerun-tasks --no-daemon` 成功，22项测试全部通过（0失败/0错误）。
- 核验两份发布包中的版本字段均为0.1.1且包含设置界面类。
- Fabric：fabric/build/libs/aibotassist-fabric-0.1.1.jar，SHA256 3fd60463480fe097f91371dc4016133dcfc3aa659b7f9543673a8854afe5fb94。
- NeoForge：neoforge/build/libs/aibotassist-neoforge-0.1.1.jar，SHA256 62f57150108ff16098b76c32c55fa9fa6b89536c339abb5b776b780a39872ea8。
- 日志：.dev/build-0.1.1.log、.dev/test-0.1.1.log。
- 本次未启动游戏；设置补全/开关界面、草地建造、真实API及联机任务仍需游戏实测。之前“源码未编译”记录是历史阶段，此次已编入0.1.1。

## 资源准备与2x2合成（源码阶段）

- 自动清障仅覆盖建筑占位处；生存负硬度拒绝，要求工具的方块检查正确工具与保守耐久预算，按块消耗1耐久；未模拟附魔耐久减免或工具相关掉落。
- 同任务可包含方块与多种一般命令，固定顺序拆除/清障、放置、一般命令。
- 新增AutoCrafting，真实Shaped/Shapeless配方、递归材料准备、模拟库存、配方matches/assemble/remainingItems。2x2背包合成不需设备，超出2x2寻找附近工作台，缺少时尝试合成并合法放置。
- `:common:test`成功，现有22项回归通过；未新增真实游戏配方集成测试，不能据此视为资源制作闭环验收。没有重新打包发布JAR。
- 未完成：熔炉/高炉/烟熏炉燃料及实际加工等待、酿造台材料和加工流程、独立物品制作目标协议、满背包产物/预留材料交付集成验证。现有合成接入面为建筑材料准备，并非完整通用制造Agent。

## 本次建造拒绝诊断

- 已读取 neoforge/run/logs/latest.log：18:27:46 出现“方案格式或命令无效”兜底提示，未记录原异常。开发环境模组标注0.1.1，但回复提及自动清障，不能仅用版本号推断用户运行的是旧发布包。
- 客户端已解析并提交方案，服务器端校验（特别是清障 removalTarget 的 IllegalArgumentException）同样会进入旧兜底分支；该提示不能证明JSON格式错误。
- 将拆除拒绝、命令权限/参数拒绝改为明确可控提示；未知异常新增阶段与随机诊断编号，服务端仅记录异常类型及栈帧，不记录请求、模型正文、异常消息或凭据。
- 公共模块测试成功；未重打JAR、未复现原始模型方案。根因尚待更新后的具体拒绝信息或诊断栈确认。

## 真实Pi Agent集成（未重新打包JAR）

- 只读参考TokenBird packages/shared/src/agent/pi-agent.ts及packages/pi-agent-server的SDK配置，未修改该参考工程。
- 新增独立agent-runtime：固定@earendil-works/pi-agent-core和pi-ai 0.85.1，Bun锁文件，本地Pi Agent驱动真实工具循环，非自写循环。未接入pi-coding-agent编码扩展。
- Java PiBridge以JSONL连接本地子进程，Java端完成HTTP/认证，未向Pi发送凭据。无Bash/文件/桌面工具；提供observe_world、execute_task。
- 服务端观察token绑定请求原点，有限地形/库存/朝向数据；执行工具等待completed/rejected/partial_possible回执，而非提交即结束。取消/断线隔离、轮次/时限/数量预算。
- `agent-runtime/bun test`：真实SDK子进程离线工具反馈闭环1项通过（7断言），未调用真实模型。
- `gradlew :common:test`成功，现有22项测试通过。
- 未完成真实Minecraft+模型端到端实测、Pi伴随运行环境分发打包；现有0.1.1 JAR未重新生成。熔炼/酿造需求仍未完成，不能用Agent替代这些游戏工具的实现。

## 19个Pi游戏工具扩展

- 新增AgentToolCatalog、WorldTools、Structures、ResourceTools、ProcessingService，接入Java客户端/Pi/服务器真实调用链。原observe_world与execute_task加17工具，共19。
- 资源配方/规划/普通合成、工作台/设备准备；原版炉类与单步酿造真实tick；任务异步等待、状态和取消；建筑预检/模板/差异验证修复；查询和个人持久位置标记。
- 本次统一执行`:common:test :fabric:compileJava :neoforge:compileJava`成功。44项JUnit，0失败/0错误；新增参数/库存安全打包、结构/schema及加工参数边界测试。
- `agent-runtime/bun test`真实Pi SDK子进程离线测试5项/27断言通过，覆盖原工具循环及扩展配方/加工/结构/任务状态工具失败反馈。
- 工具请求call ID最近64回执内存幂等；大响应限制，设备权限/空闲/来源标记/守恒检查。未知效果不自动重试。
- 本次没有启动真实Minecraft世界验收、没有付费模型端到端测试、没有生成或更新平台发布JAR。compileJava依赖生成common开发jar不等于发布包重打。
- 加工任务与建筑历史不跨服务器重启恢复；物料留设备现场，不自动认领/猜测退款；个人位置标记存档持久化。特殊配方/切石/锻造只查询不自动执行，炉类限煤/木炭及单次产量1配方。详见agent-runtime/TOOLS.md。

## 设置页溢出与Pi入口错误诊断

- 截图配置使用agent-runtime/index.mjs相对路径，fabric/run及neoforge/run下均不存在该文件；旧实现直接按游戏工作目录解析，未发模型HTTP即可能抛出入口缺失异常。截图通用错误不能证明密钥无效。
- 设置页改为连接/登录与Pi两页，紧凑行距、底部固定完成/取消，翻页保留输入，密钥仍仅内存且掩码。
- Pi入口相对路径有界向上5级查找，兼容Loom运行目录；绝对路径保持原样。
- 启动、入口、HTTP401/403/404/429、连接超时和服务器回执超时分开提示，不输出未知异常原文。
- :common:test :fabric:compileJava :neoforge:compileJava成功，新增3项路径/安全错误分类测试，现合计47项JUnit通过。
- 未启动游戏验证分页布局，未验证真实API密钥，不重新生成发布JAR。

## 内嵌依赖验证构建

- Pi+Bun独立编译五平台，SHA256 manifest自动生成；BundledPi自动平台选择、校验、缓存解包、Unix执行权限。默认路径改为空，旧默认相对入口迁移到内嵌模式。
- Architectury两端Jar-in-Jar、Fabric API Fabric端嵌套；已检查实际remapJar内容和元数据。
- :common:test :fabric:remapJar :neoforge:remapJar成功，保留0.1.1版本号（同名包已被新构建覆盖，不是版本递增发布）。Fabric163.1MiB、NeoForge160.8MiB。
- 从实际Fabric JAR提取Windows exe到无node_modules目录，校验哈希，直接启动（不经Bun命令），真实Pi核心离线完成2轮模型+1工具反馈测试。
- 玩家不再安装运行时/依赖；构建机首次仍需Bun及网络下载，未将操作系统、Java或模组加载器嵌入。
- 未验证Linux/macOS执行、真实游戏内自动解包启动、嵌套前置运行兼容性；不把资源存在等同加载验证。

## 混合材料、英简中及模式黑名单/特殊放置

- 删除固定材料白名单，注册表校验，不限混用材料种数。build_structure增加palette墙/地板/屋顶/框架/窗户。
- Plan.Placement支持properties和nbt，SpecialPlacement真实状态解析、门床展开/成本、源流体桶、受控容器NBT；创造空命令方块可放，生存黑名单禁止。
- BlockPolicy四模式独立placeDenied/breakDenied，精确ID/命名空间通配符，自动配置和OP重载。
- 配置界面en_us/zh_cn各21键一致；Agent每次读取Minecraft语言设置作为回答偏好。部分服务器提示仍中文。
- :common:test :fabric:compileJava :neoforge:compileJava最后运行成功，60项JUnit 0失败0错误。特殊方块测试实际8方法，非子会话报告的9组。
- 未新打平台发布JAR；未游戏内验收门床邻居规则、容器SNBT、流体桶和保护模组兼容。详细行为见BLOCK_POLICY.md。

## 80b3ea44建造工具空指针修复

- 实读fabric/run/logs/latest.log，01:21:55诊断80b3ea44：NullPointerException位于BuildService.receive第70行，即build_structure多参数直接读取。原请求未留存，不能证实具体遗漏的是kind/尺寸/material哪一个。
- AgentToolCatalog曾未声明required，服务器也未做缺字段校验；修复schema必填字段和服务器validateArgs。
- build_structure可只提供palette，从wall或首个有效材料取回退；既无material又无palette明确返回rejected，供Pi修正，不再空指针。
- 新增4项AgentArgumentsTest；公共及两端源码编译成功，共64项JUnit通过。
- 尚未复现真实模型原参数，未游戏内验收、未重打发布JAR。

## 玻璃板邻接状态与修复链路

- 实读Fabric日志：112格建筑包含11玻璃板，放置后状态比较失败；b10fa478栈指向WorldTools.repair第35行（既有实心目标拒绝）。没有原始坐标方块快照，不能确证报错坐标具体类型；玻璃板自动邻接是与代码和材料最吻合的原因。
- 新增SpecialPlacement.matches：玻璃板/栅栏/CrossCollision及墙忽略邻接连接状态，楼梯忽略自动shape；类型、朝向等其他状态仍严格。执行、验证、修复统一使用。
- repair预期占用拒绝转明确rejected回执，不再未知诊断异常；实际放置/拆除/命令计数独立，消除已造房仍报完成0步。
- 新增2项测试，公共与两加载器源码编译成功，66项JUnit通过。未真实世界重测、未重打发布JAR。

## 0.2.0 完整发布构建

- 新增LocalCredentials本地AES-256-GCM凭据保存，AAD绑定世界作用域，API/OAuth身份绑定端点与client_id；进入/退出客户端世界加载/清理，OAuth及refresh更新保存，清除当前世界不影响其他。
- 单人存档路径+玩家UUID、多人连接地址+玩家UUID；多人内部世界切换无法可靠识别，明确仅服务器粒度。未使用DPAPI，密钥同目录保存；Windows继承ACL/Unix尽力0600，不能抵抗同用户进程或完整目录盗取。
- Gradle build完整成功（修复sourcesJar对generated/pi显式依赖），69项JUnit零失败零错误，新增3项加密往返/隔离/删除/篡改测试。
- Fabric0.2.0：171072161字节，SHA256 4f7b91cca8b3cf9b27e2b1975add06b65030cf7f48aadd214fd55f461243e2bb。
- NeoForge0.2.0：168665632字节，SHA256 b0efb368eac51b2e0bee55d49ae08d0a605ae0a91a0729a6b30cab6546152480。
- 两包元数据0.2.0、LocalCredentials类、五平台Pi程序哈希均逐个核验。日志.dev/build-0.2.0.log。
- 未游戏内验证世界切换/OAuth回调/真实刷新恢复；先前单元/离线SDK证据不能等同全功能联机实测。

## TokenNest 原生客户端登录配置

- 项目默认 client_id 使用用户提供的 `tnc_eqezq1pCPAZ4oOkU90ZEchhiyvFf3Pks`；已有空值/旧占位 `tnc_aibotassist` 自动迁移，保留其他自定义 ID。独立回调路径 `/aibotassist/callback`；集中定义 `api offline_access`、PKCE S256、端点和配置复制文本。
- 连接页可直接登录并选择 TokenNest API；界面显示授权状态，高级页可取消浏览器登录和复制注册信息。英文/简中键一致，后台创建请求 JSON 与运行时代码配置一致。
- 无效 state、重复参数、错误路径/方法不会消耗合法授权等待；合法拒绝关闭等待。OAuth access/refresh 均限制用于 TokenNest，切换世界/配置取消旧登录；默认 client_id 的迁移保留已有 API 密钥。
- `:common:test :fabric:compileJava :neoforge:compileJava` 通过，76 项 JUnit、0 失败、0 错误、0 跳过。其中 6 项 TokenNest 测试覆盖标准 PKCE 向量、回调参数、本机真实 HTTP 回调、模拟令牌端点换码/加密保存/重新进入、跨世界隔离、目标地址限制、已有密钥兼容。
- 构建使用 `D:\Java\jdk-21`，开发依赖下载使用系统已配置的本机代理；本次源码验证排除 `compileBundledPi`/`prepareBundledPi`，未生成新的完整发行 JAR。
- 已核对公开 `.well-known/oauth-authorization-server` 元数据和本机 TokenNest 的客户端创建/loopback URI 匹配源码。注册文档见 `docs/TOKENNEST_LOGIN.md`。
- 已配置用户提供的客户端 ID，尚未验证该客户端的服务端状态，未进行真实账户浏览器授权、真实令牌刷新或游戏内联网验收；模拟端点测试不代表这些真实链路已经验证。

## 登录成功后 Agent 仍提示未就绪

- NeoForge `run/logs/latest.log` 的 12:10:32 记录“请先配置模型和登录”；对应客户端配置的 `model` 为空。只检查本机凭据的状态字段确认 access/refresh 已保存、access 未过期，未输出令牌。以现有凭据只读查询 `/v1/models` 成功：6 个模型，其中 4 个声明支持 `openai` 聊天端点。
- 原聊天入口将未登录和未选择模型合并拒绝；登录回调没有填充模型。现在登录成功、恢复世界登录、首次聊天都会在模型为空时异步获取模型，按服务端顺序选择首个兼容聊天端点的模型，保留已有手动配置。首次聊天在准备完成后继续原消息。
- 模型列表按 `supported_endpoint_types` 排除仅 Responses/图片等不兼容端点；缺失元数据的通用服务采用保守过滤。模型页支持分页选择，仍可手动输入 ID。设置页同步异步选中的模型，避免保存旧界面时重新清空。
- 模型查询失败保留授权，并明确提示模型获取错误。查询结果绑定世界、登录会话和 API 配置，切换后丢弃旧结果；取消和离开世界重置等待状态。
- 完整 `gradlew build --offline` 成功，82 项 JUnit、0 失败/错误/跳过。新测试覆盖已登录且模型为空的发现与聊天、失败保留凭据、过期令牌刷新与轮换、旧世界结果隔离、端点兼容筛选和登录成功通知。
- 两个完整 JAR 已确认包含新模型处理/选择器类、默认 client_id、中文模型入口及五平台 Pi 运行时。
- Fabric `aibotassist-fabric-0.2.0.jar`：171842567 字节，SHA256 `599b2e61f64e4a54668552da8d44a9175ee6bcd44d418dff431f05cf44740786`。
- NeoForge `aibotassist-neoforge-0.2.0.jar`：169436154 字节，SHA256 `c5ec3c6f343bd9f7743cd2375ab12e66fadb8d1613e7be025c32733434e3b4a6`。
- 实际账户只读模型查询通过；尚未用修复包重新进入游戏完成真实模型聊天和界面点击验收。

## 快捷键对话与 AI 状态 HUD（2026-11-13）

- 公共客户端使用 Architectury KeyMappingRegistry / ClientGuiEvent.RENDER_HUD，同一套界面供 Fabric、NeoForge 使用。默认 K，可在原版按键绑定中修改；`/aibot chat` 延迟打开以避开原版聊天关闭流程。
- 对话界面支持 Enter 发送、滚轮/PageUp/PageDown 浏览、草稿保留、清空、设置、HUD 开关及停止。关闭窗口不取消任务，isPauseScreen=false；发送与原有 @AI 共用入口，消息不发送至公开玩家聊天。对话上下文最多取最近 8 条，记录最多 160 条，离开/进入世界清空。
- PiBridge 按真实模型请求和工具请求回报准备/思考/执行/等待状态，记录模型轮数、全部工具调用数和耗时；状态与文本绑定运行代次和世界，取消后迟到事件不覆盖终态。停止时清理待回执并通知服务器取消，不回滚已执行效果。
- 左下角默认半透明状态面板，聊天内即时开关；设置新增「界面」页，开关及 15%～85% 背景不透明度保存（默认 55%），其他设置页保存及模型自动选择保留 HUD 配置。F1、菜单和聊天界面时隐藏面板。
- Java21 `gradlew build --offline --console=plain` 完整成功，88 项 JUnit，0 失败/错误/跳过。新增 6 项覆盖取消/完成后的迟到事件、清空与跨世界重置、近期上下文过滤和容量限制、失败后重启、旧配置默认值与设置往返。`bun test` 的 5 项真实 Pi SDK 桥接测试通过，27 断言。
- 两个 JAR 均检查新界面/状态类、完整且键一致的简中/英文语言文件、五平台运行时清单。`git diff --check` 通过。
- Fabric `aibotassist-fabric-0.2.0.jar`：171866473 字节，SHA256 `892f758769d574fdb8cf2bd166b8faebd2deba68da6e3655c8ee768518af33e3`。
- NeoForge `aibotassist-neoforge-0.2.0.jar`：169460358 字节，SHA256 `025e2be0f4053f0abd3343b59f8ead0b30d7d4d705d4c119da82bf21f161d7ea`。
- 未启动游戏进行视觉、缩放、按键冲突、真实账户聊天/停止任务等交互验收；编译和自动化结果不能等同这些游戏内验收。

## 当前视野图片工具（2026-11-13）

- 新增客户端 `capture_view {}`，在 Architectury HUD 渲染回调中、原版 HUD 绘制后及 Screen/本模组 HUD 绘制前读取 Minecraft 渲染目标；不读取桌面或文件。返回相机位置/朝向、第一/第三人称模式、维度、准星方块、时间戳及真实图像数据。公共实现支持 Fabric / NeoForge。
- NativeImage 读取/缩放在渲染线程，JPEG 编码在 Agent 工作线程；长边最多 960、编码最多 192KiB、每次玩家请求最多 3 张、渲染等待最多 5 秒。内存编码，无截图文件；停止及离开世界取消待捕获请求，图片返回前检查运行和世界。
- 工具在 ClientMod 本地截获，不向 Minecraft 服务端发送截图；未安装服务器模组时只暴露此客户端工具，安装时保留全部 20 个服务器工具。图片仅供视觉参考，不代替 observe_world 的操作 token/精确坐标或 verify_result。
- Pi 使用 image 内容块承载图片，工具文本及 details 只含元数据；适配 Chat Completions 时把最新成功截图转换为补充的 user/image_url，保留全部 tool_calls/tool 回执顺序，每次模型请求只带最新一张，避免多张图片重复发送。需要模型支持图片输入及工具调用，HTTP400 增加能力不兼容提示；普通对话不主动截图。
- Java21 完整 `gradlew build --offline --console=plain` 成功；95 项 JUnit、0 失败/错误/跳过。新增 7 项覆盖原生颜色通道、可解码 JPEG、上下方向和颜色、高分辨率压缩预算、宽高比/小图、两种服务器能力下工具目录、拒绝其他目标参数及已取消请求。
- `bun test`：6 项真实 Pi SDK 桥接测试、41 断言通过；新增图片经过真实 SDK 后转为 image_url、多工具回执顺序和最新图替换测试。该测试使用模拟图像/模型，不是实际画面识别。
- 本机 Windows 独立运行时从空临时目录运行，离线 2 轮模型/1 次 capture_view 返回的图片数据进入下一轮模型消息验证通过，不依赖 node_modules。其二进制哈希与发行包清单一致。Linux/macOS 仅完成交叉编译。
- 两个发行包均检查新视野/编码类、中英文工具文案、bridge-3 清单，并逐项核对五平台程序大小及 SHA256。`git diff --check` 通过。
- fabric `aibotassist-fabric-0.2.0.jar`：171879605 字节，SHA256 `c404e569ba0efd0776027d431c2c3a2d2bcaa279f3b0c4983a14617d9ad3d7e4`。
- neoforge `aibotassist-neoforge-0.2.0.jar`：169473638 字节，SHA256 `c35efe76176d61c0a89387d7b77bd738a1096b4eea01bdeb5a051760bd233f72`。
- 尚未启动游戏验证实际 GPU 截图、光影/模组界面兼容性或真实视觉模型的识别结果；离线图片传递与编码测试不代表这些场景已验收。

## 默认4096格、可调/无限数量及建造后图片校准（2026-11-13）

- 客户端设置新增「建造」页：blockLimit默认4096，可填自定义非负整数，0/「无限制」取消单方案数量上限；visualCalibration默认true，可关闭。旧配置通过字段初始化获得默认值；其它设置页保存、HUD开关和模型选择保留这些字段。设置在下一次玩家请求生效。
- Plan按设置检查放置/拆除数量，服务端对门/床扩展、自动清障再次计数；移除旧的256硬上限及客户端累计1024预算。无限仅取消方块数量策略，保留原点±16范围、玩家32格活动范围、世界边界/已加载区块、材料和保护权限等。单请求8MiB传输边界、32轮模型/16写调用/10分钟运行预算独立存在。
- 新增volumes盒体/椭球紧凑描述，最多64个体积；按顺序覆盖，显式blocks覆盖细节；展开后统一校验坐标、唯一方块数量和权限。可用一个16×16×16体积描述4096格，不要求模型逐格生成4096条JSON。
- 大包使用UTF-8字节分片，每片12000字节、Base64封装后小于32700字节；完整重组后才进入原有服务端校验。校验玩家/调用ID/长度/顺序/超时，取消丢弃未完成传输。旧服务器通过build_protocol检查明确提示更新两端。
- 同一次玩家请求使用固定BuildAnchor，后续观察生成新token但复用原点和默认朝向；返回绝对origin及玩家当前位置，区域查询返回自身查询中心绝对origin，避免混淆坐标系。测试使用此前日志中的(10,176,-7)与(-3,182,-11)复现移动后坐标保持。
- 服务端保存完整计划，按最多64格放置或128格独立拆除分tick执行；门/床成对保留，同批扣料/退款，较低支撑先放。全部执行后按每tick最多128格重新检查整个提交计划，然后才报告完成。暂停/取消后已执行世界修改保留，不自动回滚；不同工具调用的蓝图仍分别记录。
- Pi默认在建筑完成的最终答复前触发capture_view，把图片交回模型，允许修正后再次截图；显式关闭时不触发兜底截图。截图失败或预算用尽时附加视觉复核未完成说明。截图仍最多3次/玩家请求，不能把未入镜的部分当作已经检查。
- Java21 `gradlew build --offline --console=plain`完整成功；104项JUnit，0失败/错误/跳过。覆盖4096/4913不限数量、用户自定义上限、展开与拆除计数、体积细节覆盖和椭球纵深、4096格完整分包还原、跨玩家/调用/错序/取消/超时拒绝、固定原点、64批无丢格/重复及门床同批、旧配置默认值和保存往返。
- `bun test`共9项、55断言通过，新增建造→截图→修正→再截图、截图失败提示和关闭校准三条真实Pi SDK流程；输入为模拟工具/模型数据。编译后的Windows独立运行时在空临时目录完成建造→拦截提前结束→截图→模型收到image_url→复核回复的离线验证；已与JAR中程序哈希核对一致。
- 两个JAR检查新增服务端类、双语建造设置、bridge-4清单，并逐一验证五平台程序大小及SHA256。`git diff --check`通过。
- fabric `aibotassist-fabric-0.2.0.jar`：171896883 字节，SHA256 `91635916eae84065acdb11d261ec9367525cd67ce7a23bb96b31864b6ce7a50f`。
- neoforge `aibotassist-neoforge-0.2.0.jar`：169490877 字节，SHA256 `0d886fef508add776cfc914836ecea79c1bbff25c3170f1cb5a2f415e0967587`。
- 未在实际世界中验收大规模放置、多人保护兼容性、材料结算/中断及帧率；未真实视觉模型识别验收。自动化与离线协议测试不能等同这些场景，Linux/macOS运行时仍仅交叉编译。


## 通用模组、存档和机器协作（2026-11-13）

- 新增8个服务器工具、4个客户端工具，目前28+5。服务器覆盖环境/注册表、机器/红石、指令帮助/高级命令、完整存档快照、服务器诊断及机器蓝图；客户端覆盖JEI、Xaero路标/地图图像、崩溃日志。
- Java21 `gradlew build --offline --console=plain` 完整通过，最终112项JUnit，0失败/错误/跳过。新增用例覆盖完整目录复制（含模组维度/玩家数据）、源存档不变、递归快照拒绝、诊断路径边界/脱敏/分页、四方向漏斗连接、红石支撑和动态状态匹配、合成器槽位NBT边界。
- Bun真实Pi SDK离线桥接17项通过，104断言：包含新增工具拒绝结果传递，以及capture_view/capture_map图片消息顺序、仅保留最新图片和建造图片校准。
- 五平台运行时升级pi-0.85.1-bridge-5，文件大小与SHA256逐项通过；打包后的Windows可执行文件在临时空目录完成模拟JEI工具反馈、地图图片向模型传递的离线测试。工具返回为测试数据，不是实际JEI/Xaero运行验收。
- 两个最终JAR均检查新类、中文/英文语言键一致、JEI API未内嵌、五平台运行时校验值；Fabric JEI入口存在。`git diff --check`通过（仅既有文件LF/CRLF提示）。
- Fabric `aibotassist-fabric-0.2.0.jar`：171943649字节，SHA256 `efa55703b19ce8db500c7dd499990958e00aaddd86acaea6544ff33024dc8e94`。
- NeoForge `aibotassist-neoforge-0.2.0.jar`：169538019字节，SHA256 `f1b03845751a6fd7acaa9ee415f46257f95c347696db515281b81e15f93d8c22`。
- 未启动真实JEI/Xaero客户端做界面验收，未做联机保护插件、所有工业模组、多方块成形及机器持续生产验收。JEI API按官方19.21.0.247源包核对，Xaero按官方分发Minimap26.6.0/WorldMap1.47.0的1.21.1 Fabric JAR核对方法/界面类；不能据此承诺所有旧版/加载器版本兼容。
- 运行中世界快照不是原子一致性备份；每玩家每服务器会话复用一份，副本持久保留于存档父目录.aibotassist-snapshots。权限、保留、分页和通用模板边界见docs/MOD_COMPATIBILITY.md。


## 多人API独立/共享策略（2026-11-13）

- 默认independent：个人API/OAuth凭据留在各自客户端。管理员/存档所有者可在设置的服务器API页面切换shared，或通过服务端配置文件和/aibot api命令管理。共享模型请求经服务器转发，固定端点、模型、输出上限，既有共享密钥不回传客户端。
- 新增12项自动测试，最终124项JUnit，0失败/错误/跳过；覆盖默认与持久化、无效共享配置、普通/管理员视图不含密钥、每人和全局并发、重复ID、取消/断线状态、固定上游与模型、请求/回复隔离、提供商错误不透传、取消上游。HTTP使用可控测试客户端，未请求真实账户。
- Java21 gradlew build --offline --console=plain完整通过；两加载器JAR检查新类、策略发送通道检查、语言键一致、私有配置不打包、五平台Pi校验值。Pi运行时未变，仍为bridge-5。git diff --check通过（只有既有LF/CRLF提示）。
- fabric aibotassist-fabric-0.2.0.jar：171981044字节，SHA256 `dc2f7e0586ee2442722303b8fccfb4996f4d09a63f3d7626bc40c490fcfbbbe9`。
- neoforge aibotassist-neoforge-0.2.0.jar：169575599字节，SHA256 `e44dc06ea9a3a437971521bb2258fd15267b6ac61c19a06dd464cf81438c6133`。
- 真实双客户端联机、管理界面交互及真实服务商共享调用尚未实机验收。配置、并发限制、共享消息/截图转发与模式切换行为详见docs/MULTIPLAYER_API.md。


## 小图标 HUD、模型接口及 TokenNest 渠道（2026-11-13）

- 左下角 HUD 缩小为 14×14，仅机器人图标和状态颜色，工作时眼睛闪烁；保留关闭和背景不透明度设置。详细状态、轮数和工具信息仍在 K 对话窗口；共享模式使用服务器 ready 状态，不再按个人登录判断。
- 原模型筛选仅接受 openai 且请求固定 Chat，现根据 supported_endpoint_types 发现并适配 Chat / Responses；模型选择器显示接口，可手动设置 auto/chat/responses。Responses 转换工具声明、调用/回执及截图消息，拒绝未完成/空响应。自定义服务无目录时仍可使用手填 Chat 模型，未支持 Anthropic/Gemini 原生接口。
- 新增渠道/类型设置，默认 group=default；TokenNest 模型查询和生成请求带 X-TokenNest-Group。OAuth 渠道列表调用 /api/oauth2/groups，新授权 scopes 为 api offline_access groups:read。旧授权需重登，后台 allowed_scopes 也需开放 groups:read。普通 API Key 以其绑定渠道为准，界面和文档明确说明。
- 模型查询结果绑定世界、登录、地址、client_id、渠道和接口配置；刷新时替换不可用的旧模型，同时避免旧设置界面覆盖异步更新或玩家正在编辑的模型。渠道查询失败保留凭据。共享服务器配置新增 group/modelProtocol，固定协议和渠道，客户端不能覆盖；默认独立模式保留。
- Java21 完整 gradlew build --offline --console=plain 成功；135 项 JUnit，0 失败/错误/跳过。新增 11 项测试覆盖 Responses 工具/图片转换、响应失败处理、渠道默认值/请求头、端点类型、渠道缓存隔离、API Key/OAuth 渠道查询、自定义服务无目录、服务器固定 Responses 和渠道。
- 两个 JAR 检查新增 ModelProtocol、HUD/模型/设置/共享类，中英文 143 个语言键一致；五平台内嵌 bridge-5 程序大小及 SHA256 全部验证。Pi 运行时源码未改变，本轮未重复 Bun 测试。git diff --check 通过。
- Fabric aibotassist-fabric-0.2.0.jar：171989985 字节，SHA256 f83f8d2fb1e2040088d111c281f8fcc8cad7fda316320b0e40d68c98f76cd31d。
- NeoForge aibotassist-neoforge-0.2.0.jar：169584523 字节，SHA256 ff8d19503860c9740b507ad58e3cd816a144aab9e5bb6bb292354ff31de72649。
- 未启动游戏进行图标视觉/缩放或双客户端联机验收，未使用真实 TokenNest 账户验证渠道授权、模型生成和 Responses 工具往返；HTTP 测试使用模拟请求，不代表真实提供商链路已验收。未修改或部署 TokenNest 后端。


## 聊天窗口文字被模糊背景覆盖（2026-11-13）

- 用户截图中自绘标题、工作状态、对话和底部提示模糊，按钮及输入框正常。检查本地 Minecraft 1.21.1 映射后的 Screen 字节码，确认 Screen.render 先调用 renderBackground（含全屏 blur），再绘制控件；AgentChatScreen 原来在自绘文字后调用 super.render，因此文字先被绘制后参与了模糊。
- 将面板背景移动到 renderBackground 覆写，先调用原版背景模糊，再绘制半透明面板；render 先调用原版背景/控件流程，再绘制标题、状态、对话及提示。发送/停止按钮状态在控件绘制前更新。保留原有滚动、裁剪、草稿和按键行为。
- 已核对编译后的 AgentChatScreen 字节码，确认模糊在背景覆写的开头调用，Screen.render 在所有自绘文字之前调用；其余设置/模型/服务器界面已使用正确顺序。按原版最小 GUI 320×240 及截图对应布局检查顶部按钮、日志和输入区域间距，未发现重叠。
- 本次为局部绘制顺序调整，未新增镜像实现的单元测试。实机视觉效果仍待新版本重启游戏后确认。
- Java21 完整 gradlew build --offline --console=plain 通过；135 项 JUnit，0 失败/错误/跳过。两个发行包均通过 javap 核对新背景覆写和文字前的父类绘制调用（Fabric 对应 method_25420 / method_25394）；git diff --check 通过。
- fabric aibotassist-fabric-0.2.0.jar：171990019 字节，SHA256 6912fa7dbe9d2ab1ae6eec2fda94011d5afbf8952e4ae8e89f7487d560f1c4cc。
- neoforge aibotassist-neoforge-0.2.0.jar：169584570 字节，SHA256 97f8843ff7b6391571e36379308abfb37086ca1442ddd9db666f9a1d5ae25c28。
- 2026-11-13：按存档与玩家 UUID 保存独立会话，并加入假人/远程区域图像工具。
  - 会话键包含服务器/单人存档来源、服务端持久存档标识和实际玩家 UUID。客户端 AES-GCM 加密、身份范围绑定、原子替换，保存最近160条聊天与草稿；约每2秒保存变更，离开/正常退出提交保存。恢复最近8条玩家/助手背景，不恢复旧工具 token 或任务。错误记录保留原文件，禁止覆盖。最大记录8MiB，覆盖 JSON 转义后长文本。
  - 新增 WorldIdentityTest 2项、ChatSessionStoreTest 8项和 RemoteViewSpecTest 3项；测试覆盖重启、存档/UUID/服务器来源隔离、密文交换/篡改拒绝、清空、恢复空闲状态、中文及大量转义字符长对话、坏记录保留与相机参数验证。最终JUnit共148项，0失败0错误。
  - Java21执行 `gradlew.bat build --offline --console=plain` 完整成功；已重建 common JAR 和两平台安装包。Pi五平台更新为 `pi-0.85.1-bridge-6`。Bun桥测试19项通过，123次断言，0失败。
  - 最终JAR均通过 ZIP CRC 校验；检查新增会话/假人/远程渲染类、FakePlayers 平台转换目标、中英文146个语言键相同、五平台运行时大小/SHA256与manifest一致，未打包本地会话、密钥或服务器API配置。在无node_modules临时目录执行包内Windows运行时，以模拟远程图片完成2轮模型/1次工具的离线传递验证；不代表真实模型或GPU渲染已验收。
  - Fabric `aibotassist-fabric-0.2.0.jar`：172037052字节，SHA256 `2ee54ad1e2179fda809815c306c5b2be526453a1d4fbe52c90611a07f01026e0`。
  - NeoForge `aibotassist-neoforge-0.2.0.jar`：169629988字节，SHA256 `954917ef6688140e7d0b66ebc27fbfd77df6334a98b353f5d25a22a6e55e61b7`。
  - 尚未完成真实双客户端重进/换UUID界面验收、假人在保护/机器模组中的交互及实际GPU远程渲染验证。远程图像为最多33³格区域模型渲染，缺少实体/动态方块实体；显式load_chunks可能同步生成最多9个区块并造成卡顿，详见docs/FAKEPLAYER.md。服务端未安装模组时只能按来源/UUID隔离，不能识别远端存档更换。
