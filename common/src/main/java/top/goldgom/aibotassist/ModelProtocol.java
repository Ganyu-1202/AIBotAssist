package top.goldgom.aibotassist;

import com.google.gson.*;

/** Converts the bridge's conversation into the configured provider protocol. */
public final class ModelProtocol {
    private ModelProtocol() {}
    public static void validate(String protocol,String group,boolean auto) {
        if (!("chat".equals(protocol)||"responses".equals(protocol)||auto&&"auto".equals(protocol)))
            throw new IllegalArgumentException("模型类型必须为 chat / responses"+(auto?" / auto":""));
        if(group==null||group.isBlank()||group.length()>64||group.chars().anyMatch(c->c<=32||c==127))
            throw new IllegalArgumentException("TokenNest渠道无效");
    }
    public static String path(String protocol){return "responses".equals(protocol)?"/responses":"/chat/completions";}
    public static JsonObject body(String protocol,String model,JsonArray messages,JsonArray tools) {
        JsonObject body=new JsonObject();body.addProperty("model",model);
        boolean responses="responses".equals(protocol);
        body.addProperty(responses?"max_output_tokens":"max_tokens",12000);
        if(!responses)body.add("messages",messages.deepCopy());
        else {
            body.addProperty("store",false);
            JsonArray input=new JsonArray();
            for(JsonElement entry:messages){
                JsonObject message=entry.getAsJsonObject();String role=message.get("role").getAsString();
                if(role.equals("tool")){
                    JsonObject output=new JsonObject();output.addProperty("type","function_call_output");
                    output.add("call_id",message.get("tool_call_id"));output.add("output",message.get("content"));input.add(output);continue;
                }
                JsonElement content=message.get("content");
                if(content!=null&&!content.isJsonNull()){
                    JsonObject converted=new JsonObject();converted.addProperty("role",role);
                    if(content.isJsonArray()){
                        JsonArray parts=new JsonArray();for(JsonElement part:content.getAsJsonArray()){
                            JsonObject p=part.getAsJsonObject(),next=new JsonObject();String type=p.get("type").getAsString();
                            if(type.equals("text")){next.addProperty("type",role.equals("assistant")?"output_text":"input_text");next.add("text",p.get("text"));}
                            else if(type.equals("image_url")){next.addProperty("type","input_image");next.add("image_url",p.getAsJsonObject("image_url").get("url"));if(p.getAsJsonObject("image_url").has("detail"))next.add("detail",p.getAsJsonObject("image_url").get("detail"));}
                            else throw new IllegalArgumentException("不支持的模型消息内容类型");
                            parts.add(next);
                        }converted.add("content",parts);
                    }else converted.add("content",content.deepCopy());
                    input.add(converted);
                }
                if(message.has("tool_calls"))for(JsonElement call:message.getAsJsonArray("tool_calls")){
                    JsonObject c=call.getAsJsonObject(),fn=c.getAsJsonObject("function"),next=new JsonObject();
                    next.addProperty("type","function_call");next.add("call_id",c.get("id"));next.add("name",fn.get("name"));next.add("arguments",fn.get("arguments"));input.add(next);
                }
            }body.add("input",input);
        }
        if(!tools.isEmpty()){
            JsonArray converted=new JsonArray();for(JsonElement entry:tools){
                if(!responses){converted.add(entry.deepCopy());continue;}
                JsonObject tool=entry.getAsJsonObject().getAsJsonObject("function").deepCopy();tool.addProperty("type","function");tool.addProperty("strict",false);converted.add(tool);
            }body.add("tools",converted);body.addProperty("tool_choice","auto");body.addProperty("parallel_tool_calls",false);
        }return body;
    }
    public static JsonObject message(String protocol,JsonObject reply){
        if(!"responses".equals(protocol))return reply.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        if(reply.has("error")&&!reply.get("error").isJsonNull() || reply.has("status")&&!"completed".equals(reply.get("status").getAsString()))
            throw new IllegalStateException("Responses生成未完成，请检查模型状态或输出限制");
        JsonArray output=reply.getAsJsonArray("output");if(output==null)throw new IllegalArgumentException("Responses响应缺少output");
        StringBuilder text=new StringBuilder();JsonArray calls=new JsonArray();
        for(JsonElement entry:output){JsonObject item=entry.getAsJsonObject();String type=item.get("type").getAsString();
            if(type.equals("message"))for(JsonElement part:item.getAsJsonArray("content")){
                JsonObject p=part.getAsJsonObject();if("output_text".equals(p.get("type").getAsString()))text.append(p.get("text").getAsString());
                else if("refusal".equals(p.get("type").getAsString()))text.append(p.get("refusal").getAsString());
            }
            else if(type.equals("function_call")){
                JsonObject c=new JsonObject(),fn=new JsonObject();c.addProperty("type","function");c.add("id",item.get("call_id"));fn.add("name",item.get("name"));fn.add("arguments",item.get("arguments"));c.add("function",fn);calls.add(c);
            }
        }
        if(text.isEmpty()&&calls.isEmpty())throw new IllegalArgumentException("Responses未返回文本或工具调用");
        JsonObject message=new JsonObject();message.addProperty("role","assistant");message.addProperty("content",text.toString());if(!calls.isEmpty())message.add("tool_calls",calls);return message;
    }
}
