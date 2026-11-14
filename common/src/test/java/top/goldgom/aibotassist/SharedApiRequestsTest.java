package top.goldgom.aibotassist;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static top.goldgom.aibotassist.SharedApiRequests.Admission.*;

class SharedApiRequestsTest {
    @Test void simultaneousPlayersHaveIndependentSlotsAndCancellation(){
        var gate=new SharedApiRequests();UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        assertEquals(ACCEPTED,gate.start(alice,"alice1",0,2,0));assertEquals(ACCEPTED,gate.start(bob,"bob1",0,2,0));
        assertFalse(gate.finish(alice,"bob1"));assertTrue(gate.active(bob,"bob1"));assertEquals(BUSY,gate.start(alice,"alice2",1000,2,0));
        assertTrue(gate.finish(alice,"alice1"));assertTrue(gate.active(bob,"bob1"));assertEquals(ACCEPTED,gate.start(alice,"alice2",1000,2,0));
    }
    @Test void duplicateCallsAndCancelCannotBypassPerPlayerRateLimit(){
        var gate=new SharedApiRequests();UUID player=UUID.randomUUID();assertEquals(ACCEPTED,gate.start(player,"first",1000,4,2000));
        gate.finish(player,"first");assertEquals(DUPLICATE,gate.start(player,"first",5000,4,2000));
        assertEquals(THROTTLED,gate.start(player,"second",2000,4,2000));assertEquals(ACCEPTED,gate.start(player,"second",3000,4,2000));
    }
    @Test void globalLimitIsBoundedAndDisconnectInvalidatesOldRequest(){
        var gate=new SharedApiRequests();UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();
        assertEquals(ACCEPTED,gate.start(alice,"old",0,1,0));assertEquals(FULL,gate.start(bob,"other",0,1,0));gate.leave(alice);
        assertFalse(gate.active(alice,"old"));assertEquals(ACCEPTED,gate.start(alice,"new-session",1,1,0));assertFalse(gate.finish(alice,"old"));assertTrue(gate.active(alice,"new-session"));
        gate.clear();assertFalse(gate.active(alice,"new-session"));
    }
}
