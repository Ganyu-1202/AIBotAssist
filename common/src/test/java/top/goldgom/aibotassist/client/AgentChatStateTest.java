package top.goldgom.aibotassist.client;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static top.goldgom.aibotassist.client.AgentChatState.*;

class AgentChatStateTest {
    @Test void progressTracksToolsAndIgnoresLateEventsAfterStopping() {
        var state=new AgentChatState();state.begin();
        assertTrue(state.work().active());
        state.progress(new Progress(Phase.THINKING,"",1,0));
        state.progress(new Progress(Phase.TOOL,"build_structure",1,1));
        assertEquals("build_structure",state.work().tool());
        state.progress(new Progress(Phase.WAITING,"get_task_status",2,2));
        assertEquals(Phase.WAITING,state.work().phase());
        long started=state.work().started();state.finish(Phase.CANCELLED);
        state.progress(new Progress(Phase.THINKING,"",3,2));
        assertEquals(Phase.CANCELLED,state.work().phase());assertFalse(state.work().active());
        assertEquals(started,state.work().started());assertEquals(2,state.work().toolCalls());
        state.begin();state.progress(new Progress(Phase.THINKING,"",1,0));state.finish(Phase.COMPLETED);
        state.progress(new Progress(Phase.TOOL,"run_commands",2,1));
        assertEquals(Phase.COMPLETED,state.work().phase());assertEquals(0,state.work().toolCalls());
    }
    @Test void clearingTranscriptKeepsRunningTaskButChangingWorldResetsEverything() {
        var state=new AgentChatState();state.append(Role.USER,"build a house");state.draft("next request");state.begin();
        long revision=state.revision();state.clearMessages();
        assertTrue(state.messages().isEmpty());assertTrue(state.work().active());assertEquals("next request",state.draft());
        assertTrue(state.revision()>revision);state.reset();
        assertEquals(Phase.IDLE,state.work().phase());assertEquals(0,state.work().started());assertEquals("",state.draft());
        assertEquals("new world",state.promptWithHistory("new world"));
    }
    @Test void followupsIncludeOnlyRecentDialogueAndNeverSystemNotices() {
        var state=new AgentChatState();assertEquals("hello",state.promptWithHistory("hello"));
        for(int i=0;i<12;i++)state.append(i%2==0?Role.USER:Role.ASSISTANT,"dialogue-"+i+";");
        state.append(Role.NOTICE,"private-diagnostic");
        String prompt=state.promptWithHistory("current-request");
        assertFalse(prompt.contains("dialogue-3;"));assertTrue(prompt.contains("dialogue-4;"));
        assertTrue(prompt.contains("dialogue-11;"));assertFalse(prompt.contains("private-diagnostic"));
        assertTrue(prompt.endsWith("current-request"));
        state.clearMessages();assertEquals("current-request",state.promptWithHistory("current-request"));
    }
    @Test void transcriptAndContextRemainBoundedDuringLongConversations() {
        var state=new AgentChatState();
        for(int i=0;i<200;i++)state.append(Role.ASSISTANT,"x".repeat(7000));
        assertEquals(160,state.messages().size());assertEquals(6000,state.messages().getFirst().text().length());
        assertTrue(state.promptWithHistory("next").length()<13000);
        assertThrows(UnsupportedOperationException.class,()->state.messages().clear());
        state.draft("x".repeat(3000));assertEquals(2000,state.draft().length());
    }
    @Test void failuresCanRestartAndIndependentWorldStateDoesNotLeak() {
        var first=new AgentChatState();first.begin();first.finish(Phase.FAILED);first.append(Role.USER,"old world");
        var second=new AgentChatState();assertEquals(Phase.IDLE,second.work().phase());assertTrue(second.messages().isEmpty());
        first.begin();assertEquals(Phase.PREPARING,first.work().phase());assertEquals(0,first.work().modelTurns());
        assertThrows(IllegalArgumentException.class,()->first.finish(Phase.THINKING));
    }
    @Test void oldConfigurationKeepsHudDefaultsAndSavedPreferencesSurviveCopies() {
        var gson=new Gson();var old=gson.fromJson("{\"name\":\"AI\"}",ClientSettings.class);
        assertEquals(4096,old.blockLimit);assertTrue(old.visualCalibration);assertTrue(old.hudEnabled);assertEquals(0.55,old.hudOpacity);old.validate();
        old.hudEnabled=false;old.hudOpacity=0.25;old.blockLimit=0;old.visualCalibration=false;
        var restored=gson.fromJson(gson.toJson(old),ClientSettings.class);
        assertEquals(0,restored.blockLimit);assertFalse(restored.visualCalibration);assertFalse(restored.hudEnabled);assertEquals(0.25,restored.hudOpacity);restored.validate();
        restored.hudOpacity=Double.NaN;assertThrows(IllegalArgumentException.class,restored::validate);
        restored.hudOpacity=1.0;assertThrows(IllegalArgumentException.class,restored::validate);
    }
}
