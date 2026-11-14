package top.goldgom.aibotassist.api;

import com.google.gson.*;
import java.util.Set;

/** Deliberately small, validated JSON Schema subset for extension operation arguments. */
public final class OperationSchema {
    private OperationSchema(){}
    public static void check(JsonObject schema){check(schema,0);}
    private static void check(JsonObject s,int depth){
        if(depth>8)throw new IllegalArgumentException("Operation schema too deep");
        var allowed=Set.of("type","description","properties","required","additionalProperties","items","enum","minimum","maximum","minLength","maxLength","minItems","maxItems");
        for(String k:s.keySet())if(!allowed.contains(k))throw new IllegalArgumentException("Unsupported schema keyword: "+k);
        String type=s.get("type").getAsString();
        if(!Set.of("object","array","string","integer","number","boolean").contains(type))throw new IllegalArgumentException("Unsupported schema type");
        if(type.equals("object")){
            if(!s.has("additionalProperties")||s.get("additionalProperties").getAsBoolean())throw new IllegalArgumentException("Object schemas require additionalProperties=false");
            var p=s.getAsJsonObject("properties");if(p==null||p.size()>64)throw new IllegalArgumentException("Object properties missing or too many");
            for(var field:p.entrySet()){if(!field.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid field name");check(field.getValue().getAsJsonObject(),depth+1);}
            if(s.has("required"))for(var k:s.getAsJsonArray("required"))if(!p.has(k.getAsString()))throw new IllegalArgumentException("Required property missing from schema");
        }
        if(type.equals("array"))check(s.getAsJsonObject("items"),depth+1);
    }
    public static void validate(JsonObject schema,JsonElement value){validate(schema,value,"parameters",0);}
    private static void validate(JsonObject s,JsonElement v,String path,int depth){
        if(depth>8||v==null||v.isJsonNull())throw invalid(path);
        if(s.has("enum")&&!s.getAsJsonArray("enum").asList().contains(v))throw invalid(path);
        switch(s.get("type").getAsString()){
            case "object" -> {
                if(!v.isJsonObject())throw invalid(path);var o=v.getAsJsonObject();var properties=s.getAsJsonObject("properties");
                if(s.has("required"))for(var required:s.getAsJsonArray("required"))if(!o.has(required.getAsString()))throw invalid(path+"."+required.getAsString());
                for(var entry:o.entrySet()){
                    if(!properties.has(entry.getKey()))throw invalid(path);
                    validate(properties.getAsJsonObject(entry.getKey()),entry.getValue(),path+"."+entry.getKey(),depth+1);
                }
            }
            case "array" -> {if(!v.isJsonArray())throw invalid(path);int size=v.getAsJsonArray().size();bounds(s,size,"minItems","maxItems",path);if(size>256)throw invalid(path);for(var item:v.getAsJsonArray())validate(s.getAsJsonObject("items"),item,path+"[]",depth+1);}
            case "string" -> {if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw invalid(path);int size=v.getAsString().length();bounds(s,size,"minLength","maxLength",path);if(size>4096)throw invalid(path);}
            case "integer","number" -> {if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber())throw invalid(path);var n=v.getAsBigDecimal();if(s.get("type").getAsString().equals("integer")&&n.stripTrailingZeros().scale()>0)throw invalid(path);if(s.has("minimum")&&n.compareTo(s.get("minimum").getAsBigDecimal())<0||s.has("maximum")&&n.compareTo(s.get("maximum").getAsBigDecimal())>0)throw invalid(path);}
            case "boolean" -> {if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw invalid(path);}
            default -> throw invalid(path);
        }
    }
    private static void bounds(JsonObject s,int value,String min,String max,String path){if(s.has(min)&&value<s.get(min).getAsInt()||s.has(max)&&value>s.get(max).getAsInt())throw invalid(path);}
    private static IllegalArgumentException invalid(String path){return new IllegalArgumentException("操作参数不符合注册格式："+path);}
}
