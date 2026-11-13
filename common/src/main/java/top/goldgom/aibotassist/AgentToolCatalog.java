package top.goldgom.aibotassist;
import com.google.gson.*;
import java.util.*;

public final class AgentToolCatalog {
    public static final Set<String> NAMES=Set.of("inspect_area","inspect_inventory","query_recipes","plan_resources","find_workstations","craft_items","process_items","get_task_status","verify_result","preview_task","build_structure","repair_structure","cancel_task","get_operation_history","manage_waypoints","inspect_entities","query_game_rules","run_commands");
    public static void append(JsonArray tools) {
        add(tools,"inspect_area","有限范围地形/遮挡/访问状态查询，不读容器内容。","radius:integer,x:integer,y:integer,z:integer");
        add(tools,"inspect_inventory","查询提问者库存及工具耐久。","");
        add(tools,"query_recipes","查询真实服务器配方及所需设备，item为目标物品ID。","item:string,type:string,limit:integer,offset:integer");
        add(tools,"plan_resources","只读模拟递归合成，报告缺料，不改库存。goals为物品ID到数量对象。","goals:object,item:string,count:integer");
        add(tools,"find_workstations","查询附近合法设备及公共占用状态，不读取其他人物品。","radius:integer,limit:integer");
        add(tools,"craft_items","实际制作普通合成物品。item/count，2x2无需工作台，3x3找/制作设备。","item:string,count:integer,goals:object");
        add(tools,"process_items","真实设备加工。device=furnace/smoker/blast_furnace需input/count/fuel(coal或charcoal)/fuel_count；brewing_stand需ingredient及potion_slots(1到3个玩家药水库存槽号)，消耗1烈焰粉。自动找空设备或用材料制作，按原版时间加工，返回task_id。","device:string,input:string,count:integer,fuel:string,fuel_count:integer,ingredient:string,potion_slots:array");
        add(tools,"get_task_status","查询个人加工任务状态，task_id必填。","task_id:string,wait_ticks:integer");
        add(tools,"cancel_task","取消本人任务并结算未消耗原料，不撤销已完成操作。","task_id:string");
        add(tools,"preview_task","只预检方案，不执行不扣料；token/plan与execute_task一致。","token:string,plan:object");
        add(tools,"build_structure","参数化生成并执行house/wall/bridge/stairs。先observe_world，使用token。尺寸最多256格；palette可分别设置wall/floor/roof/frame/window为不同注册方块，建议混合材料。","token:string,kind:string,width:integer,depth:integer,height:integer,material:string,palette:object,facing:string");
        add(tools,"verify_result","验证本人建筑task_id的实际方块差异。","task_id:string");
        add(tools,"repair_structure","按本人建筑记录补差异，先观察，用新token及原task_id。","token:string,task_id:string");
        add(tools,"get_operation_history","本人最近32条操作结果，服务器重启清除。","");
        add(tools,"manage_waypoints","个人标记 action=list/set/delete，set为当前脚下；name只用汉字字母数字_-/32字。","action:string,name:string");
        add(tools,"inspect_entities","附近生物类型、距离和敌对性，不自动攻击。","");
        add(tools,"query_game_rules","读取当前维度、时间、天气与相关规则。","");
        addCommands(tools);
    }
    private static void addCommands(JsonArray tools) {
        JsonObject properties=new JsonObject();
        JsonObject token=new JsonObject();token.addProperty("type","string");properties.add("token",token);
        JsonObject commands=new JsonObject();commands.addProperty("type","array");commands.addProperty("minItems",1);commands.addProperty("maxItems",CommandPolicy.MAX_COMMANDS);JsonObject item=new JsonObject();item.addProperty("type","string");commands.add("items",item);properties.add("commands",commands);
        JsonObject schema=new JsonObject();schema.addProperty("type","object");schema.add("properties",properties);JsonArray required=new JsonArray();required.add("token");required.add("commands");schema.add("required",required);schema.addProperty("additionalProperties",false);
        JsonObject f=new JsonObject();f.addProperty("name","run_commands");f.addProperty("description","执行玩家有权限的原版命令，最多8条；必须先observe_world取得token。适合输入指令、修改gamerule、时间、天气、传送等；不提升权限，不执行被禁止命令。每条命令可带或不带开头斜杠。 ");f.add("parameters",schema);JsonObject t=new JsonObject();t.addProperty("type","function");t.add("function",f);tools.add(t);
    }
    private static void add(JsonArray tools,String name,String desc,String fields) {
        JsonObject properties=new JsonObject();for(String field:fields.split(",")) {if(field.isBlank())continue;String[] pair=field.split(":");JsonObject type=new JsonObject();type.addProperty("type",pair[1]);if(pair[1].equals("array")){JsonObject item=new JsonObject();item.addProperty("type","integer");type.add("items",item);}properties.add(pair[0],type);}
        JsonObject schema=new JsonObject();schema.addProperty("type","object");schema.add("properties",properties);schema.addProperty("additionalProperties",false);
        JsonArray required=new JsonArray();required(name).forEach(required::add);schema.add("required",required);
        JsonObject f=new JsonObject();f.addProperty("name",name);f.addProperty("description",desc);f.add("parameters",schema);JsonObject t=new JsonObject();t.addProperty("type","function");t.add("function",f);tools.add(t);
    }
    public static java.util.List<String> required(String tool) {
        return switch(tool) {
            case "build_structure" -> List.of("token","kind","width","depth","height");
            case "preview_task" -> List.of("token","plan");
            case "repair_structure" -> List.of("token","task_id");
            case "get_task_status","cancel_task","verify_result" -> List.of("task_id");
            case "run_commands" -> List.of("token","commands");
            case "query_recipes" -> List.of("item");
            case "process_items" -> List.of("device");
            default -> List.of();
        };
    }
    public static void validateArgs(String tool,JsonObject args) {
        if(args==null)throw new IllegalArgumentException("工具参数args必须为对象");
        for(String key:required(tool))if(!args.has(key)||args.get(key).isJsonNull())throw new IllegalArgumentException("工具 "+tool+" 缺少必填参数："+key);
        if(tool.equals("build_structure")) {
            for(String key:List.of("width","depth","height"))try{args.get(key).getAsBigDecimal().intValueExact();}catch(Exception e){throw new IllegalArgumentException(key+"必须为整数");}
            fallbackMaterial(args);
        }
        if(tool.equals("run_commands")) {
            if(!args.get("commands").isJsonArray())throw new IllegalArgumentException("commands必须为数组");
            var commands=args.getAsJsonArray("commands");
            if(commands.isEmpty()||commands.size()>CommandPolicy.MAX_COMMANDS)throw new IllegalArgumentException("commands数量必须1到"+CommandPolicy.MAX_COMMANDS);
            for(var command:commands)if(!command.isJsonPrimitive()||!command.getAsJsonPrimitive().isString())throw new IllegalArgumentException("commands中的每项必须是字符串");
        }
    }
    public static String fallbackMaterial(JsonObject args) {
        if(args.has("material")&&!args.get("material").isJsonNull()&&!args.get("material").getAsString().isBlank())return args.get("material").getAsString();
        if(args.has("palette")&&args.get("palette").isJsonObject()) {
            JsonObject palette=args.getAsJsonObject("palette");
            if(palette.has("wall")&&!palette.get("wall").isJsonNull())return palette.get("wall").getAsString();
            for(var value:palette.entrySet())if(!value.getValue().isJsonNull())return value.getValue().getAsString();
        }
        throw new IllegalArgumentException("build_structure需要material或非空palette；请补全建筑材料");
    }
    private AgentToolCatalog() {}
}
