package top.goldgom.aibotassist;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteViewSpecTest {
    private static RemoteViewSpec spec(String text){return RemoteViewSpec.parse(JsonParser.parseString(text).getAsJsonObject(),10,70,-5,0,0);}
    @Test void cameraUsesAbsoluteEyesAndBoundedForwardRegion(){
        var s=spec("{}");assertEquals(10,s.x());assertEquals(70,s.y());assertEquals(-5,s.z());assertEquals(33,s.side());assertEquals(3,s.centerZ());assertFalse(s.loadChunks());
        s=spec("{\"x\":1000,\"y\":80,\"z\":-200,\"yaw\":90,\"radius\":8,\"load_chunks\":true}");assertEquals(996,s.centerX());assertEquals(-200,s.centerZ());assertEquals(17,s.side());assertTrue(s.loadChunks());
    }
    @Test void targetCreatesCorrectWorldAxisFacingWithoutMovingPlayer(){
        var s=spec("{\"target_x\":20,\"target_y\":70,\"target_z\":-5}");assertEquals(-90,s.yaw(),0.0001);assertEquals(0,s.pitch(),0.0001);assertEquals(20,s.centerX());assertEquals(10,s.x());
        s=spec("{\"target_x\":10,\"target_y\":80,\"target_z\":-5}");assertEquals(-90,s.pitch(),0.0001);
    }
    @Test void rejectsIncompleteCoordinatesNonFiniteValuesAndExcessiveRenderRequests(){
        for(String text:new String[]{"{\"x\":5}","{\"target_x\":5}","{\"radius\":17}","{\"radius\":3}","{\"radius\":8.5}","{\"fov\":120}","{\"pitch\":91}","{\"yaw\":1e300}","{\"load_chunks\":\"true\"}","{\"target_x\":1000,\"target_y\":70,\"target_z\":-5}"})assertThrows(Exception.class,()->spec(text),text);
        var a=new JsonObject();a.addProperty("x",Double.NaN);a.addProperty("y",70);a.addProperty("z",0);assertThrows(IllegalArgumentException.class,()->RemoteViewSpec.parse(a,0,0,0,0,0));
    }
}
