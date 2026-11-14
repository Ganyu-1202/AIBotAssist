package top.goldgom.aibotassist.client;
import java.nio.file.*;
public final class PiDiagnostics {
 private PiDiagnostics(){}
 public static Path resolve(Path working,String configured){
  Path entry=Path.of(configured);if(entry.isAbsolute())return entry.normalize();
  Path base=working.toAbsolutePath().normalize();
  // Only exact configured relative entry; bounded parent search supports Loom fabric/run.
  for(int i=0;i<5 && base!=null;i++,base=base.getParent()){
   Path candidate=base.resolve(entry).normalize();
   if(Files.isRegularFile(candidate))return candidate;
  }
  return working.toAbsolutePath().resolve(entry).normalize();
 }
 public static String explain(Throwable e){
  if(e instanceof DiagnosticFailure d)return explain(d.getCause())+" 诊断编号 "+d.id+"，阶段 "+d.stage+"；详见 logs/latest.log。";
  for(Throwable cause=e;cause!=null;cause=cause.getCause()){
   if(cause instanceof top.goldgom.aibotassist.ModelProtocol.ResponseFailure)return cause.getMessage();
   if(cause instanceof ClientServerApi.Failure)return cause.getMessage();
   if(cause instanceof IncompatibleServer)return "服务器模组版本过旧，请将客户端和服务器同时更新到支持大方案与固定原点的版本。";
   if(cause instanceof MissingRuntime)return "找不到Pi入口文件：请在登录/Pi运行时页填写正确的index.mjs路径。";
   if(cause instanceof StartFailure)return "Pi运行器无法启动：请填写bun.exe绝对路径，检查是否已安装Bun。";
   if(cause instanceof TurnDeadline)return "本次AI请求超过10分钟，已停止。请查看最近的工具拒绝原因或模型响应耗时；已执行操作可能保留，不自动重试。";
   if(cause instanceof java.net.http.HttpTimeoutException)return "模型HTTP请求超时，请检查网络与代理。";
   if(cause instanceof java.net.ConnectException)return "无法连接模型服务或代理，请检查网络。";
   if(cause instanceof HttpFailure h)return switch(h.code){case 400->"请求格式或模型能力不兼容（HTTP400）；使用视野工具需模型支持图片输入。";case 401->"API认证失败（HTTP401）：检查密钥是否有效。";case 403->"API访问被拒绝（HTTP403）：检查账户或模型权限。";case 404->"API接口或模型不存在（HTTP404）：检查/v1地址及模型ID。";case 429->"API限流或额度不足（HTTP429），请检查服务商账户。";default->"模型服务返回HTTP"+h.code+"；请求已停止，详情见诊断日志。";};
   if(cause instanceof java.util.concurrent.TimeoutException)return "服务器工具结果超时，可能部分完成；停止且不自动重试。";
  }
  return "AI处理停止（模型、工具参数或运行时异常）。请查看最近的工具拒绝原因和日志；操作可能部分完成，不自动重试。";
 }
 public static final class IncompatibleServer extends Exception{}
 public static final class MissingRuntime extends Exception{}
 public static final class StartFailure extends Exception{public StartFailure(Throwable e){super(e);}}
 public static final class TurnDeadline extends Exception{}
 public static final class DiagnosticFailure extends Exception{
  public final String id,stage;
  public DiagnosticFailure(String id,String stage,Throwable cause){super("AI diagnostic "+id,cause);this.id=id;this.stage=stage;}
 }
 public static final class HttpFailure extends top.goldgom.aibotassist.ModelRetry.HttpFailure{public HttpFailure(int code){super(code);}}
}
