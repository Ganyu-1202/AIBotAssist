package top.goldgom.aibotassist;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedModelHttpTest {
    @Test void serverPinsResponsesProtocolAndGroupRegardlessOfClientFields()throws Exception{
        var c=ServerApiConfigTest.config();c.baseUrl="https://openai.goldgom.top/v1";c.group="vip";c.modelProtocol="responses";
        var input=input("hello");input.addProperty("group","attacker");input.addProperty("model_protocol","chat");
        var client=new FakeClient(200,"{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}");
        assertEquals("ok",SharedModelHttp.request(client,c,input).get(3,TimeUnit.SECONDS).get("content").getAsString());
        assertEquals("https://openai.goldgom.top/v1/responses",client.request.uri().toString());
        assertEquals("vip",client.request.headers().firstValue("X-TokenNest-Group").orElseThrow());
        var sent=JsonParser.parseString(body(client.request)).getAsJsonObject();assertEquals("fixed-model",sent.get("model").getAsString());assertTrue(sent.has("input"));assertFalse(sent.has("messages"));assertFalse(sent.has("group"));
    }
    private static JsonObject input(String text){var a=new JsonObject();a.add("messages",JsonParser.parseString("[{\"role\":\"user\",\"content\":\""+text+"\"}]"));return a;}
    @Test void serverPinsProviderModelAndRequestLimitsRegardlessOfClientFields()throws Exception{
        var c=ServerApiConfigTest.config();var input=input("alice-private");input.addProperty("model","expensive-client-model");input.addProperty("base_url","https://attacker.example");input.addProperty("max_tokens",999999);input.addProperty("stream",true);
        var client=new FakeClient(200,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"alice-reply\"}}],\"private_provider_header\":\"not-for-client\"}");
        var reply=SharedModelHttp.request(client,c,input).get(3,TimeUnit.SECONDS);
        assertEquals("https://provider.example/v1/chat/completions",client.request.uri().toString());assertEquals("Bearer "+c.apiKey,client.request.headers().firstValue("Authorization").orElseThrow());
        JsonObject sent=JsonParser.parseString(body(client.request)).getAsJsonObject();assertEquals("fixed-model",sent.get("model").getAsString());assertEquals(12000,sent.get("max_tokens").getAsInt());assertFalse(sent.has("base_url"));assertFalse(sent.has("stream"));assertEquals("alice-reply",reply.get("content").getAsString());assertFalse(reply.toString().contains("not-for-client"));
    }
    @Test void concurrentPlayerRequestsDoNotMixMessagesOrResults()throws Exception{
        var alice=new FakeClient(200,"{\"choices\":[{\"message\":{\"content\":\"alice-result\"}}]}");var bob=new FakeClient(200,"{\"choices\":[{\"message\":{\"content\":\"bob-result\"}}]}");
        var a=SharedModelHttp.request(alice,ServerApiConfigTest.config(),input("alice-private"));var b=SharedModelHttp.request(bob,ServerApiConfigTest.config(),input("bob-private"));
        assertEquals("alice-result",a.get(3,TimeUnit.SECONDS).get("content").getAsString());assertEquals("bob-result",b.get(3,TimeUnit.SECONDS).get("content").getAsString());
        assertTrue(body(alice.request).contains("alice-private"));assertFalse(body(alice.request).contains("bob-private"));assertFalse(body(bob.request).contains("alice-private"));
    }
    @Test void upstreamErrorsNeverForwardProviderBodyOrSecrets()throws Exception{
        var failed=SharedModelHttp.request(new FakeClient(401,"secret-key-and-provider-stacktrace"),ServerApiConfigTest.config(),input("test"));
        var error=assertThrows(ExecutionException.class,()->failed.get(3,TimeUnit.SECONDS));assertTrue(error.getCause().getMessage().contains("HTTP401"));assertFalse(error.toString().contains("secret-key"));
    }
    @Test void cancellingOneModelCallCancelsItsUpstreamWithoutTouchingAnother()throws Exception{
        var alice=new FakeClient(200,"");alice.delay=true;var bob=new FakeClient(200,"");bob.delay=true;
        var a=SharedModelHttp.request(alice,ServerApiConfigTest.config(),input("alice"));var b=SharedModelHttp.request(bob,ServerApiConfigTest.config(),input("bob"));
        assertTrue(a.cancel(true));assertTrue(alice.flight.isCancelled());assertFalse(b.isDone());assertFalse(bob.flight.isCancelled());b.cancel(true);
    }
    private static String body(HttpRequest request)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompletableFuture<Void> done=new CompletableFuture<>();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}public void onError(Throwable e){done.completeExceptionally(e);}public void onComplete(){done.complete(null);}
        });done.get(3,TimeUnit.SECONDS);return bytes.toString(StandardCharsets.UTF_8);
    }
    private static final class FakeClient extends HttpClient {
        final int code;final String response;HttpRequest request;boolean delay;CompletableFuture<?> flight;
        FakeClient(int code,String response){this.code=code;this.response=response;}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.empty();}public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){try{return SSLContext.getDefault();}catch(Exception e){throw new RuntimeException(e);}}public SSLParameters sslParameters(){return new SSLParameters();}public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}public Optional<Executor> executor(){return Optional.empty();}
        public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}
        @SuppressWarnings("unchecked") public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){request=r;HttpResponse<T> value=new HttpResponse<>(){
            public int statusCode(){return code;}public HttpRequest request(){return r;}public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}public T body(){return (T)new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}public Optional<SSLSession> sslSession(){return Optional.empty();}public URI uri(){return r.uri();}public Version version(){return Version.HTTP_1_1;}
        };var future=new CompletableFuture<HttpResponse<T>>();flight=future;if(!delay)future.complete(value);return future;}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return sendAsync(r,h);}
    }
}
