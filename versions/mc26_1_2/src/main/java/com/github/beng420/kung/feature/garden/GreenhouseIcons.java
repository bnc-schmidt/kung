package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.KungMod;
import com.google.common.collect.ImmutableMultimap;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * The in-game model that stands for each crop: a player head with a texture from textures.minecraft.net, or a
 * vanilla item. Stacks are built once and reused; building one in a render path would allocate every frame.
 *
 * <p>The table is plain data ({@link #table()}, no game classes); {@link #stack(String)} and {@link #warm()}
 * must only run on the client thread.
 */
final class GreenhouseIcons {
    static final String RESOURCE = "kung-greenhouse-icons.json";
    private static final int MAX_BYTES = 100_000;
    private static final Gson JSON = new Gson();
    /** Mojang's texture ids are 64 hex digits, written without leading zeros (63 for two of ours). */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{60,64}");
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * {@code head} is a texture hash or null. {@code item} is a vanilla item id: the icon itself when there is no
     * head, else the stand-in used if the head cannot be built. At least one is set.
     */
    record Icon(String head, String item) { }

    private record Data(int schema, Map<String, Icon> icons) { }

    private static final class Holder {
        static final Map<String, Icon> TABLE = load();
    }

    /** Client thread only. EMPTY marks a crop with no usable icon so it is not rebuilt every frame. */
    private static final Map<String, ItemStack> STACKS = new HashMap<>();
    private static final List<ResolvableProfile> HEADS = new ArrayList<>();
    private static boolean built;

    private GreenhouseIcons() { }

    /** Crop name to icon, in file order; empty when the resource is unusable. */
    static Map<String, Icon> table() { return Holder.TABLE; }

    /** Validates strictly so a bad edit of the resource fails a test instead of drawing the wrong model. */
    static Map<String, Icon> parse(String text) {
        Data data = JSON.fromJson(text, Data.class);
        if (data == null || data.schema() != 1 || data.icons() == null) {
            throw new IllegalArgumentException("Unknown icon data schema");
        }
        Map<String, Icon> out = new LinkedHashMap<>();
        for (Map.Entry<String, Icon> entry : data.icons().entrySet()) {
            String name = entry.getKey();
            Icon icon = entry.getValue();
            if (!GreenhouseCrops.NAMES.contains(name)) throw new IllegalArgumentException("Not a crop: " + name);
            if (icon == null || (icon.head() == null && icon.item() == null)) {
                throw new IllegalArgumentException("No icon for " + name);
            }
            if (icon.head() != null && !HASH.matcher(icon.head()).matches()) {
                throw new IllegalArgumentException("Bad texture hash for " + name);
            }
            if (icon.item() != null && !ITEM_ID.matcher(icon.item()).matches()) {
                throw new IllegalArgumentException("Bad item id for " + name);
            }
            out.put(name, icon);
        }
        return Map.copyOf(out);
    }

    private static Map<String, Icon> load() {
        try (InputStream stream = GreenhouseIcons.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) return Map.of();
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return Map.of();
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            return Map.of();
        }
    }

    /**
     * The cached stack for a crop, or {@link ItemStack#EMPTY} when none could be built; callers then draw a
     * coloured abbreviation. Never null.
     */
    static ItemStack stack(String crop) {
        ItemStack stack = STACKS.get(crop);
        if (stack == null) {
            stack = build(GreenhouseCrops.canonical(crop));
            STACKS.put(crop, stack);
        }
        return stack;
    }

    /**
     * Builds every stack and starts every head's skin download. A head draws the default skin until its texture
     * has arrived, so call this when a screen opens; it is cheap to repeat, and repeating keeps the entries in
     * the render cache, which drops what has not been drawn for five minutes.
     */
    static void warm() {
        if (!built) {
            built = true;
            for (String crop : table().keySet()) stack(crop);
        }
        var cache = Minecraft.getInstance().playerSkinRenderCache();
        for (ResolvableProfile profile : HEADS) cache.lookup(profile);
    }

    private static ItemStack build(String crop) {
        Icon icon = table().get(crop);
        if (icon == null) return ItemStack.EMPTY;
        try {
            if (icon.head() != null) return head(icon.head());
        } catch (RuntimeException exception) {
            KungMod.LOGGER.warn("Could not build the Greenhouse head for {}", crop, exception);
        }
        return icon.item() == null ? ItemStack.EMPTY : item(icon.item());
    }

    private static ItemStack item(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return ItemStack.EMPTY;
        return BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** A head whose skin is the texture with this hash; the profile id only has to be stable. */
    private static ItemStack head(String hash) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/" + hash + "\"}}}";
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", encoded)));
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)), "kung",
            properties);
        ResolvableProfile resolved = ResolvableProfile.createResolved(profile);
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, resolved);
        HEADS.add(resolved);
        return stack;
    }
}
