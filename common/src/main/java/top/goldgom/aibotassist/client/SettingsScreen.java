package top.goldgom.aibotassist.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.Util;

public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final String[] labels={"name","base","model","key","proxy","oauth","runner","runtime"};
    private final String[] values=new String[8];
    private final EditBox[] fields=new EditBox[8];
    private int page,startY,rowStep,left,formWidth;
    private Component status=tr("initial");
    private static Component tr(String key){return Component.translatable("aibotassist.settings."+key);}
    public SettingsScreen(Screen parent) {
        super(Component.literal("AI Bot Assist"));this.parent=parent;
        ClientSettings s=ClientMod.settings;
        String[] initial={s.name,s.baseUrl,s.model,"",s.proxy,s.clientId,s.piExecutable,s.piRuntime};
        System.arraycopy(initial,0,values,0,8);
    }
    private void capture(){for(int i=0;i<8;i++)if(fields[i]!=null)values[i]=fields[i].getValue();}
    protected void init() {
        capture();java.util.Arrays.fill(fields,null);
        formWidth=Math.min(400,width-16);left=(width-formWidth)/2;
        startY=50;rowStep=Math.max(24,Math.min(32,(height-135)/5));
        addRenderableWidget(Button.builder(tr("connection"),b->{capture();page=0;rebuildWidgets();}).bounds(left,25,formWidth/2-2,20).build());
        addRenderableWidget(Button.builder(tr("advanced"),b->{capture();page=1;rebuildWidgets();}).bounds(left+formWidth/2+2,25,formWidth/2-2,20).build());
        int first=page==0?0:5,last=page==0?5:8;
        for(int i=first;i<last;i++) {
            int y=startY+(i-first)*rowStep;
            int labelWidth=Math.min(110,formWidth/3);
            EditBox e=new EditBox(font,left+labelWidth,y,formWidth-labelWidth,20,tr(labels[i]));
            e.setMaxLength(i==0?24:i==3?512:i==7?500:250);e.setValue(values[i]);fields[i]=e;addRenderableWidget(e);
            if(i==3)e.setFormatter((text,offset)->FormattedCharSequence.forward("•".repeat(text.length()),net.minecraft.network.chat.Style.EMPTY));
        }
        int buttonWidth=(formWidth-6)/2;
        addRenderableWidget(Button.builder(tr("done"),b->{if(!save())return;onClose();ClientMod.say(Component.translatable("aibotassist.settings.saved_chat",ClientMod.settings.name).getString());}).bounds(left,height-28,buttonWidth,20).build());
        addRenderableWidget(Button.builder(tr("cancel"),b->onClose()).bounds(left+buttonWidth+6,height-28,buttonWidth,20).build());
        if(page==1) {
            addRenderableWidget(Button.builder(tr("login"),b->{
                if(!save())return;
                try{Util.getPlatform().openUri(ClientMod.connection.login(ClientMod.settings,ClientMod::say));status=tr("auth_wait");}
                catch(Exception e){status=tr("auth_error");}
            }).bounds(left,height-77,buttonWidth,20).build());
            addRenderableWidget(Button.builder(tr("logout"),b->{ClientMod.connection.logout();values[3]="";status=tr("cleared");}).bounds(left+buttonWidth+6,height-77,buttonWidth,20).build());
        }
    }
    private boolean save(){
        capture();
        try{
            ClientSettings s=new ClientSettings();s.name=values[0].trim();s.baseUrl=values[1].trim();s.model=values[2].trim();s.proxy=values[4].trim();s.clientId=values[5].trim();s.piExecutable=values[6].trim();s.piRuntime=values[7].trim();s.save();
            if(!values[3].isBlank()){ClientMod.connection.setKey(values[3]);values[3]="";if(fields[3]!=null)fields[3].setValue("");}
            ClientMod.settings=s;status=tr("saved");return true;
        }catch(Exception e){status=tr("error");return false;}
    }
    public void render(GuiGraphics g,int mx,int my,float dt){
        super.render(g,mx,my,dt);g.drawCenteredString(font,title,width/2,8,0xFFFFFF);
        int first=page==0?0:5,last=page==0?5:8;
        for(int i=first;i<last;i++)g.drawString(font,tr(labels[i]),left,startY+(i-first)*rowStep+6,0xDDDDDD);
        g.drawWordWrap(font,status,left,height-52,formWidth,0xCCCCCC);
    }
    public boolean isPauseScreen(){return false;}
    public void onClose(){values[3]="";if(fields[3]!=null)fields[3].setValue("");minecraft.setScreen(parent);}
}
