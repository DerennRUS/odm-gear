package com.derenn.odmgear;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(OdmMod.MODID)
public class OdmMod {
    public static final String MODID = "odmgear";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final RegistryObject<Item> ODM_GEAR = ITEMS.register("odm_gear",
            () -> new Item(new Item.Properties().stacksTo(1)));
    /** Only used to render the flying hook head. */
    public static final RegistryObject<Item> HOOK_TIP = ITEMS.register("hook_tip",
            () -> new Item(new Item.Properties()));

    public OdmMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::addCreative);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, OdmConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(this::onFall);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ODM_GEAR);
        }
    }

    /** No fall damage while carrying the gear (AoTTG has none either). */
    private void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof Player player && hasGear(player)) {
            event.setCanceled(true);
        }
    }

    public static boolean hasGear(Player player) {
        return player.getInventory().contains(new ItemStack(ODM_GEAR.get()));
    }
}
