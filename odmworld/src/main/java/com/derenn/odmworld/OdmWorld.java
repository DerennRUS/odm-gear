package com.derenn.odmworld;

import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** World type "Giant Forest": one biome, gentle fields, giant trees 45-75 blocks apart. */
@Mod(OdmWorld.MODID)
public class OdmWorld {
    public static final String MODID = "odmworld";

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, MODID);
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> GIANT_TREES = FEATURES.register("giant_trees", GiantTreesFeature::new);

    public OdmWorld() {
        FEATURES.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
