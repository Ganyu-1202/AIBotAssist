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
  for(Throwable cause=e;cause!=null;cause=cause.getCause()){
   if(cause instanceof MissingRuntime)return "找不到Pi入口文件：请在登录/Pi运行时页填写正确的index.mjs路径。";
   if(cause instanceof StartFailure)return "Pi运行器无法启动：请填写bun.exe绝对路径，检查是否已安装Bun。";
   if(cause instanceof java.net.http.HttpTimeoutException)return "模型HTTP请求超时，请检查网络与代理。";
   if(cause instanceof java.net.ConnectException)return "无法连接模型服务或代理，请检查网络。";
   if(cause instanceof HttpFailure h)return switch(h.code){case 401->"API认证失败（HTTP401）：检查密钥是否有效。";case 403->"API访问被拒绝（HTTP403）：检查账户或模型权限。";case 404->"API接口或模型不存在（HTTP404）：检查/v1地址及模型ID。";case 429->"API限流或额度不足（HTTP429），请检查服务商账户。";default->"模型服务返回HTTP"+h.code+"；未显示原始响应以保护凭据。";};
   if(cause instanceof java.util.concurrent.TimeoutException)return "服务器工具结果超时，可能部分完成；停止且不自动重试。";
  }
  return "Pi运行异常：检查SDK依赖是否安装及模型工具调用支持；操作可能部分完成，不自动重试。";
 }
 public static final class MissingRuntime extends Exception{}
 public static final class StartFailure extends Exception{public StartFailure(Throwable e){super(e);}}
 public static final class HttpFailure extends Exception{public final int code;public HttpFailure(int code){this.code=code;}}
}
