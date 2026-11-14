package top.goldgom.aibotassist;

import com.google.gson.*;
import dev.architectury.event.events.common.BlockEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.*;

/** Server-thread-only, player-local resource tools. Never serializes components, NBT or containers.
 * Goals are desired final holdings (not additional output); existing plain items satisfy them.
 * Only ordinary shaped/shapeless crafting is executed, including in creative mode: no free items.
 */
public final class ResourceTools {
    private ResourceTools() {}
    static final int MAX_GOALS = 16, MAX_COUNT = 4096, MAX_TOTAL = 8192;
    private static final int MAX_RECIPES = 32, RECIPE_SCAN = 4096, MAX_OPTIONS = 16;

    public static JsonObject handle(ServerPlayer p, String tool, JsonObject args) {
        if (p == null || p.server == null || !p.server.isSameThread()) return error("server_thread_required");
        if (args == null) args = new JsonObject();
        try {
            return switch (tool == null ? "" : tool) {
                case "inspect_inventory" -> inspect(p);
                case "query_recipes" -> recipes(p, args);
                case "plan_resources" -> craft(p, args, false);
                case "craft_items" -> craft(p, args, true);
                case "find_workstations" -> stations(p, args);
                default -> error("unknown_tool");
            };
        } catch (IllegalArgumentException e) {
            // Only fixed public validation text; recipe/mod exceptions may contain private data.
            return error("invalid_arguments_or_unavailable_resources");
        } catch (RuntimeException e) {
            return error("operation_failed_reobserve_before_retry");
        }
    }
    private static JsonObject ok() {
        JsonObject o = new JsonObject(); o.addProperty("ok", true); return o;
    }
    private static JsonObject error(String code) {
        JsonObject o = new JsonObject(); o.addProperty("ok", false); o.addProperty("error", code); return o;
    }
    static int integer(JsonObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        JsonElement v = args.get(key);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        try {
            int n = v.getAsBigDecimal().intValueExact();
            if (n < min || n > max) throw new IllegalArgumentException();
            return n;
        } catch (ArithmeticException e) { throw new IllegalArgumentException(); }
    }
    private static String text(JsonObject args, String key) {
        JsonElement v = args.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()
            || v.getAsString().length() > 128) throw new IllegalArgumentException();
        return v.getAsString();
    }
    static Map<String, Integer> parseGoals(JsonObject args) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (args.has("goals")) {
            if (args.has("item") || args.has("count") || !args.get("goals").isJsonObject()) throw new IllegalArgumentException();
            JsonObject goals = args.getAsJsonObject("goals");
            if (goals.size() == 0 || goals.size() > MAX_GOALS) throw new IllegalArgumentException();
            for (String id : goals.keySet()) {
                String canonical = canonicalId(id);
                if (result.putIfAbsent(canonical, integer(goals, id, 1, 1, MAX_COUNT)) != null) throw new IllegalArgumentException();
            }
        } else {
            result.put(canonicalId(text(args, "item")), integer(args, "count", 1, 1, MAX_COUNT));
        }
        if (result.values().stream().mapToInt(Integer::intValue).sum() > MAX_TOTAL) throw new IllegalArgumentException();
        return result;
    }
    // Syntax validation is deliberately separate from registry validation, allowing plain JVM tests.
    static String canonicalId(String id) {
        if (id == null || id.length() > 128 || !id.matches("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+")) throw new IllegalArgumentException();
        String canonical = id.contains(":") ? id : "minecraft:" + id;
        if (canonical.equals("minecraft:air")) throw new IllegalArgumentException();
        return canonical;
    }
    private static void registered(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.ITEM.containsKey(key) || BuiltInRegistries.ITEM.get(key) == Items.AIR) throw new IllegalArgumentException();
    }
    private static JsonObject item(ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        o.addProperty("count", stack.getCount());
        o.addProperty("plain", stack.getComponentsPatch().isEmpty());
        if (stack.isDamageableItem()) {
            o.addProperty("damage", stack.getDamageValue());
            o.addProperty("maxDamage", stack.getMaxDamage());
            o.addProperty("remainingDurability", Math.max(0, stack.getMaxDamage() - stack.getDamageValue()));
        }
        return o;
    }
    private static JsonObject inspect(ServerPlayer p) {
        JsonObject o = ok(); JsonArray slots = new JsonArray(); JsonObject counts = new JsonObject();
        Map<String, Integer> plain = new TreeMap<>();
        // Own inventory only: includes armor/offhand for inspection, but planner uses only 0..35.
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            JsonObject s = item(stack); s.addProperty("slot", i); s.addProperty("craftingStock", i < 36); slots.add(s);
            if (i < 36 && stack.getComponentsPatch().isEmpty()) plain.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        plain.forEach(counts::addProperty); o.add("slots", slots); o.add("plainCraftingCounts", counts);
        o.addProperty("mode", p.gameMode.getGameModeForPlayer().getName()); return o;
    }
    private static JsonObject recipes(ServerPlayer p, JsonObject args) {
        String id = canonicalId(text(args, "item")); registered(id);
        String type = args.has("type") ? text(args, "type") : "all";
        if (!type.equals("all")) { type=type.contains(":")?type:"minecraft:"+type; if(!BuiltInRegistries.RECIPE_TYPE.containsKey(ResourceLocation.parse(type)))throw new IllegalArgumentException(); }
        int limit = integer(args, "limit", 8, 1, MAX_RECIPES);
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
        JsonObject o = ok(); JsonArray found = new JsonArray(); int scanned = 0, matched = 0; boolean truncated = false;
        for (var holder : p.server.getRecipeManager().getOrderedRecipes()) {
            scanned++;
            try {
            var recipe = holder.value();
            String recipeType = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            if (!type.equals("all") && !type.equals(recipeType)) continue;
            ItemStack output = recipe.getResultItem(p.registryAccess());
            if (output.isEmpty() || !BuiltInRegistries.ITEM.getKey(output.getItem()).toString().equals(id)) continue;
            if (matched++ < offset) continue;
            if (found.size() >= limit) { truncated = true; break; }
            JsonObject r = new JsonObject(); r.addProperty("id", holder.id().toString()); r.addProperty("type", recipeType);
            r.add("output", item(output));
            r.addProperty("executable", recipe instanceof CraftingRecipe && !recipe.isSpecial()
                && (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) && output.getComponentsPatch().isEmpty());
            if (recipe instanceof CraftingRecipe c) r.addProperty("needsTable", !c.canCraftInDimensions(2, 2));
            JsonArray ingredients = new JsonArray(); boolean bounded = recipe.getIngredients().size() > 32;
            for (int i = 0; i < Math.min(32, recipe.getIngredients().size()); i++) {
                ItemStack[] options = recipe.getIngredients().get(i).getItems();
                JsonArray choices = new JsonArray();
                for (int j = 0; j < Math.min(MAX_OPTIONS, options.length); j++) choices.add(BuiltInRegistries.ITEM.getKey(options[j].getItem()).toString());
                JsonObject ingredient = new JsonObject(); ingredient.add("options", choices);
                ingredient.addProperty("truncated", options.length > MAX_OPTIONS); ingredients.add(ingredient);
                bounded |= options.length > MAX_OPTIONS;
            }
            r.add("ingredients", ingredients); r.addProperty("ingredientsTruncated", bounded); found.add(r);
            } catch(RuntimeException unsupported) { /* Mod recipe may require dynamic inputs. */ }
        }
        o.add("recipes", found); o.addProperty("truncated", truncated); o.addProperty("nextOffset", offset + found.size());
        o.addProperty("scanned", scanned); o.addProperty("note", "Generic item ingredients only; dynamic/fluid/energy details require query_jei. Unsupported mod recipes may be skipped."); o.addProperty("processingExecuted", false); return o;
    }
    private static List<ItemStack> snapshot(ServerPlayer p) {
        List<ItemStack> stock = new ArrayList<>();
        for (int i = 0; i < 36; i++) stock.add(p.getInventory().getItem(i).copy());
        return stock;
    }
    private static boolean unchanged(ServerPlayer p, List<ItemStack> stock) {
        for (int i = 0; i < 36; i++) if (!ItemStack.matches(stock.get(i), p.getInventory().getItem(i))) return false;
        return true;
    }
    /** Pack before any effect. Empty results are legal; overflow rejects, never drops player items. */
    static List<ItemStack> pack(List<ItemStack> input, int inventoryLimit) {
        List<ItemStack> packed = new ArrayList<>();
        for (ItemStack original : input) {
            ItemStack rest = original.copy();
            if (rest.isEmpty()) continue;
            int max = Math.min(inventoryLimit, rest.getMaxStackSize());
            if (max <= 0) throw new IllegalArgumentException();
            for (ItemStack existing : packed) {
                if (!ItemStack.isSameItemSameComponents(existing, rest)) continue;
                int n = Math.min(rest.getCount(), Math.max(0, max - existing.getCount()));
                existing.grow(n); rest.shrink(n);
                if (rest.isEmpty()) break;
            }
            while (!rest.isEmpty()) {
                if (packed.size() >= 36) throw new IllegalArgumentException();
                packed.add(rest.split(Math.min(rest.getCount(), max)));
            }
        }
        return packed;
    }
    private static JsonObject craft(ServerPlayer p, JsonObject args, boolean execute) {
        Map<String, Integer> goals = parseGoals(args); goals.keySet().forEach(ResourceTools::registered);
        if (!p.isAlive() || p.isSpectator() || !p.mayBuild()) return error("mode_disallows_crafting");
        // Do not mutate inventory while a chest, workstation, trade or other container owns it.
        if (execute && p.containerMenu != p.inventoryMenu) return error("close_container_before_crafting");
        List<ItemStack> before = snapshot(p);
        AutoCrafting.Result result = AutoCrafting.prepare(p, goals);
        BlockPos table = result.needsTable() ? usableTable(p) : null;
        BlockPos placement = null;
        if (result.needsTable() && table == null) {
            Map<String, Integer> withTable = new LinkedHashMap<>(goals);
            withTable.merge("minecraft:crafting_table", 1, Integer::sum);
            result = AutoCrafting.prepare(p, withTable);
            placement = placement(p);
            if (placement == null) return error("no_safe_crafting_table_location");
        }
        List<ItemStack> finalStock = new ArrayList<>();
        for (var s : result.inventory()) finalStock.add(s.copy());
        if (placement != null) {
            // prepare returns the reserved target too. Spend ONLY the extra table, never the goals.
            boolean removed = false;
            for (ItemStack s : finalStock) if (!s.isEmpty() && s.is(Items.CRAFTING_TABLE) && s.getComponentsPatch().isEmpty()) {
                s.shrink(1); removed = true; break;
            }
            if (!removed) return error("table_reservation_failed");
        }
        List<ItemStack> packed;
        try { packed = pack(finalStock, p.getInventory().getMaxStackSize()); }
        catch (IllegalArgumentException e) { return error("insufficient_inventory_capacity"); }
        JsonObject o = ok(); o.addProperty("simulated", !execute); o.addProperty("crafts", result.crafts());
        o.addProperty("needsTable", result.needsTable()); o.addProperty("willPlaceTable", placement != null);
        o.add("goals", new Gson().toJsonTree(goals));
        JsonArray inventory = new JsonArray(); for (ItemStack s : packed) inventory.add(item(s)); o.add("resultInventory", inventory);
        if (table != null) o.add("table", position(table));
        if (placement != null) o.add("tablePlacement", position(placement));
        if (!execute) return o;
        // Preserve exact inventory slots for a no-op, including the entirely empty inventory case.
        if (result.crafts() == 0 && placement == null) { o.addProperty("committed", false); return o; }
        if (!unchanged(p, before)) return error("inventory_changed");
        if (placement != null) {
            BlockState state = Blocks.CRAFTING_TABLE.defaultBlockState();
            if (!safePlacement(p, placement)
                || BlockEvent.PLACE.invoker().placeBlock(p.serverLevel(), placement, state, p).isFalse()) return error("table_placement_denied");
            // Event handlers can mutate inventory or world. Revalidate after they return.
            if (!unchanged(p, before) || !p.isAlive() || !p.mayBuild() || p.containerMenu != p.inventoryMenu
                || !safePlacement(p, placement)) return error("state_changed_during_placement");
            if (!p.serverLevel().setBlock(placement, state, 3)) return error("table_placement_failed");
            if (!unchanged(p, before) || !p.serverLevel().getBlockState(placement).is(Blocks.CRAFTING_TABLE)) {
                // setBlock callbacks have uncertain effects. Never claim an atomic success or retry.
                JsonObject failure = error("table_placement_partial_possible"); failure.addProperty("partialPossible", true); return failure;
            }
        } else if (result.needsTable() && (table == null || !tableAllowed(p, table))) return error("table_no_longer_available");
        // No AutoCrafting.commit: it drops overflow. Capacity was checked, and each slot gets a copy.
        for (int i = 0; i < 36; i++) p.getInventory().setItem(i, i < packed.size() ? packed.get(i).copy() : ItemStack.EMPTY);
        p.getInventory().setChanged(); p.inventoryMenu.broadcastChanges();
        o.addProperty("committed", true); return o;
    }
    private static boolean inArea(ServerPlayer p, BlockPos pos) {
        var level = p.serverLevel();
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }
    private static boolean tableAllowed(ServerPlayer p, BlockPos pos) {
        return inArea(p, pos) && p.serverLevel().mayInteract(p, pos)
            && p.serverLevel().getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }
    private static BlockPos usableTable(ServerPlayer p) {
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-4,-2,-4), p.blockPosition().offset(4,2,4)))
            if (tableAllowed(p, pos)) return pos.immutable();
        return null;
    }
    private static boolean safePlacement(ServerPlayer p, BlockPos pos) {
        var level = p.serverLevel();
        if (!p.isAlive() || p.isSpectator() || !p.mayBuild() || !inArea(p, pos) || !inArea(p, pos.below())
            || !level.mayInteract(p, pos) || !level.getBlockState(pos).isAir()
            || !level.getFluidState(pos).isEmpty() || level.getBlockEntity(pos) != null
            || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return false;
        var bounds = Blocks.CRAFTING_TABLE.defaultBlockState().getCollisionShape(level, pos).bounds().move(pos);
        return !bounds.intersects(p.getBoundingBox()) && level.getEntities(p, bounds, e -> !e.isSpectator()).isEmpty();
    }
    private static BlockPos placement(ServerPlayer p) {
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-3,-1,-3), p.blockPosition().offset(3,1,3)))
            if (safePlacement(p, pos)) return pos.immutable();
        return null;
    }
    private static JsonObject position(BlockPos pos) {
        JsonObject o = new JsonObject(); o.addProperty("x", pos.getX()); o.addProperty("y", pos.getY()); o.addProperty("z", pos.getZ()); return o;
    }
    private static boolean workstation(BlockState s) {
        return s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE)
            || s.is(Blocks.SMOKER) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
            || s.is(Blocks.BREWING_STAND) || s.is(Blocks.STONECUTTER) || s.is(Blocks.SMITHING_TABLE)
            || s.is(Blocks.ANVIL) || s.is(Blocks.CHIPPED_ANVIL) || s.is(Blocks.DAMAGED_ANVIL)
            || s.is(Blocks.GRINDSTONE) || s.is(Blocks.LOOM) || s.is(Blocks.CARTOGRAPHY_TABLE)
            || s.is(Blocks.ENCHANTING_TABLE);
    }
    private static JsonObject stations(ServerPlayer p, JsonObject args) {
        int radius = integer(args, "radius", 4, 1, 8), limit = integer(args, "limit", 32, 1, 64);
        JsonObject o = ok(); JsonArray found = new JsonArray(); int scanned = 0, skipped = 0; boolean truncated = false;
        // Fixed vertical radius 2: at most 17*5*17=1445 positions, never load a chunk.
        for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-radius,-2,-radius), p.blockPosition().offset(radius,2,radius))) {
            scanned++;
            if (!inArea(p, pos)) { skipped++; continue; }
            BlockState s = p.serverLevel().getBlockState(pos); if (!workstation(s)) continue;
            if (found.size() >= limit) { truncated = true; break; }
            JsonObject station = position(pos); station.addProperty("block", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
            station.addProperty("mayInteract", p.serverLevel().mayInteract(p, pos));
            station.addProperty("mayBuild", p.mayBuild());
            station.addProperty("occupancy", "unknown_private_container_not_inspected");
            if (s.hasProperty(BlockStateProperties.LIT)) station.addProperty("lit", s.getValue(BlockStateProperties.LIT));
            // A public block-state signal, NOT an assertion about contents, ownership or availability.
            station.addProperty("permissionScope", "vanilla_region_check_only_mod_interaction_may_still_deny"); found.add(station);
        }
        o.add("workstations", found); o.addProperty("scanned", scanned); o.addProperty("skippedUnloadedOrOutside", skipped);
        o.addProperty("truncated", truncated); o.addProperty("verticalRadius", 2); return o;
    }
}
