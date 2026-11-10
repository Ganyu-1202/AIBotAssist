package top.goldgom.aibotassist.client;

import com.google.gson.*;
import top.goldgom.aibotassist.*;
import java.util.function.*;

/** Tool schemas exposed exclusively to the Pi runtime. */
public final class MinecraftAgent {
    @FunctionalInterface public interface Transport { JsonObject request(String tool, JsonObject args) throws Exception; }
    private MinecraftAgent() {}
    private static JsonObject message(String role,String text) {
        JsonObject m=new JsonObject();m.addProperty("role",role);m.addProperty("content",text);return m;
    }
    public static JsonArray tools() {
        JsonArray tools=new JsonArray();
        JsonObject observe=new JsonObject();observe.addProperty("type","object");observe.add("properties",new JsonObject());observe.addProperty("additionalProperties",false);
        add(tools,"observe_world","读取当前模式、库存、朝向、附近地形及设备。坐标相对当前玩家脚下，执行用同一观察的token。",observe);
        JsonObject execute=JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"token\":{\"type\":\"string\"},\"plan\":{\"type\":\"object\",\"properties\":{\"reply\":{\"type\":\"string\"},\"blocks\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"block\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"block\"]}},\"removals\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}},\"commands\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"reply\",\"blocks\"]}},\"required\":[\"token\",\"plan\"],\"additionalProperties\":false}").getAsJsonObject();
        JsonObject placementSchema=execute.getAsJsonObject("properties").getAsJsonObject("plan").getAsJsonObject("properties").getAsJsonObject("blocks").getAsJsonObject("items").getAsJsonObject("properties");
        JsonObject props=new JsonObject();props.addProperty("type","object");JsonObject str=new JsonObject();str.addProperty("type","string");props.add("additionalProperties",str);placementSchema.add("properties",props);
        placementSchema.add("nbt",str.deepCopy());
        add(tools,"execute_task","按观察token执行任务并等待服务器终态。每次总计最多256方块操作、8命令。失败可能部分完成，必须观察后再调整，禁止盲重试。",execute);
        AgentToolCatalog.append(tools);
        return tools;
    }
    private static void add(JsonArray a,String name,String desc,JsonObject schema) {
        JsonObject f=new JsonObject();f.addProperty("name",name);f.addProperty("description",desc);f.add("parameters",schema);
        JsonObject t=new JsonObject();t.addProperty("type","function");t.add("function",f);a.add(t);
    }
}
