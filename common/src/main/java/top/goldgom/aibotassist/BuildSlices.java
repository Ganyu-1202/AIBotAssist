package top.goldgom.aibotassist;

import java.util.*;

/** Keep paired blocks together and place lower supports before higher blocks across ticks. */
public final class BuildSlices {
    public static final int PLACEMENTS=64,REMOVALS=128;
    private BuildSlices() {}
    public static List<List<Plan.Placement>> partition(List<Plan.Placement> expanded) {
        List<List<Plan.Placement>> groups=new ArrayList<>();
        for(int i=0;i<expanded.size();) {
            List<Plan.Placement> group=new ArrayList<>();group.add(expanded.get(i++));
            if(i<expanded.size() && SpecialPlacement.isUpperOrHead(expanded.get(i)))group.add(expanded.get(i++));
            groups.add(group);
        }
        groups.sort(Comparator.comparingInt(g->g.getFirst().y()));
        List<List<Plan.Placement>> result=new ArrayList<>();List<Plan.Placement> batch=new ArrayList<>();
        for(var group:groups) {
            if(batch.size()+group.size()>PLACEMENTS){result.add(List.copyOf(batch));batch.clear();}
            batch.addAll(group);
        }
        if(!batch.isEmpty())result.add(List.copyOf(batch));return result;
    }
}
