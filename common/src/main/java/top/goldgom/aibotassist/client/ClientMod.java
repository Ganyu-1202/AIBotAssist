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
    private static String waiting;
    private static long waitSince;
    private static boolean busy;
    private static int settingsDelay;
    private static void openSettings() { settingsDelay = 2; }
    public static void init() {
        settings = ClientSettings.load();
        ClientCommandRegistrationEvent.EVENT.register((dispatcher, context) ->
            dispatcher.register(ClientCommandRegistrationEvent.literal("aibot")
                .then(ClientCommandRegistrationEvent.literal("settings").executes(c -> { openSettings(); return 1; }))));
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, Wire.Down.TYPE, Wire.Down.CODEC, (packet, ctx) -> ctx.queue(() -> context(packet.json())));
        ClientChatEvent.SEND.register((message, component) -> {
            String mention = "@" + settings.name;
            if (!message.equals(mention) && !message.startsWith(mention + " ") && !message.startsWith(mention + "　")) return EventResult.pass();
            String prompt = message.substring(mention.length()).trim();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.gui.getChat().addMessage(Component.literal("<" + mc.player.getGameProfile().getName() + "> " + message));
            if (prompt.isEmpty() || prompt.equals("设置")) { say("请使用 /aibot settings 配置 AI。"); return EventResult.interruptFalse(); }
            if (prompt.equals("取消")) { waiting = null; if (mc.getConnection() != null) mc.getConnection().sendCommand("aibot cancel"); return EventResult.interruptFalse(); }
            if (!connection.ready() || settings.model.isBlank()) { say("请先使用 /aibot settings 配置模型和登录。"); return EventResult.interruptFalse(); }
            if (busy || (waiting != null && System.currentTimeMillis() - waitSince < 10_000)) { say("正在处理上一条消息，请稍候。"); return EventResult.interruptFalse(); }
            if (prompt.length() > 2000) { say("消息太长（最多2000字）。"); return EventResult.interruptFalse(); }
            if (!NetworkManager.canServerReceive(Wire.Up.TYPE)) {
                JsonObject context = new JsonObject(); context.addProperty("mode", "spectator"); context.add("inventory", new JsonObject());
                ask(prompt, context, false);
            } else {
                waiting = prompt; waitSince = System.currentTimeMillis();
                JsonObject request = new JsonObject(); request.addProperty("action", "context");
                NetworkManager.sendToServer(new Wire.Up(request.toString()));
            }
            return EventResult.interruptFalse();
        });
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> { waiting = null; settingsDelay = 0; });
        ClientTickEvent.CLIENT_POST.register(mc -> {
            if (settingsDelay > 0 && --settingsDelay == 0 && mc.player != null)
                mc.setScreen(new SettingsScreen(null));
            if (waiting != null && System.currentTimeMillis() - waitSince >= 10_000) {
                waiting = null; say("服务器上下文请求超时，请重试。");
            }
        });
        ClientLifecycleEvent.CLIENT_STOPPING.register(mc -> connection.logout());
    }
    public static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.player != null) mc.player.displayClientMessage(Component.literal("[" + settings.name + "] " + text), false); });
    }
    private static void context(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.get("action").getAsString().equals("context") || waiting == null || System.currentTimeMillis() - waitSince > 10_000) return;
            String prompt = waiting; waiting = null; ask(prompt, o, true);
        } catch (Exception e) { waiting = null; say("服务器上下文无效。"); }
    }
    private static void ask(String prompt, JsonObject context, boolean serverMod) {
        Minecraft mc = Minecraft.getInstance();
        var session = mc.getConnection();
        var level = mc.level;
        boolean spectator = context.get("mode").getAsString().equals("spectator");
        busy = true; say("正在思考……");
        ClientSettings snapshot = AIBotAssist.JSON.fromJson(AIBotAssist.JSON.toJson(settings), ClientSettings.class);
        worker.submit(() -> {
            try {
                Plan plan = connection.ask(snapshot, prompt, context.toString());
                mc.execute(() -> {
                    if (mc.getConnection() != session || mc.level != level || mc.player == null) return;
                    say(plan.reply());
                    if (plan.hasActions()) {
                        if (!serverMod || spectator) { say("当前仅允许聊天，未执行游戏内任务。"); return; }
                        JsonObject packet = new JsonObject(); packet.addProperty("action", "plan"); packet.addProperty("id", context.get("id").getAsString());
                        packet.addProperty("plan", AIBotAssist.JSON.toJson(new Plan("", plan.blocks(), plan.commands(), plan.removals())));
                        NetworkManager.sendToServer(new Wire.Up(packet.toString()));
                        say("任务已提交，服务器检查通过后自动执行。");
                    }
                });
            } catch (Exception e) {
                mc.execute(() -> { if (mc.getConnection() == session && mc.level == level) say("请求失败：检查模型名、密钥/登录、网络与 JSON 输出支持。未执行任何建造；敏感响应不会显示。"); });
            } finally { mc.execute(() -> busy = false); }
        });
    }
}
