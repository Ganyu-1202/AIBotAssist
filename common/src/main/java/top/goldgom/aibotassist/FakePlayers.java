package top.goldgom.aibotassist;

import com.mojang.authlib.GameProfile;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.server.level.*;

public final class FakePlayers {
    private FakePlayers(){}
    @ExpectPlatform
    public static ServerPlayer create(ServerLevel level,GameProfile profile){throw new AssertionError();}
}
