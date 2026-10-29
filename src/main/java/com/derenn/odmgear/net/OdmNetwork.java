package com.derenn.odmgear.net;

import com.derenn.odmgear.OdmMod;
import com.derenn.odmgear.titan.TitanEntity;
import com.derenn.odmgear.titan.TitanGeometry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public final class OdmNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(OdmMod.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private OdmNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(TitanHit.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TitanHit::encode)
                .decoder(TitanHit::decode)
                .consumerMainThread(TitanHit::handle)
                .add();
    }

    /** Client -> server: the player's blade hit a titan hurtbox while flying at speedUnits (AoTTG units/s). */
    public record TitanHit(int entityId, int part, float speedUnits) {
        static void encode(TitanHit m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeByte(m.part);
            buf.writeFloat(m.speedUnits);
        }

        static TitanHit decode(FriendlyByteBuf buf) {
            return new TitanHit(buf.readVarInt(), buf.readByte(), buf.readFloat());
        }

        static void handle(TitanHit m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p == null || m.part < 0 || m.part >= TitanGeometry.Part.values().length) {
                return;
            }
            Entity e = p.level().getEntity(m.entityId);
            if (e instanceof TitanEntity titan && p.distanceTo(titan) < titan.heightBlocks() * 1.5 + 12) {
                float speed = Math.max(0f, Math.min(m.speedUnits, 200f));
                titan.onBladeHit(p, TitanGeometry.Part.values()[m.part], speed);
            }
        }
    }
}
