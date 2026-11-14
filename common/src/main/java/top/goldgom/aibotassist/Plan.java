package top.goldgom.aibotassist;

import com.google.gson.*;
import java.util.*;

/** Untrusted model output; validated again on the authoritative server. */
public record Plan(String reply, List<Placement> blocks, List<String> commands, List<Removal> removals) {
    public Plan(String reply, List<Placement> blocks) { this(reply, blocks, List.of(), List.of()); }
    public Plan(String reply, List<Placement> blocks, List<String> commands) { this(reply, blocks, commands, List.of()); }
    public boolean hasActions() { return !blocks.isEmpty() || !commands.isEmpty() || !removals.isEmpty(); }
    public record Removal(int x, int y, int z) {}
    public static final int MAX_BLOCKS = 4096, RADIUS = 16;
    public static boolean validBlockId(String id) {
        return id != null && id.length()<=160 && id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
    }
    public record Placement(int x, int y, int z, String block, Map<String,String> properties, String nbt) {
        public Placement(int x,int y,int z,String block){this(x,y,z,block,Map.of(),"");}
        public Placement { properties=properties==null?Map.of():Map.copyOf(properties);nbt=nbt==null?"":nbt; }
    }
    public static void checkLimit(int limit,long count) {
        if(limit<0 || count<0)throw new IllegalArgumentException("方块限制必须为非负整数，0表示无限");
        if(limit>0 && count>limit)throw new IllegalArgumentException("放置和拆除超过设置的方块上限："+limit);
    }
    public static Plan parse(String text) {return parse(text,MAX_BLOCKS);}
    public static Plan parse(String text,int limit) {
        checkLimit(limit,0);
        if (text == null || text.length() > PacketChunks.MAX_BYTES) throw new IllegalArgumentException("方案过长");
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        String reply = o.get("reply").getAsString();
        if (reply.length() > 4000) throw new IllegalArgumentException("回复过长");
        if(!o.has("blocks")&&!o.has("volumes"))throw new IllegalArgumentException("方案需要blocks或volumes");
        JsonArray a = o.getAsJsonArray("blocks");
        if(a==null)a=new JsonArray();
        checkLimit(limit,a.size());
        Map<String,Placement> expanded=volumes(o,limit);
        List<Placement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement e : a) {
            JsonObject b = e.getAsJsonObject();
            int x = integer(b, "x"), y = integer(b, "y"), z = integer(b, "z");
            String id = b.get("block").getAsString();
            if (Math.abs((long)x) > RADIUS || Math.abs((long)y) > RADIUS || Math.abs((long)z) > RADIUS)
                throw new IllegalArgumentException("超出建造半径");
            if (!validBlockId(id)) throw new IllegalArgumentException("方块ID格式无效");
            if (!seen.add(x + "," + y + "," + z)) throw new IllegalArgumentException("重复坐标");
            Map<String,String> properties=new LinkedHashMap<>();
            if(b.has("properties")&&!b.get("properties").isJsonNull())for(var entry:b.getAsJsonObject("properties").entrySet()){
                if(properties.size()>=32||!entry.getKey().matches("[a-z0-9_]{1,64}")||!entry.getValue().getAsString().matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("方块状态无效");
                properties.put(entry.getKey(),entry.getValue().getAsString());
            }
            String nbt=b.has("nbt")&&!b.get("nbt").isJsonNull()?b.get("nbt").getAsString():"";
            if(nbt.length()>8192)throw new IllegalArgumentException("方块NBT过长");
            expanded.put(x+","+y+","+z,new Placement(x,y,z,id,properties,nbt));
            checkLimit(limit,expanded.size());
        }
        result.addAll(expanded.values());
        seen.addAll(expanded.keySet());
        List<String> commands = new ArrayList<>();
        if (o.has("commands")) {
            JsonArray c = o.getAsJsonArray("commands");
            if (c.size() > CommandPolicy.MAX_COMMANDS) throw new IllegalArgumentException("命令数量超限");
            for (JsonElement e : c) commands.add(CommandPolicy.normalize(e.getAsString()));
        }
        List<Removal> removals = new ArrayList<>();
        if (o.has("removals")) {
            JsonArray removalsJson = o.getAsJsonArray("removals");
            checkLimit(limit,(long)removalsJson.size()+result.size());
            for (JsonElement e : removalsJson) {
                JsonObject b = e.getAsJsonObject();
                int x = integer(b, "x"), y = integer(b, "y"), z = integer(b, "z");
                if (Math.abs((long)x) > RADIUS || Math.abs((long)y) > RADIUS || Math.abs((long)z) > RADIUS)
                    throw new IllegalArgumentException("拆除超出半径");
                if (!seen.add(x + "," + y + "," + z)) throw new IllegalArgumentException("放置/拆除坐标重复");
                removals.add(new Removal(x, y, z));
            }
        }
        return new Plan(reply, List.copyOf(result), List.copyOf(commands), List.copyOf(removals));
    }
    /** Compact box/ellipsoid primitives let the model describe large 3D volumes without listing every voxel. */
    private static Map<String,Placement> volumes(JsonObject plan,int limit) {
        Map<String,Placement> result=new LinkedHashMap<>();
        if(!plan.has("volumes"))return result;
        JsonArray volumes=plan.getAsJsonArray("volumes");
        if(volumes.size()>64)throw new IllegalArgumentException("单个方案最多64个体积，请分批提交");
        for(var element:volumes) {
            JsonObject volume=element.getAsJsonObject();String kind=volume.get("kind").getAsString(),block=volume.get("block").getAsString();
            if(!Set.of("box","ellipsoid").contains(kind)||!validBlockId(block))throw new IllegalArgumentException("无效体积或方块类型");
            JsonObject from=volume.getAsJsonObject("from"),to=volume.getAsJsonObject("to");
            int x1=integer(from,"x"),y1=integer(from,"y"),z1=integer(from,"z"),x2=integer(to,"x"),y2=integer(to,"y"),z2=integer(to,"z");
            for(int value:new int[]{x1,y1,z1,x2,y2,z2})if(Math.abs((long)value)>RADIUS)throw new IllegalArgumentException("体积超出建造半径");
            if(x1>x2||y1>y2||z1>z2)throw new IllegalArgumentException("from坐标必须小于等于to");
            for(int y=y1;y<=y2;y++)for(int x=x1;x<=x2;x++)for(int z=z1;z<=z2;z++) {
                double dx=(2.0*x-x1-x2)/(x2-x1+1),dy=(2.0*y-y1-y2)/(y2-y1+1),dz=(2.0*z-z1-z2)/(z2-z1+1);
                if(kind.equals("ellipsoid")&&dx*dx+dy*dy+dz*dz>1)continue;
                result.put(x+","+y+","+z,new Placement(x,y,z,block));checkLimit(limit,result.size());
            }
        }
        return result;
    }
    private static int integer(JsonObject o, String key) {
        try{return o.get(key).getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("坐标必须为有效整数",e);}
    }
    public Map<String, Integer> materials() {
        Map<String, Integer> m = new TreeMap<>();
        blocks.forEach(b -> m.merge(b.block, 1, Integer::sum));
        return m;
    }
}
