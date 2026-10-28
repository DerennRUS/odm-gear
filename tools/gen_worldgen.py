"""Generates the "ODM Crags" world type data files. Edit PARAMS and re-run:  python3 tools/gen_worldgen.py"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data")

MIN_Y = -64
HEIGHT = 576            # world from -64 to 511
SEA_LEVEL = 40

PARAMS = {
    # ---- Ultra-Amplified-style layered terrain (all values in blocks) ----
    # ground: jagged amplified land at the bottom
    "ground_base": 70, "ground_amp": 35, "ground_rough": 20, "ground_spike": 45,
    # floating layers: centre height, centre wobble, thickness, how much of the layer is holes
    "layers": [
        {"y": 200, "wobble": 25, "thick": 22, "thick_amp": 18, "rough": 10, "seed": 1000},
        {"y": 295, "wobble": 25, "thick": 20, "thick_amp": 18, "rough": 10, "seed": 2000},
        {"y": 385, "wobble": 20, "thick": 18, "thick_amp": 18, "rough": 9, "seed": 3000},
    ],
    # columns connecting the layers
    "pillar_threshold": 0.7, "pillar_sharp": 300.0, "pillar_xz": 500.0, "pillar_top": 405,
    # 3D noise used for ragged edges/overhangs
    "base_xz_scale": 0.25, "base_y_scale": 0.25, "base_xz_factor": 80.0, "base_y_factor": 60.0,
    # snow line
    "snow_y": 400,
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
    import shutil
    shutil.rmtree(os.path.join(ROOT, "odmgear/worldgen/density_function/crags"), ignore_errors=True)

    def cache2d(f):
        return {"type": "minecraft:flat_cache", "argument": {"type": "minecraft:cache_2d", "argument": f}}

    def snoise(key, xz, y, shift):
        return {"type": "minecraft:shifted_noise", "noise": key, "xz_scale": xz, "y_scale": y,
                "shift_x": float(shift), "shift_y": 0.0, "shift_z": float(shift * 0.7)}

    Y = {"type": "minecraft:y_clamped_gradient", "from_y": MIN_Y, "to_y": 512, "from_value": float(MIN_Y), "to_value": 512.0}
    base3d = {"type": "minecraft:old_blended_noise", "xz_scale": p["base_xz_scale"], "y_scale": p["base_y_scale"],
              "xz_factor": p["base_xz_factor"], "y_factor": p["base_y_factor"], "smear_scale_multiplier": 8.0}
    w("odmgear/worldgen/density_function/crags/y.json", Y)
    w("odmgear/worldgen/density_function/crags/base_3d.json", base3d)
    y_ref = "odmgear:crags/y"
    # old_blended_noise is roughly +-0.13, so x7.5 gives about +-1 before the per-part amplitude
    rough = mul(7.5, "odmgear:crags/base_3d")

    # ground: height = base + amp * large noise + spikes ; density = height - y + roughness
    ground_h = cache2d(add(float(p["ground_base"]), add(mul(float(p["ground_amp"]), noise("minecraft:erosion", 1.5, 0.0)),
                                                 mul(float(p["ground_spike"]), {"type": "minecraft:max", "argument1": 0.0,
                                                            "argument2": add(noise("minecraft:ridge", 2.0, 0.0), -0.35)}))))
    w("odmgear/worldgen/density_function/crags/ground_height.json", ground_h)
    ground = add(add("odmgear:crags/ground_height", mul(-1.0, y_ref)), mul(float(p["ground_rough"]), rough))

    parts = [ground]
    for i, L in enumerate(p["layers"]):
        centre = cache2d(add(float(L["y"]), mul(float(L["wobble"]), snoise("minecraft:ridge", 1.2, 0.0, L["seed"]))))
        thick = cache2d(add(float(L["thick"]), mul(float(L["thick_amp"]), snoise("minecraft:surface", 1.0, 0.0, L["seed"] + 500))))
        w(f"odmgear/worldgen/density_function/crags/layer{i}_centre.json", centre)
        w(f"odmgear/worldgen/density_function/crags/layer{i}_thickness.json", thick)
        dist = {"type": "minecraft:abs", "argument": add(y_ref, mul(-1.0, f"odmgear:crags/layer{i}_centre"))}
        layer = add(add(f"odmgear:crags/layer{i}_thickness", mul(-1.0, dist)), mul(float(L["rough"]), rough))
        parts.append(layer)

    # pillars: where a high-frequency 2D noise peaks, a column rises from the ground up to pillar_top
    pillar2d = cache2d(mul(p["pillar_sharp"], add(noise("minecraft:jagged", p["pillar_xz"], 0.0), -p["pillar_threshold"])))
    w("odmgear/worldgen/density_function/crags/pillar.json", pillar2d)
    pillar = {"type": "minecraft:min", "argument1": add("odmgear:crags/pillar", mul(6.0, rough)),
              "argument2": add(float(p["pillar_top"]), mul(-1.0, y_ref))}
    parts.append(pillar)

    terrain = parts[0]
    for part in parts[1:]:
        terrain = {"type": "minecraft:max", "argument1": terrain, "argument2": part}
    # always solid near the bottom of the world
    floor = {"type": "minecraft:y_clamped_gradient", "from_y": MIN_Y, "to_y": MIN_Y + 24, "from_value": 10.0, "to_value": -10.0}
    terrain = {"type": "minecraft:max", "argument1": terrain, "argument2": floor}
    final_density = {"type": "minecraft:interpolated", "argument": mul(0.1, terrain)}
    smooth = mul(0.1, add("odmgear:crags/ground_height", mul(-1.0, y_ref)))

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
