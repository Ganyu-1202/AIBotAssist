package top.goldgom.aibotassist;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelProtocolTest {
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    @Test void responsesPreservesToolRoundTripAndVisionInOrder(){
        JsonArray messages=JsonParser.parseString("""
            [{"role":"system","content":"Help build"},
             {"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"capture_view","arguments":"{}"}}]},
             {"role":"tool","tool_call_id":"call-1","content":"camera metadata"},
             {"role":"user","content":[{"type":"text","text":"Calibrate"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA","detail":"auto"}}]}]
            """).getAsJsonArray();
        JsonArray tools=JsonParser.parseString("""
            [{"type":"function","function":{"name":"capture_view","description":"See game","parameters":{"type":"object","properties":{}}}}]
            """).getAsJsonArray();
        var body=ModelProtocol.body("responses","chosen",messages,tools);
        assertEquals("/responses",ModelProtocol.path("responses"));assertFalse(body.get("store").getAsBoolean());assertFalse(body.has("messages"));assertEquals(12000,body.get("max_output_tokens").getAsInt());
        var input=body.getAsJsonArray("input");assertEquals(4,input.size());assertEquals("call-1",input.get(1).getAsJsonObject().get("call_id").getAsString());
        assertEquals("function_call_output",input.get(2).getAsJsonObject().get("type").getAsString());assertEquals("camera metadata",input.get(2).getAsJsonObject().get("output").getAsString());
        var image=input.get(3).getAsJsonObject().getAsJsonArray("content").get(1).getAsJsonObject();assertEquals("input_image",image.get("type").getAsString());assertEquals("data:image/png;base64,AAAA",image.get("image_url").getAsString());
        var tool=body.getAsJsonArray("tools").get(0).getAsJsonObject();assertEquals("capture_view",tool.get("name").getAsString());assertFalse(tool.has("function"));assertFalse(tool.get("strict").getAsBoolean());
        assertTrue(messages.get(1).getAsJsonObject().get("content").isJsonNull());assertTrue(tools.get(0).getAsJsonObject().has("function"));
    }
    @Test void responsesNormalizesTextAndFunctionCallWithCallId(){
        var message=ModelProtocol.message("responses",json("""
            {"status":"completed","output":[{"type":"reasoning","summary":[]},
             {"type":"message","content":[{"type":"output_text","text":"Working"}]},
             {"type":"function_call","id":"fc_internal","call_id":"call-public","name":"inspect","arguments":"{}"}]}
            """));
        assertEquals("Working",message.get("content").getAsString());var call=message.getAsJsonArray("tool_calls").get(0).getAsJsonObject();assertEquals("call-public",call.get("id").getAsString());assertEquals("inspect",call.getAsJsonObject("function").get("name").getAsString());
    }
    @Test void incompleteOrEmptyResponsesDoNotBecomeSuccessfulCompletions(){
        assertThrows(IllegalStateException.class,()->ModelProtocol.message("responses",json("{\"status\":\"incomplete\",\"output\":[]}")));
        assertThrows(IllegalStateException.class,()->ModelProtocol.message("responses",json("{\"error\":{\"message\":\"failed\"}}")));
        assertThrows(IllegalArgumentException.class,()->ModelProtocol.message("responses",json("{\"status\":\"completed\",\"output\":[]}")));
    }
    @Test void validatesGroupAndProtocolWithoutAllowingHeaderInjection(){
        ModelProtocol.validate("auto","default",true);ModelProtocol.validate("responses","vip",false);
        assertThrows(IllegalArgumentException.class,()->ModelProtocol.validate("auto","default",false));
        assertThrows(IllegalArgumentException.class,()->ModelProtocol.validate("chat","vip\r\nAuthorization:secret",true));
        assertThrows(IllegalArgumentException.class,()->ModelProtocol.validate("chat","",true));
    }
}
