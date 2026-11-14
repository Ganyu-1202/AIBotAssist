package top.goldgom.aibotassist;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerApiConfigTest {
    @TempDir Path temp;
    @Test void freshServersDefaultToIndependentAndPersistWithoutCredentials()throws Exception{
        Path file=temp.resolve("api.json");var c=ServerApiConfig.load(file);assertEquals("independent",c.mode);assertFalse(c.shared());assertEquals("",c.apiKey);
        assertEquals("independent",ServerApiConfig.load(file).mode);
    }
    @Test void sharedCredentialsNeverAppearInEitherPublicOrAdminViews(){
        var c=config();String normal=c.publicView("revision",false).toString(),admin=c.publicView("revision",true).toString();
        assertFalse(normal.contains(c.apiKey));assertFalse(admin.contains(c.apiKey));assertFalse(normal.contains("api_key"));assertFalse(normal.contains(c.baseUrl));
        assertTrue(c.publicView("revision",true).get("key_configured").getAsBoolean());assertFalse(c.publicView("revision",false).get("can_manage").getAsBoolean());
    }
    @Test void sharedRequiresCredentialsAndBlankUpdatesRetainExistingKey(){
        var fresh=new ServerApiConfig();JsonObject a=JsonParser.parseString("{\"mode\":\"shared\",\"model\":\"fixed-model\"}").getAsJsonObject();assertThrows(IllegalArgumentException.class,()->fresh.updated(a));
        var c=config();a.addProperty("api_key","");assertEquals(c.apiKey,c.updated(a).apiKey);
        a.addProperty("clear_key",true);var old=c;assertThrows(IllegalArgumentException.class,()->old.updated(a));a.addProperty("mode","independent");assertEquals("",c.updated(a).apiKey);
    }
    @Test void savedAdminChangesRoundTripAndInvalidReloadDoesNotAcceptBadConfiguration()throws Exception{
        var c=config();Path p=temp.resolve("server-api.json");c.save(p);var loaded=ServerApiConfig.load(p);assertEquals(c.apiKey,loaded.apiKey);assertEquals("shared",loaded.mode);
        Files.writeString(p,"{\"mode\":\"shared\"}");assertThrows(IllegalArgumentException.class,()->ServerApiConfig.load(p));
    }
    @Test void rejectsHeaderInjectionCredentialUrlsAndUnboundedLimits(){
        for(String url:new String[]{"http://example.test/v1","https://secret@example.test/v1","https://example.test/v1?api_key=x","https://example.test/v1#x"}){var c=config();c.baseUrl=url;assertThrows(IllegalArgumentException.class,c::validate);}
        var c=config();c.apiKey="secret\r\nX-Injected: yes";assertThrows(IllegalArgumentException.class,c::validate);c=config();c.maxConcurrent=100;assertThrows(IllegalArgumentException.class,c::validate);
    }
    static ServerApiConfig config(){var c=new ServerApiConfig();c.mode="shared";c.baseUrl="https://provider.example/v1";c.model="fixed-model";c.apiKey="server-only-secret-123";c.validate();return c;}
}
