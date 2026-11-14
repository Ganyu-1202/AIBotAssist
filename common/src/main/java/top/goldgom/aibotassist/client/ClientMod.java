package top.goldgom.aibotassist.client;

import com.google.gson.*;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import top.goldgom.aibotassist.*;
import java.util.concurrent.*;

public final class ClientMod {
    public static ClientSettings settings;
    public static final AiConnection connection = new AiConnection();
    public static final AgentChatState chat = new AgentChatState();
    public static final KeyMapping CHAT_KEY=new KeyMapping("key.aibotassist.chat",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_K,"key.categories.aibotassist");
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "aibot-model"); t.setDaemon(true); return t; });
    private static volatile boolean busy;
    private static volatile int generation;
    private static final java.util.Map<String,CompletableFuture<JsonObject>> toolResults=new java.util.concurrent.ConcurrentHashMap<>();
    private static final PacketChunks toolDownloads=new PacketChunks();
    private static final java.util.UUID DOWNLOAD_OWNER=java.util.UUID.randomUUID();
    private static int settingsDelay,chatDelay;
    private static void openSettings() { settingsDelay = 2; }
    public static void init() {
        settings = ClientSettings.load();
        KeyMappingRegistry.register(CHAT_KEY);
        ClientGuiEvent.RENDER_POST.register((screen,graphics,x,y,delta)->ClientVision.onScreenRender(screen,graphics));
        ClientGuiEvent.RENDER_HUD.register((graphics,delta)->{ClientVision.onHudRender(graphics);AgentUi.renderHud(graphics);});
        ClientCommandRegistrationEvent.EVENT.register((dispatcher, context) ->
            dispatcher.register(ClientCommandRegistrationEvent.literal("aibot")
                .then(ClientCommandRegistrationEvent.literal("chat").executes(c -> {chatDelay=2;return 1;}))
                .then(ClientCommandRegistrationEvent.literal("settings").executes(c -> { openSettings(); return 1; }))));
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, Wire.Down.TYPE, Wire.Down.CODEC, (packet, ctx) -> ctx.queue(() -> {
            JsonObject reply=JsonParser.parseString(packet.json()).getAsJsonObject();
            if(reply.has("action")&&reply.get("action").getAsString().equals("fragment")&&reply.has("id")&&toolResults.containsKey(reply.get("id").getAsString())) {
                try{String complete=toolDownloads.accept(DOWNLOAD_OWNER,reply,System.currentTimeMillis());if(complete==null)return;reply=JsonParser.parseString(complete).getAsJsonObject();}
                catch(IllegalArgumentException invalid){toolResults.values().forEach(f->f.completeExceptionally(invalid));toolResults.clear();return;}
            }
            if(ClientServerApi.receive(reply))return;
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
            sendMessage(prompt);
            return EventResult.interruptFalse();
        });
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            ClientServerApi.reset();
            Minecraft mc=Minecraft.getInstance();String identity;
            if(mc.getSingleplayerServer()!=null)identity="single:"+mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
            else if(mc.getCurrentServer()!=null)identity="server:"+mc.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT);
            else identity="connection:"+java.util.UUID.randomUUID();
            ClientSessions.join(identity,player.getUUID(),ClientServerApi.serverPresent());
            if(ClientServerApi.serverPresent())ClientServerApi.refresh();
            identity+="|player:"+mc.getUser().getProfileId();
            connection.enter(identity,settings);
            if(!ClientServerApi.serverPresent() && connection.ready() && settings.model.isBlank())loadModels(models->say("已恢复登录，已选择模型："+settings.model),ClientMod::say);
        });
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> { ClientSessions.leave();toolDownloads.clear();connection.logout();ClientServerApi.reset(); generation++;busy=false;ClientVision.cancel();chat.reset(); toolResults.values().forEach(f->f.completeExceptionally(new InterruptedException()));toolResults.clear(); settingsDelay = 0;chatDelay=0; });
        ClientTickEvent.CLIENT_POST.register(mc -> {
            ClientSessions.tick();
            while(CHAT_KEY.consumeClick())if(mc.player!=null && mc.screen==null)mc.setScreen(new AgentChatScreen());
            if(chatDelay>0 && --chatDelay==0 && mc.player!=null)mc.setScreen(new AgentChatScreen());
            if (settingsDelay > 0 && --settingsDelay == 0 && mc.player != null)
                mc.setScreen(new SettingsScreen(null));

        });
        ClientLifecycleEvent.CLIENT_STOPPING.register(mc -> {ClientSessions.stop();connection.logout();});
    }
    public static void say(String text) {
        display(text,AgentChatState.Role.NOTICE);
    }
    private static void display(String text,AgentChatState.Role role) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.player != null) {chat.append(role,text);mc.player.displayClientMessage(Component.literal("[" + settings.name + "] " + text), false);} });
    }
    public static boolean isBusy(){return busy;}
    public static boolean sendMessage(String value) {
        String prompt=value.trim();
        if(prompt.isEmpty())return false;
        if(!ClientSessions.ready()){say("正在加载当前存档与玩家UUID的会话，请稍候再发送。");return false;}
        if(prompt.equals("设置")){Minecraft.getInstance().setScreen(new SettingsScreen(Minecraft.getInstance().screen));return true;}
        if(prompt.equals("取消")){chat.append(AgentChatState.Role.USER,prompt);cancelAgent();return true;}
        if(ClientServerApi.serverPresent()&&!ClientServerApi.policy().known()){ClientServerApi.refresh();say("正在同步服务器API策略，请稍后发送；若持续失败请更新客户端和服务端。");return false;}
        if(!ClientServerApi.shared()&&!connection.ready()){say("尚未登录，请使用设置登录或填写 API 密钥。");return false;}
        if(busy){say("正在处理上一条消息，请稍候，或停止当前任务。");return false;}
        if(prompt.length()>2000){say("消息太长（最多2000字）。");return false;}
        String contextualPrompt=chat.promptWithHistory(prompt);chat.append(AgentChatState.Role.USER,prompt);chat.begin();
        if(!ClientServerApi.shared()&&settings.model.isBlank()) {
            busy=true;say("已登录，正在获取可用模型……");
            loadModels(models->{busy=false;askAgent(contextualPrompt,NetworkManager.canServerReceive(Wire.Up.TYPE));},error->{busy=false;chat.finish(AgentChatState.Phase.FAILED);say(error);});
        } else askAgent(contextualPrompt,NetworkManager.canServerReceive(Wire.Up.TYPE));
        return true;
    }
    public static void cancelAgent() {
        generation++;busy=false;toolDownloads.clear();ClientVision.cancel();ClientServerApi.cancelModels();chat.finish(AgentChatState.Phase.CANCELLED);
        toolResults.values().forEach(f->f.completeExceptionally(new InterruptedException()));toolResults.clear();
        Minecraft mc=Minecraft.getInstance();
        if(mc.getConnection()!=null && NetworkManager.canServerReceive(Wire.Up.TYPE))mc.getConnection().sendCommand("aibot cancel");
        say("已停止当前 AI 请求；已经执行的游戏操作保留。");
    }
    public static boolean configureHud(boolean enabled,double opacity) {
        ClientSettings next=AIBotAssist.JSON.fromJson(AIBotAssist.JSON.toJson(settings),ClientSettings.class);
        next.hudEnabled=enabled;next.hudOpacity=opacity;
        try{next.save();settings=next;return true;}catch(Exception e){say("状态面板设置保存失败，请重试。");return false;}
    }
    /** Runs discovery off the render thread; applies results only to the initiating world/configuration. */
    public static void loadModels(java.util.function.Consumer<java.util.List<String>> completed,java.util.function.Consumer<String> failed) {
        if(ClientServerApi.shared()){var id=ClientServerApi.policy().model();completed.accept(java.util.List.of(id));return;}
        Minecraft mc=Minecraft.getInstance();var session=mc.getConnection();var level=mc.level;
        int run=generation;long version=connection.sessionVersion();
        ClientSettings snapshot=AIBotAssist.JSON.fromJson(AIBotAssist.JSON.toJson(settings),ClientSettings.class);
        worker.submit(()->{
            try {
                var models=connection.listModels(snapshot);
                mc.execute(()->{
                    if(generation!=run || mc.getConnection()!=session || mc.level!=level)return;
                    if(connection.sessionVersion()!=version || !settings.baseUrl.equals(snapshot.baseUrl) || !settings.clientId.equals(snapshot.clientId) || !settings.group.equals(snapshot.group) || !settings.modelProtocol.equals(snapshot.modelProtocol)) {
                        failed.accept("登录或 API 配置已改变，请重新获取模型。");return;
                    }
                    if(models.isEmpty()){failed.accept("当前账户没有兼容聊天接口的模型；登录凭据已保留，请检查模型权限。");return;}
                    try {
                        if(!models.contains(settings.model)) {
                            ClientSettings next=AIBotAssist.JSON.fromJson(AIBotAssist.JSON.toJson(settings),ClientSettings.class);
                            next.model=AgentModels.select(next.model,models);next.save();settings=next;
                        }
                        completed.accept(models);
                    } catch(Exception e){failed.accept("模型配置保存失败；登录凭据已保留，请在设置中选择模型后重试。");}
                });
            } catch(Exception e) {
                mc.execute(()->{if(generation==run && mc.getConnection()==session && mc.level==level)
                    failed.accept("可用模型获取失败，登录凭据已保留。请检查网络、账户模型权限，或手动填写模型 ID。");});
            }
        });
    }
    private static void askAgent(String prompt,boolean serverMod) {
        Minecraft mc=Minecraft.getInstance();var session=mc.getConnection();var level=mc.level;
        String buildSession=java.util.UUID.randomUUID().toString();
        int run=++generation;busy=true;say("Pi Agent正在处理……");
        ClientSettings snapshot=AIBotAssist.JSON.fromJson(AIBotAssist.JSON.toJson(settings),ClientSettings.class);
        snapshot.gameLanguage=mc.getLanguageManager().getSelected();
        var apiPolicy=ClientServerApi.policy();boolean shared=ClientServerApi.shared();
        if(shared)snapshot.model=apiPolicy.model();
        worker.submit(()->{
            try {
                PiBridge.run(snapshot,prompt,serverMod,(messages,tools)->{
                    if(generation!=run||mc.getConnection()!=session||mc.level!=level)throw new InterruptedException();
                    return shared?ClientServerApi.turn(apiPolicy.revision(),messages,tools):connection.agentTurn(snapshot,messages,tools);
                },(name,args)->{
                    if(generation!=run)throw new InterruptedException();
                    if(ClientVision.isLocalCapture(name))return ClientVision.capture(args,()->generation==run && mc.getConnection()==session && mc.level==level,name.equals(ClientVision.MAP_TOOL));
                    if(ClientIntegrations.NAMES.contains(name))return ClientIntegrations.request(name,args,()->generation==run && mc.getConnection()==session && mc.level==level);
                    String call=java.util.UUID.randomUUID().toString();
                    CompletableFuture<JsonObject> future=new CompletableFuture<>();
                    JsonObject packet=new JsonObject();packet.addProperty("call",call);packet.addProperty("build_session",buildSession);packet.addProperty("block_limit",snapshot.blockLimit);
                    packet.addProperty("action",name.equals("observe_world")?"agent_observe":name.equals("execute_task")?"agent_execute":"agent_tool");
                    if(AgentToolCatalog.NAMES.contains(name)){packet.addProperty("tool",name);packet.add("args",args);}
                    if(name.equals("execute_task")) {packet.addProperty("id",args.get("token").getAsString());JsonObject plan=args.getAsJsonObject("plan").deepCopy();plan.addProperty("reply","");Plan.parse(plan.toString(),snapshot.blockLimit);packet.addProperty("plan",plan.toString());}
                    java.util.List<String> packets=PacketChunks.encode(packet.toString(),call);toolResults.put(call,future);
                    mc.execute(()->{
                        if(generation!=run || mc.getConnection()!=session || mc.level!=level){future.completeExceptionally(new InterruptedException());return;}
                        try{for(String part:packets)NetworkManager.sendToServer(new Wire.Up(part));}catch(Exception e){future.completeExceptionally(e);}
                    });
                    try{
                        JsonObject result=future.get(100,TimeUnit.SECONDS);
                        if(name.equals("observe_world") && result.has("status") && result.get("status").getAsString().equals("observed")
                            && (!result.has("build_protocol")||result.get("build_protocol").getAsInt()<2))throw new PiDiagnostics.IncompatibleServer();
                        if(name.equals(ClientVision.REMOTE_TOOL)&&result.has("scene_version"))return RemoteSceneRenderer.capture(result,()->generation==run&&mc.getConnection()==session&&mc.level==level);
                        return result;
                    }finally{toolResults.remove(call);}
                },text->mc.execute(()->{if(generation==run && mc.getConnection()==session && mc.level==level)display(text,AgentChatState.Role.ASSISTANT);}),()->generation!=run,
                progress->mc.execute(()->{if(generation==run && mc.getConnection()==session && mc.level==level)chat.progress(progress);}));
                mc.execute(()->{if(generation==run && mc.getConnection()==session && mc.level==level)chat.finish(AgentChatState.Phase.COMPLETED);});
            }catch(Exception e){mc.execute(()->{if(generation==run && mc.getConnection()==session){chat.finish(AgentChatState.Phase.FAILED);say("Pi任务停止："+PiDiagnostics.explain(e));}});}
            finally{mc.execute(()->{if(generation==run)busy=false;});}
        });
    }
}
