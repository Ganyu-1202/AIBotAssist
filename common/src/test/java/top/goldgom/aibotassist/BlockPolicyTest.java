package top.goldgom.aibotassist;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlockPolicyTest {
 @Test void defaultsAllowCreativeAndOrdinarySurvival(){var c=BlockPolicy.defaults();assertFalse(BlockPolicy.denied(c,"creative","minecraft:command_block",false));assertTrue(BlockPolicy.denied(c,"survival","minecraft:command_block",false));assertFalse(BlockPolicy.denied(c,"survival","minecraft:chest",false));assertFalse(BlockPolicy.denied(c,"survival","example:marble",false));assertTrue(BlockPolicy.denied(c,"survival","minecraft:bedrock",true));}
 @Test void rulesSeparatePlaceAndBreak(){var c=BlockPolicy.defaults();c.modes.get("survival").placeDenied.add("example:*");assertTrue(BlockPolicy.denied(c,"survival","example:stone",false));assertFalse(BlockPolicy.denied(c,"survival","example:stone",true));assertFalse(BlockPolicy.denied(c,"creative","example:stone",false));}
 @Test void rejectsMissingAndInvalidModes(){var c=BlockPolicy.defaults();c.modes.remove("creative");assertThrows(Exception.class,()->BlockPolicy.validate(c));}
}
