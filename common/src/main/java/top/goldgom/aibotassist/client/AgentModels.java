package top.goldgom.aibotassist.client;

import com.google.gson.*;
import java.util.*;

/** Conversational model discovery honors provider endpoint metadata. */
public final class AgentModels {
    private AgentModels() {}
    public static List<String> parse(JsonObject response) { return List.copyOf(catalog(response).keySet()); }
    public static Map<String,String> catalog(JsonObject response) {return catalog(response,"auto");}
    public static Map<String,String> catalog(JsonObject response,String requested) {
        if (!response.has("data") || !response.get("data").isJsonArray())
            throw new IllegalArgumentException("模型列表格式无效");
        Map<String,String> models = new LinkedHashMap<>();
        for (JsonElement entry : response.getAsJsonArray("data")) {
            if (!entry.isJsonObject()) continue;
            JsonObject model = entry.getAsJsonObject();
            if (!model.has("id") || !model.get("id").isJsonPrimitive() || !model.getAsJsonPrimitive("id").isString()) continue;
            String id = model.get("id").getAsString();
            if (id.isBlank() || id.length() > 100 || id.chars().anyMatch(Character::isISOControl)) continue;
            String protocol="chat";
            JsonElement endpoints = model.get("supported_endpoint_types");
            if (endpoints != null && !endpoints.isJsonNull()) {
                if (!endpoints.isJsonArray()) continue;
                boolean chat = false, responses = false;
                for (JsonElement endpoint : endpoints.getAsJsonArray())
                    if (endpoint.isJsonPrimitive() && endpoint.getAsJsonPrimitive().isString()) {
                        if(endpoint.getAsString().equals("openai"))chat=true;
                        if(endpoint.getAsString().equals("openai-response"))responses=true;
                    }
                if (!chat && !responses) continue;
                if(requested.equals("chat")&&!chat || requested.equals("responses")&&!responses)continue;
                if(requested.equals("responses")||!chat)protocol="responses";
            } else {
                // Generic OpenAI-compatible catalogs may not expose endpoint metadata.
                if(requested.equals("responses"))protocol="responses";
                String lower = id.toLowerCase(Locale.ROOT);
                if (List.of("embedding", "rerank", "whisper", "tts", "dall-e", "image", "video", "transcri").stream().anyMatch(lower::contains)) continue;
            }
            models.putIfAbsent(id,protocol);
        }
        return Collections.unmodifiableMap(models);
    }
    public static String select(String current, List<String> models) {
        if (current != null && models.contains(current)) return current;
        if (models.isEmpty()) throw new IllegalStateException("当前账户没有可用于聊天接口的模型，请检查 TokenNest 模型权限。");
        return models.getFirst();
    }
}
