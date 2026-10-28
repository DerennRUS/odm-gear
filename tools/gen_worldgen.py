"""Generates the "ODM Crags" world type data files. Edit PARAMS and re-run:  python3 tools/gen_worldgen.py"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data")

MIN_Y = -64
HEIGHT = 576            # world from -64 to 511
SEA_LEVEL = 40

PARAMS = {
    # vertical gradient: solid below, air above; zero crossing ~ (ZERO_Y)
    "grad_top_y": 448, "grad_top": -2.5,
    "grad_bottom": 2.5,
    # large regional height shifts (2D)
    "region_amp": 1.0, "region_scale": 0.6,
    # tall walls / towers: 3D noise stretched vertically
    "tower_amp": 2.2, "tower_xz_scale": 2.5, "tower_y_scale": 0.35,
    # everything fades to air above this band (no flat cut at the world ceiling)
    "ceiling_from_y": 360, "ceiling_to_y": 460, "ceiling_drop": -6.0,
    # classic 1.12-style 3D terrain noise: jagged cliffs and overhangs
    "base_amp": 4.0, "base_xz_scale": 0.25, "base_y_scale": 0.25, "base_xz_factor": 60.0, "base_y_factor": 45.0,
    # big blobby 3D structures (arches, floating ledges, overhanging walls)
    "blob_amp": 1.2, "blob_xz_scale": 2.0, "blob_y_scale": 3.0,
    # needle-like spires (2D high-frequency noise above a threshold)
    "spire_amp": 4.0, "spire_threshold": 0.5, "spire_xz_scale": 1500.0,
    # snow line
    "snow_y": 300,
}


def w(path, obj):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def add(a, b):
    return {"type": "minecraft:add", "argument1": a, "argument2": b}


def mul(a, b):
    return {"type": "minecraft:mul", "argument1": a, "argument2": b}


def noise(key, xz, y):
    return {"type": "minecraft:noise", "noise": key, "xz_scale": xz, "y_scale": y}


def main():
    p = PARAMS
    gradient = {"type": "minecraft:y_clamped_gradient", "from_y": MIN_Y, "to_y": p["grad_top_y"],
                "from_value": p["grad_bottom"], "to_value": p["grad_top"]}
    region = {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument":
              mul(p["region_amp"], noise("minecraft:erosion", p["region_scale"], 0.0))}}
    base3d = {"type": "minecraft:old_blended_noise", "xz_scale": p["base_xz_scale"], "y_scale": p["base_y_scale"],
              "xz_factor": p["base_xz_factor"], "y_factor": p["base_y_factor"], "smear_scale_multiplier": 8.0}
    blob = noise("minecraft:cave_cheese", p["blob_xz_scale"], p["blob_y_scale"])
    tower = noise("minecraft:cave_cheese", p["tower_xz_scale"], p["tower_y_scale"])
    spire = {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument":
             mul(p["spire_amp"], {"type": "minecraft:max", "argument1": 0.0,
                                  "argument2": add(noise("minecraft:jagged", p["spire_xz_scale"], 0.0), -p["spire_threshold"])})}}

    w("odmgear/worldgen/density_function/crags/gradient.json", gradient)
    w("odmgear/worldgen/density_function/crags/region.json", region)
    w("odmgear/worldgen/density_function/crags/spire.json", spire)
    w("odmgear/worldgen/density_function/crags/base_3d.json", base3d)

    smooth = add("odmgear:crags/gradient", add("odmgear:crags/region", "odmgear:crags/spire"))
    terrain = add(smooth, add(mul(p["base_amp"], "odmgear:crags/base_3d"),
                              add(mul(p["blob_amp"], blob), mul(p["tower_amp"], tower))))
    ceiling = {"type": "minecraft:y_clamped_gradient", "from_y": p["ceiling_from_y"], "to_y": p["ceiling_to_y"],
               "from_value": 0.0, "to_value": p["ceiling_drop"]}
    terrain = add(terrain, ceiling)
    # always solid near the bottom of the world
    floor = {"type": "minecraft:y_clamped_gradient", "from_y": MIN_Y, "to_y": MIN_Y + 24, "from_value": 10.0, "to_value": -10.0}
    terrain = {"type": "minecraft:max", "argument1": terrain, "argument2": floor}
    final_density = {"type": "minecraft:squeeze", "argument": mul(0.64, {"type": "minecraft:interpolated", "argument": terrain})}

    shift_x = "minecraft:shift_x"
    shift_z = "minecraft:shift_z"
    router = {
        "barrier": 0.0,
        "fluid_level_floodedness": 0.0,
        "fluid_level_spread": 0.0,
        "lava": 0.0,
        "temperature": {"type": "minecraft:shifted_noise", "noise": "minecraft:temperature", "xz_scale": 0.25, "y_scale": 0.0,
                        "shift_x": shift_x, "shift_y": 0.0, "shift_z": shift_z},
        "vegetation": {"type": "minecraft:shifted_noise", "noise": "minecraft:vegetation", "xz_scale": 0.25, "y_scale": 0.0,
                       "shift_x": shift_x, "shift_y": 0.0, "shift_z": shift_z},
        "continents": "minecraft:overworld/continents",
        "erosion": "minecraft:overworld/erosion",
        # depth only decides cave biomes: keep them well underground
        "depth": {"type": "minecraft:y_clamped_gradient", "from_y": MIN_Y, "to_y": 64, "from_value": 1.5, "to_value": -1.5},
        "ridges": "minecraft:overworld/ridges",
        "initial_density_without_jaggedness": smooth,
        "final_density": final_density,
        "vein_toggle": 0.0,
        "vein_ridged": 0.0,
        "vein_gap": 0.0,
    }

    def block(name, props=None):
        b = {"Name": name}
        if props:
            b["Properties"] = props
        return b

    def result(b):
        return {"type": "minecraft:block", "result_state": b}

    def cond(c, then):
        return {"type": "minecraft:condition", "if_true": c, "then_run": then}

    floor_cond = {"type": "minecraft:stone_depth", "offset": 0, "add_surface_depth": False, "secondary_depth_range": 0, "surface_type": "floor"}
    under_floor = {"type": "minecraft:stone_depth", "offset": 0, "add_surface_depth": True, "secondary_depth_range": 0, "surface_type": "floor"}
    above_snow = {"type": "minecraft:y_above", "anchor": {"absolute": p["snow_y"]}, "surface_depth_multiplier": 0, "add_stone_depth": False}
    not_water = {"type": "minecraft:water", "offset": -1, "surface_depth_multiplier": 0, "add_stone_depth": False}
    steep = {"type": "minecraft:steep"}

    surface = {"type": "minecraft:sequence", "sequence": [
        cond({"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor",
              "true_at_and_below": {"above_bottom": 0}, "false_at_and_above": {"above_bottom": 5}},
             result(block("minecraft:bedrock"))),
        cond(floor_cond, {"type": "minecraft:sequence", "sequence": [
            cond(above_snow, result(block("minecraft:snow_block"))),
            cond(steep, result(block("minecraft:stone"))),
            cond(not_water, result(block("minecraft:grass_block", {"snowy": "false"}))),
            result(block("minecraft:gravel")),
        ]}),
        cond(under_floor, {"type": "minecraft:sequence", "sequence": [
            cond(above_snow, result(block("minecraft:stone"))),
            cond(steep, result(block("minecraft:stone"))),
            result(block("minecraft:dirt")),
        ]}),
        cond({"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate",
              "true_at_and_below": {"absolute": 0}, "false_at_and_above": {"absolute": 8}},
             result(block("minecraft:deepslate", {"axis": "y"}))),
    ]}

    spawn_target = [
        {"temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0], "continentalness": [-0.11, 1.0], "erosion": [-1.0, 1.0],
         "depth": 0.0, "weirdness": [-1.0, -0.16], "offset": 0.0},
        {"temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0], "continentalness": [-0.11, 1.0], "erosion": [-1.0, 1.0],
         "depth": 0.0, "weirdness": [0.16, 1.0], "offset": 0.0},
    ]

    w("odmgear/worldgen/noise_settings/crags.json", {
        "sea_level": SEA_LEVEL,
        "disable_mob_generation": False,
        "aquifers_enabled": False,
        "ore_veins_enabled": False,
        "legacy_random_source": False,
        "default_block": block("minecraft:stone"),
        "default_fluid": block("minecraft:water", {"level": "0"}),
        "noise": {"min_y": MIN_Y, "height": HEIGHT, "size_horizontal": 1, "size_vertical": 2},
        "noise_router": router,
        "spawn_target": spawn_target,
        "surface_rule": surface,
    })

    w("odmgear/dimension_type/crags.json", {
        "ultrawarm": False, "natural": True, "coordinate_scale": 1.0, "has_skylight": True, "has_ceiling": False,
        "ambient_light": 0.0, "monster_spawn_light_level": {"type": "minecraft:uniform", "value": {"min_inclusive": 0, "max_inclusive": 7}},
        "monster_spawn_block_light_limit": 0, "piglin_safe": False, "bed_works": True, "respawn_anchor_works": False,
        "has_raids": True, "min_y": MIN_Y, "height": HEIGHT, "logical_height": HEIGHT,
        "infiniburn": "#minecraft:infiniburn_overworld", "effects": "minecraft:overworld",
    })

    w("odmgear/worldgen/world_preset/crags.json", {"dimensions": {
        "minecraft:overworld": {"type": "odmgear:crags", "generator": {
            "type": "minecraft:noise", "settings": "odmgear:crags",
            "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:overworld"}}},
        "minecraft:the_nether": {"type": "minecraft:the_nether", "generator": {
            "type": "minecraft:noise", "settings": "minecraft:nether",
            "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}}},
        "minecraft:the_end": {"type": "minecraft:the_end", "generator": {
            "type": "minecraft:noise", "settings": "minecraft:end",
            "biome_source": {"type": "minecraft:the_end"}}},
    }})

    w("minecraft/tags/worldgen/world_preset/normal.json", {"replace": False, "values": ["odmgear:crags"]})


if __name__ == "__main__":
    main()
