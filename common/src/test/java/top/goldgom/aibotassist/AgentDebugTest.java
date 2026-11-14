package top.goldgom.aibotassist;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentDebugTest {
    @Test void responseMetadataNeverIncludesPromptsImagesArgumentsOrProviderMessages(){
        var raw=JsonParser.parseString("""
          {"choices":[{"message":{"content":"PRIVATE_CHAT","tool_calls":[{"function":{"arguments":"SECRET_ARGS"}}]}}],
           "error":{"type":"invalid_request_error","message":"Bearer SECRET_TOKEN"},"image":"PRIVATE_BASE64"}
          """).getAsJsonObject();
        String out=AgentDebug.responseShape(raw);
        for(String secret:new String[]{"PRIVATE_CHAT","SECRET_ARGS","SECRET_TOKEN","PRIVATE_BASE64"})assertFalse(out.contains(secret));
        assertTrue(out.contains("invalid_request_error"));assertTrue(out.contains("tool_calls=array(1)"));
    }
    @Test void malformedShapesAndUnknownErrorCodesRemainInspectableWithoutRawContent(){
        var raw=JsonParser.parseString("{\"choices\":\"secret\",\"output\":null,\"error\":{\"code\":\"secret-value\"},\"status\":\"incomplete\"}").getAsJsonObject();
        String out=AgentDebug.responseShape(raw);assertTrue(out.contains("choices=string(6)"));assertTrue(out.contains("output=null"));assertTrue(out.contains("incomplete"));assertFalse(out.contains("secret"));
    }
    @Test void stderrIsBoundedCategoricalMetadataNotRawModelText(){
        String out=AgentDebug.stderrSummary("SyntaxError: Unexpected token SECRET_TOKEN, data:image/jpeg;base64,PRIVATE_IMAGE",12345);
        assertEquals("chars=12345 category=SyntaxError",out);assertEquals("invalid_or_redacted",AgentDebug.label("sk-123456789"));assertEquals("invalid_or_redacted",AgentDebug.label("bad\nline"));
    }
    @Test void traceScopesRestoreOuterRequestAndClearWorkerThread(){
        assertNull(AgentDebug.current());
        try(var first=new AgentDebug()){
            try(var second=new AgentDebug()){assertNotEquals(first.id,second.id);assertSame(second,AgentDebug.current());}
            assertSame(first,AgentDebug.current());
        }assertNull(AgentDebug.current());
    }
}
