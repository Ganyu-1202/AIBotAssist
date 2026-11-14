package top.goldgom.aibotassist;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldIdentityTest {
    @TempDir Path root;
    @Test void saveIdentitySurvivesRestartAndDifferentSavesReceiveDifferentIds()throws Exception{
        String a=WorldIdentity.load(root.resolve("a")),b=WorldIdentity.load(root.resolve("b"));
        assertEquals(a,WorldIdentity.load(root.resolve("a")));assertNotEquals(a,b);assertEquals(a,Files.readString(root.resolve("a/data/aibotassist-world-id.txt")));
    }
    @Test void invalidIdentityIsNotSilentlyReplaced()throws Exception{
        WorldIdentity.load(root);Path file=root.resolve("data/aibotassist-world-id.txt");Files.writeString(file,"invalid");
        assertThrows(IllegalArgumentException.class,()->WorldIdentity.load(root));assertEquals("invalid",Files.readString(file));
    }
}
