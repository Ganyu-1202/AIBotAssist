package top.goldgom.aibotassist;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class Wire {
    private Wire() {}
    public record Up(String json) implements CustomPacketPayload {
        public static final Type<Up> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("aibotassist", "up"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Up> CODEC = new StreamCodec<>() {
            public Up decode(RegistryFriendlyByteBuf b) { return new Up(b.readUtf(100_000)); }
            public void encode(RegistryFriendlyByteBuf b, Up p) { b.writeUtf(p.json, 100_000); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Down(String json) implements CustomPacketPayload {
        public static final Type<Down> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("aibotassist", "down"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Down> CODEC = new StreamCodec<>() {
            public Down decode(RegistryFriendlyByteBuf b) { return new Down(b.readUtf(32_000)); }
            public void encode(RegistryFriendlyByteBuf b, Down p) { b.writeUtf(p.json, 32_000); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
