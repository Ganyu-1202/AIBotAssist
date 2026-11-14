package top.goldgom.aibotassist.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class TokenNestOAuthTest {
    @TempDir Path dir;
    @Test void authorizationUsesPublicClientWithPkceAndMatchingLoopback() {
        String verifier="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",TokenNestOAuth.challenge(verifier));
        URI uri=TokenNestOAuth.authorization(TokenNestOAuth.DEFAULT_CLIENT_ID,54321,"test-state",verifier);
        var q=TokenNestOAuth.callbackQuery(uri.getRawQuery()+"&code=test", "test-state");
        assertEquals("http://127.0.0.1:54321/aibotassist/callback",q.get("redirect_uri"));
        assertEquals("api offline_access groups:read",q.get("scope"));
        assertEquals(TokenNestOAuth.DEFAULT_CLIENT_ID,q.get("client_id"));
        assertEquals("S256",q.get("code_challenge_method"));
        assertFalse(q.containsKey("client_secret"));assertFalse(q.containsKey("code_verifier"));
    }
    @Test void callbackRejectsForgeryDuplicatesAndAmbiguousResults() {
        for(String raw:List.of("state=wrong&code=x","state=s&state=s&code=x","state=s&code=",
                "state=s&code=x&error=access_denied","state=s&code=x&error=","state=s&code=%ZZ","code=x"))
            assertThrows(IllegalArgumentException.class,()->TokenNestOAuth.callbackQuery(raw,"s"));
        assertEquals("access_denied",TokenNestOAuth.callbackQuery("state=s&error=access_denied","s").get("error"));
    }
    @Test void defaultClientIdMigrationPreservesExistingApiKeys() throws Exception {
        var store=new LocalCredentials(dir);var settings=new ClientSettings();
        store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("test-key","","",0,settings.baseUrl,""));
        var connection=new AiConnection(store);
        try {connection.enter("world",settings);assertTrue(connection.ready());assertEquals("auth_api_key",connection.authStatus());}
        finally {connection.logout();}
    }
    @Test void invalidHttpCallbacksKeepLoginAliveAndDenialClosesIt() throws Exception {
        AiConnection connection=new AiConnection(new LocalCredentials(dir));
        var settings=new ClientSettings();connection.enter("world-a",settings);
        List<String> messages=new CopyOnWriteArrayList<>();
        try {
            URI auth=connection.login(settings,messages::add);
            var q=TokenNestOAuth.callbackQuery(auth.getRawQuery()+"&code=test",extractState(auth));
            String redirect=q.get("redirect_uri");
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).proxy(new ProxySelector() {
                public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,java.net.SocketAddress address,java.io.IOException error){}
            }).build();
            var invalid=http.send(HttpRequest.newBuilder(URI.create(redirect+"?state=forged&code=x")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,invalid.statusCode());assertTrue(connection.loginPending());assertTrue(messages.isEmpty());
            var denial=http.send(HttpRequest.newBuilder(URI.create(redirect+"?state="+q.get("state")+"&error=access_denied")).timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,denial.statusCode());assertEquals("no-store",denial.headers().firstValue("Cache-Control").orElseThrow());
            // The HTTP body may finish just before the handler closes its listener.
            long until=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            while(connection.loginPending()&&System.nanoTime()<until)Thread.sleep(10);
            assertFalse(connection.loginPending());assertFalse(connection.ready());assertEquals(1,messages.size());
            connection.login(settings,messages::add);assertTrue(connection.loginPending());
            connection.enter("world-b",settings);assertFalse(connection.loginPending());
        } finally { connection.logout(); }
    }
    @Test void tokenNestCredentialsCannotBeRefreshedOrSentToCustomApi() throws Exception {
        var store=new LocalCredentials(dir);var settings=new ClientSettings();settings.baseUrl="https://other.example/v1";
        store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("","","refresh-secret",0,settings.baseUrl,settings.clientId));
        var connection=new AiConnection(store);connection.enter("world",settings);
        try {
            assertThrows(IllegalArgumentException.class,()->connection.agentTurn(settings,new JsonArray(),new JsonArray()));
            assertThrows(IllegalArgumentException.class,()->connection.login(settings,message->{}));
            assertFalse(connection.loginPending());
        } finally { connection.logout(); }
    }
    @Test void successfulCallbackExchangesPkceAndPersistsOnlyForInitiatingWorld() throws Exception {
        var settings=new ClientSettings();var store=new LocalCredentials(dir);
        var authorized=new java.util.concurrent.atomic.AtomicInteger();
        List<String> forms=new CopyOnWriteArrayList<>();
        var connection=new AiConnection(store,(s,form)->{
            forms.add(form);JsonObject tokens=new JsonObject();tokens.addProperty("access_token","test-access");
            tokens.addProperty("refresh_token","test-refresh");tokens.addProperty("token_type","Bearer");tokens.addProperty("expires_in",3600);return tokens;
        });
        try {
            connection.enter("world-a",settings);
            URI auth=connection.login(settings,message->{},()->{assertTrue(connection.ready());authorized.incrementAndGet();});
            String state=extractState(auth);
            var q=TokenNestOAuth.callbackQuery(auth.getRawQuery()+"&code=test",state);
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            URI callback=URI.create(q.get("redirect_uri")+"?state="+state+"&code=test-code");
            var reply=http.send(HttpRequest.newBuilder(callback).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(reply.body().contains("Signed in"));assertEquals(1,forms.size());
            assertEquals(1,authorized.get());
            var form=TokenNestOAuth.callbackQuery(forms.getFirst()+"&state="+state,state);
            assertEquals(q.get("code_challenge"),TokenNestOAuth.challenge(form.get("code_verifier")));
            assertEquals(q.get("redirect_uri"),form.get("redirect_uri"));
            assertEquals(settings.clientId,form.get("client_id"));assertFalse(form.containsKey("client_secret"));
            assertTrue(connection.ready());
            var saved=store.load(LocalCredentials.scope("world-a"));assertEquals("test-access",saved.access());assertEquals("test-refresh",saved.refresh());
            assertNull(store.load(LocalCredentials.scope("world-b")));
            connection.enter("world-b",settings);assertFalse(connection.ready());
            connection.enter("world-a",settings);assertTrue(connection.ready());
        } finally { connection.logout(); }
    }
    private static String extractState(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).filter(p->p.startsWith("state=")).findFirst().orElseThrow().substring(6);
    }
}
