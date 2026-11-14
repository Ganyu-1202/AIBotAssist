package top.goldgom.aibotassist.api;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentExtensionsTest {
 @Test void mutationExceptionIsUnknownAndHandlerIsNeverRetried(){
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  var op=new AgentExtensions.Operation("testmod:partial",AgentExtensions.Kind.MACHINE,"Partial mutation",schema(),true,2,p->true,"Inspect first",(p,a)->{calls.incrementAndGet();throw new IllegalStateException("PRIVATE_DETAIL");});
  var result=AgentExtensions.invoke(op,null,json("{\"mode\":\"on\"}"));assertEquals(1,calls.get());assertEquals("unknown",result.get("status").getAsString());assertFalse(result.toString().contains("PRIVATE_DETAIL"));
  assertThrows(IllegalArgumentException.class,()->AgentExtensions.invoke(op,null,json("{\"mode\":\"invalid\"}")));assertEquals(1,calls.get());
 }
 static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
 static JsonObject schema(){return json("{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\",\"enum\":[\"off\",\"on\"]},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}},\"required\":[\"mode\"],\"additionalProperties\":false}");}
 @Test void validatesValuesTypesBoundsAndRejectsUnknownFields(){
  var schema=schema();OperationSchema.check(schema);assertDoesNotThrow(()->OperationSchema.validate(schema,json("{\"mode\":\"off\",\"count\":3}")));
  for(String invalid:new String[]{"{}","{\"mode\":\"invalid\"}","{\"mode\":\"on\",\"count\":1.5}","{\"mode\":\"on\",\"count\":65}","{\"mode\":\"on\",\"count\":\"3\"}","{\"mode\":\"on\",\"command\":\"op player\"}"})assertThrows(IllegalArgumentException.class,()->OperationSchema.validate(schema,json(invalid)));
 }
 @Test void refusesUnsupportedSchemasRatherThanIgnoringConstraints(){var s=schema();s.addProperty("$ref","anything");assertThrows(IllegalArgumentException.class,()->OperationSchema.check(s));s.remove("$ref");s.addProperty("additionalProperties",true);assertThrows(IllegalArgumentException.class,()->OperationSchema.check(s));}
 @Test void registrationCopiesSchemaAndRejectsDuplicateIds(){
  var source=schema();var op=new AgentExtensions.Operation("testmod:machine",AgentExtensions.Kind.MACHINE,"Switch test machine",source,true,2,p->true,"Read back mode",(p,a)->json("{\"status\":\"completed\"}"));
  AgentExtensions.register(op);source.remove("required");op.parameters().remove("required");assertTrue(op.parameters().has("required"));assertTrue(AgentExtensions.mutating("testmod:machine"));
  assertThrows(IllegalArgumentException.class,()->AgentExtensions.register(op));assertThrows(IllegalArgumentException.class,()->AgentExtensions.mutating("unknown:machine"));
 }
 @Test void nestedArrayAndObjectsAreValidated(){
  var s=json("{\"type\":\"object\",\"properties\":{\"slots\":{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8},\"maxItems\":2}},\"additionalProperties\":false}");OperationSchema.check(s);
  assertDoesNotThrow(()->OperationSchema.validate(s,json("{\"slots\":[0,8]}")));assertThrows(IllegalArgumentException.class,()->OperationSchema.validate(s,json("{\"slots\":[9]}")));assertThrows(IllegalArgumentException.class,()->OperationSchema.validate(s,json("{\"slots\":[0,1,2]}")));
 }
}
