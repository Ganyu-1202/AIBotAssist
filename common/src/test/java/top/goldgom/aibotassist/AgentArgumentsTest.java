package top.goldgom.aibotassist;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentArgumentsTest {
 @Test void missingDimensionsReturnsActionableError(){JsonObject a=JsonParser.parseString("{\"token\":\"t\",\"kind\":\"house\"}").getAsJsonObject();var e=assertThrows(IllegalArgumentException.class,()->AgentToolCatalog.validateArgs("build_structure",a));assertTrue(e.getMessage().contains("width"));}
 @Test void paletteWithoutMaterialHasExplicitFallback(){JsonObject a=JsonParser.parseString("{\"token\":\"t\",\"kind\":\"house\",\"width\":5,\"depth\":5,\"height\":4,\"palette\":{\"wall\":\"minecraft:birch_planks\",\"roof\":\"minecraft:stone_bricks\"}}").getAsJsonObject();assertDoesNotThrow(()->AgentToolCatalog.validateArgs("build_structure",a));assertEquals("minecraft:birch_planks",AgentToolCatalog.fallbackMaterial(a));}
 @Test void emptyMaterialsAndFractionalDimensionsRejected(){JsonObject a=JsonParser.parseString("{\"token\":\"t\",\"kind\":\"house\",\"width\":5,\"depth\":5,\"height\":4}").getAsJsonObject();assertThrows(IllegalArgumentException.class,()->AgentToolCatalog.validateArgs("build_structure",a));a.addProperty("material","minecraft:stone");a.addProperty("width",5.5);assertThrows(IllegalArgumentException.class,()->AgentToolCatalog.validateArgs("build_structure",a));}
 @Test void schemaRequiresBuildingInputs(){JsonArray schemas=new JsonArray();AgentToolCatalog.append(schemas);for(var t:schemas){var f=t.getAsJsonObject().getAsJsonObject("function");if(f.get("name").getAsString().equals("build_structure")){var required=f.getAsJsonObject("parameters").getAsJsonArray("required");assertTrue(required.toString().contains("width"));assertTrue(required.toString().contains("token"));}}}
}
