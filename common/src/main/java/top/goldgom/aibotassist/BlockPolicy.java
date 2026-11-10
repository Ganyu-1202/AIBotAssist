package top.goldgom.aibotassist;

import dev.architectury.platform.Platform;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Server authoritative, independent place/break lists per game mode. No material whitelist. */
public final class BlockPolicy {
    public static final class Rules {
        public Set<String> placeDenied=new LinkedHashSet<>();
        public Set<String> breakDenied=new LinkedHashSet<>();
    }
    public static final class Config {
        public int version=1;
        public Map<String,Rules> modes=new LinkedHashMap<>();
    }
    private static Config current;
    private BlockPolicy(){}
    public static Config defaults(){
        Config c=new Config();for(String mode:List.of("survival","adventure","creative","spectator"))c.modes.put(mode,new Rules());
        Set<String> restricted=Set.of("minecraft:command_block","minecraft:chain_command_block","minecraft:repeating_command_block","minecraft:structure_block","minecraft:jigsaw","minecraft:structure_void","minecraft:barrier","minecraft:light","minecraft:bedrock","minecraft:end_portal","minecraft:end_portal_frame","minecraft:nether_portal");
        for(String mode:List.of("survival","adventure")){c.modes.get(mode).placeDenied.addAll(restricted);c.modes.get(mode).breakDenied.addAll(restricted);}
        c.modes.get("spectator").placeDenied.add("*");c.modes.get("spectator").breakDenied.add("*");return c;
    }
    public static synchronized void reload(){
        Path path=Platform.getConfigFolder().resolve("aibotassist-block-policy.json");
        try {
            if(Files.exists(path)){Config config=new Gson().fromJson(Files.readString(path),Config.class);validate(config);current=config;}
            else {current=defaults();Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(current));}
        }catch(Exception e){current=defaults();org.slf4j.LoggerFactory.getLogger("aibotassist").error("Block policy invalid/unreadable; safe defaults applied");}
    }
    public static void validate(Config c){
        if(c==null||c.version!=1||c.modes==null)throw new IllegalArgumentException("Invalid policy");
        for(String mode:List.of("survival","adventure","creative","spectator")){
            Rules r=c.modes.get(mode);if(r==null||r.placeDenied==null||r.breakDenied==null)throw new IllegalArgumentException("Missing mode");
            for(String pattern:java.util.stream.Stream.concat(r.placeDenied.stream(),r.breakDenied.stream()).toList())
                if(pattern==null||!(pattern.equals("*")||pattern.matches("[a-z0-9_.-]+:(?:[a-z0-9/._-]+|\\*)")))throw new IllegalArgumentException("Invalid rule");
        }
    }
    public static boolean denied(Config config,String mode,String block,boolean breaking){
        // Command block placement is always creative-only, even if a config accidentally removes it.
        if(!mode.equals("creative")&&Set.of("minecraft:command_block","minecraft:chain_command_block","minecraft:repeating_command_block").contains(block))return true;
        Rules rules=config.modes.get(mode);if(rules==null)return true;
        Set<String> list=breaking?rules.breakDenied:rules.placeDenied;
        for(String pattern:list)if(pattern.equals("*")||pattern.equals(block)||(pattern.endsWith(":*")&&block.startsWith(pattern.substring(0,pattern.length()-1))))return true;
        return false;
    }
    public static void check(net.minecraft.server.level.ServerPlayer p,String block,boolean breaking){
        if(current==null)reload();
        if(denied(current,p.gameMode.getGameModeForPlayer().getName(),block,breaking))throw new IllegalArgumentException("当前模式的AI"+(breaking?"破坏":"放置")+"黑名单禁止："+block);
    }
    public static Config snapshot(){if(current==null)reload();return current;}
}
