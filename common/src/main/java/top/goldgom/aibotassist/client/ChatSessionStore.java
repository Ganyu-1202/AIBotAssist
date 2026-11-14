package top.goldgom.aibotassist.client;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.*;

/** Separate encrypted records for each save and authenticated Minecraft player UUID. */
public final class ChatSessionStore {
    static final int MAX_BYTES=8*1024*1024;
    public record Transcript(int version,List<AgentChatState.Message> messages,String draft) {}
    private final LocalCredentials files;
    public ChatSessionStore(Path root){files=new LocalCredentials(root);}
    public static String key(String world,UUID player){
        if(world==null||world.isBlank()||world.length()>2000||player==null)throw new IllegalArgumentException("Invalid session identity");
        return LocalCredentials.scope("chat-v1\n"+world+"\n"+player);
    }
    public Transcript load(String key)throws Exception{
        String json=files.loadPayload(key,MAX_BYTES);if(json==null)return new Transcript(1,List.of(),"");
        Transcript data=new Gson().fromJson(json,Transcript.class);
        if(data==null||data.version()!=1||data.messages()==null||data.messages().size()>160||data.draft()==null||data.draft().length()>2000)throw new IllegalArgumentException("Invalid chat session");
        for(var m:data.messages())if(m==null||m.role()==null||m.text()==null||m.text().length()>6000)throw new IllegalArgumentException("Invalid chat message");
        return new Transcript(1,List.copyOf(data.messages()),data.draft());
    }
    public void save(String key,Transcript data)throws Exception{files.savePayload(key,new Gson().toJson(data),MAX_BYTES);}
}
