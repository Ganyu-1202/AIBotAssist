package top.goldgom.aibotassist.client;

import dev.architectury.platform.Platform;
import java.nio.file.*;
import top.goldgom.aibotassist.AIBotAssist;

public final class ClientSettings {
    public String name = "AI", baseUrl = TokenNestOAuth.API_BASE, model = "", proxy = "", clientId = TokenNestOAuth.DEFAULT_CLIENT_ID;
    public String piExecutable = "bun", piRuntime = "";
    public transient String gameLanguage = "en_us";
    private static Path path() { return Platform.getConfigFolder().resolve("aibotassist-client.json"); }
    public static ClientSettings load() {
        try {
            ClientSettings s = AIBotAssist.JSON.fromJson(Files.readString(path()), ClientSettings.class);
            if(s.clientId == null || s.clientId.isBlank() || s.clientId.equals("tnc_aibotassist")) s.clientId = TokenNestOAuth.DEFAULT_CLIENT_ID;
            s.validate(); return s;
        } catch (Exception e) { return new ClientSettings(); }
    }
    public void validate() {
        if (name == null || !name.matches("[\\p{L}\\p{N}_-]{1,24}") || model == null || model.length() > 100
            || proxy == null || proxy.length() > 200 || clientId == null || !clientId.matches("[A-Za-z0-9_-]{0,200}")) throw new IllegalArgumentException("设置无效");
        if(piExecutable==null || piExecutable.isBlank() || piRuntime==null)throw new IllegalArgumentException("Pi运行时配置为空");
        AiConnection.endpoint(baseUrl);
    }
    public void save() throws Exception { validate(); Files.createDirectories(path().getParent()); Files.writeString(path(), AIBotAssist.JSON.toJson(this)); }
}
