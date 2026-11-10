package top.goldgom.aibotassist;

import java.util.*;

/** Generated from validated integer coordinates, never from model command strings. */
public final class BatchCommands {
    public record Run(int x, int y, int z, int endX, String block, int count) {
        public String command(int ox, int oy, int oz) {
            String start = (ox+x)+" "+(oy+y)+" "+(oz+z);
            return count == 1 ? "setblock "+start+" "+block+" keep"
                : "fill "+start+" "+(ox+endX)+" "+(oy+y)+" "+(oz+z)+" "+block+" keep";
        }
    }
    private BatchCommands() {}
    public static List<Run> placements(List<Plan.Placement> blocks) {
        List<Plan.Placement> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparing(Plan.Placement::block).thenComparingInt(Plan.Placement::y)
            .thenComparingInt(Plan.Placement::z).thenComparingInt(Plan.Placement::x));
        List<Run> result = new ArrayList<>();
        for (int i=0;i<sorted.size();) {
            var first=sorted.get(i++); int end=first.x();
            while(i<sorted.size()) {
                var next=sorted.get(i);
                if (!first.block().equals(next.block()) || first.y()!=next.y() || first.z()!=next.z() || next.x()!=end+1) break;
                end=next.x();i++;
            }
            result.add(new Run(first.x(),first.y(),first.z(),end,first.block(),end-first.x()+1));
        }
        return List.copyOf(result);
    }
}
