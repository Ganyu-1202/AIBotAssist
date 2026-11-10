package top.goldgom.aibotassist;
import top.goldgom.aibotassist.client.LocalCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalCredentialsTest {
 @TempDir Path dir;
 @Test void roundTripIsEncryptedAndScoped()throws Exception{
  var store=new LocalCredentials(dir);String a=LocalCredentials.scope("world-a"),b=LocalCredentials.scope("world-b");
  store.save(a,new LocalCredentials.Secret("test-key-sensitive","access","refresh",123,"https://example.com/v1","client"));
  assertEquals("test-key-sensitive",store.load(a).apiKey());assertNull(store.load(b));
  assertFalse(new String(Files.readAllBytes(dir.resolve(a+".enc")),java.nio.charset.StandardCharsets.ISO_8859_1).contains("test-key-sensitive"));
  assertEquals("refresh",new LocalCredentials(dir).load(a).refresh());
 }
 @Test void deleteOnlyCurrentWorld()throws Exception{
  var store=new LocalCredentials(dir);String a=LocalCredentials.scope("a"),b=LocalCredentials.scope("b");var secret=new LocalCredentials.Secret("k","","",0,"url","");store.save(a,secret);store.save(b,secret);store.delete(a);assertNull(store.load(a));assertNotNull(store.load(b));
 }
 @Test void tamperedAndCopiedRecordsRejected()throws Exception{
  var store=new LocalCredentials(dir);String a=LocalCredentials.scope("a"),b=LocalCredentials.scope("b");store.save(a,new LocalCredentials.Secret("k","","",0,"url",""));Files.copy(dir.resolve(a+".enc"),dir.resolve(b+".enc"));assertThrows(Exception.class,()->store.load(b));byte[] data=Files.readAllBytes(dir.resolve(a+".enc"));data[data.length-1]^=1;Files.write(dir.resolve(a+".enc"),data);assertThrows(Exception.class,()->store.load(a));
 }
}
