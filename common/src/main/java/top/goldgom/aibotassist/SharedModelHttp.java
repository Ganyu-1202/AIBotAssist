package top.goldgom.aibotassist;

import com.google.gson.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Exactly one fixed-provider request; no client-selected URL, model, headers or retries. */
public final class SharedModelHttp {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private SharedModelHttp(){}
    public static JsonObject body(ServerApiConfig config,JsonObject input){
        JsonArray messages=input.getAsJsonArray("messages");if(messages==null||messages.isEmpty()||messages.size()>256)throw new IllegalArgumentException("消息数量无效");
        JsonArray tools=input.has("tools")?input.getAsJsonArray("tools"):new JsonArray();if(tools.size()>64)throw new IllegalArgumentException("工具数量过多");
        JsonObject body=ModelProtocol.body(config.modelProtocol,config.model,messages,tools);
        if(body.toString().getBytes(StandardCharsets.UTF_8).length>PacketChunks.MAX_BYTES)throw new IllegalArgumentException("模型请求超过8MiB");return body;
    }
    public static CompletableFuture<JsonObject> request(ServerApiConfig config,JsonObject input){
        return request(CLIENT,config,input);
    }
    static CompletableFuture<JsonObject> request(HttpClient client,ServerApiConfig config,JsonObject input){
        var request=HttpRequest.newBuilder(java.net.URI.create(ServerApiConfig.endpoint(config.baseUrl)+ModelProtocol.path(config.modelProtocol))).timeout(Duration.ofSeconds(90))
            .header("Authorization","Bearer "+config.apiKey).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body(config,input).toString())).build();
        if(ServerApiConfig.endpoint(config.baseUrl).toString().equals("https://openai.goldgom.top/v1"))request=HttpRequest.newBuilder(request, (name,value)->true).header("X-TokenNest-Group",config.group).build();
        var streamRef=new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();
        var aborted=new java.util.concurrent.atomic.AtomicBoolean();
        var upstream=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
        var result=upstream.thenApplyAsync(response->{try(var stream=response.body()){
            streamRef.set(stream);if(aborted.get())throw new Failure("共享API请求已取消");
            if(response.statusCode()<200||response.statusCode()>=300)throw new Failure("共享API返回HTTP"+response.statusCode()+"，请联系管理员检查认证、模型或额度");
            byte[] bytes=stream.readNBytes(PacketChunks.MAX_BYTES+1);if(bytes.length>PacketChunks.MAX_BYTES)throw new Failure("共享API响应超过传输上限");
            JsonObject raw=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();JsonObject message=ModelProtocol.message(config.modelProtocol,raw);
            if(message==null)throw new Failure("共享API未返回有效消息");
            // Return only completion message fields, never arbitrary provider response/headers.
            JsonObject clean=new JsonObject();clean.addProperty("role","assistant");for(String key:new String[]{"content","tool_calls"})if(message.has(key))clean.add(key,message.get(key));return clean;
        }catch(Failure e){throw e;}catch(Exception e){throw new Failure("共享API网络或响应错误，请联系管理员检查；未自动重试");}});
        result.orTimeout(95,TimeUnit.SECONDS);result.whenComplete((v,e)->{if(e!=null){aborted.set(true);upstream.cancel(true);var stream=streamRef.get();if(stream!=null)try{stream.close();}catch(Exception ignored){}}});return result;
    }
    public static final class Failure extends RuntimeException{public Failure(String text){super(text);}}
}
