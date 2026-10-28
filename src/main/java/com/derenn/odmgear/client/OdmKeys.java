package com.derenn.odmgear.client;

import com.derenn.odmgear.OdmMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Default binds: Q/E hooks, LShift gas, LCtrl dash, RMB reel in, LAlt reel out (wheel also reels). */
@Mod.EventBusSubscriber(modid = OdmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OdmKeys {
    public static final String CATEGORY = "key.categories.odmgear";

    public static final KeyMapping HOOK_LEFT = key("hook_left", GLFW.GLFW_KEY_Q);
    public static final KeyMapping HOOK_RIGHT = key("hook_right", GLFW.GLFW_KEY_E);
    public static final KeyMapping HOOK_BOTH = key("hook_both", -1);
    public static final KeyMapping GAS = key("gas", GLFW.GLFW_KEY_LEFT_SHIFT);
    public static final KeyMapping DASH = key("dash", GLFW.GLFW_KEY_LEFT_CONTROL);
    public static final KeyMapping REEL_IN = mouse("reel_in", GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    public static final KeyMapping REEL_OUT = key("reel_out", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping TOGGLE = key("toggle", GLFW.GLFW_KEY_G);

    public static final KeyMapping[] ACTION_KEYS = {HOOK_LEFT, HOOK_RIGHT, HOOK_BOTH, GAS, DASH, REEL_IN, REEL_OUT};

    private OdmKeys() {
    }

    private static KeyMapping key(String name, int glfwKey) {
        InputConstants.Key k = glfwKey < 0 ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(glfwKey);
        return new KeyMapping("key.odmgear." + name, KeyConflictContext.IN_GAME, k, CATEGORY);
    }

    private static KeyMapping mouse(String name, int button) {
        return new KeyMapping("key.odmgear." + name, KeyConflictContext.IN_GAME, InputConstants.Type.MOUSE.getOrCreate(button), CATEGORY);
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(HOOK_LEFT);
        event.register(HOOK_RIGHT);
        event.register(HOOK_BOTH);
        event.register(GAS);
        event.register(DASH);
        event.register(REEL_IN);
        event.register(REEL_OUT);
        event.register(TOGGLE);
    }

    /** Raw physical state of the bound key, independent of Forge key-conflict handling. */
    public static boolean held(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.equals(InputConstants.UNKNOWN) || key.getValue() < 0) {
            return false;
        }
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(window, key.getValue());
        }
        return mapping.isDown();
    }

    public static boolean sameKey(KeyMapping a, KeyMapping b) {
        return !a.getKey().equals(InputConstants.UNKNOWN) && a.getKey().equals(b.getKey());
    }
}
