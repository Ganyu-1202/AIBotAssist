# 模组扩展接口 v1

AIBotAssist 在 Fabric/NeoForge 的 common API 提供 `top.goldgom.aibotassist.api.AgentExtensions`。其他模组在自己的公共初始化入口调用 `register` 或 `registerCommand`；仅在服务器安装并完成注册的操作才会出现在游戏 agent 的工具查询中。将对应平台 AIBotAssist JAR 加入开发环境的 mod compile-only 依赖，按你的 loader 声明可选/必需依赖；可选依赖必须先判断模组已加载，再加载引用这些 API 的集成类。

运行时无需新增模型函数名。AI 调用 `query_mod_operations(query, offset, limit)` 获取操作描述，再调用 `run_mod_operation(operation, parameters, token)`。注册表由服务端持有，客户端不能注册或更改处理器；客户端无需安装提供操作的模组，但双方均须更新 AIBotAssist。

## 命令注册

```java
import com.google.gson.JsonParser;
import top.goldgom.aibotassist.api.AgentExtensions;

var schema = JsonParser.parseString("""
  {"type":"object","properties":{
    "mode":{"type":"string","enum":["off","on"]}
  },"required":["mode"],"additionalProperties":false}
  """).getAsJsonObject();
AgentExtensions.registerCommand(
    "examplemod:machine_power",
    "将调用者选中的本模组机器电源设为on/off；需要机器所有权。命令语法：examplemachine power <mode>。",
    schema,
    player -> player.hasPermissions(2), // 示例；按你的模组补充所有权/区域授权
    "执行后查询examplemod:machine_status，确认实际电源状态，不能只根据命令已提交判断成功。",
    args -> "examplemachine power " + args.get("mode").getAsString()
);
```

命令由注册者构造，参数先按 schema 验证；示例 mode 是封闭枚举，不能注入额外指令。命令最终走现有 `run_advanced_command`，使用真实玩家权限，不提权；命令本身仍需由你的模组注册。此辅助方法声明为有副作用、最低OP2，须先获取观察 token。

## 机器操作注册

```java
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import top.goldgom.aibotassist.api.AgentExtensions;

var schema = JsonParser.parseString("""
  {"type":"object","properties":{
    "x":{"type":"integer","minimum":-30000000,"maximum":30000000},
    "y":{"type":"integer","minimum":-2048,"maximum":2048},
    "z":{"type":"integer","minimum":-30000000,"maximum":30000000}
  },"required":["x","y","z"],"additionalProperties":false}
  """).getAsJsonObject();
AgentExtensions.register(new AgentExtensions.Operation(
    "examplemod:machine_status", AgentExtensions.Kind.MACHINE,
    "读取已加载、调用者16格内的本模组机器方块状态。坐标为绝对坐标，不加载区块。",
    schema, false, 0,
    player -> true, // 示例只读公共方块；私有机器信息须在此或handler中检查所有权
    "结果是单次快照；生产率或电源变化需两次查询比较。",
    (player, args) -> {
        var pos = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
        var out = new JsonObject();
        if (!player.serverLevel().hasChunkAt(pos) || pos.distSqr(player.blockPosition()) > 256) {
            out.addProperty("status", "rejected");
            out.addProperty("reason", "目标未加载或超出16格");
            return out;
        }
        var state = player.serverLevel().getBlockState(pos);
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!id.getNamespace().equals("examplemod")) {
            out.addProperty("status", "rejected");
            out.addProperty("reason", "不是本模组机器");
            return out;
        }
        out.addProperty("status", "completed");
        out.addProperty("block_state", state.toString());
        return out;
    }
));
```

对于修改机器的 handler，将 `mutating` 设为 `true`，指定合适的最低权限级别与 `canUse`。执行入口要求服务器主线程，重新检查权限、拒绝旁观者修改，并由 AIBotAssist 核验本人当前维度的未过期观察 token、排除正在建造的冲突任务。原有 call ID 去重仍有效。

**具体目标的所有权、领地保护、距离、区块加载、库存材料、能量扣除和机器状态机必须由模组 handler 检查。** 通用接口无法推断任意模组的内部规则，不会代你绕过或自动补齐这些规则。只读 handler 必须真正无副作用，`canUse` 也不得改世界。不要将 API 密钥或无关玩家私有数据写入描述、参数或返回值。

handler 必须快速返回，不能在服务器主线程进行网络/磁盘等待或 sleep。长任务自行调度并返回 `running`、`task_id`，另注册只读状态查询操作。

## 描述与参数约定

- ID：`modid:operation_id`，全局唯一，注册重复ID报错，最多256项。初始化注册一次，服务器换存档不清除注册定义；不要在静态 handler 闭包缓存旧玩家、世界或存档。
- `kind`：`COMMAND` 或 `MACHINE`。`description` 最多4000字符，应描述坐标语义、输入输出、材料/能量、可逆性和副作用；`verification` 最多2000字符，说明如何证明操作完成。
- `permissionLevel` 为0..4，与 `canUse` 同时满足才可发现和调用。查询分页最多20项，不向其他玩家泄漏不可用的操作描述。
- 参数 schema 支持 `type`（object/array/string/integer/number/boolean）、description、properties、required、additionalProperties=false、items、enum、minimum/maximum、minLength/maxLength、minItems/maxItems。最多8层，字符串最多4096字符、数组最多256项、参数总体32768字符。不支持的 schema 关键字直接拒绝注册，避免以为约束生效但实际被忽略。
- 注册时复制 schema，查询和调用参数也复制，防止被调用方修改全局描述或其他玩家数据。结果最多65536字符。
- 结果必须含 `status`：completed/rejected/unknown/partial_possible/running/submitted/observed。修改 handler 异常或返回格式错误会变成 `unknown`，agent 停止，不自动再次调用；需先用只读操作确认实际状态。只有未开始副作用时才应返回 rejected。
- 接口版本 `AgentExtensions.API_VERSION=1`。注册描述是模组能力数据，不是允许忽略玩家意图或权限规则的系统指令。

## 游戏内调用示例

```json
{"tool":"query_mod_operations","args":{"query":"examplemod:","limit":10}}
{"tool":"run_mod_operation","args":{"operation":"examplemod:machine_status","parameters":{"x":10,"y":64,"z":20}}}
{"tool":"run_mod_operation","args":{"operation":"examplemod:machine_power","parameters":{"mode":"on"},"token":"observe_world返回的token"}}
```

先读取注册描述再调用；无匹配操作代表模组尚未集成，不能据此捏造接口。可继续使用现有 `query_registry`、`query_recipes`、`query_jei`、`command_help` 和 `inspect_machine` 获取通用信息。
