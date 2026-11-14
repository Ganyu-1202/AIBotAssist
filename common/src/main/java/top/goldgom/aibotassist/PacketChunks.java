package top.goldgom.aibotassist;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Reassembles a complete request before validation or world mutation. Each packet fits vanilla C2S limits. */
public final class PacketChunks {
    public static final int MAX_BYTES=8*1024*1024,CHUNK_BYTES=12000;
    private static final long TIMEOUT=30_000;
    private static final class Transfer {
        final String id;final int size,total;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int next;long expires;
        Transfer(String id,int size,int total,long now){this.id=id;this.size=size;this.total=total;expires=now+TIMEOUT;}
    }
    private final Map<UUID,Transfer> pending=new HashMap<>();
    public static List<String> encode(String json,String id) {
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("单次请求超过8MiB传输上限，请拆分方案并复用同一建造原点");
        if(bytes.length<=24000)return List.of(json);
        if(!id.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("Invalid transfer id");
        int total=(bytes.length+CHUNK_BYTES-1)/CHUNK_BYTES;List<String> packets=new ArrayList<>();
        for(int i=0;i<total;i++) {
            JsonObject packet=new JsonObject();packet.addProperty("action","fragment");packet.addProperty("id",id);
            packet.addProperty("index",i);packet.addProperty("total",total);packet.addProperty("size",bytes.length);
            packet.addProperty("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,i*CHUNK_BYTES,Math.min(bytes.length,(i+1)*CHUNK_BYTES))));packets.add(packet.toString());
        }
        return packets;
    }
    public String accept(UUID player,JsonObject packet,long now) {
        expire(now);
        try {
            String id=packet.get("id").getAsString();int index=packet.get("index").getAsBigDecimal().intValueExact(),total=packet.get("total").getAsBigDecimal().intValueExact(),size=packet.get("size").getAsBigDecimal().intValueExact();
            String data=packet.get("data").getAsString();
            if(!id.matches("[a-zA-Z0-9-]{1,64}")||size<1||size>MAX_BYTES||total!=(size+CHUNK_BYTES-1)/CHUNK_BYTES||index<0||index>=total||data.length()>16000)throw new IllegalArgumentException("Invalid transfer bounds");
            Transfer transfer=pending.get(player);
            if(index==0 && transfer==null){transfer=new Transfer(id,size,total,now);pending.put(player,transfer);}
            if(transfer==null||!transfer.id.equals(id)||transfer.next!=index||transfer.size!=size||transfer.total!=total)throw new IllegalArgumentException("Transfer order or identity changed");
            byte[] bytes=Base64.getDecoder().decode(data);
            if(bytes.length!=Math.min(CHUNK_BYTES,size-index*CHUNK_BYTES))throw new IllegalArgumentException("Invalid fragment size");
            transfer.bytes.writeBytes(bytes);transfer.next++;transfer.expires=now+TIMEOUT;
            if(transfer.next!=total)return null;
            pending.remove(player);
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(transfer.bytes.toByteArray())).toString();
            JsonObject request=JsonParser.parseString(json).getAsJsonObject();
            if(!request.has("call")||!id.equals(request.get("call").getAsString())||"fragment".equals(request.get("action").getAsString()))throw new IllegalArgumentException("Transfer request identity mismatch");
            return json;
        } catch(Exception e){pending.remove(player);throw new IllegalArgumentException("分包上传无效或已过期，请重新提交完整请求");}
    }
    public void cancel(UUID player){pending.remove(player);}
    public void clear(){pending.clear();}
    public void expire(long now){pending.values().removeIf(t->t.expires<now);}
}
