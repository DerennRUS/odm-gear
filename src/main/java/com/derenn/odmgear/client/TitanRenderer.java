package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmMod;
import com.derenn.odmgear.titan.TitanEntity;
import com.derenn.odmgear.titan.TitanModelData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Renders the AoTTG2 titan mesh with CPU skinning of the baked skeleton animation. */
public class TitanRenderer extends EntityRenderer<TitanEntity> {
    private static final ResourceLocation BODY1 = tex("body1");
    private static final ResourceLocation BODY2 = tex("body2");
    private static final ResourceLocation EYES = tex("eyes");
    private static final float BLEND_TICKS = 4f;

    private float[] pose;
    private float[] prevPose;
    private float[] skinned = new float[3 * 512];

    public TitanRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.5f;
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(OdmMod.MODID, "textures/entity/titan/" + name + ".png");
    }

    @Override
    public ResourceLocation getTextureLocation(TitanEntity entity) {
        return BODY1;
    }

    @Override
    public void render(TitanEntity e, float yaw, float partialTick, PoseStack ps, MultiBufferSource buffers, int light) {
        TitanModelData data = TitanModelData.get();
        if (pose == null) {
            pose = new float[data.boneCount * 12];
            prevPose = new float[data.boneCount * 12];
        }
        e.updateClientBlend(partialTick);
        int anim = e.anim();
        data.pose(anim, e.animFrame(partialTick), e.isLooping(anim), pose);
        if (e.clientPrevAnim >= 0) {
            float t = (e.tickCount - e.clientBlendStart + partialTick) / BLEND_TICKS;
            if (t < 1f) {
                float prevFrame = e.clientPrevAnimFrame + (e.tickCount - e.clientBlendStart + partialTick);
                data.pose(e.clientPrevAnim, prevFrame, e.isLooping(e.clientPrevAnim), prevPose);
                TitanModelData.blend(prevPose, pose, t, pose);
            }
        }

        float scale = e.heightBlocks() / data.height;
        float bodyYaw = net.minecraft.util.Mth.rotLerp(partialTick, e.yBodyRotO, e.yBodyRot);
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        ps.scale(scale, scale, scale);
        int overlay = LivingEntityRenderer.getOverlayCoords(e, 0f);

        int body = e.body();
        int head = e.head();
        ResourceLocation skin = body == 4 ? BODY2 : BODY1;
        float g = e.gray();
        int[] hc = e.hairColor();
        String hair = e.hair();
        String hairTex = hair.equals("eren") ? "boy1" : hair;
        int eye = e.eye();
        float eyeU = 0.25f * (eye / 8);
        float eyeV = -0.125f * (eye % 8);

        drawMesh(data, data.meshes.get("body_" + body), ps, buffers.getBuffer(RenderType.entityCutoutNoCull(skin)), g, g, g, light, overlay, 0, 0);
        drawMesh(data, data.meshes.get("head_" + head), ps, buffers.getBuffer(RenderType.entityCutoutNoCull(skin)), g, g, g, light, overlay, 0, 0);
        drawMesh(data, data.meshes.get("eyes_" + head), ps, buffers.getBuffer(RenderType.entityCutoutNoCull(EYES)), 1, 1, 1, light, overlay, eyeU, eyeV);
        drawMesh(data, data.meshes.get("hair_" + hair + "_0@head_" + head), ps, buffers.getBuffer(RenderType.entityCutoutNoCull(tex("hair_" + hairTex))),
                hc[0] / 255f, hc[1] / 255f, hc[2] / 255f, light, overlay, 0, 0);
        ps.popPose();
        super.render(e, yaw, partialTick, ps, buffers, light);
    }

    private void drawMesh(TitanModelData data, TitanModelData.Mesh mesh, PoseStack ps, VertexConsumer vc,
                          float r, float g, float b, int light, int overlay, float uOff, float vOff) {
        if (mesh == null) {
            return;
        }
        int n = mesh.vertexCount();
        if (skinned.length < n * 3) {
            skinned = new float[n * 3];
        }
        data.skin(mesh, pose, skinned);
        Matrix4f m = ps.last().pose();
        Matrix3f nm = ps.last().normal();
        Vector4f p = new Vector4f();
        Vector3f nrm = new Vector3f();
        int[] tv = mesh.triVerts;
        float[] uv = mesh.triUv;
        for (int t = 0; t + 2 < tv.length; t += 3) {
            int a = tv[t] * 3, bb = tv[t + 1] * 3, c = tv[t + 2] * 3;
            float ax = skinned[a], ay = skinned[a + 1], az = skinned[a + 2];
            float bx = skinned[bb], by = skinned[bb + 1], bz = skinned[bb + 2];
            float cx = skinned[c], cy = skinned[c + 1], cz = skinned[c + 2];
            float ux = bx - ax, uy = by - ay, uz = bz - az;
            float vx = cx - ax, vy = cy - ay, vz = cz - az;
            nrm.set(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
            if (nrm.lengthSquared() < 1.0E-12f) {
                continue;
            }
            nrm.normalize();
            nm.transform(nrm);
            for (int k = 0; k < 4; k++) {
                int corner = Math.min(k, 2);
                int vi = tv[t + corner] * 3;
                p.set(skinned[vi], skinned[vi + 1], skinned[vi + 2], 1f);
                m.transform(p);
                float u = uv[(t + corner) * 2] + uOff;
                float v = 1f - (uv[(t + corner) * 2 + 1] + vOff);
                vc.vertex(p.x(), p.y(), p.z(), r, g, b, 1f, u, v, overlay, light, nrm.x(), nrm.y(), nrm.z());
            }
        }
    }
}
