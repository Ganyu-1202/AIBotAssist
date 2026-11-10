package top.goldgom.aibotassist.fabric;
public final class FabricClient implements net.fabricmc.api.ClientModInitializer {
 public void onInitializeClient() { top.goldgom.aibotassist.client.ClientMod.init(); }
}
