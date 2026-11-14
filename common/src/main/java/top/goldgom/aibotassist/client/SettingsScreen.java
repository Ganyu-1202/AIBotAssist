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
    private String group,protocol,observedModel;
    private EditBox groupField;
    private int page,startY,rowStep,left,formWidth;
    private boolean hudEnabled;
    private double hudOpacity;
    private String blockLimit;
    private EditBox blockLimitField;
    private boolean visualCalibration;
    private String apiRevision="";
    private Component status=tr("initial");
    private static Component tr(String key){return Component.translatable("aibotassist.settings."+key);}
    public SettingsScreen(Screen parent) {
        super(Component.literal("AI Bot Assist"));this.parent=parent;
        ClientSettings s=ClientMod.settings;group=s.group;protocol=s.modelProtocol;observedModel=s.model;hudEnabled=s.hudEnabled;hudOpacity=s.hudOpacity;blockLimit=Integer.toString(s.blockLimit);visualCalibration=s.visualCalibration;
        String[] initial={s.name,s.baseUrl,s.model,"",s.proxy,s.clientId,s.piExecutable,s.piRuntime};
        System.arraycopy(initial,0,values,0,8);
    }
    private void capture(){if(groupField!=null)group=groupField.getValue();if(blockLimitField!=null)blockLimit=blockLimitField.getValue();for(int i=0;i<8;i++)if(fields[i]!=null)values[i]=fields[i].getValue();}
    protected void init() {
        apiRevision=ClientServerApi.policy().revision();
        capture();groupField=null;blockLimitField=null;java.util.Arrays.fill(fields,null);
        formWidth=Math.min(400,width-16);left=(width-formWidth)/2;
        startY=50;rowStep=Math.max(20,Math.min(32,(height-135)/5));
        String[] tabs={"connection","advanced","interface","building","server_api","provider"};
        int tabWidth=formWidth/tabs.length;
        for(int tab=0;tab<tabs.length;tab++) {
            final int selected=tab;
            addRenderableWidget(Button.builder(tr(tabs[tab]),b->{capture();page=selected;rebuildWidgets();}).bounds(left+tab*tabWidth,25,tabWidth-3,20).build());
        }
        if(page==5) {
            int labelWidth=Math.min(110,formWidth/3);
            groupField=new EditBox(font,left+labelWidth,startY,formWidth-labelWidth-76,20,tr("group"));
            groupField.setMaxLength(64);groupField.setValue(group);addRenderableWidget(groupField);groupField.setEditable(!ClientServerApi.shared());
            addRenderableWidget(Button.builder(tr("groups"),button->{
                if(!save())return;
                button.active=false;status=tr("groups_loading");
                ClientSettings snapshot=top.goldgom.aibotassist.AIBotAssist.JSON.fromJson(top.goldgom.aibotassist.AIBotAssist.JSON.toJson(ClientMod.settings),ClientSettings.class);
                long version=ClientMod.connection.sessionVersion();
                java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return ClientMod.connection.listGroups(snapshot);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}}).whenComplete((groups,error)->minecraft.execute(()->{
                    button.active=true;if(minecraft.screen!=this)return;
                    if(version!=ClientMod.connection.sessionVersion()||!snapshot.baseUrl.equals(ClientMod.settings.baseUrl)||!snapshot.clientId.equals(ClientMod.settings.clientId)){status=tr("error");return;}
                    if(error!=null){status=tr("groups_error");return;}
                    minecraft.setScreen(new ModelSelectionScreen(this,groups,id->{group=id;groupField.setValue(id);save();minecraft.setScreen(this);}));
                }));
            }).bounds(left+formWidth-72,startY,72,20).build()).active=!ClientServerApi.shared();
            addRenderableWidget(Button.builder(protocolLabel(),button->{
                protocol=protocol.equals("auto")?"chat":protocol.equals("chat")?"responses":"auto";button.setMessage(protocolLabel());
            }).bounds(left,startY+28,formWidth,20).build()).active=!ClientServerApi.shared();
        }
        if(page==4) {
            addRenderableWidget(Button.builder(ServerApiScreen.tr("title"),b->{capture();minecraft.setScreen(new ServerApiScreen(this));}).bounds(left,startY+30,formWidth,20).build()).active=ClientServerApi.serverPresent();
        }
        if(page==3) {
            blockLimitField=new EditBox(font,left+formWidth/2,startY,formWidth/2,20,tr("block_limit"));
            blockLimitField.setMaxLength(10);blockLimitField.setFilter(value->value.matches("[0-9]*"));blockLimitField.setValue(blockLimit);addRenderableWidget(blockLimitField);
            addRenderableWidget(Button.builder(tr("unlimited"),b->{blockLimit="0";blockLimitField.setValue(blockLimit);}).bounds(left,startY+27,formWidth/2-3,20).build());
            addRenderableWidget(Button.builder(tr("default_limit"),b->{blockLimit="4096";blockLimitField.setValue(blockLimit);}).bounds(left+formWidth/2+3,startY+27,formWidth/2-3,20).build());
            addRenderableWidget(Button.builder(calibrationLabel(),b->{visualCalibration=!visualCalibration;b.setMessage(calibrationLabel());}).bounds(left,startY+54,formWidth,20).build());
        }
        if(page==2) {
            addRenderableWidget(Button.builder(hudLabel(),b->{hudEnabled=!hudEnabled;b.setMessage(hudLabel());}).bounds(left,startY,formWidth,20).build());
            addRenderableWidget(new AbstractSliderButton(left,startY+28,formWidth,20,Component.empty(),(hudOpacity-0.15)/0.70) {
                {updateMessage();}
                protected void updateMessage(){setMessage(Component.translatable("aibotassist.settings.hud_opacity",Math.round((0.15+value*0.70)*100)));}
                protected void applyValue(){hudOpacity=0.15+value*0.70;}
            });
        }
        int first=page==0?0:page==1?5:8,last=page==0?(ClientServerApi.shared()?1:5):8;
        for(int i=first;i<last;i++) {
            int y=startY+(i-first)*rowStep;
            int labelWidth=Math.min(110,formWidth/3);
            EditBox e=new EditBox(font,left+labelWidth,y,formWidth-labelWidth-(i==2?76:0),20,tr(labels[i]));
            e.setMaxLength(i==0?24:i==3?512:i==7?500:250);e.setValue(values[i]);fields[i]=e;addRenderableWidget(e);
            if(i==3)e.setFormatter((text,offset)->FormattedCharSequence.forward("•".repeat(text.length()),net.minecraft.network.chat.Style.EMPTY));
            if(i==2)addRenderableWidget(Button.builder(tr("models"),button->{
                if(!save())return;
                status=tr("models_loading");button.active=false;
                ClientMod.loadModels(models->{
                    button.active=true;syncModel();status=tr("model_ready").copy().append(ClientMod.settings.model);
                    if(minecraft.screen==this)minecraft.setScreen(new ModelSelectionScreen(this,models,id->{
                        values[2]=id;if(fields[2]!=null)fields[2].setValue(id);save();minecraft.setScreen(this);
                    },true));
                },error->{button.active=true;status=Component.literal(error);});
            }).bounds(left+formWidth-72,y,72,20).build());
        }
        int buttonWidth=(formWidth-6)/2;
        addRenderableWidget(Button.builder(tr("done"),b->{if(!save())return;onClose();ClientMod.say(Component.translatable("aibotassist.settings.saved_chat",ClientMod.settings.name).getString());}).bounds(left,height-28,buttonWidth,20).build());
        addRenderableWidget(Button.builder(tr("cancel"),b->onClose()).bounds(left+buttonWidth+6,height-28,buttonWidth,20).build());
        if(page==0||page==1) {
        Button loginButton=addRenderableWidget(Button.builder(tr("login"),b->{
                capture();values[1]=TokenNestOAuth.API_BASE;values[3]="";
                if(fields[1]!=null)fields[1].setValue(values[1]);
                if(fields[3]!=null)fields[3].setValue("");
                if(!save())return;
                try{Util.getPlatform().openUri(ClientMod.connection.login(ClientMod.settings,message->{
                    minecraft.execute(()->{status=Component.literal(message);ClientMod.say(message);});
                },()->minecraft.execute(()->{
                    status=tr("models_loading");
                    ClientMod.loadModels(models->{syncModel();status=tr("model_ready").copy().append(ClientMod.settings.model);ClientMod.say(status.getString());},error->{status=Component.literal(error);ClientMod.say(error);});
                })));status=tr("auth_wait");}
                catch(Exception e){status=tr("auth_error");}
            }).bounds(left,height-77,buttonWidth,20).build());
        addRenderableWidget(Button.builder(tr("logout"),b->{
            try{ClientMod.connection.forget();values[3]="";if(fields[3]!=null)fields[3].setValue("");status=tr("cleared");}
            catch(Exception e){status=tr("clear_error");}
        }).bounds(left+buttonWidth+6,height-77,buttonWidth,20).build());
        loginButton.active=!ClientServerApi.shared();
        }
        if(page==1) {
            addRenderableWidget(Button.builder(tr("copy_oauth"),b->{capture();minecraft.keyboardHandler.setClipboard(TokenNestOAuth.registrationInfo(values[5]));status=tr("copied_oauth");}).bounds(left,height-103,buttonWidth,20).build());
            addRenderableWidget(Button.builder(tr("cancel_login"),b->{ClientMod.connection.cancelLogin();status=tr("auth_cancelled");}).bounds(left+buttonWidth+6,height-103,buttonWidth,20).build());
        }
    }
    private Component protocolLabel(){return Component.translatable("aibotassist.settings.protocol",protocol);}
    private void reloadChannelModels(){
        if(ClientServerApi.shared()||!ClientMod.connection.ready())return;
        status=tr("models_loading");
        ClientMod.loadModels(models->{syncModel();status=tr("model_ready").copy().append(ClientMod.settings.model);},error->status=Component.literal(error));
    }
    private Component calibrationLabel(){return tr(visualCalibration?"calibration_on":"calibration_off");}
    private Component hudLabel(){return tr(hudEnabled?"hud_enabled":"hud_disabled");}
    private boolean save(){
        syncModel();
        capture();
        try{
            ClientSettings s=new ClientSettings();s.name=values[0].trim();s.baseUrl=values[1].trim();s.model=values[2].trim();s.proxy=values[4].trim();s.clientId=values[5].trim();s.piExecutable=values[6].trim();s.piRuntime=values[7].trim();s.group=group.trim();s.modelProtocol=protocol;s.hudEnabled=hudEnabled;s.hudOpacity=hudOpacity;s.blockLimit=Integer.parseInt(blockLimit);s.visualCalibration=visualCalibration;s.save();
            ClientMod.connection.configure(s);
            if(!values[3].isBlank()){ClientMod.connection.setKey(values[3]);values[3]="";if(fields[3]!=null)fields[3].setValue("");}
            boolean changed=!ClientMod.settings.group.equals(s.group)||!ClientMod.settings.modelProtocol.equals(s.modelProtocol);
            ClientMod.settings=s;status=tr("saved");if(changed)reloadChannelModels();return true;
        }catch(Exception e){status=tr("error");return false;}
    }
    private void syncModel(){
        String current=ClientMod.settings.model;
        if(values[1].trim().equals(ClientMod.settings.baseUrl) && values[5].trim().equals(ClientMod.settings.clientId)
                && group.trim().equals(ClientMod.settings.group) && protocol.equals(ClientMod.settings.modelProtocol)) {
            if(!java.util.Objects.equals(observedModel,current) && (values[2].isBlank()||values[2].equals(observedModel))
                    && (fields[2]==null||fields[2].getValue().isBlank()||fields[2].getValue().equals(observedModel))) {
                values[2]=current;if(fields[2]!=null)fields[2].setValue(current);
            }
            observedModel=current;
        }
    }
    public void render(GuiGraphics g,int mx,int my,float dt){
        syncModel();
        super.render(g,mx,my,dt);g.drawCenteredString(font,title,width/2,8,0xFFFFFF);
        int first=page==0?0:page==1?5:8,last=page==0?(ClientServerApi.shared()?1:5):8;
        for(int i=first;i<last;i++)g.drawString(font,tr(labels[i]),left,startY+(i-first)*rowStep+6,0xDDDDDD);
        if(page==3){g.drawString(font,tr("block_limit"),left,startY+6,0xDDDDDD);g.drawWordWrap(font,tr("build_hint"),left,startY+81,formWidth,0xCCCCCC);}
        if(page==5){g.drawString(font,tr("group"),left,startY+6,0xDDDDDD);g.drawWordWrap(font,tr("provider_hint"),left,startY+58,formWidth,0xCCCCCC);}
        if(page==2)g.drawWordWrap(font,Component.translatable("aibotassist.settings.hud_hint",ClientMod.CHAT_KEY.getTranslatedKeyMessage()),left,startY+58,formWidth,0xCCCCCC);
        if(page==0&&ClientServerApi.shared())g.drawWordWrap(font,ServerApiScreen.tr("shared_hint").copy().append(" "+ClientServerApi.policy().model()),left,startY+rowStep+6,formWidth,0xCCCCCC);
        if(page==4)g.drawWordWrap(font,ServerApiScreen.tr(ClientServerApi.shared()?"shared_hint":"independent_hint"),left,startY,formWidth,0xCCCCCC);
        Component readiness=ClientServerApi.shared()?ServerApiScreen.tr("shared"):ClientMod.connection.ready()?(ClientMod.settings.model.isBlank()?tr("model_missing"):tr("model_ready").copy().append(ClientMod.settings.model)):tr(ClientMod.connection.authStatus());
        g.drawWordWrap(font,readiness.copy().append(" · ").append(status),left,height-52,formWidth,0xCCCCCC);
    }
    public boolean isPauseScreen(){return false;}
    public void tick(){super.tick();if(!apiRevision.equals(ClientServerApi.policy().revision())){capture();rebuildWidgets();}}
    public void onClose(){values[3]="";if(fields[3]!=null)fields[3].setValue("");minecraft.setScreen(parent);}
}
