package top.goldgom.aibotassist.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.Util;

public final class SettingsScreen extends Screen {
    private final Screen parent;
    private EditBox name, base, model, key, proxy, clientId;
    private String status = "密钥/令牌仅保存在内存，不写磁盘，不发送给服务器。";
    public SettingsScreen(Screen parent) { super(Component.literal("AI Bot Assist")); this.parent = parent; }
    private EditBox field(int y, String label, String value, int max) {
        EditBox e = new EditBox(font, width / 2 - 30, y, 230, 20, Component.literal(label));
        e.setMaxLength(max); e.setValue(value); addRenderableWidget(e); return e;
    }
    protected void init() {
        int y = Math.max(32, (height - 290) / 2);
        ClientSettings s = ClientMod.settings;
        name = field(y, "AI 名称", s.name, 24);
        base = field(y + 28, "API /v1 地址", s.baseUrl, 250);
        model = field(y + 56, "模型 ID", s.model, 100);
        key = field(y + 84, "API 密钥", "", 512);
        key.setFormatter((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        proxy = field(y + 112, "HTTP 代理（可空）", s.proxy, 200);
        clientId = field(y + 140, "OAuth client_id", s.clientId, 200);
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> {
            if (!save()) return;
            onClose();
            ClientMod.say("设置已保存；聊天栏直接 @" + ClientMod.settings.name + " 你好。");
        }).bounds(width/2-200,y+174,125,20).build());
        addRenderableWidget(Button.builder(Component.literal("TokenNest 登录"), b -> {
            if (!save()) return;
            try { var uri = ClientMod.connection.login(ClientMod.settings, ClientMod::say); Util.getPlatform().openUri(uri); status = "请在浏览器授权，3分钟内完成。"; }
            catch (Exception e) { status = "登录未启动：请检查 OAuth client_id 和代理设置。"; }
        }).bounds(width/2-65,y+174,130,20).build());
        addRenderableWidget(Button.builder(Component.literal("清除登录/密钥"), b -> { ClientMod.connection.logout(); key.setValue(""); status = "已清除本地会话凭据。"; }).bounds(width/2+75,y+174,125,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose()).bounds(width/2-65,y+202,130,20).build());
    }
    private boolean save() {
        try {
            ClientSettings s = new ClientSettings();
            s.name = name.getValue().trim(); s.baseUrl = base.getValue().trim(); s.model = model.getValue().trim();
            s.proxy = proxy.getValue().trim(); s.clientId = clientId.getValue().trim(); s.save();
            if (!key.getValue().isBlank()) { ClientMod.connection.setKey(key.getValue()); key.setValue(""); }
            ClientMod.settings = s; status = "已保存；聊天栏直接 @" + s.name + " 你好。"; return true;
        } catch (Exception e) { status = "保存失败：名称为1–24位字母/汉字/数字，API 使用 HTTPS。"; return false; }
    }
    public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        int y = Math.max(32, (height - 290) / 2);
        g.drawCenteredString(font, title, width/2, y-22, 0xFFFFFF);
        String[] labels = {"AI 名称", "API /v1 地址", "模型 ID", "API 密钥", "HTTP 代理（可空）", "OAuth client_id"};
        for (int i=0;i<labels.length;i++) g.drawString(font, labels[i], width/2-200,y+6+i*28,0xDDDDDD);
        g.drawCenteredString(font, status, width/2, y+238,0xCCCCCC);
    }
    public boolean isPauseScreen() { return false; }
    public void onClose() { key.setValue(""); minecraft.setScreen(parent); }
}
