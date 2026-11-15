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

/** Secrets remain client-local and are never logged or sent to Minecraft/Pi. */
public final class AiConnection {
    public static final String NEST = TokenNestOAuth.ISSUER;
    private volatile String apiKey = "", access = "", refresh = "";
    private volatile long expires;
    private volatile HttpServer callback;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "aibot-oauth-timeout"); t.setDaemon(true); return t; });
    private String scope, boundEndpoint="",boundClientId="";
    private long sessionVersion;
    private volatile Map<String,String> modelTypes=Map.of();
    private volatile String catalogKey="";
    private static String catalogKey(ClientSettings s){return s.baseUrl+"|"+s.group+"|"+s.modelProtocol;}
    public String modelType(ClientSettings s,String id){return catalogKey(s).equals(catalogKey)?modelTypes.getOrDefault(id,"chat"):"chat";}
    private final LocalCredentials store;
    @FunctionalInterface interface TokenRequest { JsonObject request(ClientSettings settings,String form) throws Exception; }
    @FunctionalInterface interface ApiRequest { JsonObject request(ClientSettings settings,String token,String path,JsonObject body) throws Exception; }
    private final TokenRequest tokenRequest;
    private final ApiRequest apiRequest;
    public AiConnection() { this(new LocalCredentials(dev.architectury.platform.Platform.getConfigFolder().resolve("aibotassist/private"))); }
    AiConnection(LocalCredentials store) { this(store,AiConnection::requestTokens); }
    AiConnection(LocalCredentials store,TokenRequest tokenRequest) { this(store,tokenRequest,AiConnection::requestApi); }
    AiConnection(LocalCredentials store,TokenRequest tokenRequest,ApiRequest apiRequest) { this.store=store;this.tokenRequest=tokenRequest;this.apiRequest=apiRequest; }
    public synchronized void enter(String identity,ClientSettings settings) {
        logout();scope=LocalCredentials.scope(identity);boundEndpoint=settings.baseUrl;boundClientId=settings.clientId;
        try{var saved=store.load(scope);if(saved!=null&&saved.endpoint().equals(settings.baseUrl)&&(saved.clientId().equals(settings.clientId)||(saved.access().isBlank()&&saved.refresh().isBlank()))){
            apiKey=saved.apiKey();access=saved.access();refresh=saved.refresh();expires=saved.expires();
        }}catch(Exception e){ClientMod.say("无法读取当前世界的本地凭据，请重新填写；未输出敏感数据。");}
    }
    public synchronized void configure(ClientSettings settings){
        if(!boundEndpoint.equals(settings.baseUrl)||!boundClientId.equals(settings.clientId)) {clearMemory();boundEndpoint=settings.baseUrl;boundClientId=settings.clientId;}
    }
    private void persist(){
        if(scope==null)throw new IllegalStateException("尚未进入世界，无法保存凭据");
        try{store.save(scope,new LocalCredentials.Secret(apiKey,access,refresh,expires,boundEndpoint,boundClientId));}
        catch(Exception e){throw new IllegalStateException("本地凭据保存失败",e);}
    }
    public synchronized void setKey(String key) {clearMemory();apiKey=key.trim();persist();}
    private void clearMemory(){sessionVersion++;modelTypes=Map.of();catalogKey="";apiKey="";access="";refresh="";expires=0;if(callback!=null){callback.stop(0);callback=null;}}
    public synchronized void logout(){clearMemory();scope=null;}
    public synchronized void forget(){try{if(scope!=null)store.delete(scope);}catch(Exception e){throw new IllegalStateException("无法删除本地凭据",e);}clearMemory();}
    public boolean ready(){return !apiKey.isBlank()||!access.isBlank()||!refresh.isBlank();}
    public synchronized long sessionVersion(){return sessionVersion;}
    public synchronized boolean loginPending(){return callback!=null;}
    public synchronized String authStatus(){return callback!=null?"auth_pending":!access.isBlank()||!refresh.isBlank()?"auth_logged_in":!apiKey.isBlank()?"auth_api_key":"auth_signed_out";}
    public synchronized void cancelLogin(){if(callback!=null){callback.stop(0);callback=null;}}
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
        if (access.isBlank()&&refresh.isBlank()) return apiKey;
        TokenNestOAuth.requireApi(settings.baseUrl);
        if(!boundClientId.equals(settings.clientId)||!boundEndpoint.equals(settings.baseUrl))throw new IllegalStateException("登录配置已改变，请重新登录");
        if (expires <= System.currentTimeMillis() + 30_000) {
            if (refresh.isBlank()) throw new IllegalStateException("请重新登录");
            tokens(settings, "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(settings.clientId));
        }
        return access;
    }
    public JsonObject agentTurn(ClientSettings settings, JsonArray messages, JsonArray tools) throws Exception {
        String token=bearer(settings);
        if(token.isBlank())throw new IllegalStateException("请先登录或配置密钥");
        String protocol=settings.modelProtocol;
        if(protocol.equals("auto")) {
            if(!catalogKey(settings).equals(catalogKey)) {
                long version=sessionVersion();
                try{listModels(settings);}catch(InterruptedException e){throw e;}catch(Exception unavailable){
                    // Custom providers can serve a manual model without a discovery endpoint.
                    synchronized(this){if(version!=sessionVersion)throw new InterruptedException("登录状态已改变");modelTypes=Map.of();catalogKey=catalogKey(settings);}
                }
            }
            protocol=modelType(settings,settings.model);
        }
        JsonObject body=top.goldgom.aibotassist.ModelProtocol.body(protocol,settings.model,messages,tools);
        return top.goldgom.aibotassist.ModelProtocol.message(protocol,apiRequest.request(settings,token,top.goldgom.aibotassist.ModelProtocol.path(protocol),body));
    }
    public List<String> listModels(ClientSettings settings) throws Exception {
        String token;long version;
        synchronized(this) {token=bearer(settings);version=sessionVersion;}
        if(token.isBlank())throw new IllegalStateException("请先登录或配置密钥");
        var catalog=AgentModels.catalog(apiRequest.request(settings,token,"/models",null),settings.modelProtocol);
        synchronized(this) {
            if(sessionVersion!=version)throw new InterruptedException("模型查询期间登录状态已改变");
            modelTypes=catalog;catalogKey=catalogKey(settings);
        }
        return List.copyOf(catalog.keySet());
    }
    public List<String> listGroups(ClientSettings settings) throws Exception {
        TokenNestOAuth.requireApi(settings.baseUrl);
        String token;long version;
        synchronized(this){if(access.isBlank()&&refresh.isBlank())throw new IllegalStateException("API密钥绑定自身渠道；切换渠道请使用TokenNest登录");token=bearer(settings);version=sessionVersion;}
        JsonObject reply=apiRequest.request(settings,token,"/api/oauth2/groups",null);
        JsonObject data=reply.getAsJsonObject("data");if(data==null)throw new IllegalArgumentException("渠道列表格式无效");
        List<String> groups=new ArrayList<>();for(String group:data.keySet()) {
            try{top.goldgom.aibotassist.ModelProtocol.validate("auto",group,true);groups.add(group);}catch(IllegalArgumentException ignored){}
        }
        synchronized(this){if(sessionVersion!=version)throw new InterruptedException("登录状态已改变");}
        return List.copyOf(groups);
    }

    public synchronized URI login(ClientSettings settings, Consumer<String> status) throws Exception {
        return login(settings,status,()->{});
    }
    public synchronized URI login(ClientSettings settings, Consumer<String> status,Runnable authorized) throws Exception {
        if (scope == null) throw new IllegalStateException("请进入游戏世界后再登录 TokenNest");
        TokenNestOAuth.requireApi(settings.baseUrl);
        configure(settings);
        if (settings.clientId.isBlank()) throw new IllegalStateException("请先填写注册的 TokenNest OAuth client_id");
        if (callback != null) throw new IllegalStateException("登录已在等待浏览器授权");
        String loginScope=scope;
        String verifier = random(), state = random();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String redirect = TokenNestOAuth.redirect(server.getAddress().getPort());
        callback = server;
        server.createContext(TokenNestOAuth.CALLBACK_PATH, exchange -> {
            Map<String, String> query;
            try {
                if (callback != server || !"GET".equals(exchange.getRequestMethod()) || !TokenNestOAuth.CALLBACK_PATH.equals(exchange.getRequestURI().getPath())) throw new IllegalArgumentException();
                query = TokenNestOAuth.callbackQuery(exchange.getRequestURI().getRawQuery(), state);
            } catch (IllegalArgumentException invalid) {
                callbackResponse(exchange, 400, "Invalid callback. Continue authorization in the original browser tab.");
                return; // Stray or forged requests must not consume the legitimate login session.
            }
            String result = "Authorization failed. Return to Minecraft.";
            try {
                if (query.containsKey("error")) {
                    result = "Authorization declined. You can close this tab and return to Minecraft.";
                    status.accept("TokenNest 授权已取消，可重新发起登录。");
                } else {
                    finishLogin(loginScope,server,settings, "grant_type=authorization_code&client_id=" + enc(settings.clientId) + "&code=" + enc(query.get("code")) + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + enc(verifier));
                    result = "Signed in. You can close this tab and return to Minecraft.";
                    status.accept("TokenNest 登录成功，已保存到本机当前世界的凭据库。");
                    authorized.run();
                }
            } catch (Exception e) { status.accept("TokenNest 授权失败或已过期，请重试；未输出令牌或服务端响应。"); }
            try { callbackResponse(exchange, 200, result); }
            finally { closeLogin(server); }
        });
        server.start();
        timer.schedule(() -> { if (closeLogin(server)) status.accept("TokenNest 登录等待超时。"); }, 180, TimeUnit.SECONDS);
        return TokenNestOAuth.authorization(settings.clientId,server.getAddress().getPort(),state,verifier);
    }
    private synchronized boolean closeLogin(HttpServer server) {
        if (callback != server) return false;
        callback = null;server.stop(0);return true;
    }
    private static void callbackResponse(com.sun.net.httpserver.HttpExchange exchange,int status,String result) throws java.io.IOException {
        byte[] bytes=result.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        try {exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
    }
    private synchronized void finishLogin(String expected,HttpServer server,ClientSettings settings,String form)throws Exception{
        if(!java.util.Objects.equals(scope,expected)||callback!=server||!boundEndpoint.equals(settings.baseUrl)||!boundClientId.equals(settings.clientId))throw new IllegalStateException("登录世界或配置已改变");
        tokens(settings,form);
    }
    private synchronized void tokens(ClientSettings s, String form) throws Exception {
        TokenNestOAuth.requireApi(s.baseUrl);
        JsonObject o = tokenRequest.request(s,form);
        String newAccess = o.get("access_token").getAsString();
        if (newAccess.isBlank()) throw new IllegalStateException();
        if(o.has("token_type")&&!"Bearer".equalsIgnoreCase(o.get("token_type").getAsString()))throw new IllegalStateException("不支持的令牌类型");
        long lifetime=o.has("expires_in")?o.get("expires_in").getAsBigDecimal().longValueExact():3600;
        if(lifetime<=0)throw new IllegalStateException("无效令牌有效期");
        String nextRefresh=o.has("refresh_token")?o.get("refresh_token").getAsString():form.startsWith("grant_type=refresh_token")?refresh:"";
        access = newAccess; apiKey = "";
        refresh = nextRefresh;
        expires = System.currentTimeMillis() + Math.min(lifetime, 86400) * 1000;
        persist();
        if(!form.startsWith("grant_type=refresh_token"))sessionVersion++;
    }
    static HttpRequest apiHttpRequest(ClientSettings settings,String token,String path,JsonObject body) {
        URI base=endpoint(settings.baseUrl);
        URI target=path.startsWith("/api/oauth2/")?URI.create(NEST+path):URI.create(base+path);
        HttpRequest.Builder request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(body==null?25:90))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json");
        if(base.toString().equals(TokenNestOAuth.API_BASE))request.header("X-TokenNest-Group",settings.group);
        if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        return request.build();
    }
    private static JsonObject requestApi(ClientSettings settings,String token,String path,JsonObject body) throws Exception {
        return read(client(settings.proxy).send(apiHttpRequest(settings,token,path,body),HttpResponse.BodyHandlers.ofInputStream()),body==null?1_000_000:top.goldgom.aibotassist.PacketChunks.MAX_BYTES);
    }
    private static JsonObject requestTokens(ClientSettings settings,String form) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create(NEST+"/api/oauth2/token")).timeout(Duration.ofSeconds(25))
            .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        return read(client(settings.proxy).send(request,HttpResponse.BodyHandlers.ofInputStream()),32_000);
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String random() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}

