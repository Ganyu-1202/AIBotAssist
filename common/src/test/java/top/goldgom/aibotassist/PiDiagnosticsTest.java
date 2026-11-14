package top.goldgom.aibotassist;
import top.goldgom.aibotassist.client.PiDiagnostics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PiDiagnosticsTest {
 @Test void diagnosticIdPreservesActionableHttpReasonWithoutExceptionPayload(){
  var error=new PiDiagnostics.DiagnosticFailure("abc123","model_http",new PiDiagnostics.HttpFailure(401));
  String text=PiDiagnostics.explain(error);assertTrue(text.contains("HTTP401"));assertTrue(text.contains("abc123"));assertTrue(text.contains("model_http"));
  assertFalse(PiDiagnostics.explain(new PiDiagnostics.DiagnosticFailure("id","parse",new Exception("SECRET_PAYLOAD"))).contains("SECRET_PAYLOAD"));
 }
 @Test void deadlineAndUnknownFailuresDoNotBlameInstalledSdkDependencies(){assertTrue(PiDiagnostics.explain(new PiDiagnostics.TurnDeadline()).contains("10分钟"));assertFalse(PiDiagnostics.explain(new Exception()).contains("SDK依赖"));}
 @TempDir Path root;
 @Test void locatesRuntimeFromLoomRunDirectory() throws Exception {Path entry=root.resolve("agent-runtime/index.mjs");Files.createDirectories(entry.getParent());Files.writeString(entry,"// test");Path run=root.resolve("neoforge/run");Files.createDirectories(run);assertEquals(entry.toAbsolutePath(),PiDiagnostics.resolve(run,"agent-runtime/index.mjs"));}
 @Test void respectsAbsoluteConfiguredPath(){Path expected=root.resolve("custom/index.mjs");assertEquals(expected,PiDiagnostics.resolve(root,expected.toString()));}
 @Test void distinguishesRuntimeAndApiFailures(){assertTrue(PiDiagnostics.explain(new PiDiagnostics.MissingRuntime()).contains("入口"));assertTrue(PiDiagnostics.explain(new PiDiagnostics.HttpFailure(401)).contains("认证"));assertTrue(PiDiagnostics.explain(new PiDiagnostics.HttpFailure(429)).contains("额度"));assertTrue(PiDiagnostics.explain(new PiDiagnostics.StartFailure(new Exception("secret"))).contains("运行器"));assertFalse(PiDiagnostics.explain(new Exception("secret-api-key")).contains("secret"));}
}
