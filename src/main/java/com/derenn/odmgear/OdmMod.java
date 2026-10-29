package com.derenn.odmgear;

import com.derenn.odmgear.titan.TitanEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
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

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    public static final RegistryObject<EntityType<TitanEntity>> TITAN = ENTITIES.register("titan",
            () -> EntityType.Builder.<TitanEntity>of(TitanEntity::new, MobCategory.MONSTER)
                    .sized(3f, 10f).fireImmune().clientTrackingRange(20).updateInterval(1).build("titan"));
    public static final RegistryObject<Item> TITAN_SPAWN_EGG = ITEMS.register("titan_spawn_egg",
            () -> new ForgeSpawnEggItem(TITAN, 0xD9A27E, 0x6B3A2C, new Item.Properties()));

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, MODID);
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> REMOVE_DEBRIS = FEATURES.register("remove_debris",
            DebrisCleanupFeature::new);

    public OdmMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        FEATURES.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener(this::entityAttributes);
        modBus.addListener(this::addCreative);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, OdmConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(this::onFall);
        MinecraftForge.EVENT_BUS.addListener(com.derenn.odmgear.titan.TitanSpawner::onLevelTick);
        com.derenn.odmgear.net.OdmNetwork.register();
    }

    private void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(TITAN.get(), TitanEntity.createAttributes().build());
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ODM_GEAR);
        }
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(TITAN_SPAWN_EGG);
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
