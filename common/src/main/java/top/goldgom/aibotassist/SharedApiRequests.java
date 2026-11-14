package top.goldgom.aibotassist;

import java.util.*;

/** No cross-player state/results. All admission and cancellation runs on the server thread. */
public final class SharedApiRequests {
    public enum Admission { ACCEPTED, BUSY, DUPLICATE, THROTTLED, FULL }
    private final Map<UUID,String> active=new HashMap<>();
    private final Map<UUID,Long> started=new HashMap<>();
    private final Map<UUID,LinkedHashSet<String>> seen=new HashMap<>();
    public Admission start(UUID player,String id,long now,int max,int intervalMillis){
        if(seen.getOrDefault(player,new LinkedHashSet<>()).contains(id))return Admission.DUPLICATE;
        if(active.containsKey(player))return Admission.BUSY;
        if(active.size()>=max)return Admission.FULL;
        if(started.containsKey(player)&&now-started.get(player)<intervalMillis)return Admission.THROTTLED;
        active.put(player,id);started.put(player,now);var ids=seen.computeIfAbsent(player,p->new LinkedHashSet<>());ids.add(id);while(ids.size()>64)ids.remove(ids.iterator().next());return Admission.ACCEPTED;
    }
    public boolean finish(UUID player,String id){return active.remove(player,id);}
    public boolean active(UUID player,String id){return id.equals(active.get(player));}
    public void leave(UUID player){active.remove(player);started.remove(player);seen.remove(player);}
    public void clear(){active.clear();started.clear();seen.clear();}
}
