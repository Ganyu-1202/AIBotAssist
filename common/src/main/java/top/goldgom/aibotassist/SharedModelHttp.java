package top.goldgom.aibotassist;

import com.google.gson.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Fixed-provider model-only retries; never executes or replays game tools. */
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
        String diagnostic=java.util.UUID.randomUUID().toString().substring(0,12);
        JsonObject snapshot=input.deepCopy();
        try(var trace=new AgentDebug(diagnostic)){
            return ModelRetry.async(()->requestOnce(client,config,snapshot,diagnostic)).orTimeout(300,TimeUnit.SECONDS);
        }
    }
    private static CompletableFuture<JsonObject> requestOnce(HttpClient client,ServerApiConfig config,JsonObject input,String diagnostic){
        org.slf4j.LoggerFactory.getLogger("aibotassist").info("AI shared HTTP [{}] model={} protocol={} group={}",diagnostic,AgentDebug.label(config.model),AgentDebug.label(config.modelProtocol),AgentDebug.label(config.group));
        var request=HttpRequest.newBuilder(java.net.URI.create(ServerApiConfig.endpoint(config.baseUrl)+ModelProtocol.path(config.modelProtocol))).timeout(Duration.ofSeconds(90))
            .header("Authorization","Bearer "+config.apiKey).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body(config,input).toString())).build();
        if(ServerApiConfig.endpoint(config.baseUrl).toString().equals("https://openai.goldgom.top/v1"))request=HttpRequest.newBuilder(request, (name,value)->true).header("X-TokenNest-Group",config.group).build();
        var streamRef=new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();
        var aborted=new java.util.concurrent.atomic.AtomicBoolean();
        var upstream=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
        var result=upstream.thenApplyAsync(response->{try(var trace=new AgentDebug(diagnostic)) {try(var stream=response.body()){
            trace.stage("shared_http_response");AgentDebug.http(response,0);
            streamRef.set(stream);if(aborted.get())throw new Failure("共享API请求已取消");
            if(response.statusCode()<200||response.statusCode()>=300)throw new ModelRetry.HttpFailure(response.statusCode());
            byte[] bytes=stream.readNBytes(PacketChunks.MAX_BYTES+1);if(bytes.length>PacketChunks.MAX_BYTES)throw new Failure("共享API响应超过传输上限");
            trace.event("shared_body_size","bytes="+bytes.length);trace.stage("shared_json_parse");
            JsonObject raw=ModelResponseReader.parse(new String(bytes,StandardCharsets.UTF_8),response.headers().firstValue("Content-Type").orElse(""));trace.event("response_shape",AgentDebug.responseShape(raw));trace.stage("shared_response_decode");JsonObject message=ModelProtocol.message(config.modelProtocol,raw);
            ModelRetry.validateMessage(message);
            // Return only completion message fields, never arbitrary provider response/headers.
            JsonObject clean=new JsonObject();clean.addProperty("role","assistant");for(String key:new String[]{"content","tool_calls"})if(message.has(key))clean.add(key,message.get(key));return clean;
        }catch(Exception e){trace.failure(e);String reason=e instanceof ModelRetry.HttpFailure h?"共享API返回HTTP"+h.code:e instanceof ModelProtocol.ResponseFailure||e instanceof Failure?e.getMessage():"共享API网络或响应错误，请联系管理员检查";throw new Failure(reason+"；服务端诊断编号 "+diagnostic,ModelRetry.retryable(e));}}});
        upstream.whenComplete((response,error)->{if(error!=null)try(var trace=new AgentDebug(diagnostic)){trace.stage("shared_http_transport");trace.failure(error);}});
        result.orTimeout(95,TimeUnit.SECONDS);result.whenComplete((v,e)->{if(e!=null){aborted.set(true);upstream.cancel(true);var stream=streamRef.get();if(stream!=null)try{stream.close();}catch(Exception ignored){}}});return result;
    }
    public static final class Failure extends RuntimeException implements ModelRetry.Retryable{
        private final boolean retry;
        public Failure(String text){this(text,false);}public Failure(String text,boolean retry){super(text);this.retry=retry;}
        public boolean retryable(){return retry;}
    }
}
