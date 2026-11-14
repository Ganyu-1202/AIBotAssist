package top.goldgom.aibotassist;
import org.junit.jupiter.api.Test;
import com.google.gson.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StructuresTest {
 @Test void generatesHouseWithinBudget(){var p=Structures.create("house",5,5,4,"minecraft:oak_planks","north",0,0,0);assertTrue(p.blocks().size()<=256);assertFalse(p.blocks().isEmpty());assertTrue(p.blocks().stream().allMatch(b->b.z()<=-3));}
 @Test void supportsOtherKinds(){for(String k:List.of("wall","bridge","stairs"))assertFalse(Structures.create(k,5,5,4,"minecraft:stone","east",0,0,0).blocks().isEmpty());}
 @Test void rejectsOversizedAndUnsafe(){assertThrows(Exception.class,()->Structures.create("house",33,11,8,"minecraft:stone","north",0,0,0));assertThrows(Exception.class,()->Structures.create("house",5,5,4,"invalid id","north",0,0,0));}
 @Test void supportsMixedPalette(){var p=Structures.create("house",5,5,4,"minecraft:oak_planks",Map.of("floor","minecraft:cobblestone","roof","minecraft:bricks","frame","minecraft:oak_log","window","minecraft:glass"),"south",0,0,0);assertEquals(5,p.blocks().stream().map(Plan.Placement::block).distinct().count());}
 @Test void supportsModdedMaterialIds(){assertDoesNotThrow(()->Structures.create("house",5,5,4,"example:building_block","north",0,0,0));}
 @Test void exposesAllRegisteredToolsOnce(){JsonArray tools=new JsonArray();AgentToolCatalog.append(tools);Set<String> names=new HashSet<>();for(var t:tools)assertTrue(names.add(t.getAsJsonObject().getAsJsonObject("function").get("name").getAsString()));assertEquals(AgentToolCatalog.NAMES,names);}
}
