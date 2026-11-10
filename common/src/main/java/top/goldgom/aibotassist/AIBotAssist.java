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
        TickEvent.SERVER_POST.register(BuildService::tick);
        LifecycleEvent.SERVER_STOPPED.register(server -> BuildService.clear());
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            dispatcher.register(Commands.literal("aibot")
                .then(Commands.literal("confirm").executes(c -> { BuildService.confirm(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("cancel").executes(c -> { BuildService.cancel(c.getSource().getPlayerOrException()); return 1; }))
                .executes(c -> { BuildService.tell(c.getSource().getPlayerOrException(), "聊天：@AI 你好；设置：@AI 设置；方案确认 /aibot confirm；取消 /aibot cancel。AI 名称可在客户端设置中更改。"); return 1; }));
        });
    }
}
