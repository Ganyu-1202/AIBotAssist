package top.goldgom.aibotassist.client;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientVisionTest {
    @Test void vanillaServersExposeOnlyClientViewAndModdedServersKeepEveryExistingTool() {
        var client=ClientVision.tools(false);assertEquals(2+ClientIntegrations.NAMES.size(),client.size());
        var tool=client.get(0).getAsJsonObject().getAsJsonObject("function");
        assertEquals("capture_view",tool.get("name").getAsString());
        assertFalse(tool.getAsJsonObject("parameters").get("additionalProperties").getAsBoolean());
        var modded=ClientVision.tools(true);assertEquals(MinecraftAgent.tools().size()+2+ClientIntegrations.NAMES.size(),modded.size());
        var names=new HashSet<String>();for(var item:modded)assertTrue(names.add(item.getAsJsonObject().getAsJsonObject("function").get("name").getAsString()));
        assertTrue(names.containsAll(Set.of("capture_view","observe_world","execute_task","run_commands")));
    }
    @Test void toolCannotCaptureOtherWindowsFilesOrArbitraryTargets() throws Exception {
        var args=new JsonObject();args.addProperty("path","desktop.png");
        var result=ClientVision.capture(args,()->true);
        assertEquals("rejected",result.get("status").getAsString());assertFalse(result.has("image"));
    }
    @Test void cancelledWorldCannotEnqueueACapture() {
        assertThrows(InterruptedException.class,()->ClientVision.capture(new JsonObject(),()->false));
    }
}
