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
        add(tools,"observe_world","读取当前模式、库存、朝向、附近地形及设备。返回固定建筑origin的绝对坐标；本次请求后续观察复用原点/朝向，坐标相对该原点，执行用本次观察token。",observe);
        JsonObject execute=JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"token\":{\"type\":\"string\"},\"plan\":{\"type\":\"object\",\"properties\":{\"reply\":{\"type\":\"string\"},\"blocks\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"block\":{\"type\":\"string\"}},\"required\":[\"x\",\"y\",\"z\",\"block\"]}},\"removals\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\",\"z\"]}},\"commands\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"reply\",\"blocks\"]}},\"required\":[\"token\",\"plan\"],\"additionalProperties\":false}").getAsJsonObject();
        JsonObject placementSchema=execute.getAsJsonObject("properties").getAsJsonObject("plan").getAsJsonObject("properties").getAsJsonObject("blocks").getAsJsonObject("items").getAsJsonObject("properties");
        JsonObject props=new JsonObject();props.addProperty("type","object");JsonObject str=new JsonObject();str.addProperty("type","string");props.add("additionalProperties",str);placementSchema.add("properties",props);
        placementSchema.add("nbt",str.deepCopy());
        JsonObject planSchema=execute.getAsJsonObject("properties").getAsJsonObject("plan");
        planSchema.add("required",JsonParser.parseString("[\"reply\"]"));
        planSchema.getAsJsonObject("properties").add("volumes",JsonParser.parseString("""
            {"type":"array","maxItems":64,"description":"按顺序展开体积，后面的体积覆盖前面的；显式blocks最后覆盖。坐标相对固定origin。",
             "items":{"type":"object","properties":{
               "kind":{"type":"string","enum":["box","ellipsoid"]},
               "from":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]},
               "to":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"]},
               "block":{"type":"string"}},"required":["kind","from","to","block"],"additionalProperties":false}}
            """));
        add(tools,"execute_task","按观察token执行任务并等待服务器终态。默认4096方块操作，上限由设置/观察回执给出，0为不限数量；最多8命令。支持volumes盒体/椭球生成3D造型，服务器分批执行；原点在本轮请求内固定。失败可能部分完成，必须观察后再调整，禁止盲重试。",execute);
        AgentToolCatalog.append(tools);
        return tools;
    }
    private static void add(JsonArray a,String name,String desc,JsonObject schema) {
        JsonObject f=new JsonObject();f.addProperty("name",name);f.addProperty("description",desc);f.add("parameters",schema);
        JsonObject t=new JsonObject();t.addProperty("type","function");t.add("function",f);a.add(t);
    }
}
