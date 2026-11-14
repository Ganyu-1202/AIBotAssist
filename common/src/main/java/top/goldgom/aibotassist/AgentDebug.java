package top.goldgom.aibotassist;

import com.google.gson.*;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Metadata-only traces: never serialize prompts, arguments, HTTP bodies or exception messages. */
public final class AgentDebug implements AutoCloseable {
    private static final ThreadLocal<AgentDebug> CURRENT=new ThreadLocal<>();
    public final String id;
    private final AgentDebug previous=CURRENT.get();
    private final long started=System.nanoTime();
    private String stage="start";
    private int events;
    public AgentDebug(){this(UUID.randomUUID().toString().substring(0,12));}
    public AgentDebug(String id){this.id=label(id);CURRENT.set(this);}
    public static AgentDebug current(){return CURRENT.get();}
    public String stage(){return stage;}
    public void stage(String value){stage=label(value);event("stage",stage);}
    public synchronized void event(String name,String metadata){
        if(events++>=512)return;
        LoggerFactory.getLogger("aibotassist").info("AI debug [{}] elapsed_ms={} event={} {}",id,(System.nanoTime()-started)/1_000_000,label(name),metadata);
    }
    public static void note(String name,String metadata){var trace=current();if(trace!=null)trace.event(name,metadata);}
    public static void phase(String stage){var trace=current();if(trace!=null)trace.stage(stage);}
    public static String label(String value){return value!=null&&value.matches("[a-zA-Z0-9_.:/-]{1,128}")&&!value.startsWith("sk-")?value:"invalid_or_redacted";}
    public static String shape(JsonElement value){return value==null?"missing":value.isJsonNull()?"null":value.isJsonArray()?"array("+value.getAsJsonArray().size()+")":value.isJsonObject()?"object":value.getAsJsonPrimitive().isString()?"string("+value.getAsString().length()+")":value.getAsJsonPrimitive().isBoolean()?"boolean":"number";}
    public static String responseShape(JsonObject object){
        return responseShape(object,0);
    }
    private static String responseShape(JsonObject object,int depth){
        if(object==null)return "null";
        StringBuilder out=new StringBuilder();
        for(String field:List.of("choices","output","error","status","content","tool_calls","incomplete_details"))out.append(field).append('=').append(shape(object.get(field))).append(' ');
        for(String field:List.of("status","finish_reason"))if(object.has(field)&&object.get(field).isJsonPrimitive()){
            String s=object.get(field).getAsString();out.append(field).append("_value=").append(Set.of("completed","incomplete","failed","cancelled","stop","length","tool_calls","content_filter").contains(s)?s:"other").append(' ');
        }
        if(depth<2&&object.get("choices") instanceof JsonArray choices&&!choices.isEmpty()&&choices.get(0) instanceof JsonObject choice){
            out.append("choice_message=").append(shape(choice.get("message"))).append(' ');
            if(choice.get("message") instanceof JsonObject message)out.append("message{").append(responseShape(message,depth+1)).append("} ");
        }
        if(object.get("error") instanceof JsonObject error)for(String key:List.of("type","code")){
            JsonElement field=error.get(key);String code=field!=null&&field.isJsonPrimitive()?field.getAsString():"";
            out.append("error_").append(key).append('=').append(Set.of("invalid_request_error","authentication_error","rate_limit_error","server_error","insufficient_quota","model_not_found","context_length_exceeded","content_policy_violation").contains(code)?code:shape(field)).append(' ');
        }
        return out.toString();
    }
    public static void http(java.net.http.HttpResponse<?> response,int bytes){
        String contentType=response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim();
        note("http_response","status="+response.statusCode()+" bytes="+bytes+" content_type="+label(contentType));
        for(String header:List.of("x-request-id","x-oneapi-request-id","x-amzn-requestid","cf-ray"))
            response.headers().firstValue(header).ifPresent(value->note("http_request_id",header+"="+label(value)));
    }
    public void failure(Throwable error){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());int depth=0;
        for(Throwable e=error;e!=null&&depth<8&&seen.add(e);e=e.getCause(),depth++){
            event("exception","stage="+stage+" cause="+depth+" type="+label(e.getClass().getName()));
            for(var frame:Arrays.stream(e.getStackTrace()).limit(10).toList())event("stack",label(frame.getClassName())+"."+label(frame.getMethodName())+":"+frame.getLineNumber());
        }
    }
    /** Raw stderr may contain model text. Retain size and known error categories, never raw lines. */
    public static String stderrSummary(String sample,long total){
        StringBuilder out=new StringBuilder("chars="+total);
        for(String kind:List.of("SyntaxError","TypeError","RangeError","ReferenceError","ENOENT","EACCES","OutOfMemory","Cannot find module"))if(sample.contains(kind))out.append(" category=").append(kind.replace(' ','_'));
        return out.toString();
    }
    @Override public void close(){event("end","stage="+stage);if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
