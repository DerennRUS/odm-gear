package com.derenn.odmgear.titan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Titan model exported from AoTTG2 (tools/titan/export_titan.py): skeleton, skinned meshes and baked
 * animations (20 frames per second = one frame per Minecraft tick). Coordinates are in model units,
 * Minecraft axes, titan facing +Z, feet at y = 0.
 */
public final class TitanModelData {
    private static TitanModelData instance;

    public final int boneCount;
    public final String[] boneNames;
    public final float[][] boneHead;
    public final float[][] boneTail;
    public final Map<String, Integer> boneIndex = new HashMap<>();
    public final String[] animNames;
    public final int[] animFrames;
    public final float[][] animData;
    public final Map<String, Integer> animIndex = new HashMap<>();
    public final Map<String, Mesh> meshes = new HashMap<>();
    /** Forearm + hand bones of each arm (they disappear while the arm is cut off). */
    public final int[] armBonesL;
    public final int[] armBonesR;
    private final boolean[] isArmL;
    private final boolean[] isArmR;
    /** Top of the head in model units (titan height). */
    public final float height;

    public static final class Mesh {
        public final String name;
        public final float[] pos;
        public final int[][] bones;
        public final float[][] weights;
        public final int[] triVerts;
        public final float[] triUv;

        Mesh(String name, float[] pos, int[][] bones, float[][] weights, int[] triVerts, float[] triUv) {
            this.name = name;
            this.pos = pos;
            this.bones = bones;
            this.weights = weights;
            this.triVerts = triVerts;
            this.triUv = triUv;
        }

        public int vertexCount() {
            return pos.length / 3;
        }
    }

    public static synchronized TitanModelData get() {
        if (instance == null) {
            try {
                instance = new TitanModelData();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load titan model", e);
            }
        }
        return instance;
    }

    private TitanModelData() throws Exception {
        JsonObject root;
        try (InputStream in = TitanModelData.class.getResourceAsStream("/odmgear_titan/titan_model.json")) {
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonArray bones = root.getAsJsonArray("bones");
        boneCount = bones.size();
        boneNames = new String[boneCount];
        boneHead = new float[boneCount][];
        boneTail = new float[boneCount][];
        for (int i = 0; i < boneCount; i++) {
            JsonObject b = bones.get(i).getAsJsonObject();
            boneNames[i] = b.get("name").getAsString();
            boneHead[i] = vec(b.getAsJsonArray("head"));
            boneTail[i] = vec(b.getAsJsonArray("tail"));
            boneIndex.put(boneNames[i], i);
        }
        isArmL = new boolean[boneCount];
        isArmR = new boolean[boneCount];
        java.util.List<Integer> al = new java.util.ArrayList<>(), ar = new java.util.ArrayList<>();
        for (int i = 0; i < boneCount; i++) {
            String n = boneNames[i];
            if (n.startsWith("forearm.L") || n.startsWith("hand.L")) {
                isArmL[i] = true;
                al.add(i);
            }
            if (n.startsWith("forearm.R") || n.startsWith("hand.R")) {
                isArmR[i] = true;
                ar.add(i);
            }
        }
        armBonesL = al.stream().mapToInt(Integer::intValue).toArray();
        armBonesR = ar.stream().mapToInt(Integer::intValue).toArray();
        float top = 0;
        for (JsonElement me : root.getAsJsonArray("meshes")) {
            JsonObject m = me.getAsJsonObject();
            String name = m.get("name").getAsString();
            JsonArray pos = m.getAsJsonArray("pos");
            float[] p = new float[pos.size() * 3];
            for (int i = 0; i < pos.size(); i++) {
                JsonArray v = pos.get(i).getAsJsonArray();
                p[i * 3] = v.get(0).getAsFloat();
                p[i * 3 + 1] = v.get(1).getAsFloat();
                p[i * 3 + 2] = v.get(2).getAsFloat();
                if (name.startsWith("head_") || name.startsWith("body_")) {
                    top = Math.max(top, p[i * 3 + 1]);
                }
            }
            JsonArray ws = m.getAsJsonArray("weights");
            int[][] bi = new int[ws.size()][];
            float[][] bw = new float[ws.size()][];
            for (int i = 0; i < ws.size(); i++) {
                JsonArray list = ws.get(i).getAsJsonArray();
                bi[i] = new int[list.size()];
                bw[i] = new float[list.size()];
                for (int k = 0; k < list.size(); k++) {
                    JsonArray pair = list.get(k).getAsJsonArray();
                    bi[i][k] = pair.get(0).getAsInt();
                    bw[i][k] = pair.get(1).getAsFloat();
                }
            }
            JsonArray tris = m.getAsJsonArray("tris");
            int[] tv = new int[tris.size()];
            float[] tuv = new float[tris.size() * 2];
            for (int i = 0; i < tris.size(); i++) {
                JsonArray t = tris.get(i).getAsJsonArray();
                tv[i] = t.get(0).getAsInt();
                tuv[i * 2] = t.get(1).getAsFloat();
                tuv[i * 2 + 1] = t.get(2).getAsFloat();
            }
            meshes.put(name, new Mesh(name, p, bi, bw, tv, tuv));
        }
        height = top;

        JsonArray anims = root.getAsJsonArray("anims");
        animNames = new String[anims.size()];
        animFrames = new int[anims.size()];
        animData = new float[anims.size()][];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                TitanModelData.class.getResourceAsStream("/odmgear_titan/titan_anims.bin"), 1 << 16))) {
            for (int a = 0; a < anims.size(); a++) {
                JsonObject an = anims.get(a).getAsJsonObject();
                animNames[a] = an.get("name").getAsString();
                animFrames[a] = an.get("frames").getAsInt();
                animIndex.put(animNames[a], a);
                float[] data = new float[animFrames[a] * boneCount * 12];
                for (int i = 0; i < data.length; i++) {
                    data[i] = in.readFloat();
                }
                animData[a] = data;
            }
        }
    }

    private static float[] vec(JsonArray a) {
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    public int anim(String name) {
        Integer i = animIndex.get(name);
        return i == null ? 0 : i;
    }

    /** Length of an animation in ticks (frames). */
    public int length(int anim) {
        return animFrames[anim];
    }

    /**
     * Skin matrices (3x4 row-major per bone) at a fractional frame, optionally cross-faded from another pose.
     */
    public void pose(int anim, float frame, boolean loop, float[] out) {
        int n = animFrames[anim];
        float f = frame;
        if (loop) {
            f = n <= 1 ? 0 : (f % n + n) % n;
        } else {
            f = Math.max(0, Math.min(n - 1, f));
        }
        int f0 = (int) Math.floor(f);
        int f1 = loop ? (f0 + 1) % n : Math.min(f0 + 1, n - 1);
        float t = f - f0;
        float[] d = animData[anim];
        int stride = boneCount * 12;
        int o0 = f0 * stride;
        int o1 = f1 * stride;
        for (int i = 0; i < stride; i++) {
            out[i] = d[o0 + i] + (d[o1 + i] - d[o0 + i]) * t;
        }
    }

    public boolean isArmBone(int bone, boolean left) {
        return bone >= 0 && bone < boneCount && (left ? isArmL[bone] : isArmR[bone]);
    }

    /** Shrinks a cut-off arm to 1% around the elbow (AoTTG2 scales the forearm to 0.01). */
    public void collapseArm(float[] pose, boolean left) {
        int fore = boneIndex.get(left ? "forearm.L" : "forearm.R");
        float[] h = boneHead[fore];
        float[] p = new float[3];
        transform(pose, fore, h[0], h[1], h[2], p);
        for (int b : left ? armBonesL : armBonesR) {
            int o = b * 12;
            for (int r = 0; r < 3; r++) {
                pose[o + r * 4] *= 0.01f;
                pose[o + r * 4 + 1] *= 0.01f;
                pose[o + r * 4 + 2] *= 0.01f;
                pose[o + r * 4 + 3] = 0.99f * p[r] + 0.01f * pose[o + r * 4 + 3];
            }
        }
    }

    public static void blend(float[] a, float[] b, float t, float[] out) {
        for (int i = 0; i < out.length; i++) {
            out[i] = a[i] + (b[i] - a[i]) * t;
        }
    }

    /** Transforms a rest-pose point by bone matrix b. */
    public static void transform(float[] m, int b, float x, float y, float z, float[] out) {
        int o = b * 12;
        out[0] = m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 3];
        out[1] = m[o + 4] * x + m[o + 5] * y + m[o + 6] * z + m[o + 7];
        out[2] = m[o + 8] * x + m[o + 9] * y + m[o + 10] * z + m[o + 11];
    }

    /** Skins all vertices of a mesh into out (xyz per vertex). */
    public void skin(Mesh mesh, float[] pose, float[] out) {
        float[] tmp = new float[3];
        int n = mesh.vertexCount();
        for (int v = 0; v < n; v++) {
            float x = mesh.pos[v * 3], y = mesh.pos[v * 3 + 1], z = mesh.pos[v * 3 + 2];
            float ax = 0, ay = 0, az = 0;
            int[] bs = mesh.bones[v];
            float[] ws = mesh.weights[v];
            for (int k = 0; k < bs.length; k++) {
                transform(pose, bs[k], x, y, z, tmp);
                ax += tmp[0] * ws[k];
                ay += tmp[1] * ws[k];
                az += tmp[2] * ws[k];
            }
            out[v * 3] = ax;
            out[v * 3 + 1] = ay;
            out[v * 3 + 2] = az;
        }
    }

    /** Position (model units) of a bone's head or tail in the given pose. */
    public void bonePoint(float[] pose, int bone, boolean tail, float[] out) {
        float[] p = tail ? boneTail[bone] : boneHead[bone];
        transform(pose, bone, p[0], p[1], p[2], out);
    }
}
