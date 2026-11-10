package top.goldgom.aibotassist.neoforge;
@net.neoforged.fml.common.Mod("aibotassist")
public final class NeoForgeMod {
 public NeoForgeMod() {
  top.goldgom.aibotassist.AIBotAssist.init();
  if (dev.architectury.platform.Platform.getEnvironment() == dev.architectury.utils.Env.CLIENT) ClientInit.init();
 }
 private static final class ClientInit { static void init() { top.goldgom.aibotassist.client.ClientMod.init(); } }
}
