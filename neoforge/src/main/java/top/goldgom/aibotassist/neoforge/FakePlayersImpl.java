package top.goldgom.aibotassist.neoforge;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.common.util.FakePlayer;

public final class FakePlayersImpl {
    public static ServerPlayer create(ServerLevel level,GameProfile profile){
        return new FakePlayer(level,profile){@Override public int requestedViewDistance(){return 2;}};
    }
}
