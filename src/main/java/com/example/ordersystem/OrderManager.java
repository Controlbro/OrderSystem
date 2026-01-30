package com.example.ordersystem;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages order lifecycle and concurrency-safe delivery handling.
 */
public class OrderManager {
    private final OrderSystemPlugin plugin;
    private final Map<Integer, Order> orders = new ConcurrentHashMap<>();
    private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public OrderManager(OrderSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public Map<Integer, Order> getOrders() {
        return orders;
    }

    public Map<Integer, OrderSnapshot> snapshotOrders() {
        Map<Integer, OrderSnapshot> snapshot = new ConcurrentHashMap<>();
        for (Order order : orders.values()) {
            ReentrantLock lock = locks.computeIfAbsent(order.getId(), id -> new ReentrantLock());
            lock.lock();
            try {
                snapshot.put(order.getId(), new OrderSnapshot(order.getId(),
                        order.getOwnerUuid(),
                        order.getOwnerName(),
                        order.getMaterial(),
                        order.getTotalQuantity(),
                        order.getRemainingQuantity(),
                        order.getPricePerItem(),
                        order.getTotalEscrow(),
                        order.getTotalPaid(),
                        order.getCreatedTimestamp(),
                        order.getExpirationTimestamp(),
                        order.getStatus(),
                        new ArrayList<>(order.getStoredItems()),
                        new ArrayList<>(order.getTrustedPlayers())));
            } finally {
                lock.unlock();
            }
        }
        return snapshot;
    }

    public void addLoadedOrder(Order order) {
        orders.put(order.getId(), order);
        locks.put(order.getId(), new ReentrantLock());
        nextId.updateAndGet(current -> Math.max(current, order.getId() + 1));
    }

    public Material parseMaterial(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) {
            return Material.STONE;
        }
        return material;
    }

    public int getActiveOrderCount(UUID ownerUuid) {
        int count = 0;
        for (Order order : orders.values()) {
            if (order.getOwnerUuid().equals(ownerUuid) && order.getStatus() == OrderStatus.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    public Order createOrder(Player player, Material material, long quantity, double pricePerItem) {
        int id = nextId.getAndIncrement();
        double totalEscrow = quantity * pricePerItem;
        long created = Instant.now().toEpochMilli();
        Order order = new Order(id, player.getUniqueId(), player.getName(), material, quantity, quantity,
                pricePerItem, totalEscrow, 0D, created, 0L, OrderStatus.ACTIVE, new ArrayList<>(), new ArrayList<>());
        orders.put(order.getId(), order);
        locks.put(order.getId(), new ReentrantLock());
        return order;
    }

    public Optional<Order> getOrder(int id) {
        return Optional.ofNullable(orders.get(id));
    }

    public List<Order> getOrdersSorted() {
        List<Order> list = new ArrayList<>(orders.values());
        list.sort(Comparator.comparingLong(Order::getCreatedTimestamp).reversed());
        return list;
    }

    public List<Order> getOrdersSortedFiltered(Material material) {
        List<Order> list = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.getMaterial() == material) {
                list.add(order);
            }
        }
        list.sort(Comparator.comparingLong(Order::getCreatedTimestamp).reversed());
        return list;
    }

    public DeliveryResult deliverItems(Player player, Order order, long deliverAmount, Economy economy) {
        ReentrantLock lock = locks.computeIfAbsent(order.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            if (order.getStatus() != OrderStatus.ACTIVE) {
                return DeliveryResult.failed("Order is no longer active.");
            }
            long remaining = order.getRemainingQuantity();
            long actual = Math.min(remaining, deliverAmount);
            if (actual <= 0) {
                return DeliveryResult.failed("No remaining quantity to deliver.");
            }
            double payout = actual * order.getPricePerItem();
            order.setRemainingQuantity(remaining - actual);
            order.setTotalPaid(order.getTotalPaid() + payout);
            addStoredItems(order, actual);
            economy.depositPlayer(player, payout);
            if (order.getRemainingQuantity() <= 0) {
                completeOrder(order);
            }
            return DeliveryResult.success(actual, payout);
        } finally {
            lock.unlock();
        }
    }

    public DeliveryResult deliverFromPlayer(Player player, Order order, Economy economy) {
        ReentrantLock lock = locks.computeIfAbsent(order.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            if (order.getStatus() != OrderStatus.ACTIVE) {
                return DeliveryResult.failed("Order is no longer active.");
            }
            long deliverable = countItems(player, order.getMaterial());
            long actual = Math.min(deliverable, order.getRemainingQuantity());
            if (actual <= 0) {
                return DeliveryResult.failed("No remaining quantity to deliver.");
            }
            removeItems(player, order.getMaterial(), actual);
            double payout = actual * order.getPricePerItem();
            order.setRemainingQuantity(order.getRemainingQuantity() - actual);
            order.setTotalPaid(order.getTotalPaid() + payout);
            addStoredItems(order, actual);
            economy.depositPlayer(player, payout);
            if (order.getRemainingQuantity() <= 0) {
                completeOrder(order);
            }
            return DeliveryResult.success(actual, payout);
        } finally {
            lock.unlock();
        }
    }

    public void completeOrder(Order order) {
        order.setStatus(OrderStatus.COMPLETED);
        long retentionDays = plugin.getConfig().getLong("completion-retention-days", 7L);
        long expiration = Instant.now().plus(Duration.ofDays(retentionDays)).toEpochMilli();
        order.setExpirationTimestamp(expiration);
        double threshold = plugin.getConfig().getDouble("completion-broadcast-threshold", 0D);
        if (order.getTotalEscrow() >= threshold && threshold > 0D) {
            Bukkit.broadcastMessage(order.getOwnerName() + "'s order for " + order.getMaterial().name() + " has been completed!");
        }
    }

    public void removeExpiredOrders() {
        List<Integer> toRemove = new ArrayList<>();
        for (Order order : orders.values()) {
            if (order.isExpired()) {
                toRemove.add(order.getId());
            }
        }
        for (Integer id : toRemove) {
            orders.remove(id);
            locks.remove(id);
        }
    }

    public boolean canCollect(Player player, Order order) {
        if (order.getOwnerUuid().equals(player.getUniqueId())) {
            return true;
        }
        if (order.getTrustedPlayers().contains(player.getUniqueId())) {
            return true;
        }
        return player.hasPermission("ordersystem.collect.trusted");
    }

    public void addTrustedPlayer(Order order, OfflinePlayer target) {
        if (!order.getTrustedPlayers().contains(target.getUniqueId())) {
            order.getTrustedPlayers().add(target.getUniqueId());
        }
    }

    private void addStoredItems(Order order, long amount) {
        long remaining = amount;
        int maxStack = order.getMaterial().getMaxStackSize();
        while (remaining > 0) {
            int stack = (int) Math.min(remaining, maxStack);
            order.getStoredItems().add(new ItemStack(order.getMaterial(), stack));
            remaining -= stack;
        }
    }

    private long countItems(Player player, Material material) {
        long count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, Material material, long amount) {
        long remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int remove = (int) Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - remove);
            if (stack.getAmount() <= 0) {
                contents[i] = null;
            }
            remaining -= remove;
            if (remaining <= 0) {
                break;
            }
        }
        player.getInventory().setContents(contents);
    }

    public static class DeliveryResult {
        private final boolean success;
        private final String message;
        private final long amountDelivered;
        private final double payout;

        private DeliveryResult(boolean success, String message, long amountDelivered, double payout) {
            this.success = success;
            this.message = message;
            this.amountDelivered = amountDelivered;
            this.payout = payout;
        }

        public static DeliveryResult success(long amount, double payout) {
            return new DeliveryResult(true, null, amount, payout);
        }

        public static DeliveryResult failed(String message) {
            return new DeliveryResult(false, message, 0L, 0D);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public long getAmountDelivered() {
            return amountDelivered;
        }

        public double getPayout() {
            return payout;
        }
    }

    public record OrderSnapshot(int id,
                                 UUID ownerUuid,
                                 String ownerName,
                                 Material material,
                                 long totalQuantity,
                                 long remainingQuantity,
                                 double pricePerItem,
                                 double totalEscrow,
                                 double totalPaid,
                                 long createdTimestamp,
                                 long expirationTimestamp,
                                 OrderStatus status,
                                 List<ItemStack> storedItems,
                                 List<UUID> trustedPlayers) {
    }
}
