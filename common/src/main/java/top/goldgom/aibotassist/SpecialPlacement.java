package top.goldgom.aibotassist;

import com.google.gson.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.util.*;
import java.util.stream.Collectors;

/** Pure validation/serialization; no world mutation. All failures are IllegalArgumentException.
 * expand accepts anchors only; costs accepts anchors, expanded results or successful subsets.
 * NBT deliberately supports only CustomName/Lock and, in creative, plain container Items.
 * Item components/custom_data are not accepted: nested executable payloads cannot be audited generically.
 */
public final class SpecialPlacement {
    public static final int MAX_NBT_LENGTH = 8192, MAX_NBT_DEPTH = 16;
    private SpecialPlacement() {}

    public static BlockState state(Plan.Placement p) {
        if (p == null || !Plan.validBlockId(p.block())) throw invalid("Invalid block ID");
        ResourceLocation id = ResourceLocation.tryParse(p.block());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw invalid("Unknown block: " + p.block());
        BlockState s = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        if (p.properties() == null) throw invalid("Null properties");
        for (var e : p.properties().entrySet()) {
            if (e.getKey() == null || e.getValue() == null) throw invalid("Null property");
            Property<?> property = s.getBlock().getStateDefinition().getProperty(e.getKey());
            if (property == null) throw invalid("Unknown property: " + e.getKey());
            s = withValue(s, property, e.getValue());
        }
        if (s.getBlock() instanceof LiquidBlock && s.getValue(LiquidBlock.LEVEL) != 0)
            throw invalid("Only source fluids (level=0) are supported");
        return s;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState s, Property<T> p, String value) {
        return s.setValue(p, p.getValue(value).orElseThrow(() -> invalid("Invalid property value: " + p.getName())));
    }

    public static boolean isUpperOrHead(Plan.Placement p) { return isUpperOrHead(state(p)); }
    private static boolean isUpperOrHead(BlockState s) {
        return s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
            || s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    /** Every generated cell participates in collision, radius and total-count validation. */
    public static List<Plan.Placement> expand(List<Plan.Placement> input) {
        checkList(input);
        Map<Cell, Plan.Placement> cells = new LinkedHashMap<>();
        for (Plan.Placement p : input) {
            BlockState s = state(p);
            if (isUpperOrHead(s)) throw invalid("Submit only a door lower half or bed foot");
            // Vanilla doors/beds do not necessarily default to north.
            if ((s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BedBlock) && !p.properties().containsKey("facing"))
                s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
            put(cells, placement(p, s, p.x(), p.y(), p.z(), p.nbt()));
            if (s.getBlock() instanceof DoorBlock)
                put(cells, placement(p, s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), p.x(), p.y() + 1, p.z(), null));
            else if (s.getBlock() instanceof BedBlock) {
                Direction d = s.getValue(BedBlock.FACING);
                put(cells, placement(p, s.setValue(BedBlock.PART, BedPart.HEAD), p.x() + d.getStepX(), p.y(), p.z() + d.getStepZ(), null));
            }
        }
        return List.copyOf(cells.values());
    }

    /** Counts real item IDs; a block with no item form is not a survival material.
     * Successful subsets are supported: either half costs one item; a matching pair costs one total.
     */
    public static Map<String, Integer> costs(List<Plan.Placement> input) {
        checkList(input);
        Map<Cell, Plan.Placement> submitted = new LinkedHashMap<>();
        Map<Cell, Plan.Placement> anchors = new LinkedHashMap<>();
        for (Plan.Placement p : input) {
            BlockState s = state(p);
            put(submitted, p);
            if ((s.getBlock() instanceof DoorBlock || s.getBlock() instanceof BedBlock) && !p.properties().containsKey("facing"))
                s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
            int x = p.x(), y = p.y(), z = p.z();
            if (s.getBlock() instanceof DoorBlock && isUpperOrHead(s)) {
                y--; s = s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            } else if (s.getBlock() instanceof BedBlock && isUpperOrHead(s)) {
                Direction d = s.getValue(BedBlock.FACING);
                x -= d.getStepX(); z -= d.getStepZ(); s = s.setValue(BedBlock.PART, BedPart.FOOT);
            }
            Plan.Placement anchor = placement(p, s, x, y, z, null);
            Plan.Placement existing = anchors.putIfAbsent(cell(anchor), anchor);
            if (existing != null && !state(existing).equals(s)) throw invalid("Mismatched door/bed companion");
        }
        List<Plan.Placement> expanded = expand(new ArrayList<>(anchors.values()));
        Map<String, Integer> result = new TreeMap<>();
        for (Plan.Placement p : expanded) {
            BlockState s = state(p);
            if (isUpperOrHead(s)) continue;
            var item = s.is(Blocks.WATER) ? Items.WATER_BUCKET : s.is(Blocks.LAVA) ? Items.LAVA_BUCKET : s.getBlock().asItem();
            if (item == Items.AIR) throw invalid("Block has no material item: " + p.block());
            result.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Returns one block argument, never a command. Uses canonical state names and parsed SNBT only.
     * Caller must invoke this for the whole batch BEFORE charging items or mutating the world.
     */
    public static String blockArgument(Plan.Placement p, boolean creative) {
        BlockState s = state(p);
        if (!creative && s.getBlock() instanceof CommandBlock) throw invalid("Command blocks require creative");
        String properties = s.getProperties().stream().sorted(Comparator.comparing(Property::getName))
            .map(prop -> prop.getName() + "=" + valueName(s, prop)).collect(Collectors.joining(","));
        String result = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString() + (properties.isEmpty() ? "" : "[" + properties + "]");
        if (p.nbt() == null || p.nbt().isBlank()) return result;
        CompoundTag tag = parseNbt(p.nbt());
        if (!s.hasBlockEntity()) throw invalid("NBT requires a block entity");
        for (String key : tag.getAllKeys()) {
            switch (key) {
                case "CustomName" -> {
                    if (!(tag.get(key) instanceof StringTag)) throw invalid("CustomName must be a JSON string");
                    boundNesting(tag.getString(key));
                    try { checkText(JsonParser.parseString(tag.getString(key)), 0); }
                    catch (JsonParseException e) { throw invalid("Invalid CustomName JSON"); }
                }
                case "Lock" -> { if (!(tag.get(key) instanceof StringTag)) throw invalid("Lock must be a string"); }
                case "disabled_slots" -> {
                    if(!s.is(Blocks.CRAFTER)||!(tag.get(key) instanceof IntArrayTag))throw invalid("disabled_slots requires crafter integer array");
                    int[] slots=tag.getIntArray(key);Set<Integer> seen=new HashSet<>();
                    if(slots.length>9)throw invalid("Too many crafter slots");
                    for(int slot:slots)if(slot<0||slot>8||!seen.add(slot))throw invalid("Invalid crafter slot");
                }
                case "Items" -> {
                    if (!creative) throw invalid("Survival cannot create container contents");
                    // Capability rather than block-ID whitelist (chests, hoppers, barrels, modded containers).
                    if (!(s.getBlock() instanceof EntityBlock entityBlock)) throw invalid("Not a container");
                    var entity = entityBlock.newBlockEntity(net.minecraft.core.BlockPos.ZERO, s);
                    if (!(entity instanceof net.minecraft.world.Container)) throw invalid("Not a container");
                    checkItems(tag.get(key), ((net.minecraft.world.Container) entity).getContainerSize());
                }
                default -> throw invalid("NBT field not permitted: " + key);
            }
        }
        return result + tag; // Canonical SNBT, not the untrusted original text.
    }

    /** Neighbor-derived connections are expected to change after a batch. Keep semantic states strict. */
    public static boolean matches(Plan.Placement expected, BlockState actual) {
        BlockState desired=state(expected);
        if(actual.getBlock()!=desired.getBlock())return false;
        Set<String> derived=new HashSet<>();
        if(desired.getBlock() instanceof CrossCollisionBlock || desired.getBlock() instanceof WallBlock)
            derived.addAll(Set.of("north","south","east","west","up"));
        if(desired.getBlock() instanceof StairBlock)derived.add("shape");
        if(desired.getBlock() instanceof net.minecraft.world.level.block.RedStoneWireBlock)
            derived.addAll(Set.of("north","south","east","west"));
        // Runtime electrical/activity states are not placement errors unless explicitly constrained.
        // Keep structural settings (facing, delay, orientation, mode, half, etc.) strict.
        if(BuiltInRegistries.BLOCK.getKey(desired.getBlock()).getNamespace().equals("minecraft"))
            for(String dynamic:Set.of("power","powered","lit","triggered","crafting","enabled","locked"))
                if(!expected.properties().containsKey(dynamic))derived.add(dynamic);
        for(Property<?> property:desired.getProperties()) {
            if(derived.contains(property.getName()))continue;
            if(!Objects.equals(actual.getValue(property),desired.getValue(property)))return false;
        }
        return true;
    }
    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) { return p.getName(s.getValue(p)); }

    private static CompoundTag parseNbt(String text) {
        if (text.length() > MAX_NBT_LENGTH) throw invalid("NBT too long");
        boundNesting(text);
        try { return TagParser.parseTag(text); }
        catch (CommandSyntaxException e) { throw invalid("Invalid SNBT"); }
    }

    private static void boundNesting(String text) {
        // Bound nesting BEFORE parsers recurse, respecting quoted strings and escapes.
        int depth = 0; char quote = 0; boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{' || c == '[') { if (++depth > MAX_NBT_DEPTH) throw invalid("NBT too deep"); }
            else if (c == '}' || c == ']') depth--;
        }
    }

    private static void checkText(JsonElement e, int depth) {
        if (depth > MAX_NBT_DEPTH) throw invalid("Text too deep");
        if (e.isJsonObject()) for (var entry : e.getAsJsonObject().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT).replace("_", "");
            // Ban all click events, not merely a case-sensitive run_command spelling.
            if (key.equals("clickevent") || key.equals("command") || key.equals("runcommand")) throw invalid("Executable text not permitted");
            checkText(entry.getValue(), depth + 1);
        }
        else if (e.isJsonArray()) for (JsonElement child : e.getAsJsonArray()) checkText(child, depth + 1);
    }

    private static void checkItems(Tag tag, int size) {
        if (!(tag instanceof ListTag list) || list.size() > size) throw invalid("Invalid Items list");
        Set<Integer> slots = new HashSet<>();
        for (Tag entry : list) {
            if (!(entry instanceof CompoundTag stack)) throw invalid("Invalid item stack");
            for (String key : stack.getAllKeys())
                if (!Set.of("Slot", "id", "count").contains(key)) throw invalid("Item components and nested NBT are not permitted");
            if (!(stack.get("id") instanceof StringTag) || !(stack.get("Slot") instanceof NumericTag slot)
                || !(stack.get("count") instanceof NumericTag count)) throw invalid("Incomplete item stack");
            ResourceLocation id = ResourceLocation.tryParse(stack.getString("id"));
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw invalid("Unknown item");
            var item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR || item == Items.COMMAND_BLOCK || item == Items.CHAIN_COMMAND_BLOCK || item == Items.REPEATING_COMMAND_BLOCK
                || item == Items.COMMAND_BLOCK_MINECART) throw invalid("Unsafe container item");
            int index = slot.getAsInt(), n = count.getAsInt();
            if (slot.getAsDouble() != index || count.getAsDouble() != n || index < 0 || index >= size || !slots.add(index)
                || n < 1 || n > item.getDefaultMaxStackSize()) throw invalid("Invalid slot/count");
        }
    }

    private record Cell(int x, int y, int z) {}
    private static Cell cell(Plan.Placement p) { return new Cell(p.x(), p.y(), p.z()); }
    private static void checkList(List<Plan.Placement> input) {
        if (input == null) throw invalid("Too many placements");
    }
    private static void put(Map<Cell, Plan.Placement> cells, Plan.Placement p) {
        if (p == null || Math.abs((long) p.x()) > Plan.RADIUS || Math.abs((long) p.y()) > Plan.RADIUS || Math.abs((long) p.z()) > Plan.RADIUS)
            throw invalid("Placement outside radius");
        if (cells.putIfAbsent(cell(p), p) != null) throw invalid("Placement collision");
    }
    private static Plan.Placement placement(Plan.Placement p, BlockState s, int x, int y, int z, String nbt) {
        Map<String, String> properties = new TreeMap<>();
        for (Property<?> prop : s.getProperties()) properties.put(prop.getName(), valueName(s, prop));
        return new Plan.Placement(x, y, z, p.block(), Map.copyOf(properties), nbt);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
