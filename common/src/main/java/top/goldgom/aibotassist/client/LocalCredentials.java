package top.goldgom.aibotassist.client;

import com.google.gson.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Client-local encryption. The local key is NOT a defense against another process running as this user. */
public final class LocalCredentials {
    public record Secret(String apiKey,String access,String refresh,long expires,String endpoint,String clientId) {}
    private final Path root;
    public LocalCredentials(Path root){this.root=root;}
    public static String scope(String identity){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Path file(String scope){if(!scope.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid scope");return root.resolve(scope+".enc");}
    private static void restrict(Path file){try{Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString(Files.isDirectory(file)?"rwx------":"rw-------"));}catch(UnsupportedOperationException|java.io.IOException ignored){}}
    private byte[] key()throws Exception{
        Files.createDirectories(root);restrict(root);Path file=root.resolve("local.key");
        if(!Files.exists(file)){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);try{Files.write(file,bytes,StandardOpenOption.CREATE_NEW);restrict(file);}catch(FileAlreadyExistsException ignored){}}
        byte[] key=Files.readAllBytes(file);if(key.length!=32)throw new IllegalStateException("Invalid local key");return key;
    }
    public synchronized void save(String scope,Secret secret)throws Exception{
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(scope.getBytes(StandardCharsets.US_ASCII));
        byte[] encrypted=cipher.doFinal(new Gson().toJson(secret).getBytes(StandardCharsets.UTF_8));byte[] data=new byte[12+encrypted.length];System.arraycopy(iv,0,data,0,12);System.arraycopy(encrypted,0,data,12,encrypted.length);
        Path target=file(scope),temp=Files.createTempFile(root,"save-",".tmp");try{restrict(temp);Files.write(temp,data);try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}restrict(target);}finally{Files.deleteIfExists(temp);}
    }
    public synchronized Secret load(String scope)throws Exception{
        Path file=file(scope);if(!Files.exists(file))return null;if(Files.size(file)>32768)throw new IllegalStateException("Credential record too large");byte[] data=Files.readAllBytes(file);if(data.length<28)throw new IllegalStateException("Credential record invalid");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,Arrays.copyOf(data,12)));cipher.updateAAD(scope.getBytes(StandardCharsets.US_ASCII));return new Gson().fromJson(new String(cipher.doFinal(Arrays.copyOfRange(data,12,data.length)),StandardCharsets.UTF_8),Secret.class);
    }
    public synchronized void delete(String scope)throws Exception{Files.deleteIfExists(file(scope));}
}
