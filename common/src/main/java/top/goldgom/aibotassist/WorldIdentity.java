package top.goldgom.aibotassist;

import java.nio.file.*;
import java.util.UUID;

/** A save-owned ID survives restarts and address changes, unlike a server address. */
public final class WorldIdentity {
    private WorldIdentity() {}
    public static String load(Path worldRoot)throws Exception {
        Path file=worldRoot.resolve("data/aibotassist-world-id.txt");
        Files.createDirectories(file.getParent());
        if(!Files.exists(file))try{Files.writeString(file,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}
        if(Files.size(file)>100)throw new IllegalArgumentException("Invalid world identity");
        return UUID.fromString(Files.readString(file).trim()).toString();
    }
}
