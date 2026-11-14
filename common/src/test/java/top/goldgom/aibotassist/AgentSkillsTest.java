package top.goldgom.aibotassist;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentSkillsTest {
 @Test void packagedRedstoneSkillIsDiscoverableAndReadable()throws Exception{
  assertEquals("minecraft-redstone",AgentSkills.read(new JsonObject()).getAsJsonArray("skills").get(0).getAsJsonObject().get("name").getAsString());
  var a=new JsonObject();a.addProperty("name","minecraft-redstone");String content=AgentSkills.read(a).get("content").getAsString();
  assertTrue(content.contains("facing=north"));assertTrue(content.contains("query_mod_operations"));assertTrue(content.contains("实际功能验证通过"));
 }
 @Test void skillReaderCannotTraverseFiles(){var a=new JsonObject();a.addProperty("name","../../private/local.key");assertThrows(IllegalArgumentException.class,()->AgentSkills.read(a));}
}
