package top.goldgom.aibotassist;

import com.google.gson.*;
import java.net.URI;
import java.nio.file.*;

/** Server-owned connection policy. Only publicView may be sent to ordinary clients. */
public final class ServerApiConfig {
    public String mode="independent";
    public String baseUrl="https://openai.goldgom.top/v1",model="",apiKey="";
    public String group="default",modelProtocol="chat";
    public int maxConcurrent=4,playerIntervalSeconds=0;
    public boolean shared(){return mode.equals("shared");}
    public void validate(){
        if(!"independent".equals(mode)&&!"shared".equals(mode))throw new IllegalArgumentException("mode必须为independent或shared");
        endpoint(baseUrl);
        ModelProtocol.validate(modelProtocol,group,false);
        if(model==null||model.length()>100||model.contains("\n")||model.contains("\r")||apiKey==null||apiKey.length()>4096||apiKey.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("模型或密钥格式无效");
        if(maxConcurrent<1||maxConcurrent>16||playerIntervalSeconds<0||playerIntervalSeconds>60)throw new IllegalArgumentException("并发必须1至16，每玩家请求间隔0至60秒");
        if(shared()&&(model.isBlank()||apiKey.isBlank()))throw new IllegalArgumentException("共享模式需要配置模型和API密钥");
    }
    public static URI endpoint(String value){
        if(value==null||value.length()>1000)throw new IllegalArgumentException("API地址无效");
        URI u=URI.create(value.replaceAll("/+$",""));
        if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException("API地址必须为无凭据和查询参数的HTTPS地址");return u;
    }
    public JsonObject publicView(String revision,boolean admin){
        JsonObject o=new JsonObject();o.addProperty("mode",mode);o.addProperty("model",shared()?model:"");o.addProperty("revision",revision);o.addProperty("can_manage",admin);o.addProperty("ready",!shared()||!apiKey.isBlank()&&!model.isBlank());
        if(admin){o.addProperty("base_url",baseUrl);o.addProperty("configured_model",model);o.addProperty("group",group);o.addProperty("model_protocol",modelProtocol);o.addProperty("key_configured",!apiKey.isBlank());o.addProperty("max_concurrent",maxConcurrent);o.addProperty("player_interval_seconds",playerIntervalSeconds);}return o;
    }
    public ServerApiConfig updated(JsonObject a){
        ServerApiConfig next=new Gson().fromJson(new Gson().toJson(this),ServerApiConfig.class);
        if(a.has("mode"))next.mode=a.get("mode").getAsString();
        if(a.has("base_url"))next.baseUrl=a.get("base_url").getAsString().trim();
        if(a.has("group"))next.group=a.get("group").getAsString().trim();
        if(a.has("model_protocol"))next.modelProtocol=a.get("model_protocol").getAsString();
        if(a.has("model"))next.model=a.get("model").getAsString().trim();
        if(a.has("clear_key")&&a.get("clear_key").getAsBoolean())next.apiKey="";
        if(a.has("api_key")&&!a.get("api_key").getAsString().isBlank())next.apiKey=a.get("api_key").getAsString().trim();
        if(a.has("max_concurrent"))next.maxConcurrent=a.get("max_concurrent").getAsBigDecimal().intValueExact();
        if(a.has("player_interval_seconds"))next.playerIntervalSeconds=a.get("player_interval_seconds").getAsBigDecimal().intValueExact();
        next.validate();return next;
    }
    public static ServerApiConfig load(Path file)throws Exception{
        if(!Files.exists(file)){ServerApiConfig c=new ServerApiConfig();c.save(file);return c;}
        if(Files.size(file)>32768)throw new IllegalArgumentException("服务器API配置文件过大");
        ServerApiConfig c=new Gson().fromJson(Files.readString(file),ServerApiConfig.class);if(c==null)throw new IllegalArgumentException("服务器API配置无效");c.validate();return c;
    }
    public void save(Path file)throws Exception{
        validate();Files.createDirectories(file.toAbsolutePath().getParent());Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"aibot-api-",".tmp");
        try{restrict(temp);Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(this));try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}restrict(file);}finally{Files.deleteIfExists(temp);}
    }
    private static void restrict(Path p){try{Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(Exception ignored){}}
}
