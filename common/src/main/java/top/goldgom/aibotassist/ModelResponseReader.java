package top.goldgom.aibotassist;

import com.google.gson.*;
import java.util.*;

/** Decodes a size-bounded HTTP body. Only completed output items may reach game tools. */
public final class ModelResponseReader {
    private ModelResponseReader() {}
    public static JsonObject parse(String body,String contentType) {
        String trimmed=body.stripLeading();
        if(!contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")
            && !trimmed.startsWith("data:") && !trimmed.startsWith("event:") && !trimmed.startsWith(":"))
            return JsonParser.parseString(body).getAsJsonObject();
        AgentDebug.phase("responses_stream_decode");
        var state=new State();StringBuilder data=new StringBuilder();String event="";
        for(String line:body.split("\\r\\n|\\n|\\r",-1)) {
            if(line.isEmpty()) {
                if(!data.isEmpty())state.accept(event,data.toString());
                data.setLength(0);event="";
            } else if(line.startsWith("data:")) {
                if(!data.isEmpty())data.append('\n');data.append(value(line,5));
            } else if(line.startsWith("event:"))event=value(line,6);
        }
        if(!data.isEmpty())state.accept(event,data.toString());
        return state.finish();
    }
    private static String value(String line,int offset){String value=line.substring(offset);return value.startsWith(" ")?value.substring(1):value;}
    private static final class State {
        final SortedMap<Integer,JsonObject> items=new TreeMap<>();
        JsonObject terminal;int events;
        void accept(String event,String data) {
            if(data.equals("[DONE]"))return;
            JsonObject envelope=JsonParser.parseString(data).getAsJsonObject();events++;
            String type=envelope.has("type")?envelope.get("type").getAsString():event;
            if(type.equals("error")||type.equals("response.failed")||type.equals("response.incomplete")||type.equals("response.cancelled"))
                throw new ModelProtocol.ResponseFailure("模型流式响应失败或未完成；未执行本轮工具。",transientError(envelope));
            if(type.equals("response.output_item.done")) {
                int index=envelope.get("output_index").getAsInt();
                if(index<0||index>=4096)throw new ModelProtocol.ResponseFailure("模型输出序号超出限制。");
                JsonObject item=envelope.getAsJsonObject("item");
                if(item==null)throw new ModelProtocol.ResponseFailure("模型已完成输出缺少内容。");
                items.put(index,item.deepCopy());
            } else if(type.equals("response.completed")||type.equals("response.done")) {
                if(terminal!=null)throw new ModelProtocol.ResponseFailure("模型返回重复完成事件。");
                terminal=envelope.getAsJsonObject("response");
                if(terminal==null||!terminal.has("status")||!"completed".equals(terminal.get("status").getAsString())
                    ||terminal.has("error")&&!terminal.get("error").isJsonNull())
                    throw new ModelProtocol.ResponseFailure("模型流式响应未成功完成；未执行本轮工具。",false);
            }
        }
        private boolean transientError(JsonObject envelope){
            JsonObject error=envelope.get("error") instanceof JsonObject e?e:envelope;
            if(envelope.get("response") instanceof JsonObject r&&r.get("error") instanceof JsonObject e)error=e;
            for(String key:List.of("type","code"))if(error.get(key) instanceof JsonPrimitive p&&p.isString()&&Set.of("server_error","rate_limit_error","rate_limit_exceeded","timeout").contains(p.getAsString()))return true;
            return false;
        }
        JsonObject finish() {
            if(terminal==null)throw new ModelProtocol.ResponseFailure("模型流式响应中断，缺少完成事件；未执行本轮工具。");
            JsonArray output=terminal.getAsJsonArray("output");
            // A populated final output is authoritative. Empty envelopes must not erase completed items.
            boolean restored=(output==null||output.isEmpty())&&!items.isEmpty();
            if(restored){output=new JsonArray();items.values().forEach(output::add);terminal.add("output",output);}
            AgentDebug.note("responses_stream","events="+events+" completed_items="+items.size()+" restored="+restored+" output="+AgentDebug.shape(output));
            return terminal;
        }
    }
}
