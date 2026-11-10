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
