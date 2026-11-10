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
