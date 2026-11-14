package top.goldgom.aibotassist.client;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static top.goldgom.aibotassist.client.AgentChatState.*;

class ChatSessionStoreTest {
    @TempDir Path root;
    private static final UUID ALICE=UUID.fromString("11111111-1111-1111-1111-111111111111"),BOB=UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static ChatSessionStore.Transcript transcript(String text){return new ChatSessionStore.Transcript(1,List.of(new Message(Role.USER,text)),"draft-"+text);}
    @Test void saveAndPlayerUuidBothIsolatePersistentContextAcrossRestarts()throws Exception{
        var store=new ChatSessionStore(root);String aliceA=ChatSessionStore.key("world-A",ALICE),aliceB=ChatSessionStore.key("world-B",ALICE),bobA=ChatSessionStore.key("world-A",BOB);
        store.save(aliceA,transcript("alice-A"));store.save(aliceB,transcript("alice-B"));store.save(bobA,transcript("bob-A"));
        var restarted=new ChatSessionStore(root);assertEquals("alice-A",restarted.load(aliceA).messages().getFirst().text());assertEquals("alice-B",restarted.load(aliceB).messages().getFirst().text());assertEquals("bob-A",restarted.load(bobA).messages().getFirst().text());assertEquals("draft-alice-A",restarted.load(aliceA).draft());
        assertEquals(64,aliceA.length());assertFalse(Files.readString(root.resolve("local.key"),java.nio.charset.StandardCharsets.ISO_8859_1).isBlank());
        assertFalse(new String(Files.readAllBytes(root.resolve(aliceA+".enc")),java.nio.charset.StandardCharsets.ISO_8859_1).contains("alice-A"));
    }
    @Test void swappedOrModifiedSessionCiphertextsCannotLeakAnotherPlayersContext()throws Exception{
        var store=new ChatSessionStore(root);String a=ChatSessionStore.key("save",ALICE),b=ChatSessionStore.key("save",BOB);store.save(a,transcript("private"));
        Files.copy(root.resolve(a+".enc"),root.resolve(b+".enc"));assertThrows(Exception.class,()->store.load(b));
        byte[] encrypted=Files.readAllBytes(root.resolve(a+".enc"));encrypted[encrypted.length-1]^=1;Files.write(root.resolve(a+".enc"),encrypted);assertThrows(Exception.class,()->store.load(a));
    }
    @Test void sameWorldIdentityOnAnotherServerDoesNotRestorePrivateContext()throws Exception{
        var store=new ChatSessionStore(root);String first=ChatSessionStore.key("server:first.example|world:same-id",ALICE);
        String other=ChatSessionStore.key("server:other.example|world:same-id",ALICE);
        store.save(first,transcript("private-first-server"));assertTrue(store.load(other).messages().isEmpty());
        assertNotEquals(first,ChatSessionStore.key("server:first.example|world:replacement-save",ALICE));
    }
    @Test void restoringTranscriptKeepsBackgroundOnlyAndNeverResumesActions()throws Exception{
        var state=new AgentChatState();state.append(Role.USER,"old world");state.begin();state.progress(new Progress(Phase.TOOL,"execute_task",3,7));
        state.restore(List.of(new Message(Role.USER,"saved request"),new Message(Role.ASSISTANT,"saved reply")),"new draft");
        assertEquals(Phase.IDLE,state.work().phase());assertFalse(state.work().active());assertEquals(0,state.work().toolCalls());assertEquals("new draft",state.draft());
        String prompt=state.promptWithHistory("new request");assertTrue(prompt.contains("saved request"));assertTrue(prompt.contains("saved reply"));assertFalse(prompt.contains("old world"));assertTrue(prompt.endsWith("new request"));
    }
    @Test void clearPersistsAndMissingSessionsStartEmpty()throws Exception{
        var store=new ChatSessionStore(root);String key=ChatSessionStore.key("save",ALICE);assertTrue(store.load(key).messages().isEmpty());
        store.save(key,transcript("before clear"));store.save(key,new ChatSessionStore.Transcript(1,List.of(),""));
        assertTrue(new ChatSessionStore(root).load(key).messages().isEmpty());
    }
    @Test void fullBoundedConversationRoundTripsWithoutTheSmallCredentialLimit()throws Exception{
        var state=new AgentChatState();for(int i=0;i<160;i++)state.append(Role.ASSISTANT,"中".repeat(6000));
        var store=new ChatSessionStore(root);String key=ChatSessionStore.key("save",ALICE);store.save(key,new ChatSessionStore.Transcript(1,state.messages(),""));
        assertEquals(state.messages(),store.load(key).messages());assertTrue(Files.size(root.resolve(key+".enc"))>32768);
    }
    @Test void fullConversationWithJsonEscapedTextAlsoRoundTrips()throws Exception{
        var state=new AgentChatState();for(int i=0;i<160;i++)state.append(Role.ASSISTANT,"<".repeat(6000));
        var store=new ChatSessionStore(root);String key=ChatSessionStore.key("save",ALICE);
        store.save(key,new ChatSessionStore.Transcript(1,state.messages(),"\u0001".repeat(2000)));
        assertEquals(state.messages(),store.load(key).messages());assertEquals(2000,store.load(key).draft().length());
    }
    @Test void unsupportedVersionsAndOversizedRecordsAreRejectedWithoutRewritingThem()throws Exception{
        var store=new ChatSessionStore(root);String key=ChatSessionStore.key("save",ALICE);store.save(key,new ChatSessionStore.Transcript(2,List.of(),""));byte[] original=Files.readAllBytes(root.resolve(key+".enc"));
        assertThrows(IllegalArgumentException.class,()->store.load(key));assertArrayEquals(original,Files.readAllBytes(root.resolve(key+".enc")));
        Files.write(root.resolve(key+".enc"),new byte[ChatSessionStore.MAX_BYTES+1]);assertThrows(IllegalStateException.class,()->store.load(key));
        assertThrows(IllegalArgumentException.class,()->ChatSessionStore.key("",ALICE));
    }
}
