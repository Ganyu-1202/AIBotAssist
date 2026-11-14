package top.goldgom.aibotassist.fabric;

import com.mojang.authlib.GameProfile;
import java.util.OptionalInt;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.stats.Stat;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/** No socket, login, operator grant or player-data load. */
public final class FakePlayersImpl {
    public static ServerPlayer create(ServerLevel level,GameProfile profile){return new Actor(level,profile);}
    private static final class Actor extends ServerPlayer {
        Actor(ServerLevel level,GameProfile profile){
            super(level.getServer(),level,profile,ClientInformation.createDefault());
            Connection dummy=new Connection(PacketFlow.SERVERBOUND){@Override public void setListenerForServerboundHandshake(PacketListener listener){}};
            connection=new ServerGamePacketListenerImpl(level.getServer(),dummy,this,CommonListenerCookie.createInitial(profile,false)){
                @Override public void send(Packet<?> packet){}
                @Override public void send(Packet<?> packet,PacketSendListener listener){}
                @Override public void tick(){}
                @Override public void disconnect(Component reason){}
            };
        }
        @Override public void tick(){}
        @Override public int requestedViewDistance(){return 2;}
        @Override public void awardStat(Stat<?> stat,int count){}
        @Override public void displayClientMessage(Component text,boolean overlay){}
        @Override public boolean isInvulnerableTo(DamageSource source){return true;}
        @Override public boolean canHarmPlayer(Player player){return false;}
        @Override public void die(DamageSource source){}
        @Override public OptionalInt openMenu(MenuProvider menu){return OptionalInt.empty();}
    }
}
