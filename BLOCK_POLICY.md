# 方块策略与混合材料（开发源码）

不再用15种材料白名单限制建筑。单次混用种数不限，仍保留256步/±16格规模限制。只接受服务器已注册方块ID，可包含模组方块。

## 模式黑名单

服务器启动自动创建 `config/aibotassist-block-policy.json`，按survival/adventure/creative/spectator分别设置placeDenied、breakDenied。支持精确ID、`modid:*`、`*`。

示例（仅展示生存部分，实际文件需保留四种模式）：

```json
{"placeDenied":["minecraft:command_block","minecraft:bedrock","example:*"] ,"breakDenied":["minecraft:bedrock"]}
```

- 默认生存/冒险禁管理方块、基岩、传送门等；创造默认种类黑名单为空。
- 命令方块本体始终仅创造允许放置，即使误删生存黑名单也不开放。
- 旁观禁止操作；冒险仍检查mayBuild；生存不可破坏原版负硬度方块。
- 管理员 `/aibot reload` 重载；配置错误使用安全默认值并记录固定日志。
- 区域保护、库存材料、工具能力、世界边界等不是黑名单，仍独立检查。

## 状态、液体、门床和NBT

方案每个blocks元素支持 `properties` 状态对象和 `nbt` SNBT字符串；没有properties/nbt兼容旧方案。

```json
{"x":2,"y":0,"z":3,"block":"minecraft:oak_door","properties":{"facing":"east","half":"lower"}}
```

- 门只提交lower、床只提交foot，服务器展开另一格，两格都安全检查，生存一对只扣一物品。朝向默认north。
- 水/岩浆只支持源方块，生存扣对应水桶/岩浆桶，成功使用后返空桶；创造不扣。其它无物品形态的方块生存无法凭空支付。
- 放置整批延迟邻居通知，以免门床缺另一半立即掉落。最终比较实际状态；缺支撑、邻居改变或第三方干预可能导致部分完成，不自动猜测退款或重试。
- 容器默认空放置可用。NBT限8192字符/16层，用原版解析再序列化，不直接拼接原始SNBT。
- 生存/创造允许CustomName、Lock；创造另允许普通容器Items列表，单项仅Slot/id/count，计数和槽位检查。
- 不接受任意NBT字段、物品components/tag、自定义嵌套数据、Command/自动命令激活、可执行点击文本。创造可放空命令方块，但不能由AI写命令绕过kill/gamemode禁令。
- 不承诺所有模组自定义方块实体/特殊联动都能自动操作；支持注册并不等于已为每个mod做适配。

## 多材料建筑

build_structure仍兼容material回退材料，增加palette：wall/floor/roof/frame/window分别指定方块ID。execute_task可逐格任意混用。

## 英语和简体中文

配置界面采用Minecraft translatable组件；en_us和zh_cn提供同21个翻译键。AI每次任务读取当前游戏语言代码作为偏好，用户明确指定回复语言优先。不是强制翻译模型输出，游戏规则/任务详情的部分服务器提示仍为中文，未宣称全部游戏消息已国际化。

## 验证

公共模块与Fabric/NeoForge源码编译成功，60项JUnit通过，两个语言文件键集合一致。未做真实世界门床/液体/容器数据联机测试，未重新打包发布JAR。
