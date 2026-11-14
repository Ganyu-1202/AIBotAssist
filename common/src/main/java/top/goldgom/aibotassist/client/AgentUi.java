package top.goldgom.aibotassist.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class AgentUi {
    private AgentUi() {}
    public static Component tr(String key,Object... args){return Component.translatable("aibotassist."+key,args);}
    public static Component status() {
        if(!ClientSessions.ready())return tr("status.loading_session");
        var work=ClientMod.chat.work();
        if(work.phase()==AgentChatState.Phase.IDLE || ((work.phase()==AgentChatState.Phase.COMPLETED || work.phase()==AgentChatState.Phase.CANCELLED) && System.currentTimeMillis()-work.updated()>10000)) {
            if(!ClientServerApi.shared() && !ClientMod.connection.ready())return tr("status.signed_out");
            if(ClientServerApi.shared()?!ClientServerApi.policy().ready():ClientMod.settings.model.isBlank())return tr("status.model_missing");
            return tr("status.idle");
        }
        if(work.phase()==AgentChatState.Phase.TOOL)return tr("status.tool",tr("tool."+work.tool()));
        return tr("status."+work.phase().name().toLowerCase(java.util.Locale.ROOT));
    }
    public static int color(){return ClientMod.chat.work().phase()==AgentChatState.Phase.FAILED?0xFFFFB56B:ClientMod.isBusy()?0xFF7CCBFF:0xFF91E3B0;}
    public static Component details() {
        var work=ClientMod.chat.work();
        if(work.started()==0)return Component.literal(ClientServerApi.shared()?ClientServerApi.policy().model():ClientMod.settings.model);
        long end=work.active()?System.currentTimeMillis():work.updated();
        return tr("status.details",work.modelTurns(),work.toolCalls(),Math.max(0,(end-work.started())/1000));
    }
    public static void renderHud(GuiGraphics graphics) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || mc.options.hideGui || !ClientMod.settings.hudEnabled || mc.screen!=null)return;
        int left=4,top=graphics.guiHeight()-18;
        if(top<0)return;
        int alpha=(int)Math.round(ClientMod.settings.hudOpacity*255);
        graphics.fill(left,top,left+14,top+14,(alpha<<24)|0x101924);
        int tint=color();
        boolean ready=ClientServerApi.shared()?ClientServerApi.policy().ready():ClientMod.connection.ready()&&!ClientMod.settings.model.isBlank();
        ready=ready&&ClientSessions.ready();
        if(!ClientMod.isBusy()&&!ready)tint=0xFF87909D;
        // A 10x10 robot face, with blinking eyes while working.
        graphics.fill(left+3,top+4,left+11,top+11,tint);
        graphics.fill(left+6,top+2,left+8,top+4,tint);
        int eye=ClientMod.isBusy()&&(System.currentTimeMillis()/350)%2==0?tint:0xFF101924;
        graphics.fill(left+4,top+6,left+5,top+8,eye);
        graphics.fill(left+9,top+6,left+10,top+8,eye);
        if(ClientMod.chat.work().phase()==AgentChatState.Phase.FAILED)graphics.fill(left+6,top+8,left+8,top+10,0xFF101924);
    }

    public static void drawClipped(GuiGraphics graphics,Component text,int x,int y,int width,int color) {
        var font=Minecraft.getInstance().font;
        graphics.drawString(font,font.plainSubstrByWidth(text.getString(),Math.max(1,width)),x,y,color);
    }
}
