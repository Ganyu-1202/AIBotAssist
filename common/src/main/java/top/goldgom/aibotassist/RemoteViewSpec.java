package top.goldgom.aibotassist;

import com.google.gson.*;

/** Pure, bounded camera request shared by capture validation and tests. Coordinates are absolute. */
public record RemoteViewSpec(double x,double y,double z,float yaw,float pitch,float fov,int radius,int centerX,int centerY,int centerZ,boolean loadChunks) {
    public static double number(JsonObject a,String key,double fallback){
        if(!a.has(key))return fallback;JsonElement v=a.get(key);
        if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException(key+"必须为数字");
        double value=v.getAsDouble();if(!Double.isFinite(value))throw new IllegalArgumentException(key+"必须为有限数值");return value;
    }
    public static RemoteViewSpec parse(JsonObject a,double x,double y,double z,float yaw,float pitch){
        boolean xyz=a.has("x")||a.has("y")||a.has("z");if(xyz&&!(a.has("x")&&a.has("y")&&a.has("z")))throw new IllegalArgumentException("位置需要完整x/y/z");
        x=number(a,"x",x);y=number(a,"y",y);z=number(a,"z",z);
        if(Math.abs(x)>29_999_900||Math.abs(z)>29_999_900||Math.abs(y)>20_000_000)throw new IllegalArgumentException("位置超出世界范围");
        yaw=(float)number(a,"yaw",yaw);pitch=(float)number(a,"pitch",pitch);float fov=(float)number(a,"fov",70);
        int radius=a.has("radius")?a.get("radius").getAsBigDecimal().intValueExact():16;
        if(!Float.isFinite(yaw)||!Float.isFinite(pitch)||pitch< -90||pitch>90||fov<30||fov>110||radius<4||radius>16)throw new IllegalArgumentException("pitch=-90..90，fov=30..110，radius=4..16");
        double cx=x-Math.sin(Math.toRadians(yaw))*Math.cos(Math.toRadians(pitch))*radius/2.0;
        double cy=y-Math.sin(Math.toRadians(pitch))*radius/2.0,cz=z+Math.cos(Math.toRadians(yaw))*Math.cos(Math.toRadians(pitch))*radius/2.0;
        boolean target=a.has("target_x")||a.has("target_y")||a.has("target_z");
        if(target){
            if(!(a.has("target_x")&&a.has("target_y")&&a.has("target_z")))throw new IllegalArgumentException("目标需要完整target_x/y/z");
            cx=number(a,"target_x",0);cy=number(a,"target_y",0);cz=number(a,"target_z",0);
            double dx=cx-x,dy=cy-y,dz=cz-z,distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(distance<0.01||distance>48)throw new IllegalArgumentException("镜头与目标距离需为0.01到48格");
            yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        }
        boolean load=false;if(a.has("load_chunks")){JsonElement v=a.get("load_chunks");if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("load_chunks必须为布尔值");load=v.getAsBoolean();}
        return new RemoteViewSpec(x,y,z,yaw,pitch,fov,radius,(int)Math.floor(cx),(int)Math.floor(cy),(int)Math.floor(cz),load);
    }
    public int side(){return radius*2+1;}
}
