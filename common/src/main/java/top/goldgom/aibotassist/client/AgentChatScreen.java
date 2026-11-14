package top.goldgom.aibotassist.client;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

public final class AgentChatScreen extends Screen {
    private record Line(FormattedCharSequence text,int color) {}
    private EditBox input;
    private Button send,stop,clear;
    private int left,right,logTop,logBottom,scroll;
    private boolean waitingForSession;
    private long cachedRevision=-1;
    private int cachedWidth;
    private List<Line> lines=List.of();
    public AgentChatScreen(){super(AgentUi.tr("chat.title"));}
    protected void init() {
        saveDraft();waitingForSession=!ClientSessions.ready();
        int panelWidth=Math.min(720,width-16);left=(width-panelWidth)/2;right=left+panelWidth;
        logTop=70;logBottom=height-64;
        addRenderableWidget(Button.builder(AgentUi.tr("chat.settings"),b->{saveDraft();minecraft.setScreen(new SettingsScreen(this));}).bounds(right-216,12,52,20).build());
        addRenderableWidget(Button.builder(hudLabel(),b->{
            if(ClientMod.configureHud(!ClientMod.settings.hudEnabled,ClientMod.settings.hudOpacity))b.setMessage(hudLabel());
        }).bounds(right-160,12,80,20).build());
        clear=addRenderableWidget(Button.builder(AgentUi.tr("chat.clear"),b->{ClientMod.chat.clearMessages();scroll=0;}).bounds(right-76,12,44,20).build());
        addRenderableWidget(Button.builder(Component.literal("×"),b->onClose()).bounds(right-28,12,20,20).build());
        input=new EditBox(font,left+10,height-44,Math.max(40,panelWidth-138),20,AgentUi.tr("chat.input"));
        input.setMaxLength(2000);input.setValue(ClientMod.chat.draft());input.setHint(AgentUi.tr("chat.input"));input.setResponder(ClientMod.chat::draft);
        addRenderableWidget(input);setInitialFocus(input);
        send=addRenderableWidget(Button.builder(AgentUi.tr("chat.send"),b->submit()).bounds(right-122,height-44,54,20).build());
        stop=addRenderableWidget(Button.builder(AgentUi.tr("chat.stop"),b->ClientMod.cancelAgent()).bounds(right-64,height-44,54,20).build());
    }
    private Component hudLabel(){return AgentUi.tr(ClientMod.settings.hudEnabled?"hud.on":"hud.off");}
    private void saveDraft(){if(input!=null&&!waitingForSession)ClientMod.chat.draft(input.getValue());}
    private void submit(){
        if(ClientMod.sendMessage(input.getValue())){input.setValue("");ClientMod.chat.draft("");scroll=0;}
        setFocused(input);
    }
    private int visibleLines(){return Math.max(1,(logBottom-logTop-12)/11);}
    private void updateLines() {
        int wrapWidth=Math.max(20,right-left-32);long revision=ClientMod.chat.revision();
        if(cachedRevision==revision && cachedWidth==wrapWidth)return;
        int before=lines.size();Deque<Line> next=new ArrayDeque<>();
        for(var message:ClientMod.chat.messages()) {
            String speaker=switch(message.role()){case USER->AgentUi.tr("chat.you").getString();case ASSISTANT->ClientMod.settings.name;case NOTICE->AgentUi.tr("chat.notice").getString();};
            int color=switch(message.role()){case USER->0xFFA8D8FF;case ASSISTANT->0xFFF2F5FA;case NOTICE->0xFFA9B5C6;};
            for(var line:font.split(Component.literal(speaker+": "+message.text()),wrapWidth)){
                next.addLast(new Line(line,color));while(next.size()>2048)next.removeFirst();
            }
            next.addLast(new Line(FormattedCharSequence.EMPTY,color));
        }
        lines=List.copyOf(next);cachedRevision=revision;cachedWidth=wrapWidth;
        if(scroll>0)scroll+=lines.size()-before;
        scroll=Math.max(0,Math.min(scroll,Math.max(0,lines.size()-visibleLines())));
    }
    @Override
    public void renderBackground(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        // Screen.render runs this before widgets. Blur only the world, never the chat text.
        super.renderBackground(graphics,mouseX,mouseY,delta);
        graphics.fill(left,6,right,height-6,0xDA101924);
        graphics.fill(left,6,left+3,height-6,0xFF4E99C4);
        graphics.fill(left+8,logTop,right-8,logBottom,0x60101621);
    }
    public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        if(waitingForSession&&ClientSessions.ready()){input.setValue(ClientMod.chat.draft());waitingForSession=false;}
        clear.active=ClientSessions.ready();input.setEditable(ClientSessions.ready());
        send.active=ClientSessions.ready()&&!ClientMod.isBusy() && !input.getValue().isBlank();stop.active=ClientMod.isBusy();
        super.render(graphics,mouseX,mouseY,delta);
        AgentUi.drawClipped(graphics,title,left+12,18,right-left-242,0xFFF3F7FF);
        AgentUi.drawClipped(graphics,AgentUi.status(),left+12,40,right-left-24,AgentUi.color());
        AgentUi.drawClipped(graphics,AgentUi.details(),left+12,54,right-left-24,0xFFB1BED0);
        updateLines();
        graphics.enableScissor(left+9,logTop+3,right-9,logBottom-3);
        if(lines.isEmpty())graphics.drawWordWrap(font,AgentUi.tr("chat.empty"),left+16,logTop+12,Math.max(20,right-left-32),0xFFB1BED0);
        int end=Math.max(0,lines.size()-scroll),start=Math.max(0,end-visibleLines());
        for(int i=start;i<end;i++)graphics.drawString(font,lines.get(i).text(),left+16,logTop+6+(i-start)*11,lines.get(i).color());
        graphics.disableScissor();
        if(lines.size()>visibleLines()) {
            int track=logBottom-logTop-8,thumb=Math.max(8,track*visibleLines()/lines.size());
            int thumbY=logTop+4+(track-thumb)*(Math.max(0,lines.size()-visibleLines()-scroll))/Math.max(1,lines.size()-visibleLines());
            graphics.fill(right-12,thumbY,right-10,thumbY+thumb,0xA080B7DB);
        }
        AgentUi.drawClipped(graphics,AgentUi.tr("chat.hint"),left+12,height-18,right-left-24,0xFF93A5BB);
    }
    public boolean mouseScrolled(double mouseX,double mouseY,double horizontal,double vertical) {
        if(mouseX>=left && mouseX<=right && mouseY>=logTop && mouseY<=logBottom){
            scroll=Math.max(0,Math.min(scroll+(int)(vertical*3),Math.max(0,lines.size()-visibleLines())));return true;
        }
        return super.mouseScrolled(mouseX,mouseY,horizontal,vertical);
    }
    public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_ENTER || key==GLFW.GLFW_KEY_KP_ENTER){if(!ClientMod.isBusy())submit();return true;}
        if(key==GLFW.GLFW_KEY_PAGE_UP || key==GLFW.GLFW_KEY_PAGE_DOWN){
            scroll=Math.max(0,Math.min(scroll+(key==GLFW.GLFW_KEY_PAGE_UP?1:-1)*visibleLines(),Math.max(0,lines.size()-visibleLines())));return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
    public void removed(){saveDraft();super.removed();}
    public void onClose(){saveDraft();minecraft.setScreen(null);}
    public boolean isPauseScreen(){return false;}
}
