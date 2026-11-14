package top.goldgom.aibotassist;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class PacketChunksTest {
    private String request(String id,int n) {
        var blocks=new ArrayList<Plan.Placement>();
        for(int i=0;i<n;i++)blocks.add(new Plan.Placement(i%16,(i/16)%16,i/256,"minecraft:polished_andesite"));
        JsonObject request=new JsonObject();request.addProperty("action","agent_execute");request.addProperty("call",id);request.addProperty("plan",AIBotAssist.JSON.toJson(new Plan("中文建筑",blocks)));return request.toString();
    }
    private JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    @Test void full4096BlockPlanSurvivesUtf8FragmentationWithinMinecraftPacketSize() {
        String original=request("call1",4096);var packets=PacketChunks.encode(original,"call1");assertTrue(packets.size()>1);
        var receiver=new PacketChunks();UUID player=UUID.randomUUID();String complete=null;
        for(int i=0;i<packets.size();i++) {
            assertTrue(packets.get(i).getBytes(StandardCharsets.UTF_8).length<32700);
            complete=receiver.accept(player,json(packets.get(i)),1000+i);
            if(i<packets.size()-1)assertNull(complete);
        }
        assertEquals(original,complete);assertEquals(4096,Plan.parse(json(complete).get("plan").getAsString()).blocks().size());
    }
    @Test void partialReorderedCancelledAndExpiredUploadsNeverProduceRequests() {
        var packets=PacketChunks.encode(request("c",1024),"c");var receiver=new PacketChunks();UUID player=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->receiver.accept(player,json(packets.get(1)),0));
        assertNull(receiver.accept(player,json(packets.getFirst()),0));receiver.cancel(player);
        assertThrows(IllegalArgumentException.class,()->receiver.accept(player,json(packets.get(1)),1));
        assertNull(receiver.accept(player,json(packets.getFirst()),0));
        assertThrows(IllegalArgumentException.class,()->receiver.accept(player,json(packets.get(1)),31000));
    }
    @Test void uploadsAreBoundToPlayerAndCallAndRejectOversizedDeclarations() {
        var packets=PacketChunks.encode(request("one",1024),"one");var receiver=new PacketChunks();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        assertNull(receiver.accept(a,json(packets.getFirst()),0));
        assertThrows(IllegalArgumentException.class,()->receiver.accept(b,json(packets.get(1)),1));
        var wrong=json(packets.get(1));wrong.addProperty("id","two");assertThrows(IllegalArgumentException.class,()->receiver.accept(a,wrong,1));
        var huge=json(packets.getFirst());huge.addProperty("size",Integer.MAX_VALUE);assertThrows(IllegalArgumentException.class,()->receiver.accept(a,huge,0));
        var mismatch=PacketChunks.encode(request("different",1024),"envelope");
        assertThrows(IllegalArgumentException.class,()->{for(String part:mismatch)receiver.accept(a,json(part),0);});
    }
}
