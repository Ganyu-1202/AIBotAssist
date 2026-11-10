package top.goldgom.aibotassist.client;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import top.goldgom.aibotassist.Plan;

/** Secrets are intentionally session-only and never serialized, logged or sent to Minecraft. */
public final class AiConnection {
    public static final String NEST = "https://openai.goldgom.top";
    private volatile String apiKey = "", access = "", refresh = "";
    private volatile long expires;
    private volatile HttpServer callback;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "aibot-oauth-timeout"); t.setDaemon(true); return t; });
    public void setKey(String key) { apiKey = key.trim(); access = ""; refresh = ""; }
    public void logout() { apiKey = ""; access = ""; refresh = ""; if (callback != null) { callback.stop(0); callback = null; } }
    public boolean ready() { return !apiKey.isBlank() || !access.isBlank(); }
    private static HttpClient client(String proxy) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER);
        if (!proxy.isBlank()) {
            URI u = URI.create(proxy);
            if (!"http".equals(u.getScheme()) || u.getHost() == null || u.getPort() < 1 || u.getUserInfo() != null) throw new IllegalArgumentException("代理格式应为 http://主机:端口");
            b.proxy(ProxySelector.of(new InetSocketAddress(u.getHost(), u.getPort())));
        }
        return b.build();
    }
    public static URI endpoint(String base) {
        URI u = URI.create(base.replaceAll("/+$", ""));
        if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null)
            throw new IllegalArgumentException("API 地址必须为无凭据、无查询参数的 HTTPS URL");
        return u;
    }
    private static JsonObject read(HttpResponse<java.io.InputStream> response, int max) throws Exception {
        try (var input = response.body()) {
            byte[] bytes = input.readNBytes(max + 1);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new PiDiagnostics.HttpFailure(response.statusCode());
            if (bytes.length > max) throw new IllegalStateException("响应过大");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private synchronized String bearer(ClientSettings settings) throws Exception {
        if (access.isBlank()) return apiKey;
        if (expires <= System.currentTimeMillis() + 30_000) {
            if (refresh.isBlank()) throw new IllegalStateException("请重新登录");
            tokens(settings, "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(settings.clientId));
        }
        return access;
    }
    public JsonObject agentTurn(ClientSettings settings, JsonArray messages, JsonArray tools) throws Exception {
        URI base=endpoint(settings.baseUrl);
        if (!access.isBlank() && !base.toString().equals(NEST+"/v1")) throw new IllegalStateException("TokenNest令牌不能发送到其他地址");
        String token=bearer(settings);
        if(token.isBlank())throw new IllegalStateException("请先登录或配置密钥");
        JsonObject body=new JsonObject();body.addProperty("model",settings.model);body.add("messages",messages);
        body.addProperty("max_tokens",12000);
        if(!tools.isEmpty()) {body.add("tools",tools);body.addProperty("tool_choice","auto");body.addProperty("parallel_tool_calls",false);}
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/chat/completions")).timeout(Duration.ofSeconds(90))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        JsonObject reply=read(client(settings.proxy).send(request,HttpResponse.BodyHandlers.ofInputStream()),500_000);
        return reply.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
    }
    public synchronized URI login(ClientSettings settings, Consumer<String> status) throws Exception {
        if (settings.clientId.isBlank()) throw new IllegalStateException("请先填写注册的 TokenNest OAuth client_id");
        if (callback != null) throw new IllegalStateException("登录已在等待浏览器授权");
        String verifier = random(), state = random();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        callback = server;
        server.createContext("/callback", exchange -> {
            String result = "Authorization failed. Return to Minecraft.";
            try {
                if (callback != server || !"GET".equals(exchange.getRequestMethod()) || !"/callback".equals(exchange.getRequestURI().getPath())) throw new IllegalStateException();
                Map<String, String> query = new HashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                if (raw == null || raw.length() > 8192) throw new IllegalStateException();
                for (String pair : raw.split("&")) { String[] kv = pair.split("=", 2); if (kv.length == 2) query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8)); }
                if (!state.equals(query.get("state")) || !query.containsKey("code")) throw new IllegalStateException();
                tokens(settings, "grant_type=authorization_code&client_id=" + enc(settings.clientId) + "&code=" + enc(query.get("code")) + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + enc(verifier));
                result = "Signed in. You can close this tab and return to Minecraft.";
                status.accept("TokenNest 登录成功（令牌仅保存在本次游戏内存中）。");
            } catch (Exception e) { status.accept("TokenNest 授权失败或已过期，请重试；未输出令牌或服务端响应。"); }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            server.stop(0); if (callback == server) callback = null;
        });
        server.start();
        timer.schedule(() -> { if (callback == server) { server.stop(0); callback = null; status.accept("TokenNest 登录等待超时。"); } }, 180, TimeUnit.SECONDS);
        return URI.create(NEST + "/oauth/authorize?response_type=code&client_id=" + enc(settings.clientId)
            + "&redirect_uri=" + enc(redirect) + "&scope=" + enc("api offline_access") + "&state=" + state + "&code_challenge=" + challenge + "&code_challenge_method=S256");
    }
    private synchronized void tokens(ClientSettings s, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(NEST + "/api/oauth2/token")).timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        JsonObject o = read(client(s.proxy).send(request, HttpResponse.BodyHandlers.ofInputStream()), 32_000);
        String newAccess = o.get("access_token").getAsString();
        if (newAccess.isBlank()) throw new IllegalStateException();
        access = newAccess; apiKey = "";
        if (o.has("refresh_token")) refresh = o.get("refresh_token").getAsString();
        expires = System.currentTimeMillis() + Math.min(o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600, 86400) * 1000;
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String random() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}

