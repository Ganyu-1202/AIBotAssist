package top.goldgom.aibotassist;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;

/** Only bundled, reviewed skills can be read. No arbitrary filesystem access. */
public final class AgentSkills {
    private AgentSkills(){}
    public static final String GUIDANCE="涉及红石机器、活塞、发射器、时钟或自动合成时，先read_agent_skill(name=minecraft-redstone)，再设计。用analyze_redstone_plan检查自定义plan端口与支撑，再preview_task/execute_task。模板不支持不等于不能制造：按skill规划自定义blocks。涉及模组专用机器或命令时先query_mod_operations，使用注册描述和参数调用run_mod_operation；不捏造接口。模型请求可能有限重试，但不得重放已执行或效果未知的游戏操作。";
    public static JsonObject read(JsonObject args)throws java.io.IOException{
        var out=new JsonObject();out.addProperty("status","completed");
        if(!args.has("name")){var names=new JsonArray();var s=new JsonObject();s.addProperty("name","minecraft-redstone");s.addProperty("description","Java 1.21.1 红石端口、时序、活塞、供料、建造与功能验收");names.add(s);out.add("skills",names);return out;}
        String name=args.get("name").getAsString();if(!name.equals("minecraft-redstone"))throw new IllegalArgumentException("未知skill；省略name查询列表");
        try(var stream=AgentSkills.class.getResourceAsStream("/aibotassist/skills/"+name+"/SKILL.md")){
            if(stream==null)throw new java.io.IOException("Missing bundled skill");byte[] bytes=stream.readNBytes(24577);if(bytes.length>24576)throw new java.io.IOException("Skill too large");
            out.addProperty("name",name);out.addProperty("content",new String(bytes,StandardCharsets.UTF_8));return out;
        }
    }
}
