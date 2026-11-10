package top.goldgom.aibotassist.client;

import dev.architectury.platform.Platform;
import java.nio.file.*;
import top.goldgom.aibotassist.AIBotAssist;

public final class ClientSettings {
    public String name = "AI", baseUrl = AiConnection.NEST + "/v1", model = "", proxy = "", clientId = "";
    private static Path path() { return Platform.getConfigFolder().resolve("aibotassist-client.json"); }
    public static ClientSettings load() {
        try {
            ClientSettings s = AIBotAssist.JSON.fromJson(Files.readString(path()), ClientSettings.class);
            s.validate(); return s;
        } catch (Exception e) { return new ClientSettings(); }
    }
    public void validate() {
        if (name == null || !name.matches("[\\p{L}\\p{N}_-]{1,24}") || model == null || model.length() > 100
            || proxy == null || proxy.length() > 200 || clientId == null || clientId.length() > 200) throw new IllegalArgumentException("设置无效");
        AiConnection.endpoint(baseUrl);
    }
    public void save() throws Exception { validate(); Files.createDirectories(path().getParent()); Files.writeString(path(), AIBotAssist.JSON.toJson(this)); }
}
