package top.goldgom.aibotassist;

import java.util.function.Predicate;

/** Keep matching world blocks, including their inventories, instead of clearing and replacing them. */
public final class BuildReconciliation {
    private BuildReconciliation() {}
    public static Plan missing(Plan desired,Predicate<Plan.Placement> matches) {
        return new Plan(desired.reply(),desired.blocks().stream().filter(block->!matches.test(block)).toList(),desired.commands(),desired.removals());
    }
}
