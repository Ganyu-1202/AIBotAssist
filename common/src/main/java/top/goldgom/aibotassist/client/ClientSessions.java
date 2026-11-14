package top.goldgom.aibotassist.client;

import dev.architectury.platform.Platform;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;

/** Render-thread binding, ordered background persistence, and stale-load isolation. */
public final class ClientSessions {
    private static final ChatSessionStore store=new ChatSessionStore(Platform.getConfigFolder().resolve("aibotassist/sessions"));
    private static final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"aibot-sessions");t.setDaemon(true);return t;});
    private static String fallback="",bound="",savedDraft="";
    private static UUID player;
    private static int generation,ticks;
    private static long savedRevision=-1;
    private static boolean ready,writable;
    private ClientSessions(){}
    public static boolean ready(){return ready;}
    public static void join(String world,UUID uuid,boolean serverMod){
        leave();fallback=world;player=uuid;ClientMod.chat.reset();
        if(!serverMod)bind("");
    }
    public static void bind(String world){
        if(player==null)return;
        String key=ChatSessionStore.key(world==null||world.isBlank()?fallback:fallback+"|world:"+world,player);
        if(key.equals(bound))return;
        save();bound=key;ready=false;writable=false;int run=++generation;ClientMod.chat.reset();
        io.submit(()->{
            try{var transcript=store.load(key);Minecraft.getInstance().execute(()->{
                if(run!=generation||!bound.equals(key))return;
                ClientMod.chat.restore(transcript.messages(),transcript.draft());savedRevision=ClientMod.chat.revision();savedDraft=ClientMod.chat.draft();ready=true;writable=true;
            });}catch(Exception e){Minecraft.getInstance().execute(()->{
                if(run!=generation||!bound.equals(key))return;
                ready=true;writable=false;ClientMod.say("当前存档会话无法读取，原文件已保留。本次聊天暂不覆盖旧记录。");
            });}
        });
    }
    public static void tick(){if(++ticks>=40){ticks=0;save();}}
    public static void save(){
        if(!ready||!writable||bound.isBlank())return;
        var chat=ClientMod.chat;long revision=chat.revision();String draft=chat.draft();
        if(revision==savedRevision&&draft.equals(savedDraft))return;
        String key=bound;int run=generation;var data=new ChatSessionStore.Transcript(1,chat.messages(),draft);savedRevision=revision;savedDraft=draft;
        io.submit(()->{try{store.save(key,data);}catch(Exception e){Minecraft.getInstance().execute(()->{if(run==generation){savedRevision=-1;ClientMod.say("对话会话保存失败，将稍后重试；未输出聊天内容。");}});}});
    }
    public static void leave(){save();generation++;ready=false;writable=false;bound="";player=null;savedRevision=-1;savedDraft="";}
    public static void stop(){leave();io.shutdown();try{io.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
