# Minecraft Pi工具目录（开发源码）

共19个服务器工具；所有游戏写操作均由服务器按玩家身份检查。Pi本地没有Bash/文件工具。客户端和服务器需使用相同新版源码/包；旧0.1.1未包含此目录。

## 观察与资源

- `observe_world {}`：模式、库存、朝向、有限地形和单次观察token。执行方块任务需token。
- `inspect_area {radius?:1..4,x?:-12..12,y?:-12..12,z?:-12..12}`：查询玩家相对偏移附近地形，最多160个非空气格，坐标相对此查询中心，附偏移和截断标记，不强制加载区块。
- `inspect_inventory {}`：本人库存ID/数量、槽号、工具耐久；不输出NBT。主库存0..35为合成库存。
- `inspect_entities {}`：12格内最多24个生物类型、距离、敌对性；不自动攻击、不读他人库存。
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

- `execute_task {token,plan:{reply,blocks:[],removals?:[],commands?:[]}}`：最多256放置/拆除步，8条受限命令。先清障、建造、执行一般命令。只有服务器终态completed才可报告成功。
- `preview_task {token,plan}`：同样安全预检，不修改世界、不扣材料。预检不消费token，后续执行仍重新检查。
- `build_structure {token,kind:"house"|"wall"|"bridge"|"stairs",width,depth,height,material,facing?}`：确定性完整方块结构，门洞留空。宽深3..11，高2..8，仍受256格/每轴±16限制，大尺寸可能拒绝。默认按玩家朝向，不扫描整片地形后自动选址。
- `verify_result {task_id}`：按本人建筑记录检查放置位置实际方块，返回差异数，不验证主观审美/实体/NBT。
- `repair_structure {token,task_id}`：只补缺失/可替换目标，不自动破坏后来新增的实心改建，照常扣料和检查权限。
- `get_operation_history {}`：本人最近32条状态记录；建筑蓝图最多16份，服务器重启清除。
- `manage_waypoints {action:"list"|"set"|"delete",name?}`：set记录当前脚下，不接受任意他人位置。每人32标记，按存档/玩家UUID持久保存。

## 错误、幂等与恢复

- 每次请求有call ID，服务器缓存最近64份个人回执；同ID重传不再次写入。此窗口内幂等，不承诺跨服务器重启幂等。
- 不同ID是新操作。遇到unknown/partial_possible先查状态/观察，绝不能凭空判断没有发生效果。
- 加工任务目前**不持久恢复**。重启后设备、物料留现场，不自动领取/退款；需玩家核对。加工未知状态会保留设备租约，不猜测取物。
- 玩家离线、死亡、旁观、离开设备4格或失去访问权限时不会自动提取产物；回到合法状态后归还。设备按原版仍可能继续加工，不强制加载区块。
- 设备/新工作台留在世界；不是临时借用完自动拆除。
- Pi每任务12模型轮/8写调用/10分钟限制仍适用，长加工可以保持游戏任务并在后续提问查询。

## 验证边界

Java公共模块/两加载器源码编译及JUnit通过，Pi真实SDK离线工具反馈测试通过。未做实际游戏世界中所有工具的联机、保护模组、加工全流程测试；不能把单元测试等同生存经济和第三方保护兼容性验收。
