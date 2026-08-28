package greenebolt.autotrade;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.ComponentType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Objects;

public final class StackNormalizer {
    private StackNormalizer() {
    }

    public static boolean isCompatEnabled() {
        return ShulkerCompatConfig.isEnabled();
    }

    public static boolean sameIdentity(ItemStack first, ItemStack second) {
        if (!isCompatEnabled()) {
            return false;
        }
        if (first == second) {
            return true;
        }
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) {
            return first != null && second != null && first.isEmpty() && second.isEmpty();
        }
        if (first.getItem() != second.getItem()) {
            return false;
        }

        boolean shulker = isShulkerBox(first) || isShulkerBox(second);
        return normalizedComponentsEqual(first.getComponents(), second.getComponents(), shulker);
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isShulkerBoxItem(stack.getItem());
    }

    public static boolean isShulkerBoxItem(Item item) {
        return item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean normalizedComponentsEqual(ComponentMap first, ComponentMap second, boolean ignoreConfigured) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }

        HashSet<ComponentType<?>> types = new HashSet<>();
        types.addAll(first.getTypes());
        types.addAll(second.getTypes());
        for (ComponentType<?> type : types) {
            if (ignoreConfigured && isIgnoredComponent(type)) {
                continue;
            }
            if (!Objects.equals(first.get(type), second.get(type))) {
                return false;
            }
        }
        return true;
    }

    public static int normalizedComponentsHash(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return normalizedComponentsHash(stack.getComponents(), isShulkerBox(stack));
    }

    public static int normalizedComponentsHash(ComponentMap components, boolean ignoreConfigured) {
        if (components == null) {
            return 0;
        }

        int result = 1;
        return components.getTypes().stream()
                .filter(type -> !ignoreConfigured || !isIgnoredComponent(type))
                .sorted((first, second) -> componentId(first).toString().compareTo(componentId(second).toString()))
                .reduce(result, (hash, type) -> 31 * (31 * hash + Objects.hashCode(type))
                        + Objects.hashCode(components.get(type)), (left, right) -> 31 * left + right);
    }

    private static boolean isIgnoredComponent(ComponentType<?> type) {
        Identifier identifier = Registries.DATA_COMPONENT_TYPE.getId(type);
        return identifier != null && ShulkerCompatConfig.isIgnored(identifier);
    }

    private static Identifier componentId(ComponentType<?> type) {
        Identifier identifier = Registries.DATA_COMPONENT_TYPE.getId(type);
        return identifier != null ? identifier : Identifier.of("satella", "unknown_component");
    }
}
