# Minecraft Pi工具目录（开发源码）

共30个服务器工具和5个客户端工具；所有游戏写操作均由服务器按玩家身份检查。Pi本地没有Bash/文件工具。客户端和服务器需使用相同新版源码/包；旧0.1.1未包含此目录。

`fakeplayer_control` 提供管理员自己的假人生成、移动、朝向、装备、方块交互、破坏及非玩家实体攻击；`capture_fakeplayer` 提供任意授权位置的有限区域模型图像。完整参数、权限及渲染限制见 [假人与远程取景](../docs/FAKEPLAYER.md)。

## 客户端视野

- `capture_view {}`：截取玩家当前渲染的游戏画面（含原版 HUD，在菜单/AI 对话及本模组 HUD 绘制之前读取），返回 JPEG 图片和 `captured_at` 毫秒时间戳、相机位置/偏航角/俯仰角、第一/第三人称模式、维度、准星指向方块（若存在）。不会转动视角或扫描视野外/遮挡后的内容，不提供执行 token。第三人称取当前相机画面；截图不是精确坐标、隐藏方块或操作成功的证明，实际修改仍需服务器观察和验证。
- 玩家可说“看看我眼前有什么”“看一下这栋房子的外观”。需要支持图片输入和工具调用的模型；普通文字模型不能读取该画面，HTTP400 会提示模型能力/请求不兼容。不开启持续截图，每个玩家请求最多3次；窗口未渲染时最多等待5秒，失败返回 rejected，不伪造画面。
- 工具在客户端执行，独立模式不向 Minecraft 服务器发送截图，共享API模式下图片随模型上下文由服务器转发；服务器没有安装本模组时也可使用。截图仅取游戏渲染目标，不读取桌面、其他应用、屏幕文件。原版 HUD/聊天等画面内文字可能包含在截图中。
- 图片在内存中缩至长边最多960像素、JPEG最多192KiB，再按需压缩。图片内容发送给当前配置的模型服务，不保存为截图文件；Pi 工具结果使用真正的 image 内容块，适配 Chat Completions 时置于补充的 user/image_url 消息。每次模型请求只附最近一次成功截图，工具文本和聊天记录不写入 Base64。
- 截图请求绑定当前运行和世界，停止/离开时取消等待；普通对话不会自动截图，Agent 需要按任务调用此工具。旧世界/任务的图片不会进入新会话。已显示的画面可能随玩家移动过期，模型须以截图时间和后续观察为准。

## 观察与资源

- `observe_world {}`：模式、库存、朝向、有限地形和单次观察token。执行方块任务需token；返回固定建筑origin的绝对坐标、玩家当前位置、block_limit和build_session。同一玩家请求内重新观察不会移动或旋转建筑基准，token仍须每次执行前更新。
- `inspect_area {radius?:1..4,x?:-12..12,y?:-12..12,z?:-12..12}`：查询玩家相对偏移附近地形，最多160个非空气格，坐标相对此查询中心，返回查询中心绝对origin、偏移和截断标记，规划时应转换到建筑固定origin，不强制加载区块。
- `inspect_inventory {}`：本人库存ID/数量、槽号、工具耐久；不输出NBT。主库存0..35为合成库存。
- `inspect_entities {}`：12格内最多24个生物类型、距离、敌对性；不自动攻击、不读他人库存。
- `run_commands {token,commands:["..."]}`：观察后执行最多8条允许的原版命令，服务器检查当前玩家权限；不提升OP、不绕过命令黑名单。
- `query_game_rules {}`：维度、时间、天气、难度、方块掉落和死亡保留库存规则。
- `query_recipes {item,type?,limit?:1..32,offset?}`：服务器真实配方；支持all/crafting/smelting/blasting/smoking/campfire_cooking/stonecutting/smithing。有扫描和材料候选截断标记，查询支持不等于执行支持。
- `plan_resources {goals:{物品ID:数量}}` 或 `{item,count?}`：模拟递归普通合成，不改库存。目标表示最终应持有量，非额外新增量。最多16目标、单目标4096、合计8192。
- `find_workstations {radius?:1..8,limit?:1..64}`：设备位置/权限/公共状态；不把其他人物品当作材料。公共查询不读取容器内容，实际使用时重新检查设备是否空闲。

## 制作和加工

- `craft_items {item,count?}` 或 `{goals:{ID:数量}}`：普通shaped/shapeless合成，真实配方matches/assemble/余物。2x2免工作台，3x3寻找附近工作台，缺少时用材料制作并合法放置。满背包无法容纳则拒绝。特殊配方、切石/锻造目前只查询，不执行。
- `process_items` 炉类：`{device:"furnace"|"smoker"|"blast_furnace",input:"minecraft:...",count:1..64,fuel:"minecraft:coal"|"minecraft:charcoal",fuel_count:1..64}`。真实设备原版tick、真实配方和燃料，预检燃料足够整批，不跳过时间。当前仅接受单次产量1的炉配方及单槽可容纳批次。
- `process_items` 酿造：`{device:"brewing_stand",ingredient:"minecraft:...",potion_slots:[槽号]}`。1至3个不重复的主库存槽，槽内为单瓶现有药水/水瓶。原版单步mix，消耗1原料和1玩家烈焰粉。多步药水需逐步加工。
- 加工附近4格找空闲设备；没有则尝试使用已有物料制作并放置，3x3配方补工作台。不会取用他人库存、余热或既有酿造燃料。租约期间拦截设备菜单和挖掘，拒绝相邻容器及可见容器实体连接。
- `get_task_status {task_id,wait_ticks?:0..1200}`：加工进度/结果/未知状态；可异步等最多60秒，等待不阻塞服务器、不频繁请求模型。建筑任务也可查询本次运行记录。
- `cancel_task {task_id}`：本人加工任务取消，只归还可证明属于任务的现存物料；已烧燃料、酿造燃料储量不退款。也支持本人当前建筑任务取消。

## 建筑和任务

- `execute_task {token,plan:{reply,blocks:[],removals?:[],commands?:[]}}`：默认每个方案最多4096次放置/拆除（含自动清障），可在「设置 → 建造」自定义，0取消方块数量上限；仍最多8条受限命令。显式拆除先执行，放置分批清理占位，最后执行一般命令。只有服务器终态completed才可报告成功。
- `preview_task {token,plan}`：同样安全预检，不修改世界、不扣材料。预检不消费token，后续执行仍重新检查。
- `build_structure {token,kind:"house"|"wall"|"bridge"|"stairs",width,depth,height,material,facing?}`：确定性完整方块结构，门洞留空。宽深3..11，高2..8，仍受设置中的方块上限和原点每轴±16范围限制，大尺寸可能拒绝。默认按玩家朝向，不扫描整片地形后自动选址。
- `verify_result {task_id}`：按本人建筑记录检查放置位置实际方块，返回差异数及最多32个绝对坐标/预期/实际状态，不验证主观审美/实体/NBT。
- `repair_structure {token,task_id}`：只补缺失/可替换目标，不自动破坏后来新增的实心改建，照常扣料和检查权限。
- `get_operation_history {}`：本人最近32条状态记录；建筑蓝图最多16份，服务器重启清除。
- `manage_waypoints {action:"list"|"set"|"delete",name?}`：set记录当前脚下，不接受任意他人位置。每人32标记，按存档/玩家UUID持久保存。

## 大方案、无限模式与图片校准

- 「设置 → 建造」中的 `blockLimit` 默认4096，是**单个方案**的放置和拆除/清障合计上限；填写0或点击「无限制」关闭数量检查。不会再被旧的累计1024格限制拦住。设置在下次玩家请求生效，服务端将其绑定到观察token；门/床自动补出的方块和自动清障也计入限额。无限模式仍检查已加载区块、世界边界、原点每轴±16、玩家距原点32格以内、材料与保护权限；不是无限空间或无限模型运行时间。
- 大请求通过小于原版C2S上限的分包上传，完整重组并验证后才执行；单个请求最多8MiB，超出需拆成多个工具调用并复用固定建筑原点。半包、错序、跨玩家、过期和取消的上传不会执行。客户端和服务器需一起更新；客户端检测旧观察协议并明确提示升级。
- 服务器保存完整方案，再切成每批最多64个放置方块（门/床保持同批）或128个独立拆除，分tick执行；各批继续验证材料、权限和世界状态，取消保留已执行部分。执行完会重新核验整份提交方案；这证明指定位置/方块符合方案，不自动证明审美或用户要求。不同工具调用的方案仍各自记录task_id。
- `plan.volumes` 支持最多64个盒体或椭球：`{kind:"box"|"ellipsoid",from:{x,y,z},to:{x,y,z},block:"minecraft:..."}`，两个端点都包含在范围内。按数组顺序展开覆盖，再由显式 `blocks` 覆盖细节，所有展开的唯一方块都计数。使用多个盒体/椭球组合3D身体、头部、鳍和尾巴，不必让模型逐格输出数千条坐标。门/床/朝向/NBT等特殊细节仍通过blocks指定。
- 「建造后图片校准」默认开启。Pi 在接受建造后的最终回复前获取当前视野，模型可据图修正并再次截图；截图不可用或耗尽3次预算时明确说明视觉复核未完成。可关闭自动校准以使用不支持图片的模型；玩家明确要求查看画面时仍可调用视野工具。相机未覆盖目标时模型应要求调整视角，不把单张截图当作全建筑证明。

以下紧凑方案可生成4096个方块，服务端会分批执行（放置区仍须满足权限、空间与材料要求）：

```json
{"reply":"16格立体方块","volumes":[{"kind":"box","from":{"x":-8,"y":0,"z":1},"to":{"x":7,"y":15,"z":16},"block":"minecraft:blue_concrete"}]}
```

## 错误、幂等与恢复

- 每次请求有call ID，服务器缓存最近64份个人回执；同ID重传不再次写入。此窗口内幂等，不承诺跨服务器重启幂等。
- 不同ID是新操作。遇到unknown/partial_possible先查状态/观察，绝不能凭空判断没有发生效果。
- 加工任务目前**不持久恢复**。重启后设备、物料留现场，不自动领取/退款；需玩家核对。加工未知状态会保留设备租约，不猜测取物。
- 玩家离线、死亡、旁观、离开设备4格或失去访问权限时不会自动提取产物；回到合法状态后归还。设备按原版仍可能继续加工，不强制加载区块。
- 设备/新工作台留在世界；不是临时借用完自动拆除。
- Pi每任务32模型轮/16写调用/10分钟限制仍适用，长加工可以保持游戏任务并在后续提问查询。

## 验证边界

Java公共模块/两加载器源码编译及JUnit通过，Pi真实SDK离线工具反馈测试通过。未做实际游戏世界中所有工具的联机、保护模组、加工全流程测试；不能把单元测试等同生存经济和第三方保护兼容性验收。


## 通用兼容与管理员工具

- `query_environment {}`：服务器模组/版本、配方类型、玩家实际权限。
- `query_registry {kind:"blocks"|"items",query?,offset?,limit?}`：实际注册 ID 和方块状态选项，最多40项/页。
- `inspect_machine {x,y,z}`：**绝对坐标**，只查已加载区块。普通玩家32格内公共状态；所有者/OP另可读库存与最多10000字符NBT。返回邻接方块及六方向红石输入，不能据一次采样证明时钟稳定。
- `command_help {root?}`：实际注册且玩家有权限的指令及语法，包括模组指令。
- `run_advanced_command {token,command}`：观察令牌有效、无建筑运行且玩家有实际权限2时执行一条指令，最多4096字符。直接按玩家源解析，不提升OP。支持execute、gamemode、data、模组指令；原生命令不受Plan方块上限约束，而遵循Minecraft/模组自己的限制和权限。`~`相对执行时位置，**不是**固定建造原点。回传反馈与成功/失败回调；submitted不等于完成，partial_possible需重新观察。
- `world_snapshot {action,snapshot_id?,file?,path?,chunk_x?,chunk_z?,offset?,limit?}`：create/status/list/nbt/chunk/text。create强制保存后后台复制整个存档；同玩家同服务器会话复用一份，started_at说明数据时间。后续操作必填snapshot_id。list每页最多80文件；nbt读.dat，chunk读.mca中绝对区块坐标（支持负数），path用 `/` 选择NBT键/列表序号；SNBT每页12000字符，text每页12000 UTF-8字节。符号链接/越界路径拒绝，NBT解压预算16MiB。副本不触发实时世界区块加载，也不能当作原子备份。
- `read_server_diagnostics {file?,offset?,length?}` / 客户端 `read_client_diagnostics`：省略file列最新日志及最多40份崩溃报告。只接受logs/latest.log和crash-reports内标准报告名，默认末尾，单页最多16000字节。常见密钥/令牌会脱敏，但无法承诺识别所有自定义日志中的秘密。
- `machine_blueprint {kind,facing?,recipe_id?}`：返回Plan但不执行。kind=auto_smelter/repeater_clock/auto_crafter；auto_crafter需真实recipe_id，仅支持可用同一种普通原料供料且无容器余物的普通配方。返回可选原料、每次消耗、输出数量和启动说明。使用preview_task/execute_task执行，照常材料/保护检查。crafter新增严格限定disabled_slots整数数组，不能借此写其它NBT。
- 客户端 `query_jei {item?,role?:"input"|"output",category?,offset?,limit?}`：不填item/category列类别；配方每页最多8，槽位最多32，每槽最多8替代项，标明截断。查询真实JEI运行时，包含物品/流体等显示成分和机器催化物。只画在界面上的能量/耗时/机械配置不保证读取；JEI缺失/未就绪/不兼容返回明确错误，服务器query_recipes可作后备。
- 客户端 `read_xaero_map {offset?,limit?}`：当前会话所选世界的路标及维度，最多64/页，不扫描其它存档。`capture_map {}`仅截取已打开的Xaero World Map画面，和capture_view共用每轮3次预算；不能替代地形缓存解码或精确方块读取。

详细集成版本、使用示例与限制参见 [MOD_COMPATIBILITY.md](../docs/MOD_COMPATIBILITY.md)。


多人API默认每人独立，管理员可改为服务器共享连接；Pi继续按各玩家独立运行，模型请求路由由Java桥接层按服务器策略选择，工具及游戏权限不变。详见 [多人API说明](../docs/MULTIPLAYER_API.md)。
