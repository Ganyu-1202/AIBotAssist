package top.goldgom.aibotassist.client;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModelRoutingTest {
    @TempDir Path dir;
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    @Test void channelDefaultsAndRequestHeadersApplyOnlyToTokenNest(){
        var s=new ClientSettings();assertEquals("default",s.group);assertEquals("auto",s.modelProtocol);
        assertEquals("default",AiConnection.apiHttpRequest(s,"test","/models",null).headers().firstValue("X-TokenNest-Group").orElseThrow());
        s.group="vip";var body=new JsonObject();
        assertEquals("vip",AiConnection.apiHttpRequest(s,"test","/responses",body).headers().firstValue("X-TokenNest-Group").orElseThrow());
        assertEquals(TokenNestOAuth.ISSUER+"/api/oauth2/groups",AiConnection.apiHttpRequest(s,"test","/api/oauth2/groups",null).uri().toString());
        s.baseUrl="https://custom.example/v1";assertTrue(AiConnection.apiHttpRequest(s,"test","/models",null).headers().firstValue("X-TokenNest-Group").isEmpty());
    }
    @Test void endpointSelectionUsesMetadataAndReplacesUnavailableModel(){
        var catalog=json("""
            {"data":[{"id":"both-image-chat","supported_endpoint_types":["openai","openai-response"]},
             {"id":"response-model","supported_endpoint_types":["openai-response"]},
             {"id":"claude-only","supported_endpoint_types":["anthropic"]},
             {"id":"image-only","supported_endpoint_types":["image-generation"]}]}
            """);
        assertEquals(Map.of("both-image-chat","chat","response-model","responses"),AgentModels.catalog(catalog));
        assertEquals(Map.of("both-image-chat","responses","response-model","responses"),AgentModels.catalog(catalog,"responses"));
        assertEquals(Map.of("both-image-chat","chat"),AgentModels.catalog(catalog,"chat"));
        assertEquals("response-model",AgentModels.select("old-model",List.of("response-model")));
    }
    @Test void changingChannelInvalidatesCachedModelTypeWithoutClearingLogin()throws Exception{
        var s=new ClientSettings();var store=new LocalCredentials(dir);store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("key","","",0,s.baseUrl,s.clientId));
        List<String> paths=new ArrayList<>();
        var c=new AiConnection(store,(settings,form)->{throw new AssertionError();},(settings,token,path,body)->{
            paths.add(settings.group+path);
            if(path.equals("/models"))return json("{\"data\":[{\"id\":\"model\",\"supported_endpoint_types\":[\""+(settings.group.equals("default")?"openai":"openai-response")+"\"]}]}");
            if(path.equals("/responses"))return json("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ready\"}]}]}");
            return json("{\"choices\":[{\"message\":{\"content\":\"ready\"}}]}");
        });
        try{c.enter("world",s);s.model="model";c.agentTurn(s,new JsonArray(),new JsonArray());s.group="vip";c.agentTurn(s,new JsonArray(),new JsonArray());
            assertEquals(List.of("default/models","default/chat/completions","vip/models","vip/responses"),paths);assertTrue(c.ready());
        }finally{c.logout();}
    }
    @Test void apiKeyCannotPretendToDiscoverOAuthGroups()throws Exception{
        var s=new ClientSettings();var c=new AiConnection(new LocalCredentials(dir),(settings,form)->{throw new AssertionError();},(settings,token,path,body)->{throw new AssertionError();});
        try{c.enter("world",s);c.setKey("test-key");assertThrows(IllegalStateException.class,()->c.listGroups(s));assertTrue(c.ready());}finally{c.logout();}
    }
    @Test void manualModelStillWorksWhenCustomProviderHasNoCatalog()throws Exception{
        var s=new ClientSettings();s.baseUrl="https://custom.example/v1";s.model="manual-model";
        var store=new LocalCredentials(dir);store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("key","","",0,s.baseUrl,s.clientId));
        List<String> paths=new ArrayList<>();
        var c=new AiConnection(store,(settings,form)->{throw new AssertionError();},(settings,token,path,body)->{
            paths.add(path);if(path.equals("/models"))throw new java.io.IOException("unsupported catalog");
            assertEquals("/chat/completions",path);assertEquals("manual-model",body.get("model").getAsString());return json("{\"choices\":[{\"message\":{\"content\":\"ready\"}}]}");
        });
        try{c.enter("world",s);c.agentTurn(s,new JsonArray(),new JsonArray());c.agentTurn(s,new JsonArray(),new JsonArray());assertEquals(List.of("/models","/chat/completions","/chat/completions"),paths);}finally{c.logout();}
    }
    @Test void oauthListsAuthorizedGroupsWithoutExposingOrReplacingCredentials()throws Exception{
        var s=new ClientSettings();var store=new LocalCredentials(dir);store.save(LocalCredentials.scope("world"),new LocalCredentials.Secret("","access","refresh",System.currentTimeMillis()+3600000,s.baseUrl,s.clientId));
        var c=new AiConnection(store,(settings,form)->{throw new AssertionError();},(settings,token,path,body)->{assertEquals("/api/oauth2/groups",path);assertEquals("access",token);return json("{\"success\":true,\"data\":{\"default\":{},\"vip\":{}}}");});
        try{c.enter("world",s);assertEquals(List.of("default","vip"),c.listGroups(s));assertEquals("refresh",store.load(LocalCredentials.scope("world")).refresh());}finally{c.logout();}
    }
}
