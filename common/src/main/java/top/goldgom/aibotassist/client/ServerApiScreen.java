package top.goldgom.aibotassist.client;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;

/** Public mode display; only the server authorizes edits. Existing keys are never downloaded. */
public final class ServerApiScreen extends Screen {
    private final Screen parent;
    private String mode="independent",revision="",base="",model="",key="",concurrent="4",interval="0";
    private String group="default",protocol="chat";
    private EditBox groupBox;
    private EditBox baseBox,modelBox,keyBox,concurrentBox,intervalBox;
    private boolean providerPage;
    private boolean canManage,loaded,loading,clearKey,keyConfigured;
    private Component status=tr("loading");
    private int left,formWidth,start,row;
    public static Component tr(String key){return Component.translatable("aibotassist.server_api."+key);}
    public ServerApiScreen(Screen parent){super(tr("title"));this.parent=parent;}
    private void capture(){if(baseBox!=null){base=baseBox.getValue();model=modelBox.getValue();key=keyBox.getValue();concurrent=concurrentBox.getValue();interval=intervalBox.getValue();group=groupBox.getValue();}}
    protected void init(){
        capture();formWidth=Math.min(440,width-20);left=(width-formWidth)/2;start=56;row=Math.max(20,Math.min(26,(height-126)/5));
        addRenderableWidget(Button.builder(tr(mode),b->{capture();mode=mode.equals("shared")?"independent":"shared";rebuildWidgets();}).bounds(left,30,formWidth-196,20).build()).active=canManage&&loaded;
        addRenderableWidget(Button.builder(tr("refresh"),b->refresh()).bounds(left+formWidth-96,30,96,20).build());
        addRenderableWidget(Button.builder(tr("provider"),button->{capture();providerPage=!providerPage;rebuildWidgets();}).bounds(left+formWidth-192,30,92,20).build());
        int label=Math.min(120,formWidth/3),fieldLeft=left+label,fieldWidth=formWidth-label;
        baseBox=field(fieldLeft,start,fieldWidth,base,1000);modelBox=field(fieldLeft,start+row,fieldWidth,model,100);keyBox=field(fieldLeft,start+row*2,fieldWidth-60,key,4096);
        keyBox.setFormatter((text,offset)->FormattedCharSequence.forward("•".repeat(text.length()),Style.EMPTY));
        Button clearButton=addRenderableWidget(Button.builder(tr("clear_key"),b->{clearKey=!clearKey;key="";keyBox.setValue("");status=tr(clearKey?"key_cleared_on_save":"key_retained");}).bounds(left+formWidth-56,start+row*2,56,20).build());clearButton.active=canManage;clearButton.visible=!providerPage;
        concurrentBox=field(fieldLeft,start+row*3,fieldWidth,concurrent,2);intervalBox=field(fieldLeft,start+row*4,fieldWidth,interval,2);
        groupBox=field(fieldLeft,start,fieldWidth,group,64);
        Button protocolButton=addRenderableWidget(Button.builder(Component.literal("API: "+protocol),button->{protocol=protocol.equals("chat")?"responses":"chat";button.setMessage(Component.literal("API: "+protocol));}).bounds(left,start+28,formWidth,20).build());protocolButton.active=canManage;protocolButton.visible=providerPage;
        groupBox.visible=providerPage;for(var e:new EditBox[]{baseBox,modelBox,keyBox,concurrentBox,intervalBox})e.visible=!providerPage;
        for(var e:new EditBox[]{concurrentBox,intervalBox})e.setFilter(value->value.matches("[0-9]*"));
        addRenderableWidget(Button.builder(tr("save"),b->save()).bounds(left,height-27,formWidth/2-3,20).build()).active=canManage&&loaded;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose()).bounds(left+formWidth/2+3,height-27,formWidth/2-3,20).build());
        if(!loaded&&!loading)refresh();
    }
    private EditBox field(int x,int y,int width,String value,int max){var e=new EditBox(font,x,y,width,20,Component.empty());e.setMaxLength(max);e.setValue(value);e.setEditable(canManage);return addRenderableWidget(e);}
    private void refresh(){
        if(loading)return;loading=true;status=tr("loading");
        ClientServerApi.refresh().whenComplete((data,error)->minecraft.execute(()->{
            loading=false;if(minecraft.screen!=this)return;
            if(error!=null){status=tr("load_failed");return;}
            mode=data.get("mode").getAsString();revision=data.get("revision").getAsString();canManage=data.get("can_manage").getAsBoolean();loaded=true;
            if(canManage){group=data.has("group")?data.get("group").getAsString():"default";protocol=data.has("model_protocol")?data.get("model_protocol").getAsString():"chat";base=data.get("base_url").getAsString();model=data.get("configured_model").getAsString();keyConfigured=data.get("key_configured").getAsBoolean();concurrent=data.get("max_concurrent").getAsString();interval=data.get("player_interval_seconds").getAsString();}
            else {model=data.get("model").getAsString();keyConfigured=mode.equals("shared")&&data.get("ready").getAsBoolean();}
            key="";clearKey=false;baseBox=null;status=tr(canManage?"admin_hint":"player_hint");rebuildWidgets();
            if(data.has("config_error")&&!data.get("config_error").getAsString().isBlank())status=Component.literal(data.get("config_error").getAsString());
        }));
    }
    private void save(){
        capture();JsonObject data=new JsonObject();
        try{data.addProperty("group",group.trim());data.addProperty("model_protocol",protocol);data.addProperty("mode",mode);data.addProperty("base_url",base.trim());data.addProperty("model",model.trim());data.addProperty("api_key",key);data.addProperty("clear_key",clearKey);data.addProperty("max_concurrent",Integer.parseInt(concurrent));data.addProperty("player_interval_seconds",Integer.parseInt(interval));}
        catch(Exception e){status=tr("invalid");return;}
        status=tr("saving");ClientServerApi.configure(data,revision).whenComplete((saved,error)->minecraft.execute(()->{
            if(minecraft.screen!=this)return;key="";keyBox.setValue("");
            if(error!=null){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();status=cause instanceof ClientServerApi.Failure?Component.literal(cause.getMessage()):tr("save_failed");return;}
            revision=saved.get("revision").getAsString();keyConfigured=saved.get("key_configured").getAsBoolean();clearKey=false;status=tr("saved");
        }));
    }
    public void render(GuiGraphics g,int x,int y,float dt){
        super.render(g,x,y,dt);g.drawCenteredString(font,title,width/2,10,0xFFFFFF);
        String[] labels=providerPage?new String[]{"group"}:new String[]{"base","model","key","concurrent","interval"};for(int i=0;i<labels.length;i++)g.drawString(font,tr(labels[i]),left,start+row*i+6,0xDDDDDD);
        if(providerPage)g.drawWordWrap(font,tr("provider_hint"),left,start+58,formWidth,0xCCCCCC);
        Component hint=tr(keyConfigured&&!clearKey?"key_present":"key_missing");g.drawWordWrap(font,hint.copy().append(" · ").append(status),left,height-65,formWidth,0xCCCCCC);
    }
    public boolean isPauseScreen(){return false;}
    public void onClose(){key="";if(keyBox!=null)keyBox.setValue("");minecraft.setScreen(parent);}
}
