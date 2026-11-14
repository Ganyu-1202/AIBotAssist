package top.goldgom.aibotassist;

import com.google.gson.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import dev.architectury.event.events.common.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.Commands;

public final class AIBotAssist {
    public static final Gson JSON = new Gson();
    public static void init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, Wire.Up.TYPE, Wire.Up.CODEC, (packet, context) -> {
            context.queue(() -> {
                if (context.getPlayer() instanceof ServerPlayer player) BuildService.receive(player, packet.json());
            });
        });
        if (Platform.getEnvironment() == Env.SERVER)
            NetworkManager.registerS2CPayloadType(Wire.Down.TYPE, Wire.Down.CODEC);
        LifecycleEvent.SERVER_STARTING.register(server -> {BlockPolicy.reload();ServerApiService.start(server);});
        PlayerEvent.PLAYER_JOIN.register(p->{ServerApiService.sendPolicy(p);FakePlayerService.sendProfiles(p);});
        PlayerEvent.PLAYER_QUIT.register(p->{ServerApiService.leave(p);FakePlayerService.remove(p.getUUID());});
        TickEvent.SERVER_POST.register(BuildService::tick);
        LifecycleEvent.SERVER_STOPPED.register(server -> {BuildService.clear();ServerApiService.clear();});
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            dispatcher.register(Commands.literal("aibot")
                .then(Commands.literal("reload").requires(source -> source.hasPermission(2)).executes(c -> { BlockPolicy.reload(); c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("AI block policy reloaded"), false); return 1; }))
                .then(Commands.literal("api").requires(source -> source.hasPermission(2))
                    .then(Commands.literal("mode")
                        .then(Commands.literal("independent").executes(c -> apiMode(c.getSource(),"independent")))
                        .then(Commands.literal("shared").executes(c -> apiMode(c.getSource(),"shared"))))
                    .then(Commands.literal("reload").executes(c -> {boolean ok=ServerApiService.reload(c.getSource().getServer());c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(ok?"AI API配置已重新加载":"API配置无效，未应用；请检查配置文件"),false);return ok?1:0;}))
                    .executes(c -> {c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("AI API模式："+ServerApiService.mode()+"；设置界面 → 服务器API，或编辑config/aibotassist-server-api.json后/aibot api reload"),false);return 1;}))
                .then(Commands.literal("settings").executes(c -> { BuildService.tell(c.getSource().getPlayerOrException(), "设置界面由客户端模组打开；若未弹出，请检查客户端版本。"); return 1; }))
                .then(Commands.literal("cancel").executes(c -> { BuildService.cancel(c.getSource().getPlayerOrException()); return 1; }))
                .executes(c -> { BuildService.tell(c.getSource().getPlayerOrException(), "设置：/aibot settings；聊天：@AI 你好。任务检查通过后自动执行；取消 /aibot cancel。AI 名称可在客户端设置中更改。"); return 1; }));
        });
    }
    private static int apiMode(net.minecraft.commands.CommandSourceStack source,String mode){
        boolean ok=ServerApiService.setMode(source.getServer(),mode);
        source.sendSuccess(()->net.minecraft.network.chat.Component.literal(ok?"AI API模式已切换为 "+mode:"切换失败；共享模式需先配置模型和API密钥"),false);return ok?1:0;
    }
}
