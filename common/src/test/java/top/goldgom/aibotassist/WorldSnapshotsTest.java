package top.goldgom.aibotassist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class WorldSnapshotsTest {
    @TempDir Path temp;
    @Test void copiesAllDimensionsAndPrivateSaveDataWithoutChangingSource()throws Exception{
        Path save=Files.createDirectory(temp.resolve("world")),parent=Files.createDirectory(temp.resolve("snapshots"));
        for(String file:new String[]{"level.dat","playerdata/player.dat","DIM-1/region/r.-1.0.mca","dimensions/mod/moon/region/r.0.0.mca","stats/player.json"}){Path p=save.resolve(file);Files.createDirectories(p.getParent());Files.writeString(p,file);}
        Files.writeString(save.resolve("session.lock"),"locked");var s=new WorldSnapshots.Snapshot(save,parent);WorldSnapshots.copy(s);
        assertEquals("completed",s.status);assertEquals(5,s.copied);assertEquals(1,s.skipped);assertEquals(0,s.changed);
        assertEquals("dimensions/mod/moon/region/r.0.0.mca",Files.readString(s.destination.resolve("save/dimensions/mod/moon/region/r.0.0.mca")));
        assertEquals("locked",Files.readString(save.resolve("session.lock")));assertFalse(Files.exists(s.destination.resolve("save/session.lock")));
        try(var lines=Files.lines(s.destination.resolve("manifest.jsonl"))){assertEquals(5,lines.count());}
    }
    @Test void refusesRecursiveSnapshotInsideLiveSave()throws Exception{
        Path save=Files.createDirectory(temp.resolve("world"));assertThrows(IOException.class,()->new WorldSnapshots.Snapshot(save,save.resolve("snapshots")));
    }
}
