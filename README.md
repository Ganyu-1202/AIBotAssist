# AI Bot Assist

Minecraft **1.21.1 / Java 21** 的 Architectury 跨平台 AI 助手。

## 安装

只选择与你的加载器对应的一份 JAR：

- Fabric：`fabric/build/libs/aibotassist-fabric-x.x.x.jar`，需要 Fabric Loader 0.16.10+、Fabric API 0.115.1+1.21.1、Architectury API 13.0.8。
- NeoForge：`neoforge/build/libs/aibotassist-neoforge-x.x.x.jar`，需要 NeoForge 21.1.172+、Architectury API 13.0.8。

完整功能需要客户端和服务器都安装模组及相应依赖。单人世界由内置服务器执行。仅客户端安装、服务器没有该模组时，只提供个人 AI 聊天。仅服务器安装不能让未安装客户端模组的玩家使用 AI（凭据与模型请求在客户端处理）。这不是 Fabric 与 NeoForge 混合加载器服务器兼容层。

## 不用指令，直接在聊天栏提及

首次进入世界，在聊天栏输入：

```text
/aibot settings
```

填写：AI 名称、API 的 `/v1` 地址、精确模型 ID，以及 API 密钥。点击“完成”后，设置校验及保存成功会自动关闭界面并在聊天栏提示；保存失败则保留页面显示错误。保存成功仅表示本地设置写入，不代表 API 连通性或凭据已通过服务商验证。代理可以填写 `http://127.0.0.1:7897`，也可留空。默认 API 地址为 `https://openai.goldgom.top/v1`；模型 ID 需要按账户实际可用模型填写，没有预设一个未经验证的模型。

```text
@AI 你好
@AI 帮我在前方用橡木木板搭一个小屋，留入口
```

如果改名为“小萤”，之后使用 `@小萤 你好`。`@` 名称后需有空格。AI 提及消息会在本人聊天栏回显，不作为普通玩家聊天广播；回复也仅本人可见。

生成任务方案后，服务器检查权限、模式、材料与区域，检查通过后自动执行，无需手动确认。取消排队任务或尚未执行的后续命令可用 `@AI 取消` 或 `/aibot cancel`；已经完成的操作不会自动撤销。首次及以后配置都使用客户端命令 `/aibot settings`，无需知道当前 AI 名称，服务器未安装模组也可打开设置。

## 游戏模式与建造

- 生存（包括极限世界的生存玩家）：整批核对提问玩家主库存/快捷栏材料，预扣后按实际放置结果结算，失败未放置部分退款。不自动合成、不使用箱子、末影箱、副手或装备栏。
- 创造：直接放置，不扣材料，不需要 OP 的 `/give` 权限。
- 旁观：只允许聊天。服务器不会接受建造。
- 冒险模式：仍受原版 `mayBuild` 限制，不能通过本模组绕过。
- 收到方案时整批检查模式、维度、距离、材料和区域；执行前再次校验。
- 每玩家最多一个任务；每个方块任务合计最多256格，在同一服务器 tick 执行，不再逐 tick 放置。相同材料连续 X 行合并成 `/fill ... replace`，孤立格用 `/setblock ... replace`；不额外修改包围盒中的其他格。
- 每个方案最多256块，坐标相对请求时脚下，每轴偏移不超过16格；执行时玩家需在原点32格以内。
- 仅允许放在空气或原版可替换植物上，不覆盖已有实心方块、液体或方块实体；不强制加载区块、不在玩家或生物占用位置放块（掉落物不阻挡），遵守世界边界、建造高度和原版出生点保护。
- 触发 Architectury `BlockEvent.PLACE` 的取消检查；第三方领地/保护模组必须实际测试，不能保证只监听其他专有事件的保护模组自动兼容。
- 缺材料/离线/死亡/换维度/离开区域/目标被占用时停止，保留已完成建筑，未放置部分不扣料。取消不是撤销。批量任务执行后不能在同一 tick 中途手动取消；异常可能部分生效，不保证跨方块原子事务。
- 目前仅15种静态完整方块白名单；支持受控拆除与一般原版命令；无方块状态/NBT/容器操作/自动合成/通用游戏 Agent。小屋可以用完整方块留出门洞，不会放置功能门。

模型未接收完整地形扫描，可能生成与地形冲突或材料不足的方案，服务器会拒绝，需换空地或重新描述。请求为单轮，尚无持久多轮聊天记忆。

## 新增：命令与批量拆除

直接说 `@AI 查询现在的游戏时间`、`@AI 清除前方指定区域的方块`。AI 必须先判断需求合理性，在回复中解释目标和影响；服务器检查通过后自动执行，不要求玩家手动确认。合理性判断是模型建议，真正权限由服务器硬校验。

- `/kill`、`/gamemode`、`/defaultgamemode` 禁止；`execute`、`function`、`schedule`、管理命令、NBT 等间接执行渠道也禁止。
- 一般命令按玩家现有权限执行，不自动授予 OP；例如非 OP 玩家不能让 AI 越权修改天气。资源生成/修改类命令还要求创造模式。只支持代码列出的原版根命令，不执行未知模组命令。
- 原始 `setblock/fill/clone/place/fillbiome` 不接受来自模型的 commands 数组，方块操作必须走 blocks/removals 专用方案。只有服务器根据校验坐标生成的方块指令使用受控权限2，因此非 OP 生存玩家也能在允许区域用自己的材料建造。
- 拆除：JSON `removals:[{x,y,z}]`，按请求时玩家脚下相对坐标；放置与拆除总计最多256格，两者坐标不可重叠。
- 生存/极限拒绝负硬度（不可挖掘）方块，如基岩。创造允许拆除基岩。旁观禁止全部游戏内任务。
- 拆除用服务器生成的 `/setblock ... air destroy` 在同一 tick 执行，按原版 destroy 掉落规则，不模拟玩家工具/精准采集/时运；没有掉落表的方块不凭空掉落物品。原版 doTileDrops 等规则仍然有效。
- 为避免物品丢失或特殊方块副作用，目前所有模式均不支持拆除带方块实体的容器或含液体方块，仍遵守区域权限。方案校验后目标方块状态发生变化将拒绝。
- 普通命令批次最多8条、每条256字符，逐 tick 执行并重新校验，失败停止后续命令；命令不能和方块任务混在一个方案。
- 破坏掉落不能用于支付同一任务的放置材料，必须预先备齐。异常时已执行部分保留，不自动重试/回滚。

## TokenNest 登录

已实现原生客户端 OAuth 授权码 + PKCE S256：打开浏览器授权，使用随机 state、临时环回地址、3分钟超时、令牌交换和刷新。

**需要在 TokenNest 注册属于本模组的公共 OAuth 客户端**，把注册后的 `client_id` 填到设置；注册策略需允许 `http://127.0.0.1:<临时端口>/callback`，授权范围 `api offline_access`。尚未取得专用 client_id，因此真实浏览器登录闭环未验收；不能借用 TokenBird 或其他应用的身份。API 密钥方式不需要 client_id。

已从站点 OAuth 发现文档核实：

- `https://openai.goldgom.top/.well-known/oauth-authorization-server`
- 授权：`https://openai.goldgom.top/oauth/authorize`
- 令牌：`https://openai.goldgom.top/api/oauth2/token`
- 模型：`https://openai.goldgom.top/v1/chat/completions`

## 隐私与凭据

- API 密钥、access token、refresh token 仅驻留客户端当前 Java 进程内存，退出游戏后重新登录/填写。
- 设置文件 `config/aibotassist-client.json` 只保存名称、模型、API 地址、代理、client_id，不含密钥。
- 密钥输入框掩码显示；不要把密钥输入聊天栏或 Minecraft 指令。
- 不输出服务商原始错误响应，不把密钥写入日志或 Minecraft 数据包。
- HTTPS 模型请求在客户端发起，不跟随 HTTP 重定向；TokenNest OAuth 令牌只允许发送到固定 TokenNest `/v1` 地址。
- 模型服务商会收到玩家输入、游戏模式及主库存普通物品 ID/数量。服务器会收到建造方案；其他玩家能看到实际建造效果。
- 不上传带自定义组件修改的物品，不把物品自定义名称/NBT发送给模型。
- 内存保存不等于能防御本机恶意程序、内存转储或恶意其他模组；退出登录清除引用也不保证 JVM 立即擦除所有历史字符串。
- 使用“清除登录/密钥”清除本地会话；未实现云端 OAuth 撤销，需要时在服务商侧撤销授权。

## 开发构建（PowerShell）

在当前项目目录执行：

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21'
# 网络需要代理时，Java/Gradle 需要 JVM 参数，不能只依赖 HTTP_PROXY：
$env:JAVA_OPTS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897'
.\gradlew.bat build
.\gradlew.bat :fabric:runClient
# 或 .\gradlew.bat :neoforge:runClient
```

IDEA 直接打开根目录的 Gradle 工程，Gradle JVM 选择 Java 21。不要将 `common`、`dev`、`sources` 包当成可安装发布包。

见 `VERIFICATION.md` 了解已验证范围和待验收项目。
