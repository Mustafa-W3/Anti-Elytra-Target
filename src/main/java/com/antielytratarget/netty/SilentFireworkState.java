package com.antielytratarget.netty;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class SilentFireworkState {

    private static final int PLAYER_WINDOW_SIZE = 46;
    private static final int HOTBAR_PACKET_START = 36;
    private static final int OFFHAND_PACKET_SLOT = 45;
    private static final long SERVER_INTERACTION_TTL_NANOS =
            1_000_000_000L;
    private int currentSlot;
    private int previousSlot;
    private long lastSlotChangeTime;
    private long clientTick;
    private final ItemStack[] hotbar = new ItemStack[9];
    private boolean isGliding;

    private final ItemStack[] playerInventory = new ItemStack[PLAYER_WINDOW_SIZE];
    private ItemStack[] activeWindowItems = new ItemStack[0];
    private int activeWindowId;
    private ItemStack cursor;

    private boolean initialized;
    private boolean inventoryReliable;
    private long inventoryMutationSequence;
    private long nextSwapSequenceId = 1L;
    private final ArrayDeque<PendingMainHandInteraction>
            pendingMainHandInteractions =
            new ArrayDeque<>();
    private PendingBoostCandidate pendingBoostCandidate;

    private HotbarSequence hotbarSequence;
    private InventorySequence inventorySequence;

    public SilentFireworkState() {
    }

    public synchronized void seed(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] initialHotbar = new ItemStack[9];
        for (int slot = 0; slot < initialHotbar.length; slot++) {
            initialHotbar[slot] = fromBukkit(inventory.getItem(slot));
        }

        seed(inventory.getHeldItemSlot(), initialHotbar,
                fromBukkit(inventory.getItemInOffHand()), player.isGliding());

        for (int slot = 9; slot <= 35; slot++) {
            setPlayerSlot(slot, fromBukkit(inventory.getItem(slot)));
        }
        setPlayerSlot(5, fromBukkit(inventory.getHelmet()));
        setPlayerSlot(6, fromBukkit(inventory.getChestplate()));
        setPlayerSlot(7, fromBukkit(inventory.getLeggings()));
        setPlayerSlot(8, fromBukkit(inventory.getBoots()));
        activeWindowItems = copyItems(playerInventory);
    }

    public synchronized void seed(int selectedSlot, ItemStack[] initialHotbar,
                                  ItemStack offhand, boolean gliding) {
        currentSlot = validHotbarSlot(selectedSlot) ? selectedSlot : 0;
        previousSlot = currentSlot;
        for (int slot = 0; slot < hotbar.length; slot++) {
            ItemStack item = initialHotbar != null && slot < initialHotbar.length
                    ? initialHotbar[slot] : null;
            setPlayerSlot(HOTBAR_PACKET_START + slot, item);
        }
        setPlayerSlot(OFFHAND_PACKET_SLOT, offhand);
        isGliding = gliding;
        initialized = true;
        inventoryReliable = true;
        activeWindowId = 0;
        activeWindowItems = copyItems(playerInventory);
        clearSequences();
        pendingMainHandInteractions.clear();
        pendingBoostCandidate = null;
    }

    public synchronized void setGliding(boolean gliding) {
        isGliding = gliding;
    }

    public synchronized SwapPattern onHeldItemChange(int newSlot, long tick,
                                                      long receivedNanos) {
        clientTick = tick;
        if (!validHotbarSlot(newSlot)) {
            inventoryReliable = false;
            clearSequences();
            return null;
        }

        int fromSlot = currentSlot;
        if (newSlot == fromSlot) {
            return null;
        }

        ItemStack fromItem = hotbar[fromSlot];
        ItemStack toItem = hotbar[newSlot];
        SwapPattern completed = null;
        inventorySequence = null;

        if (hotbarSequence != null) {
            HotbarSequence sequence = hotbarSequence;
            if (sequence.used && fromSlot == sequence.rocketSlot
                    && newSlot == sequence.originalSlot
                    && sameItemType(sequence.originalItem, toItem)
                    && sequence.inventoryMutationSequence == inventoryMutationSequence) {
                completed = new SwapPattern(
                        sequence.sequenceId, SwapPath.HOTBAR,
                        sequence.originalSlot, sequence.rocketSlot,
                        sequence.switchToRocketTick, sequence.useRocketTick, tick,
                        sequence.switchToRocketNanos, sequence.useRocketNanos,
                        receivedNanos, sequence.glidingAtUse || isGliding,
                        inventoryReliable, itemKey(sequence.originalItem));
                hotbarSequence = null;
            } else if (newSlot != sequence.rocketSlot) {
                hotbarSequence = null;
            }
        }

        previousSlot = fromSlot;
        currentSlot = newSlot;
        lastSlotChangeTime = System.currentTimeMillis();
        if (completed == null && inventoryReliable
                && isCombatWeapon(fromItem) && isFirework(toItem)) {
            hotbarSequence = new HotbarSequence(
                    nextSequenceId(), fromSlot, newSlot, copy(fromItem), tick, receivedNanos,
                    inventoryMutationSequence);
        }
        return completed;
    }

    public synchronized UseSnapshot onUseItem(InteractionHand hand, long tick,
                                               long receivedNanos) {
        clientTick = tick;
        ItemStack expectedItem = hand == InteractionHand.OFF_HAND
                ? playerInventory[OFFHAND_PACKET_SLOT] : hotbar[currentSlot];
        boolean firework = isFirework(expectedItem);
        long candidateSequenceId = -1L;

        if (hand == InteractionHand.MAIN_HAND && firework
                && hotbarSequence != null
                && currentSlot == hotbarSequence.rocketSlot
                && hotbarSequence.inventoryMutationSequence == inventoryMutationSequence) {
            markUsed(hotbarSequence, tick, receivedNanos);
            candidateSequenceId = hotbarSequence.sequenceId;
        } else if (hand == InteractionHand.MAIN_HAND && firework
                && inventorySequence != null) {
            markUsed(inventorySequence, tick, receivedNanos);
            candidateSequenceId = inventorySequence.sequenceId;
        }

        if (hand == InteractionHand.MAIN_HAND) {
            queueMainHandInteraction(candidateSequenceId, receivedNanos);
        }

        return new UseSnapshot(firework, initialized && inventoryReliable,
                currentSlot, tick, isGliding);
    }

    public synchronized SwapPattern onClickWindow(
            WrapperPlayClientClickWindow click, long tick, long receivedNanos) {
        clientTick = tick;

        ItemStack beforeSelected = copy(hotbar[currentSlot]);
        inventoryMutationSequence++;
        hotbarSequence = null;

        int windowId = click.getWindowId();
        boolean applied = false;
        Map<Integer, ItemStack> changedSlots = click.getSlots().orElse(null);
        if (changedSlots != null) {
            for (Map.Entry<Integer, ItemStack> entry : changedSlots.entrySet()) {
                applied |= setWindowSlot(windowId, entry.getKey(), entry.getValue());
            }
        }

        if (!applied && click.getWindowClickType()
                == WrapperPlayClientClickWindow.WindowClickType.SWAP) {
            applied = applyDeterministicSwap(
                    windowId, click.getSlot(), click.getButton());
        }

        cursor = copy(click.getCarriedItemStack());
        if (!applied && click.getWindowClickType()
                != WrapperPlayClientClickWindow.WindowClickType.UNKNOWN) {
            inventoryReliable = false;
        }
        return updateInventorySequence(
                beforeSelected, hotbar[currentSlot], tick, receivedNanos,
                SwapPath.INVENTORY);
    }

    public synchronized SwapPattern onPickItem(
            int inventorySlot, long tick, long receivedNanos) {
        clientTick = tick;
        ItemStack beforeSelected = copy(hotbar[currentSlot]);
        inventoryMutationSequence++;
        hotbarSequence = null;

        int sourceProtocolSlot = bukkitStorageToProtocol(inventorySlot);
        if (sourceProtocolSlot >= 0) {
            swapPlayerSlots(sourceProtocolSlot,
                    HOTBAR_PACKET_START + currentSlot);
        } else {
            inventoryReliable = false;
        }
        return updateInventorySequence(
                beforeSelected, hotbar[currentSlot], tick, receivedNanos,
                SwapPath.PICK_ITEM);
    }

    public synchronized SwapPattern onOffhandSwap(long tick,
                                                   long receivedNanos) {
        clientTick = tick;
        ItemStack beforeSelected = copy(hotbar[currentSlot]);
        inventoryMutationSequence++;
        hotbarSequence = null;

        swapPlayerSlots(HOTBAR_PACKET_START + currentSlot, OFFHAND_PACKET_SLOT);
        return updateInventorySequence(
                beforeSelected, hotbar[currentSlot], tick, receivedNanos,
                SwapPath.OFFHAND);
    }

    public synchronized long onServerItemInteraction(
            boolean mainHand, boolean fireworkItem, boolean useAllowed,
            long interactionNanos) {
        pendingBoostCandidate = null;
        if (!mainHand) return -1L;

        while (!pendingMainHandInteractions.isEmpty()) {
            PendingMainHandInteraction interaction =
                    pendingMainHandInteractions.peekFirst();
            long age = interactionNanos - interaction.useNanos();
            if (age < 0L) return -1L;
            pendingMainHandInteractions.removeFirst();
            if (age > SERVER_INTERACTION_TTL_NANOS) continue;
            if (interaction.sequenceId() < 0L
                    || !fireworkItem || !useAllowed) {
                return -1L;
            }

            pendingBoostCandidate = new PendingBoostCandidate(
                    interaction.sequenceId(), interactionNanos);
            return interaction.sequenceId();
        }
        return -1L;
    }

    public synchronized long confirmServerElytraBoost(long boostNanos) {
        PendingBoostCandidate candidate = pendingBoostCandidate;
        pendingBoostCandidate = null;
        if (candidate == null) return -1L;

        long age = boostNanos - candidate.interactionNanos();
        return age >= 0L && age <= SERVER_INTERACTION_TTL_NANOS
                ? candidate.sequenceId() : -1L;
    }

    public synchronized void onServerWindowItems(int windowId,
                                                 List<ItemStack> items,
                                                 long tick) {
        ItemStack[] copied = items == null
                ? new ItemStack[0] : copyItems(items.toArray(new ItemStack[0]));

        if (windowId == 0) {
            int length = Math.min(copied.length, playerInventory.length);
            for (int slot = 0; slot < length; slot++) {
                setPlayerSlot(slot, copied[slot]);
            }
            activeWindowItems = copyItems(playerInventory);
            activeWindowId = 0;
        } else {
            activeWindowId = windowId;
            activeWindowItems = copied;
            mapContainerPlayerTail(copied);
        }
        initialized = true;
        inventoryReliable = true;
        validateUnconsumedSequences();
    }

    public synchronized void onServerSetSlot(int windowId, int slot,
                                             ItemStack item, long tick) {
        if (windowId == -1 && slot == -1) {
            cursor = copy(item);
            return;
        }

        boolean applied = setWindowSlot(windowId, slot, item);
        if (applied) {
            initialized = true;
            validateUnconsumedSequences();
        }
    }

    public synchronized void onServerPlayerInventorySlot(int directSlot,
                                                         ItemStack item,
                                                         long tick) {
        int protocolSlot = directPlayerSlotToProtocol(directSlot);
        if (protocolSlot >= 0) {
            setPlayerSlot(protocolSlot, item);
            initialized = true;
            validateUnconsumedSequences();
        }
    }

    public synchronized void onServerCursor(ItemStack item, long tick) {
        cursor = copy(item);
    }

    public synchronized void onOpenWindow(int windowId) {
        activeWindowId = windowId;
        activeWindowItems = new ItemStack[0];
        hotbarSequence = null;
        inventorySequence = null;
    }

    public synchronized void onCloseWindow(int windowId) {
        if (windowId == activeWindowId || windowId == 0) {
            activeWindowId = 0;
            activeWindowItems = copyItems(playerInventory);
        }
    }

    public synchronized void onServerHeldItemChange(int slot, long tick) {
        clientTick = tick;
        if (!validHotbarSlot(slot)) {
            inventoryReliable = false;
            clearSequences();
            return;
        }
        previousSlot = currentSlot;
        currentSlot = slot;
        lastSlotChangeTime = System.currentTimeMillis();
        clearSequences();
    }

    public synchronized void reset() {
        initialized = false;
        inventoryReliable = false;
        activeWindowId = 0;
        activeWindowItems = new ItemStack[0];
        cursor = null;
        pendingMainHandInteractions.clear();
        pendingBoostCandidate = null;
        Arrays.fill(hotbar, null);
        Arrays.fill(playerInventory, null);
        clearSequences();
    }

    public synchronized int getCurrentSlot() {
        return currentSlot;
    }

    public synchronized int getPreviousSlot() {
        return previousSlot;
    }

    public synchronized long getLastSlotChangeTime() {
        return lastSlotChangeTime;
    }

    public synchronized long getClientTick() {
        return clientTick;
    }

    public synchronized ItemStack[] getHotbarSnapshot() {
        return copyItems(hotbar);
    }

    public synchronized boolean isGliding() {
        return isGliding;
    }

    private boolean applyDeterministicSwap(int windowId, int clickedSlot,
                                           int button) {
        int targetPlayerSlot;
        if (button >= 0 && button <= 8) {
            targetPlayerSlot = HOTBAR_PACKET_START + button;
        } else if (button == 40) {
            targetPlayerSlot = OFFHAND_PACKET_SLOT;
        } else {
            return false;
        }

        int clickedPlayerSlot = toPlayerSlot(windowId, clickedSlot);
        ItemStack clickedItem = getWindowSlot(windowId, clickedSlot);
        if (clickedItem == null) return false;

        ItemStack target = playerInventory[targetPlayerSlot];
        if (clickedPlayerSlot >= 0) {
            setPlayerSlot(clickedPlayerSlot, target);
        } else if (windowId == activeWindowId
                && clickedSlot >= 0 && clickedSlot < activeWindowItems.length) {
            activeWindowItems[clickedSlot] = copy(target);
        } else {
            return false;
        }
        setPlayerSlot(targetPlayerSlot, clickedItem);
        return true;
    }

    private boolean setWindowSlot(int windowId, int windowSlot, ItemStack item) {
        int playerSlot = toPlayerSlot(windowId, windowSlot);
        if (playerSlot >= 0) {
            setPlayerSlot(playerSlot, item);
        }

        if (windowId == 0) {
            if (windowSlot < 0 || windowSlot >= PLAYER_WINDOW_SIZE) return false;
            if (activeWindowId == 0 && activeWindowItems.length == PLAYER_WINDOW_SIZE) {
                activeWindowItems[windowSlot] = copy(item);
            }
            return true;
        }

        if (windowId == activeWindowId
                && windowSlot >= 0 && windowSlot < activeWindowItems.length) {
            activeWindowItems[windowSlot] = copy(item);
            return true;
        }
        return playerSlot >= 0;
    }

    private ItemStack getWindowSlot(int windowId, int windowSlot) {
        int playerSlot = toPlayerSlot(windowId, windowSlot);
        if (playerSlot >= 0) return playerInventory[playerSlot];
        if (windowId == activeWindowId
                && windowSlot >= 0 && windowSlot < activeWindowItems.length) {
            return activeWindowItems[windowSlot];
        }
        return null;
    }

    private int toPlayerSlot(int windowId, int windowSlot) {
        if (windowId == 0) {
            return windowSlot >= 0 && windowSlot < PLAYER_WINDOW_SIZE
                    ? windowSlot : -1;
        }
        if (windowId == activeWindowId && activeWindowItems.length >= 36) {
            int playerTailStart = activeWindowItems.length - 36;
            if (windowSlot >= playerTailStart
                    && windowSlot < activeWindowItems.length) {
                return 9 + (windowSlot - playerTailStart);
            }
        }
        return -1;
    }

    private void mapContainerPlayerTail(ItemStack[] items) {
        if (items.length < 36) return;
        int start = items.length - 36;
        for (int offset = 0; offset < 36; offset++) {
            setPlayerSlot(9 + offset, items[start + offset]);
        }
    }

    private void swapPlayerSlots(int first, int second) {
        ItemStack item = playerInventory[first];
        setPlayerSlot(first, playerInventory[second]);
        setPlayerSlot(second, item);
    }

    private void setPlayerSlot(int protocolSlot, ItemStack item) {
        if (protocolSlot < 0 || protocolSlot >= playerInventory.length) return;
        ItemStack copied = copy(item);
        playerInventory[protocolSlot] = copied;
        if (protocolSlot >= HOTBAR_PACKET_START
                && protocolSlot < HOTBAR_PACKET_START + hotbar.length) {
            hotbar[protocolSlot - HOTBAR_PACKET_START] = copied;
        }
    }

    private void validateUnconsumedSequences() {
        if (hotbarSequence != null && !hotbarSequence.used
                && !isFirework(hotbar[hotbarSequence.rocketSlot])) {
            hotbarSequence = null;
        }
        if (inventorySequence != null && !inventorySequence.used
                && !isFirework(hotbar[currentSlot])) {
            inventorySequence = null;
        }
    }

    private void clearSequences() {
        hotbarSequence = null;
        inventorySequence = null;
    }

    private SwapPattern updateInventorySequence(
            ItemStack beforeSelected, ItemStack afterSelected,
            long tick, long receivedNanos, SwapPath path) {
        SwapPattern completed = null;
        if (inventorySequence != null) {
            InventorySequence sequence = inventorySequence;
            if (sequence.used && sameItemType(sequence.originalItem, afterSelected)) {
                completed = new SwapPattern(
                        sequence.sequenceId, sequence.path,
                        sequence.originalSlot, sequence.originalSlot,
                        sequence.switchToRocketTick, sequence.useRocketTick, tick,
                        sequence.switchToRocketNanos, sequence.useRocketNanos,
                        receivedNanos, sequence.glidingAtUse || isGliding,
                        inventoryReliable, itemKey(sequence.originalItem));
                inventorySequence = null;
            } else if (!isFirework(afterSelected)) {
                inventorySequence = null;
            }
        }

        if (completed == null && inventoryReliable
                && isCombatWeapon(beforeSelected) && isFirework(afterSelected)) {
            inventorySequence = new InventorySequence(
                    nextSequenceId(), path, currentSlot, copy(beforeSelected),
                    tick, receivedNanos);
        }
        return completed;
    }

    private void markUsed(Sequence sequence, long tick, long receivedNanos) {
        sequence.used = true;
        sequence.useRocketTick = tick;
        sequence.useRocketNanos = receivedNanos;
        sequence.glidingAtUse = isGliding;
    }

    private void queueMainHandInteraction(long sequenceId,
                                          long receivedNanos) {
        pendingMainHandInteractions.addLast(
                new PendingMainHandInteraction(sequenceId, receivedNanos));
        while (pendingMainHandInteractions.size() > 32) {
            pendingMainHandInteractions.removeFirst();
        }
    }

    private long nextSequenceId() {
        if (nextSwapSequenceId == Long.MAX_VALUE) nextSwapSequenceId = 1L;
        return nextSwapSequenceId++;
    }

    private static int bukkitStorageToProtocol(int slot) {
        if (slot >= 0 && slot <= 8) return HOTBAR_PACKET_START + slot;
        if (slot >= 9 && slot <= 35) return slot;
        return -1;
    }

    private static int directPlayerSlotToProtocol(int slot) {
        if (slot >= 0 && slot <= 8) return HOTBAR_PACKET_START + slot;
        if (slot >= 9 && slot <= 35) return slot;
        return switch (slot) {
            case 36 -> 8;
            case 37 -> 7;
            case 38 -> 6;
            case 39 -> 5;
            case 40 -> OFFHAND_PACKET_SLOT;
            default -> -1;
        };
    }

    private static boolean validHotbarSlot(int slot) {
        return slot >= 0 && slot <= 8;
    }

    private static boolean isFirework(ItemStack item) {
        return item != null && !item.isEmpty()
                && item.getType() == ItemTypes.FIREWORK_ROCKET;
    }

    private static boolean isCombatWeapon(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        String key = itemKey(item);
        return key.endsWith("_sword") || key.endsWith("_axe")
                || key.equals("minecraft:trident")
                || key.equals("minecraft:mace");
    }

    private static String itemKey(ItemStack item) {
        return item == null || item.isEmpty() || item.getType() == null
                || item.getType().getName() == null
                ? "minecraft:air" : item.getType().getName().toString();
    }

    private static boolean sameItemType(ItemStack first, ItemStack second) {
        if ((first == null || first.isEmpty())
                && (second == null || second.isEmpty())) return true;
        return first != null && second != null
                && !first.isEmpty() && !second.isEmpty()
                && first.getType() == second.getType();
    }

    private static ItemStack fromBukkit(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        return copy(SpigotConversionUtil.fromBukkitItemStack(item));
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.isEmpty() ? null : item.copy();
    }

    private static ItemStack[] copyItems(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = copy(source[i]);
        return result;
    }

    private abstract static class Sequence {
        final long sequenceId;
        final ItemStack originalItem;
        final long switchToRocketTick;
        final long switchToRocketNanos;
        boolean used;
        long useRocketTick = Long.MIN_VALUE;
        long useRocketNanos = Long.MIN_VALUE;
        boolean glidingAtUse;

        private Sequence(long sequenceId, ItemStack originalItem,
                         long tick, long receivedNanos) {
            this.sequenceId = sequenceId;
            this.originalItem = originalItem;
            this.switchToRocketTick = tick;
            this.switchToRocketNanos = receivedNanos;
        }
    }

    private static final class HotbarSequence extends Sequence {
        private final int originalSlot;
        private final int rocketSlot;
        private final long inventoryMutationSequence;

        private HotbarSequence(long sequenceId,
                               int originalSlot, int rocketSlot,
                               ItemStack originalItem,
                               long tick, long receivedNanos,
                               long inventoryMutationSequence) {
            super(sequenceId, originalItem, tick, receivedNanos);
            this.originalSlot = originalSlot;
            this.rocketSlot = rocketSlot;
            this.inventoryMutationSequence = inventoryMutationSequence;
        }
    }

    private static final class InventorySequence extends Sequence {
        private final SwapPath path;
        private final int originalSlot;

        private InventorySequence(long sequenceId, SwapPath path,
                                  int originalSlot, ItemStack originalItem,
                                  long tick, long receivedNanos) {
            super(sequenceId, originalItem, tick, receivedNanos);
            this.path = path;
            this.originalSlot = originalSlot;
        }
    }

    private record PendingMainHandInteraction(long sequenceId,
                                              long useNanos) {
    }

    private record PendingBoostCandidate(long sequenceId,
                                         long interactionNanos) {
    }

    public record UseSnapshot(boolean fireworkAtUse, boolean stateKnown,
                              int currentSlot, long clientTick,
                              boolean gliding) {
    }

    public enum SwapPath {
        HOTBAR,
        INVENTORY,
        PICK_ITEM,
        OFFHAND
    }

    public record SwapPattern(long sequenceId, SwapPath path,
                              int originalSlot, int rocketSlot,
                              long switchToRocketTick, long useRocketTick,
                              long switchBackTick,
                              long switchToRocketNanos, long useRocketNanos,
                              long switchBackNanos, boolean gliding,
                              boolean inventoryReliable,
                              String originalItemKey) {
        public boolean sameClientTick() {
            return switchToRocketTick == useRocketTick
                    && useRocketTick == switchBackTick;
        }

        public long tickSpan() {
            return Math.max(0L, switchBackTick - switchToRocketTick);
        }

        public long elapsedMillis() {
            return Math.max(0L,
                    (switchBackNanos - switchToRocketNanos) / 1_000_000L);
        }
    }

}
