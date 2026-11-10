package top.goldgom.aibotassist;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure schema tests; actual ticking/region hooks require an in-game dedicated-server test. */
class ProcessingServiceTest {
    private static Object invoke(String method, Class<?>[] types, Object... args) throws Exception {
        Method m=ProcessingService.class.getDeclaredMethod(method,types);m.setAccessible(true);
        try {return m.invoke(null,args);} catch(InvocationTargetException e) {
            if(e.getCause() instanceof IllegalArgumentException x)throw x;
            throw e;
        }
    }
    private static int number(JsonElement e,int min,int max) throws Exception {
        return (Integer)invoke("integer",new Class<?>[]{JsonElement.class,int.class,int.class},e,min,max);
    }
    @Test void acceptsInclusiveIntegerBounds() throws Exception {
        assertEquals(1,number(new JsonPrimitive(1),1,64));
        assertEquals(64,number(new JsonPrimitive(64),1,64));
        assertEquals(0,number(new JsonPrimitive(0),0,35));
        assertEquals(35,number(new JsonPrimitive(35),0,35));
    }
    @Test void refusesFractionsCoercionsOverflowAndNull() {
        for(JsonElement v:new JsonElement[]{JsonNull.INSTANCE,new JsonPrimitive("1"),new JsonPrimitive(true),
            new JsonPrimitive(0),new JsonPrimitive(65),new JsonPrimitive(1.5),JsonParser.parseString("999999999999999999999")})
            assertThrows(IllegalArgumentException.class,()->number(v,1,64));
        assertThrows(IllegalArgumentException.class,()->number(null,1,64));
    }
    @Test void textMustBeAnExplicitString() throws Exception {
        JsonObject a=new JsonObject();a.addProperty("device","furnace");
        assertEquals("furnace",invoke("text",new Class<?>[]{JsonObject.class,String.class},a,"device"));
        assertThrows(IllegalArgumentException.class,()->invoke("text",new Class<?>[]{JsonObject.class,String.class},a,"input"));
        a.addProperty("device",1);
        assertThrows(IllegalArgumentException.class,()->invoke("text",new Class<?>[]{JsonObject.class,String.class},a,"device"));
    }
    @Test void clearDoesNotInventWorldOrInventoryChanges() throws Exception {
        ProcessingService.clear();
        Field tasks=ProcessingService.class.getDeclaredField("TASKS");tasks.setAccessible(true);
        assertTrue(((java.util.Map<?,?>)tasks.get(null)).isEmpty());
    }
}
