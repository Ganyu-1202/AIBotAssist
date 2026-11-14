package top.goldgom.aibotassist;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticFilesTest {
    @TempDir Path folder;
    @Test void redactsCommonHeaderJsonAndQueryCredentials(){
        String text=DiagnosticFiles.redact("Authorization: Bearer secret123\n{\"access_token\":\"abc123\",\"password\":\"pass123\"}\n?api_key=key123&x=ok sk-1234567890");
        for(String secret:new String[]{"secret123","abc123","pass123","key123","sk-1234567890"})assertFalse(text.contains(secret));
        assertTrue(text.contains("x=ok"));assertTrue(text.contains("REDACTED"));
    }
    @Test void refusesTraversalAndUnapprovedFiles()throws Exception{
        Path root=Files.createDirectory(folder.resolve("game"));Files.writeString(folder.resolve("private.txt"),"secret");Files.writeString(root.resolve("config.json"),"secret");
        assertThrows(IOException.class,()->DiagnosticFiles.confined(root,"../private.txt"));
        JsonObject args=new JsonObject();args.addProperty("file","config.json");assertThrows(IOException.class,()->DiagnosticFiles.read(root,args));
    }
    @Test void listsReportsAndReadsBoundedTailWithOffsets()throws Exception{
        Files.createDirectories(folder.resolve("logs"));Files.createDirectories(folder.resolve("crash-reports"));
        Files.writeString(folder.resolve("logs/latest.log"),"a".repeat(20000)+"Caused by: TestException");Files.writeString(folder.resolve("crash-reports/crash-2026-11-13-client.txt"),"crash");
        var list=DiagnosticFiles.read(folder,new JsonObject()).getAsJsonArray("files");assertEquals(2,list.size());
        JsonObject args=new JsonObject();args.addProperty("file","logs/latest.log");args.addProperty("length",100);
        var result=DiagnosticFiles.read(folder,args);assertEquals(100,result.get("text").getAsString().length());assertTrue(result.get("truncated").getAsBoolean());assertTrue(result.get("offset").getAsLong()>0);assertEquals(1,result.getAsJsonArray("evidence_lines").size());
        args.addProperty("offset",0);assertEquals("a".repeat(100),DiagnosticFiles.read(folder,args).get("text").getAsString());
    }
}
