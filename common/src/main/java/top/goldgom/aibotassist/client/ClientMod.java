package top.goldgom.aibotassist.client;

import com.google.gson.*;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.*;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import top.goldgom.aibotassist.*;
import java.util.concurrent.*;

public final class ClientMod {
    public static ClientSettings settings;
    public static final AiConnection connection = new AiConnection();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "aibot-model"); t.setDaemon(true); return t; });
    private static volatile boolean busy;
    private static volatile int generation;
    private static final java.util.Map<String,CompletableFuture<JsonObject>> toolResults=new java.util.concurrent.ConcurrentHashMap<>();
    private static int settingsDelay;
    private static void openSettings() { settingsDelay = 2; }
    public static void init() {
        settings = ClientSettings.load();
        ClientCommandRegistrationEvent.EVENT.register((dispatcher, context) ->
            dispatcher.register(ClientCommandRegistrationEvent.literal("aibot")
                .then(ClientCommandRegistrationEvent.literal("settings").executes(c -> { openSettings(); return 1; }))));
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, Wire.Down.TYPE, Wire.Down.CODEC, (packet, ctx) -> ctx.queue(() -> {
            JsonObject reply=JsonParser.parseString(packet.json()).getAsJsonObject();
            if(reply.has("action") && reply.get("action").getAsString().equals("agent_result")) {
                var pending=toolResults.remove(reply.get("call").getAsString());if(pending!=null)pending.complete(reply);
            }
        }));
        ClientChatEvent.SEND.register((message, component) -> {
            String mention = "@" + settings.name;
            if (!message.equals(mention) && !message.startsWith(mention + " ") && !message.startsWith(mention + "　")) return EventResult.pass();
            String prompt = message.substring(mention.length()).trim();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.gui.getChat().addMessage(Component.literal("<" + mc.player.getGameProfile().getName() + "> " + message));
            if (prompt.isEmpty() || prompt.equals("设置")) { say("请使用 /aibot settings 配置 AI。"); return EventResult.interruptFalse(); }
            if (prompt.equals("取消")) { generation++; toolResults.values().forEach(f->f.completeExceptionally(new InterruptedException()));toolResults.clear(); if (mc.getConnection() != null) mc.getConnection().sendCommand("aibot cancel"); return EventResult.interruptFalse(); }
            if (!connection.ready() || settings.model.isBlank()) { say("请先使用 /aibot settings 配置模型和登录。"); return EventResult.interruptFalse(); }
            if (busy) { say("正在处理上一条消息，请稍候。"); return EventResult.interruptFalse(); }
            if (prompt.length() > 2000) { say("消息太长（最多2000字）。"); return EventResult.interruptFalse(); }
            askAgent(prompt,NetworkManager.canServerReceive(Wire.Up.TYPE));
            return EventResult.interruptFalse();
        });
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> { generation++; toolResults.values().forEach(f->f.completeExceptionally(new InterruptedException()));toolResults.clear(); settingsDelay = 0; });
        ClientTickEvent.CLIENT_POST.register(mc -> {
            if (settingsDelay > 0 && --settingsDelay == 0 && mc.player != null)
                mc.setScreen(new SettingsScreen(null));

        });
        ClientLifecycleEvent.CLIENT_STOPPING.register(mc -> connection.logout());
    }
    public static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.player != null) mc.player.displayClientMessage(Component.literal("[" + settings.name + "] " + text), false); });
    }
    private static void askAgent(String prompt,boolean serverMod) {
        Minecraft mc=Minecraft.getInstance();var session=mc.getConnection();var level=mc.level;
        int run=++generation;busy=true;say("Pi Agent正在处理……");
        ClientSettings snapshot=AIBotAssist.JSON.fromJson(AIBotAssist.JSON.toJson(settings),ClientSettings.class);
        snapshot.gameLanguage=mc.getLanguageManager().getSelected();
        worker.submit(()->{
            try {
                PiBridge.run(snapshot,prompt,serverMod,connection,(name,args)->{
                    if(generation!=run)throw new InterruptedException();
                    String call=java.util.UUID.randomUUID().toString();
                    CompletableFuture<JsonObject> future=new CompletableFuture<>();toolResults.put(call,future);
                    JsonObject packet=new JsonObject();packet.addProperty("call",call);
                    packet.addProperty("action",name.equals("observe_world")?"agent_observe":name.equals("execute_task")?"agent_execute":"agent_tool");
                    if(AgentToolCatalog.NAMES.contains(name)){packet.addProperty("tool",name);packet.add("args",args);}
                    if(name.equals("execute_task")) {packet.addProperty("id",args.get("token").getAsString());Plan checked=Plan.parse(args.get("plan").toString());packet.addProperty("plan",AIBotAssist.JSON.toJson(new Plan("",checked.blocks(),checked.commands(),checked.removals())));}
                    mc.execute(()->{
                        if(generation!=run || mc.getConnection()!=session || mc.level!=level){future.completeExceptionally(new InterruptedException());return;}
                        NetworkManager.sendToServer(new Wire.Up(packet.toString()));
                    });
                    try{return future.get(100,TimeUnit.SECONDS);}finally{toolResults.remove(call);}
                },text->mc.execute(()->{if(generation==run && mc.getConnection()==session && mc.level==level)say(text);}),()->generation!=run);
            }catch(Exception e){mc.execute(()->{if(generation==run && mc.getConnection()==session)say("Pi任务停止："+PiDiagnostics.explain(e));});}
            finally{mc.execute(()->busy=false);}
        });
    }
}
