package top.goldgom.aibotassist;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelResponseReaderTest {
    static final String CALL="{\"type\":\"function_call\",\"call_id\":\"call-test\",\"name\":\"observe_world\",\"arguments\":\"{}\"}";
    static final String TEXT="{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"完成\"}]}";
    static String done(int index,String item){return "data: {\"type\":\"response.output_item.done\",\"output_index\":"+index+",\"item\":"+item+"}\n\n";}
    static final String END="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\ndata: [DONE]\n\n";
    @Test void emptyTerminalRetainsCompletedToolCallWithoutExecutingDeltas(){
        String stream="data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"BROKEN_PARTIAL\"}\n\n"+done(0,CALL)+END;
        var raw=ModelResponseReader.parse(stream,"text/event-stream; charset=utf-8");
        var message=ModelProtocol.message("responses",raw);var call=message.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call-test",call.get("id").getAsString());assertEquals("{}",call.getAsJsonObject("function").get("arguments").getAsString());
        assertEquals(1,message.getAsJsonArray("tool_calls").size());
    }
    @Test void reconstructsOrderedTextAndToolsDespiteDuplicateDoneEvents(){
        var raw=ModelResponseReader.parse(": keepalive\n\n"+done(1,CALL)+done(0,TEXT)+done(1,CALL)+END,"");
        assertEquals("message",raw.getAsJsonArray("output").get(0).getAsJsonObject().get("type").getAsString());
        var message=ModelProtocol.message("responses",raw);assertEquals("完成",message.get("content").getAsString());assertEquals(1,message.getAsJsonArray("tool_calls").size());
    }
    @Test void populatedTerminalWinsWithoutDuplicatingItems(){
        String end="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["+TEXT+"]}}\n\n";
        var message=ModelProtocol.message("responses",ModelResponseReader.parse(done(0,CALL)+end,"text/event-stream"));
        assertEquals("完成",message.get("content").getAsString());assertFalse(message.has("tool_calls"));
    }
    @Test void interruptedOrFailedStreamsCannotReleaseCompletedTools(){
        for(String end:new String[]{"","data: [DONE]\n\n","data: {\"type\":\"response.failed\",\"error\":{\"message\":\"PRIVATE\"}}\n\n","data: {\"type\":\"response.incomplete\"}\n\n"}){
            var failure=assertThrows(ModelProtocol.ResponseFailure.class,()->ModelResponseReader.parse(done(0,CALL)+end,"text/event-stream"));
            assertFalse(failure.getMessage().contains("PRIVATE"));
        }
    }
    @Test void doesNotInventOutputFromAddedItemOrTreatEmptyOutputAsSuccess(){
        var raw=ModelResponseReader.parse("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":"+CALL+"}\n\n"+END,"text/event-stream");
        assertThrows(ModelProtocol.ResponseFailure.class,()->ModelProtocol.message("responses",raw));
    }
    @Test void handlesCrLfMultilineDataEventFieldAndUnknownMetadata(){
        String stream="event: response.output_item.done\ndata: {\"output_index\":0,\ndata: \"item\":"+TEXT+"}\n\n";
        stream="data: {\"type\":\"codex.rate_limits\"}\n\n"+stream+END;
        assertEquals("完成",ModelProtocol.message("responses",ModelResponseReader.parse(stream.replace("\n","\r\n"),"text/event-stream")).get("content").getAsString());
    }
    @Test void jsonProvidersStillWorkAndFailedTerminalIsRejected(){
        var raw=ModelResponseReader.parse("{\"status\":\"completed\",\"output\":["+TEXT+"]}","application/json");
        assertEquals("完成",ModelProtocol.message("responses",raw).get("content").getAsString());
        assertThrows(ModelProtocol.ResponseFailure.class,()->ModelResponseReader.parse(done(0,CALL)+END.replace("\"status\":\"completed\"","\"status\":\"incomplete\""),"text/event-stream"));
    }
}
