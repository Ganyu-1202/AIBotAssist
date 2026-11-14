package top.goldgom.aibotassist;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuildAnchorTest {
    @Test void reproducesLoggedMovementWithoutMovingBuildCoordinates() {
        var original=new BlockPos(10,176,-7);var moved=new BlockPos(-3,182,-11);
        var anchor=new BuildAnchor("task",original,"overworld","east",4096,600000);
        var reused=anchor.reuse("task","overworld",4096,200000);
        assertEquals(original.offset(1,2,3),reused.origin().offset(1,2,3));
        assertNotEquals(moved.offset(1,2,3),reused.origin().offset(1,2,3));assertEquals("east",reused.facing());
    }
    @Test void worldSessionLimitAndExpiryChangesCannotSilentlyReanchor() {
        var anchor=new BuildAnchor("task",BlockPos.ZERO,"overworld","north",0,1000);
        assertThrows(IllegalArgumentException.class,()->anchor.reuse("other","overworld",0,0));
        assertThrows(IllegalArgumentException.class,()->anchor.reuse("task","nether",0,0));
        assertThrows(IllegalArgumentException.class,()->anchor.reuse("task","overworld",1,0));
        assertThrows(IllegalArgumentException.class,()->anchor.reuse("task","overworld",0,1001));
    }
}
