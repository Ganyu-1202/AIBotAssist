package top.goldgom.aibotassist.client;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentModelsTest {
    @TempDir Path dir;
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static JsonObject catalog(){return json("""
        {"data":[
          {"id":"responses-only","supported_endpoint_types":["openai-response"]},
          {"id":"chat-model","supported_endpoint_types":["openai","openai-response"]},
          {"id":"chat-model","supported_endpoint_types":["openai"]},
          {"id":"image-model","supported_endpoint_types":["image-generation"]}
        ]}
        """);}
    @Test void catalogSelectsChatEndpointAndDeduplicatesWithoutInventingModels() {
        assertEquals(List.of("responses-only","chat-model"),AgentModels.parse(catalog()));
        assertEquals("responses-only",AgentModels.select("",AgentModels.parse(catalog())));
        assertEquals("responses-only",AgentModels.select("my-configured-model",AgentModels.parse(catalog())));
        assertThrows(IllegalStateException.class,()->AgentModels.select("",List.of()));
    }
    @Test void genericCatalogSupportsCustomIdsAndRejectsMalformedOrNonChatEntries() {
        var result=AgentModels.parse(json("""
            {"data":[{"id":"custom-chat"},{"id":"text-embedding-3-small"},{"id":"whisper-1"},
              {"id":"gpt-image-1"},{"id":false},{"id":""},{"id":"bad\\nmodel"},null]}
            """));
        assertEquals(List.of("custom-chat"),result);
        assertThrows(IllegalArgumentException.class,()->AgentModels.parse(json("{\"success\":false}")));
    }
    @Test void restoredLoginWithBlankModelCanDiscoverAndCallAgentWithoutAnotherLogin() throws Exception {
        var settings=new ClientSettings();var store=new LocalCredentials(dir);
        store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("","saved-access","saved-refresh",
            System.currentTimeMillis()+3600_000,settings.baseUrl,settings.clientId));
        List<String> paths=new ArrayList<>();
        var connection=new AiConnection(store,(s,form)->{throw new AssertionError("A valid login must not be requested again");},(s,token,path,body)->{
            assertEquals("saved-access",token);paths.add(path);
            if(path.equals("/models")){assertNull(body);return catalog();}
            assertEquals("responses-only",body.get("model").getAsString());assertEquals("/responses",path);
            return json("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ready\"}]}]}");
        });
        try {
            connection.enter("world",settings);assertTrue(connection.ready());assertTrue(settings.model.isBlank());
            settings.model=AgentModels.select(settings.model,connection.listModels(settings));
            assertEquals("ready",connection.agentTurn(settings,new JsonArray(),new JsonArray()).get("content").getAsString());
            assertEquals(List.of("/models","/responses"),paths);
        } finally {connection.logout();}
    }
    @Test void modelLookupFailureDoesNotClearTheSuccessfulLogin() throws Exception {
        var settings=new ClientSettings();var store=new LocalCredentials(dir);
        store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("","access","refresh",
            System.currentTimeMillis()+3600_000,settings.baseUrl,settings.clientId));
        var connection=new AiConnection(store,(s,form)->{throw new AssertionError();},(s,token,path,body)->{throw new java.io.IOException("offline");});
        try {
            connection.enter("world",settings);
            assertThrows(java.io.IOException.class,()->connection.listModels(settings));
            assertTrue(connection.ready());assertEquals("auth_logged_in",connection.authStatus());
            assertEquals("refresh",store.load(LocalCredentials.scope("world")).refresh());
        } finally {connection.logout();}
    }
    @Test void expiredAccessIsRefreshedBeforeModelDiscoveryAndRotationIsSaved() throws Exception {
        var settings=new ClientSettings();var store=new LocalCredentials(dir);
        store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("","expired-access","old-refresh",0,settings.baseUrl,settings.clientId));
        var connection=new AiConnection(store,(s,form)->{
            assertTrue(form.startsWith("grant_type=refresh_token&"));assertTrue(form.contains("refresh_token=old-refresh"));
            return json("{\"access_token\":\"fresh-access\",\"refresh_token\":\"rotated-refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        },(s,token,path,body)->{assertEquals("fresh-access",token);return catalog();});
        try {
            connection.enter("world",settings);long version=connection.sessionVersion();
            assertEquals(List.of("responses-only","chat-model"),connection.listModels(settings));
            assertEquals(version,connection.sessionVersion());
            assertEquals("rotated-refresh",store.load(LocalCredentials.scope("world")).refresh());
        } finally {connection.logout();}
    }
    @Test void modelResultFromThePreviousWorldIsDiscarded() throws Exception {
        var settings=new ClientSettings();var store=new LocalCredentials(dir);
        store.save(LocalCredentials.scope("world-a"),new LocalCredentials.Secret("key","","",0,settings.baseUrl,settings.clientId));
        var requested=new CountDownLatch(1);var release=new CountDownLatch(1);
        var connection=new AiConnection(store,(s,form)->{throw new AssertionError();},(s,token,path,body)->{
            requested.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return catalog();
        });
        var worker=Executors.newSingleThreadExecutor();
        try {
            connection.enter("world-a",settings);
            Future<List<String>> future=worker.submit(()->connection.listModels(settings));
            assertTrue(requested.await(3,TimeUnit.SECONDS));connection.enter("world-b",settings);release.countDown();
            ExecutionException error=assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class,error.getCause());assertFalse(connection.ready());
        } finally {release.countDown();connection.logout();worker.shutdownNow();}
    }
}
