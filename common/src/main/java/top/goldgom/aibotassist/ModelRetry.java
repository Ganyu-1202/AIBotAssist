package top.goldgom.aibotassist;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Model generation only. Never wrap game tools or an entire agent turn in this policy. */
public final class ModelRetry {
    public static final int MAX_ATTEMPTS=3;
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"aibot-model-retry");t.setDaemon(true);return t;});
    private ModelRetry(){}
    public interface Retryable {boolean retryable();}
    public static class HttpFailure extends RuntimeException implements Retryable {
        public final int code;
        public HttpFailure(int code){super("Model HTTP "+code);this.code=code;}
        public boolean retryable(){return code==408||code==429||code>=500&&code<=599;}
    }
    public static Throwable cause(Throwable e){while((e instanceof CompletionException||e instanceof ExecutionException)&&e.getCause()!=null)e=e.getCause();return e;}
    public static boolean retryable(Throwable error){
        Throwable e=cause(error);
        if(e instanceof InterruptedException||e instanceof CancellationException)return false;
        if(e instanceof Retryable r)return r.retryable();
        return e instanceof IOException||e instanceof TimeoutException||e instanceof com.google.gson.JsonParseException;
    }
    static long delay(int attempt){return 1000L*attempt;}
    public static <T>T call(Callable<T> request)throws Exception{return call(request,Thread::sleep);}
    @FunctionalInterface interface Sleeper{void sleep(long millis)throws InterruptedException;}
    static <T>T call(Callable<T> request,Sleeper sleep)throws Exception{
        for(int attempt=1;;attempt++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            AgentDebug.note("model_attempt","attempt="+attempt+" max="+MAX_ATTEMPTS);
            try{return request.call();}catch(Exception e){
                if(attempt>=MAX_ATTEMPTS||!retryable(e))throw e;
                AgentDebug.note("model_retry","attempt="+(attempt+1)+" delay_ms="+delay(attempt)+" type="+AgentDebug.label(e.getClass().getName()));
                sleep.sleep(delay(attempt));
            }
        }
    }
    public static <T>CompletableFuture<T> async(Supplier<CompletableFuture<T>> request){return async(request,ModelRetry::delay);}
    static <T>CompletableFuture<T> async(Supplier<CompletableFuture<T>> request,java.util.function.IntToLongFunction backoff){
        var result=new CompletableFuture<T>();var current=new AtomicReference<CompletableFuture<T>>();
        var scheduled=new AtomicReference<ScheduledFuture<?>>();
        var trace=AgentDebug.current();
        class Attempt {
            void run(int number){
                synchronized(result){
                    if(result.isDone())return;
                    if(trace!=null)trace.event("model_attempt","attempt="+number+" max="+MAX_ATTEMPTS);
                    CompletableFuture<T> flight;
                    try{flight=request.get();}catch(Exception e){flight=CompletableFuture.failedFuture(e);}
                    current.set(flight);
                    flight.whenComplete((value,error)->{
                        synchronized(result){
                            if(result.isDone())return;
                            if(error==null){result.complete(value);return;}
                            if(number>=MAX_ATTEMPTS||!retryable(error)){result.completeExceptionally(cause(error));return;}
                            long wait=backoff.applyAsLong(number);
                            if(trace!=null)trace.event("model_retry","attempt="+(number+1)+" delay_ms="+wait);
                            scheduled.set(TIMER.schedule(()->run(number+1),wait,TimeUnit.MILLISECONDS));
                        }
                    });
                }
            }
        }
        result.whenComplete((v,e)->{synchronized(result){var s=scheduled.get();if(s!=null)s.cancel(false);var f=current.get();if(f!=null&&!f.isDone())f.cancel(true);}});
        new Attempt().run(1);return result;
    }
    public static com.google.gson.JsonObject validateMessage(com.google.gson.JsonObject message){
        if(message==null)throw new ModelProtocol.ResponseFailure("模型没有返回有效消息。");
        try{
            var calls=message.getAsJsonArray("tool_calls");
            if(calls!=null)for(var entry:calls){var call=entry.getAsJsonObject();var fn=call.getAsJsonObject("function");
                if(call.get("id").getAsString().isBlank()||fn.get("name").getAsString().isBlank())throw new IllegalArgumentException();
                if(!com.google.gson.JsonParser.parseString(fn.get("arguments").getAsString()).isJsonObject())throw new IllegalArgumentException();
            }
            boolean text=message.has("content")&&!message.get("content").isJsonNull()&&!message.get("content").getAsString().isBlank();
            if(!text&&(calls==null||calls.isEmpty()))throw new IllegalArgumentException();
        }catch(RuntimeException e){throw new ModelProtocol.ResponseFailure("模型消息或工具参数不完整；本轮工具尚未执行。");}
        return message;
    }
}
