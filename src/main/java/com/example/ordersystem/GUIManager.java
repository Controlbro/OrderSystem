package com.example.ordersystem;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.ShulkerBox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

/**
 * Manages all GUI interactions for orders.
 */
public class GUIManager implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int DELIVERY_SIZE = 54;
    private static final int DELIVERY_CONTENTS_END = 45;
    private final OrderSystemPlugin plugin;
    private final OrderManager orderManager;
    private final Economy economy;
    private final Map<UUID, BoardSession> boardSessions = new HashMap<>();
    private final Map<Integer, UUID> deliveryLocks = new HashMap<>();
    private final Set<UUID> deliveryClosing = new HashSet<>();
    private final List<Material> selectableMaterials = new ArrayList<>();

    public GUIManager(OrderSystemPlugin plugin, OrderManager orderManager, Economy economy) {
        this.plugin = plugin;
        this.orderManager = orderManager;
        this.economy = economy;
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isLegacy()) {
                selectableMaterials.add(material);
            }
        }
    }

    public void openOrderBoard(Player player, int page, Material filter) {
        openOrderBoard(player, page, filter, null);
    }

    public void openOrderBoard(Player player, int page, Material filter, UUID ownerFilter) {
        List<Order> orders;
        if (ownerFilter != null) {
            orders = new ArrayList<>();
            for (Order order : orderManager.getOrdersSorted()) {
                if (order.getOwnerUuid().equals(ownerFilter)) {
                    if (filter == null || order.getMaterial() == filter) {
                        orders.add(order);
                    }
                }
            }
        } else {
            orders = filter == null ? orderManager.getOrdersSorted() : orderManager.getOrdersSortedFiltered(filter);
        }
        int maxPage = Math.max(1, (int) Math.ceil(orders.size() / (double) PAGE_SIZE));
        int currentPage = Math.min(Math.max(page, 1), maxPage);
        String title = filter == null
                ? "ORDERS (Page " + currentPage + ")"
                : "ORDERS - " + filter.name() + " (Page " + currentPage + ")";
        Inventory inventory = Bukkit.createInventory(new OrderBoardHolder(), 54, title);

        int startIndex = (currentPage - 1) * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, orders.size());
        List<Integer> orderIds = new ArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            Order order = orders.get(i);
            ItemStack item = new ItemStack(order.getMaterial());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + order.getOwnerName() + "'s Order");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + NumberFormatter.formatCompact(order.getTotalQuantity()) + " " + formatMaterialName(order.getMaterial()));
            lore.add(ChatColor.GREEN + "$" + NumberFormatter.formatCompact(order.getPricePerItem()) + " each");
            long delivered = order.getTotalQuantity() - order.getRemainingQuantity();
            lore.add(ChatColor.GRAY + NumberFormatter.formatCompact(delivered) + " / " + NumberFormatter.formatCompact(order.getTotalQuantity()) + " Delivered");
            lore.add(ChatColor.GREEN + "$" + NumberFormatter.formatCompact(order.getTotalPaid()) + " / $" + NumberFormatter.formatCompact(order.getTotalEscrow()) + " Paid");
            lore.add(ChatColor.GRAY + formatRemainingTime(order));
            lore.add(ChatColor.GRAY + "Status: " + (order.getStatus() == OrderStatus.ACTIVE ? ChatColor.GREEN + "Active" : ChatColor.GOLD + "Completed"));
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(orderIds.size(), item);
            orderIds.add(order.getId());
        }

        inventory.setItem(45, createButton(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        inventory.setItem(48, createButton(Material.COMPASS, ChatColor.AQUA + "Search"));
        inventory.setItem(49, createButton(Material.MAP, ChatColor.GREEN + "Refresh"));
        inventory.setItem(50, createButton(Material.BOOK, ChatColor.LIGHT_PURPLE + "My Orders"));
        inventory.setItem(53, createButton(Material.ARROW, ChatColor.YELLOW + "Next Page"));

        BoardSession session = new BoardSession(currentPage, filter, ownerFilter, orderIds);
        boardSessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    public void openMaterialSelector(Player player, boolean forSearch) {
        openMaterialSelector(player, forSearch, 1);
    }

    public void openMaterialSelector(Player player, boolean forSearch, int page) {
        int maxPage = Math.max(1, (int) Math.ceil(selectableMaterials.size() / (double) PAGE_SIZE));
        int currentPage = Math.min(Math.max(page, 1), maxPage);
        Inventory inventory = Bukkit.createInventory(new MaterialSelectorHolder(forSearch, currentPage), 54,
                forSearch ? "Search Material" : "Select Material");
        int startIndex = (currentPage - 1) * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, selectableMaterials.size());
        for (int i = startIndex; i < endIndex; i++) {
            inventory.setItem(i - startIndex, new ItemStack(selectableMaterials.get(i)));
        }
        inventory.setItem(45, createButton(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        inventory.setItem(53, createButton(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        player.openInventory(inventory);
    }

    public void openDeliveryInventory(Player player, Order order) {
        if (order.getStatus() != OrderStatus.ACTIVE) {
            player.sendMessage(ChatColor.RED + "Order is no longer active.");
            return;
        }
        UUID current = deliveryLocks.get(order.getId());
        if (current != null && !current.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Someone else is already delivering to this order.");
            return;
        }
        deliveryLocks.put(order.getId(), player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(new DeliveryHolder(order.getId()), DELIVERY_SIZE,
                "Deliver Items \u2192 Order #" + order.getId());
        ItemStack filler = createButton(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = DELIVERY_CONTENTS_END; i < DELIVERY_SIZE; i++) {
            inventory.setItem(i, filler);
        }
        ItemStack info = createButton(Material.PAPER,
                ChatColor.YELLOW + "Deliver " + formatMaterialName(order.getMaterial()),
                ChatColor.GRAY + "Remaining: " + NumberFormatter.formatCompact(order.getRemainingQuantity()),
                ChatColor.GRAY + "Price per item: $" + NumberFormatter.formatCompact(order.getPricePerItem()));
        inventory.setItem(47, info);
        inventory.setItem(45, createButton(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "Cancel"));
        inventory.setItem(53, createButton(Material.GREEN_STAINED_GLASS_PANE, ChatColor.GREEN + "Deliver Items"));
        player.openInventory(inventory);
    }

    public void openCollectGUI(Player player, Order order, int page) {
        int maxPage = Math.max(1, (int) Math.ceil(order.getStoredItems().size() / (double) PAGE_SIZE));
        int currentPage = Math.min(Math.max(page, 1), maxPage);
        Inventory inventory = Bukkit.createInventory(new CollectHolder(order.getId(), currentPage), 54, "ORDERS \u2192 Collect Items");
        int startIndex = (currentPage - 1) * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, order.getStoredItems().size());
        for (int i = startIndex; i < endIndex; i++) {
            inventory.setItem(i - startIndex, order.getStoredItems().get(i));
        }
        ItemStack info = createButton(Material.PAPER, ChatColor.YELLOW + "Stored Items",
                ChatColor.GRAY + "Total: " + NumberFormatter.formatCompact(calculateStoredAmount(order)));
        inventory.setItem(4, info);
        inventory.setItem(45, createButton(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        inventory.setItem(49, createButton(Material.EMERALD, ChatColor.GREEN + "Drop Loot", ChatColor.GRAY + "Collect all items on this page"));
        inventory.setItem(53, createButton(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        player.openInventory(inventory);
    }

    public void openConfirmCreation(Player player, OrderCreationSession session, double listingFee) {
        Inventory inventory = Bukkit.createInventory(new ConfirmCreateHolder(), 27, "Confirm Order");
        ItemStack item = new ItemStack(session.getMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + formatMaterialName(session.getMaterial()));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Quantity: " + NumberFormatter.formatCompact(session.getQuantity()));
        lore.add(ChatColor.GRAY + "Total Price: $" + NumberFormatter.formatCompact(session.getTotalPrice()));
        lore.add(ChatColor.GRAY + "Price per item: $" + NumberFormatter.formatCompact(session.getPricePerItem()));
        lore.add(ChatColor.GRAY + "Total Escrow: $" + NumberFormatter.formatCompact(session.getTotalPrice()));
        lore.add(ChatColor.GRAY + "Listing Fee: $" + NumberFormatter.formatCompact(listingFee));
        meta.setLore(lore);
        item.setItemMeta(meta);
        inventory.setItem(13, item);
        inventory.setItem(11, createButton(Material.GREEN_STAINED_GLASS_PANE, ChatColor.GREEN + "Confirm"));
        inventory.setItem(15, createButton(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "Cancel"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof OrderBoardHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getInventory().getSize()) {
                handleOrderBoardClick(player, event.getRawSlot(), event.getClick());
            }
        } else if (holder instanceof MaterialSelectorHolder selector) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getInventory().getSize()) {
                handleMaterialSelector(player, event.getCurrentItem(), selector, event.getRawSlot());
            }
        } else if (holder instanceof DeliveryHolder deliveryHolder) {
            handleDeliveryClick(player, deliveryHolder, event);
        } else if (holder instanceof CollectHolder collectHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getInventory().getSize()) {
                handleCollectClick(player, collectHolder, event.getRawSlot(), event.getClick());
            }
        } else if (holder instanceof ConfirmCreateHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getInventory().getSize()) {
                handleConfirmCreateClick(player, event.getRawSlot());
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof OrderBoardHolder) {
            boardSessions.remove(event.getPlayer().getUniqueId());
            return;
        }
        if (holder instanceof DeliveryHolder deliveryHolder && event.getPlayer() instanceof Player player) {
            if (deliveryClosing.remove(player.getUniqueId())) {
                releaseDeliveryLock(deliveryHolder.orderId());
                return;
            }
            returnDeliveryItems(player, collectInventoryItems(event.getInventory()));
            releaseDeliveryLock(deliveryHolder.orderId());
        }
    }

    private void handleOrderBoardClick(Player player, int slot, ClickType clickType) {
        BoardSession session = boardSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (slot == 45) {
            openOrderBoard(player, session.page() - 1, session.filter(), session.ownerFilter());
            return;
        }
        if (slot == 48) {
            if (clickType == ClickType.RIGHT && session.filter() != null) {
                openOrderBoard(player, 1, null, session.ownerFilter());
            } else {
                plugin.beginSearch(player, session.ownerFilter());
            }
            return;
        }
        if (slot == 49) {
            openOrderBoard(player, session.page(), session.filter(), session.ownerFilter());
            return;
        }
        if (slot == 50) {
            if (session.ownerFilter() != null && session.ownerFilter().equals(player.getUniqueId())) {
                openOrderBoard(player, 1, session.filter(), null);
            } else {
                openOrderBoard(player, 1, session.filter(), player.getUniqueId());
            }
            return;
        }
        if (slot == 53) {
            openOrderBoard(player, session.page() + 1, session.filter(), session.ownerFilter());
            return;
        }
        int index = slot;
        if (index >= 0 && index < session.orderIds().size()) {
            int orderId = session.orderIds().get(index);
            orderManager.getOrder(orderId).ifPresent(order -> openDeliveryInventory(player, order));
        }
    }

    private void handleMaterialSelector(Player player, ItemStack clicked, MaterialSelectorHolder holder, int slot) {
        if (slot == 45) {
            openMaterialSelector(player, holder.forSearch(), holder.page() - 1);
            return;
        }
        if (slot == 53) {
            openMaterialSelector(player, holder.forSearch(), holder.page() + 1);
            return;
        }
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        Material material = clicked.getType();
        if (holder.forSearch()) {
            Optional<UUID> ownerFilter = plugin.consumeSearchOwnerFilter(player);
            openOrderBoard(player, 1, material, ownerFilter.orElse(null));
        } else {
            plugin.setSelectedMaterial(player, material);
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "Selected material: " + formatMaterialName(material));
            player.sendMessage(ChatColor.GRAY + "Enter quantity in chat.");
        }
    }

    private void handleDeliveryClick(Player player, DeliveryHolder holder, InventoryClickEvent event) {
        int slot = event.getSlot();
        if (slot >= DELIVERY_CONTENTS_END) {
            event.setCancelled(true);
            if (slot == 45) {
                player.closeInventory();
            } else if (slot == 53) {
                handleDeliverySubmit(player, holder, event.getInventory());
            }
            return;
        }
    }

    private void handleDeliverySubmit(Player player, DeliveryHolder holder, Inventory inventory) {
        Optional<Order> optionalOrder = orderManager.getOrder(holder.orderId());
        if (optionalOrder.isEmpty()) {
            player.closeInventory();
            return;
        }
        Order order = optionalOrder.get();
        if (order.getStatus() != OrderStatus.ACTIVE) {
            player.sendMessage(ChatColor.RED + "Order is no longer active.");
            returnDeliveryItems(player, collectInventoryItems(inventory));
            player.closeInventory();
            return;
        }
        DeliveryExtraction extraction = extractDeliverables(inventory, order.getMaterial(), order.getRemainingQuantity());
        if (extraction.amountDelivered() <= 0) {
            player.sendMessage(ChatColor.RED + "You have no items to deliver.");
            returnDeliveryItems(player, collectInventoryItems(inventory));
            player.closeInventory();
            return;
        }
        OrderManager.DeliveryResult result = orderManager.deliverItems(player, order, extraction.amountDelivered(), economy);
        if (result.isSuccess()) {
            plugin.getStorageManager().requestSaveAsync(orderManager);
            player.sendMessage(ChatColor.GREEN + "You delivered " + NumberFormatter.formatCompact(result.getAmountDelivered()) + " "
                    + formatMaterialName(order.getMaterial()) + " and received $" + NumberFormatter.formatCompact(result.getPayout()));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1F, 1F);
        } else {
            player.sendMessage(ChatColor.RED + result.getMessage());
        }
        deliveryClosing.add(player.getUniqueId());
        returnDeliveryItems(player, extraction.leftovers());
        player.closeInventory();
    }

    private void handleCollectClick(Player player, CollectHolder holder, int slot, ClickType clickType) {
        Optional<Order> optionalOrder = orderManager.getOrder(holder.orderId());
        if (optionalOrder.isEmpty()) {
            player.closeInventory();
            return;
        }
        Order order = optionalOrder.get();
        if (!orderManager.canCollect(player, order)) {
            player.sendMessage(ChatColor.RED + "You are not trusted to collect items.");
            player.closeInventory();
            return;
        }
        if (slot == 45) {
            openCollectGUI(player, order, holder.page() - 1);
            return;
        }
        if (slot == 53) {
            openCollectGUI(player, order, holder.page() + 1);
            return;
        }
        if (slot == 49) {
            collectPage(player, order, holder.page());
            return;
        }
        if (slot >= 0 && slot < PAGE_SIZE) {
            int index = (holder.page() - 1) * PAGE_SIZE + slot;
            if (index < order.getStoredItems().size()) {
                ItemStack stack = order.getStoredItems().remove(index);
                Map<Integer, ItemStack> remaining = player.getInventory().addItem(stack);
                if (!remaining.isEmpty()) {
                    for (ItemStack leftover : remaining.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
                plugin.getStorageManager().requestSaveAsync(orderManager);
                openCollectGUI(player, order, holder.page());
            }
        }
    }

    private void handleConfirmCreateClick(Player player, int slot) {
        if (slot == 11) {
            plugin.confirmCreate(player);
        } else if (slot == 15) {
            plugin.cancelCreate(player);
        }
    }

    private void collectPage(Player player, Order order, int page) {
        int startIndex = (page - 1) * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, order.getStoredItems().size());
        if (startIndex >= endIndex) {
            return;
        }
        List<ItemStack> toCollect = new ArrayList<>(order.getStoredItems().subList(startIndex, endIndex));
        order.getStoredItems().subList(startIndex, endIndex).clear();
        for (ItemStack stack : toCollect) {
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(stack);
            if (!remaining.isEmpty()) {
                for (ItemStack leftover : remaining.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }
        plugin.getStorageManager().requestSaveAsync(orderManager);
        openCollectGUI(player, order, page);
    }

    private long calculateStoredAmount(Order order) {
        long total = 0;
        for (ItemStack stack : order.getStoredItems()) {
            total += stack.getAmount();
        }
        return total;
    }

    private String formatRemainingTime(Order order) {
        if (order.getStatus() == OrderStatus.ACTIVE) {
            return "Time remaining: Active";
        }
        long remainingMillis = order.getExpirationTimestamp() - Instant.now().toEpochMilli();
        if (remainingMillis <= 0) {
            return "Expired";
        }
        Duration duration = Duration.ofMillis(remainingMillis);
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        return "Time remaining: " + days + "d " + hours + "h";
    }

    private ItemStack createButton(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            List<String> loreList = new ArrayList<>();
            for (String line : lore) {
                loreList.add(line);
            }
            meta.setLore(loreList);
        }
        item.setItemMeta(meta);
        return item;
    }

    public String formatMaterialName(Material material) {
        return material.name().toLowerCase().replace('_', ' ');
    }

    public Material findExactMaterial(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = normalize(input);
        for (Material material : selectableMaterials) {
            if (normalize(material.name()).equals(normalized)) {
                return material;
            }
        }
        return null;
    }

    public Material findClosestMaterial(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = normalize(input);
        Material best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Material material : selectableMaterials) {
            String candidate = normalize(material.name());
            if (candidate.equals(normalized)) {
                return material;
            }
            if (candidate.contains(normalized)) {
                return material;
            }
            int distance = levenshteinDistance(normalized, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = material;
            }
        }
        return best;
    }

    public List<Material> suggestMaterials(String input, int limit) {
        if (input == null || input.isBlank() || limit <= 0) {
            return List.of();
        }
        String normalized = normalize(input);
        List<MaterialSuggestion> suggestions = new ArrayList<>();
        for (Material material : selectableMaterials) {
            String candidate = normalize(material.name());
            int score;
            if (candidate.contains(normalized) || normalized.contains(candidate)) {
                score = 0;
            } else {
                score = levenshteinDistance(normalized, candidate);
            }
            suggestions.add(new MaterialSuggestion(material, score));
        }
        suggestions.sort((a, b) -> {
            int scoreComparison = Integer.compare(a.score(), b.score());
            if (scoreComparison != 0) {
                return scoreComparison;
            }
            return a.material().name().compareTo(b.material().name());
        });
        List<Material> result = new ArrayList<>();
        for (MaterialSuggestion suggestion : suggestions) {
            if (result.size() >= limit) {
                break;
            }
            result.add(suggestion.material());
        }
        return result;
    }

    public boolean isExactMaterialMatch(String input, Material material) {
        if (input == null || material == null) {
            return false;
        }
        return normalize(input).equals(normalize(material.name()));
    }

    private String normalize(String value) {
        return value.toLowerCase().replace("_", "").replace(" ", "");
    }

    private int levenshteinDistance(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) {
            costs[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }

    private DeliveryExtraction extractDeliverables(Inventory inventory, Material material, long limit) {
        List<ItemStack> leftovers = new ArrayList<>();
        long delivered = 0;
        for (int i = 0; i < DELIVERY_CONTENTS_END; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            if (isShulkerBox(stack.getType())) {
                DeliveryExtraction shulkerExtraction = extractFromShulker(stack, material, limit - delivered);
                delivered += shulkerExtraction.amountDelivered();
                leftovers.addAll(shulkerExtraction.leftovers());
            } else if (stack.getType() == material) {
                int remove = (int) Math.min(stack.getAmount(), Math.max(0, limit - delivered));
                int remaining = stack.getAmount() - remove;
                delivered += remove;
                if (remaining > 0) {
                    leftovers.add(new ItemStack(material, remaining));
                }
            } else {
                leftovers.add(stack);
            }
        }
        for (int i = 0; i < DELIVERY_CONTENTS_END; i++) {
            inventory.setItem(i, null);
        }
        return new DeliveryExtraction(delivered, leftovers);
    }

    private DeliveryExtraction extractFromShulker(ItemStack shulkerStack, Material material, long limit) {
        ItemStack updatedShulker = shulkerStack.clone();
        ItemMeta meta = updatedShulker.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return new DeliveryExtraction(0L, List.of(updatedShulker));
        }
        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return new DeliveryExtraction(0L, List.of(updatedShulker));
        }
        ItemStack[] contents = shulkerBox.getInventory().getContents();
        long delivered = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack content = contents[i];
            if (content == null || content.getType() != material) {
                continue;
            }
            int remove = (int) Math.min(content.getAmount(), Math.max(0, limit - delivered));
            int remaining = content.getAmount() - remove;
            delivered += remove;
            if (remaining <= 0) {
                contents[i] = null;
            } else {
                content.setAmount(remaining);
            }
            if (delivered >= limit) {
                break;
            }
        }
        shulkerBox.getInventory().setContents(contents);
        blockStateMeta.setBlockState(shulkerBox);
        updatedShulker.setItemMeta(blockStateMeta);
        return new DeliveryExtraction(delivered, List.of(updatedShulker));
    }

    private boolean isShulkerBox(Material material) {
        return material.name().endsWith("SHULKER_BOX");
    }

    private List<ItemStack> collectInventoryItems(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < DELIVERY_CONTENTS_END; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && stack.getType() != Material.AIR) {
                items.add(stack);
            }
            inventory.setItem(i, null);
        }
        return items;
    }

    private void returnDeliveryItems(Player player, List<ItemStack> items) {
        for (ItemStack stack : items) {
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(stack);
            if (!remaining.isEmpty()) {
                for (ItemStack leftover : remaining.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }
    }

    private void releaseDeliveryLock(int orderId) {
        deliveryLocks.remove(orderId);
    }

    private record BoardSession(int page, Material filter, UUID ownerFilter, List<Integer> orderIds) {
    }

    private record OrderBoardHolder() implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record MaterialSelectorHolder(boolean forSearch, int page) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record DeliveryHolder(int orderId) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record CollectHolder(int orderId, int page) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record ConfirmCreateHolder() implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record DeliveryExtraction(long amountDelivered, List<ItemStack> leftovers) {
    }

    private record MaterialSuggestion(Material material, int score) {
    }
}
