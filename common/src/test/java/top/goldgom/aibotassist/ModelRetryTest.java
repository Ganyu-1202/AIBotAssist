package top.goldgom.aibotassist;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelRetryTest {
    @Test void transientModelErrorsRetryOnlyUntilSuccess()throws Exception{
        var calls=new AtomicInteger();var waits=new ArrayList<Long>();
        String value=ModelRetry.call(()->{if(calls.incrementAndGet()<3)throw new ModelRetry.HttpFailure(503);return "ok";},waits::add);
        assertEquals("ok",value);assertEquals(3,calls.get());assertEquals(List.of(1000L,2000L),waits);
    }
    @Test void permanentAndCancellationErrorsNeverRetry(){
        for(Exception error:List.of(new ModelRetry.HttpFailure(401),new ModelRetry.HttpFailure(403),new ModelRetry.HttpFailure(400),new InterruptedException(),new CancellationException(),new ModelProtocol.ResponseFailure("policy",false))){
            var calls=new AtomicInteger();assertThrows(Exception.class,()->ModelRetry.call(()->{calls.incrementAndGet();throw error;},ms->fail("must not sleep")));assertEquals(1,calls.get());
        }
    }
    @Test void exhaustionAndInterruptedBackoffStop(){
        var calls=new AtomicInteger();assertThrows(IOException.class,()->ModelRetry.call(()->{calls.incrementAndGet();throw new IOException();},ms->{}));assertEquals(3,calls.get());
        calls.set(0);assertThrows(InterruptedException.class,()->ModelRetry.call(()->{calls.incrementAndGet();throw new IOException();},ms->{throw new InterruptedException();}));assertEquals(1,calls.get());
    }
    @Test void malformedArgumentsRetryBeforeToolCanExecute()throws Exception{
        var calls=new AtomicInteger();var executions=new AtomicInteger();
        var message=ModelRetry.call(()->ModelRetry.validateMessage(JsonParser.parseString(calls.incrementAndGet()==1?"{\"tool_calls\":[{\"id\":\"1\",\"function\":{\"name\":\"build\",\"arguments\":\"{broken\"}}]}":"{\"tool_calls\":[{\"id\":\"2\",\"function\":{\"name\":\"build\",\"arguments\":\"{}\"}}]}").getAsJsonObject()),ms->{});
        for(var call:message.getAsJsonArray("tool_calls"))executions.incrementAndGet();assertEquals(2,calls.get());assertEquals(1,executions.get());
    }
    @Test void asyncRetriesRemainWithinOneOperation()throws Exception{
        var calls=new AtomicInteger();var result=ModelRetry.async(()->calls.incrementAndGet()<3?CompletableFuture.failedFuture(new ModelRetry.HttpFailure(429)):CompletableFuture.completedFuture("ok"),n->0);
        assertEquals("ok",result.get(2,TimeUnit.SECONDS));assertEquals(3,calls.get());
    }
    @Test void asyncCancellationCancelsUpstreamAndScheduledRetry()throws Exception{
        var upstream=new CompletableFuture<String>();var calls=new AtomicInteger();
        var active=ModelRetry.async(()->{calls.incrementAndGet();return upstream;},n->0);active.cancel(true);assertTrue(upstream.isCancelled());assertEquals(1,calls.get());
        var scheduled=ModelRetry.async(()->{calls.incrementAndGet();return CompletableFuture.failedFuture(new IOException());},n->60000);scheduled.cancel(true);assertEquals(2,calls.get());assertTrue(scheduled.isCancelled());
    }
}
