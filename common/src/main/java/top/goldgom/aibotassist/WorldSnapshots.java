package top.goldgom.aibotassist;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.*;

/** Copies the entire save, without loading chunks into the live world. Copies are explicitly non-atomic. */
public final class WorldSnapshots {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"aibot-snapshot");t.setDaemon(true);return t;});
    private static final Map<UUID,Snapshot> snapshots=new HashMap<>();
    static final class Snapshot {
        final String id=UUID.randomUUID().toString();final Path source,destination;
        volatile String status="running";volatile int copied,changed,skipped;volatile long bytes;
        final long started=System.currentTimeMillis();volatile long finished;
        Snapshot(Path source,Path parent)throws IOException{this.source=source.toRealPath();this.destination=parent.resolve(id).toAbsolutePath().normalize();if(destination.startsWith(this.source))throw new IOException("snapshot_must_be_outside_save");}
    }
    private WorldSnapshots(){}
    public static void clear(){snapshots.clear();}
    public static JsonObject handle(ServerPlayer p,JsonObject a)throws Exception{
        String action=a.has("action")?a.get("action").getAsString():"status";
        Path source=p.server.getWorldPath(LevelResource.ROOT).toRealPath();Snapshot s=snapshots.get(p.getUUID());
        if(action.equals("create")){
            // One retained snapshot per player/session; explicit reuse prevents unbounded agent disk consumption.
            if(s==null||!s.source.equals(source)){
                Path parent=source.getParent().resolve(".aibotassist-snapshots");
                if(Files.isSymbolicLink(parent))throw new IOException("snapshot_parent_symlink");
                Files.createDirectories(parent);s=new Snapshot(source,parent.toRealPath());
                p.server.saveEverything(true,true,true);snapshots.put(p.getUUID(),s);Snapshot task=s;IO.submit(()->copy(task));
            }
            return status(s);
        }
        if(s==null||!s.source.equals(source)||!a.has("snapshot_id")||!s.id.equals(a.get("snapshot_id").getAsString()))return AdvancedTools.error("snapshot_id_required_create_first");
        if(action.equals("status"))return status(s);
        if(!s.status.equals("completed"))return status(s);
        if(action.equals("list")){
            int offset=ResourceTools.integer(a,"offset",0,0,Integer.MAX_VALUE),limit=ResourceTools.integer(a,"limit",40,1,80);JsonArray files=new JsonArray();
            // Manifest is written incrementally during copy, avoiding a world-sized in-memory list.
            try(var lines=Files.lines(s.destination.resolve("manifest.jsonl"))){lines.skip(offset).limit(limit).forEach(line->files.add(JsonParser.parseString(line)));}
            JsonObject o=status(s);o.add("files",files);o.addProperty("next_offset",offset+files.size());return o;
        }
        Path file=DiagnosticFiles.confined(s.destination.resolve("save"),a.get("file").getAsString());
        if(action.equals("text")){
            if(!file.getFileName().toString().matches(".*\\.(json|txt|mcfunction|toml|properties)"))return AdvancedTools.error("text_file_required");
            int offset=ResourceTools.integer(a,"offset",0,0,Integer.MAX_VALUE);long size=Files.size(file);byte[] bytes=new byte[(int)Math.min(12000,Math.max(0,size-offset))];
            try(var input=new RandomAccessFile(file.toFile(),"r")){input.seek(offset);input.readFully(bytes);}
            JsonObject o=status(s);o.addProperty("text",DiagnosticFiles.redact(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));o.addProperty("next_offset",offset+bytes.length);o.addProperty("total_bytes",size);return o;
        }
        CompoundTag tag;
        if(action.equals("chunk")){
            if(!file.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))return AdvancedTools.error("region_file_required");
            int x=ResourceTools.integer(a,"chunk_x",0,-1875000,1875000),z=ResourceTools.integer(a,"chunk_z",0,-1875000,1875000);
            if(!file.getFileName().toString().equals("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)+".mca"))return AdvancedTools.error("chunk_not_in_region");
            try(var region=new RegionFile(new RegionStorageInfo("aibot_snapshot",p.level().dimension(),"chunk"),file,file.getParent(),false);var input=region.getChunkDataInputStream(new ChunkPos(x,z))){if(input==null)return AdvancedTools.error("chunk_not_saved");tag=NbtIo.read(input,NbtAccounter.create(16L*1024*1024));}
        }else if(action.equals("nbt")){
            if(!file.getFileName().toString().endsWith(".dat")||Files.size(file)>16L*1024*1024)return AdvancedTools.error("bounded_dat_file_required");
            tag=NbtIo.readCompressed(file,NbtAccounter.create(16L*1024*1024));
        }else return AdvancedTools.error("action_create_status_list_nbt_chunk");
        Tag selected=tag;
        if(a.has("path"))for(String key:a.get("path").getAsString().split("/")){if(key.isEmpty())continue;if(selected instanceof CompoundTag c)selected=c.get(key);else if(selected instanceof ListTag l)selected=l.get(Integer.parseInt(key));else return AdvancedTools.error("invalid_nbt_path");if(selected==null)return AdvancedTools.error("nbt_path_not_found");}
        String text=selected.toString();int offset=ResourceTools.integer(a,"offset",0,0,Integer.MAX_VALUE);int start=Math.min(offset,text.length()),end=Math.min(start+12000,text.length());JsonObject o=status(s);o.addProperty("snbt",DiagnosticFiles.redact(text.substring(start,end)));o.addProperty("next_offset",end);o.addProperty("total_chars",text.length());o.addProperty("truncated",end<text.length());return o;
    }
    private static JsonObject status(Snapshot s){JsonObject o=new JsonObject();o.addProperty("status",s.status);o.addProperty("snapshot_id",s.id);o.addProperty("copied_files",s.copied);o.addProperty("bytes",s.bytes);o.addProperty("changed_during_copy",s.changed);o.addProperty("skipped_files",s.skipped);o.addProperty("started_at",s.started);o.addProperty("finished_at",s.finished);o.addProperty("consistency","保存后后台复制整个存档；游戏继续运行，非原子备份，文件可能跨tick。session.lock与符号链接不复制。不能把此副本当成停服一致性备份。");o.addProperty("retention","每玩家每服务器会话复用一个快照；副本保留在存档父目录.aibotassist-snapshots，重启后可由管理员手动归档/删除。");return o;}
    static void copy(Snapshot s){
        try{
            Files.createDirectories(s.destination.resolve("save"));
            try(var manifest=Files.newBufferedWriter(s.destination.resolve("manifest.jsonl"))){
                Files.walkFileTree(s.source,new SimpleFileVisitor<Path>(){
                    @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException{if(!dir.toRealPath().startsWith(s.source)){s.skipped++;return FileVisitResult.SKIP_SUBTREE;}Files.createDirectories(s.destination.resolve("save").resolve(s.source.relativize(dir)));return FileVisitResult.CONTINUE;}
                    @Override public FileVisitResult visitFile(Path file,BasicFileAttributes before)throws IOException{
                        if(!before.isRegularFile()||!file.toRealPath().startsWith(s.source)||file.getFileName().toString().equals("session.lock")){s.skipped++;return FileVisitResult.CONTINUE;}
                        Path relative=s.source.relativize(file),target=s.destination.resolve("save").resolve(relative);
                        Files.copy(file,target,StandardCopyOption.COPY_ATTRIBUTES);var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                        boolean changed=before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime());if(changed)s.changed++;
                        s.copied++;s.bytes+=Files.size(target);JsonObject entry=new JsonObject();entry.addProperty("file",relative.toString().replace('\\','/'));entry.addProperty("bytes",Files.size(target));entry.addProperty("changed",changed);manifest.write(entry.toString());manifest.newLine();return FileVisitResult.CONTINUE;
                    }
                });
            }
            s.status="completed";
        }catch(Exception e){s.status="failed";}finally{s.finished=System.currentTimeMillis();}
    }
}
