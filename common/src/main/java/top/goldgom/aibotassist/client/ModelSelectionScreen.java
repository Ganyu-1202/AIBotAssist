package top.goldgom.aibotassist.client;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A paged picker; the editable model ID in settings remains available for custom models. */
public final class ModelSelectionScreen extends Screen {
    private final Screen parent;
    private final List<String> models;
    private final Consumer<String> selected;
    private int page;
    private final boolean typed;
    public ModelSelectionScreen(Screen parent,List<String> models,Consumer<String> selected) {
        this(parent,models,selected,false);
    }
    public ModelSelectionScreen(Screen parent,List<String> models,Consumer<String> selected,boolean typed) {
        super(Component.translatable(typed?"aibotassist.settings.select_model":"aibotassist.settings.select_entry"));this.typed=typed;
        this.parent=parent;this.models=List.copyOf(models);this.selected=selected;
    }
    protected void init() {
        int rows=Math.max(1,(height-88)/24),pages=Math.max(1,(models.size()+rows-1)/rows);
        page=Math.min(page,pages-1);
        int formWidth=Math.min(400,width-20),left=(width-formWidth)/2;
        for(int row=0;row<rows && page*rows+row<models.size();row++) {
            String id=models.get(page*rows+row);
            addRenderableWidget(Button.builder(Component.literal(typed?id+" ["+ClientMod.connection.modelType(ClientMod.settings,id)+"]":id),button->selected.accept(id)).bounds(left,32+row*24,formWidth,20).build());
        }
        Button previous=Button.builder(Component.literal("<"),button->{page--;rebuildWidgets();}).bounds(left,height-50,40,20).build();
        previous.active=page>0;addRenderableWidget(previous);
        Button next=Button.builder(Component.literal(">"),button->{page++;rebuildWidgets();}).bounds(left+formWidth-40,height-50,40,20).build();
        next.active=page+1<pages;addRenderableWidget(next);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),button->onClose()).bounds(left,height-26,formWidth,20).build());
    }
    public void render(GuiGraphics graphics,int mouseX,int mouseY,float tickDelta) {
        super.render(graphics,mouseX,mouseY,tickDelta);
        graphics.drawCenteredString(font,title,width/2,12,0xFFFFFF);
        int rows=Math.max(1,(height-88)/24),pages=Math.max(1,(models.size()+rows-1)/rows);
        graphics.drawCenteredString(font,Component.literal((page+1)+" / "+pages),width/2,height-44,0xCCCCCC);
    }
    public boolean isPauseScreen(){return false;}
    public void onClose(){minecraft.setScreen(parent);}
}
