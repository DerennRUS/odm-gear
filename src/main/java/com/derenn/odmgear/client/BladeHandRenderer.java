package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** First-person wind-up: while LMB is held the blade is drawn back over the shoulder; release plays the vanilla swing. */
@Mod.EventBusSubscriber(modid = OdmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BladeHandRenderer {
    private BladeHandRenderer() {
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ItemStack stack = event.getItemStack();
        float w = TitanClient.windUp(event.getPartialTick());
        if (p == null || stack.isEmpty() || w <= 0f) {
            return;
        }
        event.setCanceled(true);
        boolean right = p.getMainArm() == HumanoidArm.RIGHT;
        float side = right ? 1f : -1f;
        float e = w * w * (3f - 2f * w); // smoothstep
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        // vanilla first-person item position
        ps.translate(side * 0.56f, -0.52f, -0.72f);
        // draw the blade back and up, out to the side
        ps.translate(side * 0.18f * e, 0.22f * e, 0.18f * e);
        ps.mulPose(Axis.YP.rotationDegrees(side * 35f * e));
        ps.mulPose(Axis.ZP.rotationDegrees(side * 25f * e));
        ps.mulPose(Axis.XP.rotationDegrees(55f * e));
        mc.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(p, stack,
                right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                !right, ps, event.getMultiBufferSource(), event.getPackedLight());
        ps.popPose();
    }
}
