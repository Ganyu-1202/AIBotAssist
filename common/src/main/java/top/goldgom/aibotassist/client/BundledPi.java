package top.goldgom.aibotassist.client;

import com.google.gson.*;
import dev.architectury.platform.Platform;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class BundledPi {
    private BundledPi() {}
    public static String platform(String os,String arch) {
        String system=os.toLowerCase(Locale.ROOT),cpu=arch.toLowerCase(Locale.ROOT);
        String family=system.contains("win")?"windows":system.contains("mac")||system.contains("darwin")?"macos":system.contains("linux")?"linux":"unsupported";
        String suffix=Set.of("amd64","x86_64","x64").contains(cpu)?"x64":Set.of("aarch64","arm64").contains(cpu)?"arm64":"unsupported";
        return family+"-"+suffix;
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static synchronized Path executable()throws Exception {
        String key=platform(System.getProperty("os.name"),System.getProperty("os.arch"));
        JsonObject manifest;
        try(var stream=BundledPi.class.getResourceAsStream("/aibotassist/pi/manifest.json")){
            if(stream==null)throw new IllegalStateException("构建包缺少内嵌Pi运行时，请使用完整发行包");
            manifest=JsonParser.parseString(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject files=manifest.getAsJsonObject("files");if(!files.has(key))throw new IllegalStateException("当前系统未包含内嵌Pi："+key);
        JsonObject file=files.getAsJsonObject(key);String name=file.get("name").getAsString(),expected=file.get("sha256").getAsString();
        if(!name.matches("aibotassist-pi-[a-z0-9-]+(?:\\.exe)?")||!expected.matches("[a-f0-9]{64}"))throw new IllegalStateException("内嵌清单无效");
        Path dir=Platform.getConfigFolder().resolve("aibotassist/runtime").resolve(expected.substring(0,16));Files.createDirectories(dir);Path target=dir.resolve(name);
        if(Files.isRegularFile(target)&&!Files.isSymbolicLink(target)&&hash(Files.readAllBytes(target)).equals(expected))return target.toAbsolutePath();
        byte[] bytes;
        try(var resource=BundledPi.class.getResourceAsStream("/aibotassist/pi/"+name)){if(resource==null)throw new IllegalStateException("缺少内嵌程序");bytes=resource.readAllBytes();}
        if(!hash(bytes).equals(expected))throw new IllegalStateException("内嵌Pi校验失败");
        Path temp=Files.createTempFile(dir,"extract-",".tmp");
        try{Files.write(temp,bytes);if(!temp.toFile().setExecutable(true,true)&&!key.startsWith("windows"))throw new IllegalStateException("无法设置Pi执行权限");
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
        return target.toAbsolutePath();
    }
}
