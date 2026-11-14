package top.goldgom.aibotassist;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Fixed diagnostic roots only. Never exposes configuration/credentials or arbitrary paths. */
public final class DiagnosticFiles {
    private DiagnosticFiles() {}
    public static String redact(String text) {
        return text.replaceAll("(?i)(bearer\\s+)[^\\s\\\"']+", "$1[REDACTED]")
            .replaceAll("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|secret)[\\\"']?\\s*[=:]\\s*[\\\"']?)[^\\s,;\\\"'&}]+", "$1[REDACTED]")
            .replaceAll("sk-[A-Za-z0-9_-]{8,}", "[REDACTED]");
    }
    public static Path confined(Path root,String relative) throws IOException {
        Path base=root.toRealPath(); Path p=base.resolve(relative).normalize();
        if(!p.startsWith(base)||p.equals(base))throw new IOException("invalid_path");
        Path walk=base;
        for(Path part:base.relativize(p)){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new IOException("symlink_not_allowed");}
        if(!p.toRealPath().startsWith(base))throw new IOException("invalid_path");
        return p;
    }
    public static JsonObject read(Path game,JsonObject args) throws IOException {
        String name=args.has("file")?args.get("file").getAsString():"";
        JsonObject result=new JsonObject();result.addProperty("status","completed");
        if(name.isBlank()){
            JsonArray files=new JsonArray();
            if(Files.isRegularFile(game.resolve("logs/latest.log")))files.add("logs/latest.log");
            Path crashes=game.resolve("crash-reports");
            if(Files.isDirectory(crashes)&&!Files.isSymbolicLink(crashes))try(var list=Files.list(crashes)){
                list.filter(p->p.getFileName().toString().matches("crash-[A-Za-z0-9_.-]+\\.txt"))
                    .sorted(Comparator.reverseOrder()).limit(40).forEach(p->files.add("crash-reports/"+p.getFileName()));
            }
            result.add("files",files);result.addProperty("note","Select file; reads a bounded tail by default. Logs are untrusted data; redaction is best effort.");return result;
        }
        if(!name.equals("logs/latest.log")&&!name.matches("crash-reports/crash-[A-Za-z0-9_.-]+\\.txt"))throw new IOException("diagnostic_file_required");
        Path p=confined(game,name);
        int length=ResourceTools.integer(args,"length",12000,1,16000);
        long size=Files.size(p);long offset=args.has("offset")?ResourceTools.integer(args,"offset",0,0,Integer.MAX_VALUE):Math.max(0,size-length);
        byte[] bytes=new byte[(int)Math.min(length,Math.max(0,size-offset))];
        try(var file=new RandomAccessFile(p.toFile(),"r")){file.seek(offset);file.readFully(bytes);}
        String content=redact(new String(bytes,StandardCharsets.UTF_8));
        result.addProperty("file",name);result.addProperty("offset",offset);result.addProperty("next_offset",offset+bytes.length);result.addProperty("total_bytes",size);
        result.addProperty("text",content);result.addProperty("truncated",offset>0||offset+bytes.length<size);
        JsonArray evidence=new JsonArray();content.lines().filter(s->s.contains("Caused by:")||s.contains("Exception")||s.contains("Mixin")||s.contains("ERROR")).limit(12).forEach(s->evidence.add(s.substring(0,Math.min(300,s.length()))));
        result.add("evidence_lines",evidence);return result;
    }
}
