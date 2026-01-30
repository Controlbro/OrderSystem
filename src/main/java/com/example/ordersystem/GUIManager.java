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
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages all GUI interactions for orders.
 */
public class GUIManager implements Listener {
    private static final int PAGE_SIZE = 45;
    private final OrderSystemPlugin plugin;
    private final OrderManager orderManager;
    private final Economy economy;
    private final Map<UUID, BoardSession> boardSessions = new HashMap<>();
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

    public void openDeliveryConfirm(Player player, Order order) {
        Inventory inventory = Bukkit.createInventory(new DeliveryConfirmHolder(order.getId()), 27, "Confirm Delivery");
        ItemStack filler = createButton(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, filler);
        }
        ItemStack preview = new ItemStack(order.getMaterial());
        ItemMeta previewMeta = preview.getItemMeta();
        previewMeta.setDisplayName(ChatColor.YELLOW + formatMaterialName(order.getMaterial()));
        preview.setItemMeta(previewMeta);
        inventory.setItem(4, preview);

        long deliverable = getDeliverableAmount(player, order);
        double payout = deliverable * order.getPricePerItem();
        ItemStack confirm = createButton(Material.GREEN_STAINED_GLASS_PANE,
                ChatColor.GREEN + "CONFIRM",
                ChatColor.GRAY + "Click to deliver items (" + ChatColor.GREEN + "$" + NumberFormatter.formatCompact(payout) + ChatColor.GRAY + ")");
        ItemStack cancel = createButton(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "CANCEL");
        inventory.setItem(11, confirm);
        inventory.setItem(15, cancel);
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
        lore.add(ChatColor.GRAY + "Price: $" + NumberFormatter.formatCompact(session.getPricePerItem()));
        double escrow = session.getQuantity() * session.getPricePerItem();
        lore.add(ChatColor.GRAY + "Total Escrow: $" + NumberFormatter.formatCompact(escrow));
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
            handleOrderBoardClick(player, event.getSlot(), event.getClick());
        } else if (holder instanceof MaterialSelectorHolder selector) {
            event.setCancelled(true);
            handleMaterialSelector(player, event.getCurrentItem(), selector, event.getSlot());
        } else if (holder instanceof DeliveryConfirmHolder confirmHolder) {
            event.setCancelled(true);
            handleDeliveryConfirm(player, confirmHolder, event.getSlot());
        } else if (holder instanceof CollectHolder collectHolder) {
            event.setCancelled(true);
            handleCollectClick(player, collectHolder, event.getSlot(), event.getClick());
        } else if (holder instanceof ConfirmCreateHolder) {
            event.setCancelled(true);
            handleConfirmCreateClick(player, event.getSlot());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof OrderBoardHolder) {
            boardSessions.remove(event.getPlayer().getUniqueId());
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
                openMaterialSelector(player, true);
            }
            return;
        }
        if (slot == 49) {
            openOrderBoard(player, session.page(), session.filter(), session.ownerFilter());
            return;
        }
        if (slot == 50) {
            openOrderBoard(player, 1, session.filter(), player.getUniqueId());
            return;
        }
        if (slot == 53) {
            openOrderBoard(player, session.page() + 1, session.filter(), session.ownerFilter());
            return;
        }
        int index = slot;
        if (index >= 0 && index < session.orderIds().size()) {
            int orderId = session.orderIds().get(index);
            orderManager.getOrder(orderId).ifPresent(order -> openDeliveryConfirm(player, order));
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
            openOrderBoard(player, 1, material);
        } else {
            plugin.setSelectedMaterial(player, material);
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "Selected material: " + formatMaterialName(material));
            player.sendMessage(ChatColor.GRAY + "Enter quantity in chat.");
        }
    }

    private void handleDeliveryConfirm(Player player, DeliveryConfirmHolder holder, int slot) {
        Optional<Order> optionalOrder = orderManager.getOrder(holder.orderId());
        if (optionalOrder.isEmpty()) {
            player.closeInventory();
            return;
        }
        Order order = optionalOrder.get();
        if (slot == 11) {
            long deliverable = getDeliverableAmount(player, order);
            if (deliverable <= 0) {
                player.sendMessage(ChatColor.RED + "You have no items to deliver.");
                player.closeInventory();
                return;
            }
            OrderManager.DeliveryResult result = orderManager.deliverFromPlayer(player, order, economy);
            if (result.isSuccess()) {
                plugin.getStorageManager().requestSaveAsync(orderManager);
                player.sendMessage(ChatColor.GREEN + "You delivered " + NumberFormatter.formatCompact(result.getAmountDelivered()) + " "
                        + formatMaterialName(order.getMaterial()) + " and received $" + NumberFormatter.formatCompact(result.getPayout()));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1F, 1F);
            } else {
                player.sendMessage(ChatColor.RED + result.getMessage());
            }
            player.closeInventory();
        } else if (slot == 15) {
            player.closeInventory();
        }
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

    private long getDeliverableAmount(Player player, Order order) {
        long count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == order.getMaterial()) {
                count += stack.getAmount();
            }
        }
        return Math.min(count, order.getRemainingQuantity());
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

    private String formatMaterialName(Material material) {
        return material.name().toLowerCase().replace('_', ' ');
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

    private record DeliveryConfirmHolder(int orderId) implements InventoryHolder {
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
}
