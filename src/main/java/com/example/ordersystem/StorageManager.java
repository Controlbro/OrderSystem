package com.example.ordersystem;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles loading and saving orders to a single YAML file.
 */
public class StorageManager {
    private final OrderSystemPlugin plugin;
    private final File dataFile;
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);

    public StorageManager(OrderSystemPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "orders.yml");
    }

    public void loadOrders(OrderManager orderManager) {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection ordersSection = config.getConfigurationSection("orders");
        if (ordersSection == null) {
            return;
        }
        for (String key : ordersSection.getKeys(false)) {
            ConfigurationSection section = ordersSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            int id = Integer.parseInt(key);
            String ownerUuidRaw = section.getString("ownerUuid", "");
            if (ownerUuidRaw.isBlank()) {
                continue;
            }
            UUID ownerUuid;
            try {
                ownerUuid = UUID.fromString(ownerUuidRaw);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String ownerName = section.getString("ownerName", "Unknown");
            String materialName = section.getString("material", "STONE");
            long totalQuantity = section.getLong("totalQuantity");
            long remainingQuantity = section.getLong("remainingQuantity");
            double pricePerItem = section.getDouble("pricePerItem");
            double totalEscrow = section.getDouble("totalEscrow");
            double totalPaid = section.getDouble("totalPaid");
            long createdTimestamp = section.getLong("createdTimestamp");
            long expirationTimestamp = section.getLong("expirationTimestamp");
            OrderStatus status = OrderStatus.valueOf(section.getString("status", OrderStatus.ACTIVE.name()));
            List<ItemStack> storedItems = new ArrayList<>();
            List<?> storedRaw = section.getList("storedItems");
            if (storedRaw != null) {
                for (Object item : storedRaw) {
                    if (item instanceof ItemStack) {
                        storedItems.add((ItemStack) item);
                    }
                }
            }
            List<UUID> trustedPlayers = new ArrayList<>();
            List<String> trustedRaw = section.getStringList("trustedPlayers");
            for (String uuid : trustedRaw) {
                try {
                    trustedPlayers.add(UUID.fromString(uuid));
                } catch (IllegalArgumentException ignored) {
                    // Skip invalid UUIDs
                }
            }
            Order order = new Order(id, ownerUuid, ownerName, orderManager.parseMaterial(materialName),
                    totalQuantity, remainingQuantity, pricePerItem, totalEscrow, totalPaid,
                    createdTimestamp, expirationTimestamp, status, storedItems, trustedPlayers);
            orderManager.addLoadedOrder(order);
        }
    }

    public void requestSaveAsync(OrderManager orderManager) {
        if (saveQueued.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    saveNow(orderManager);
                } finally {
                    saveQueued.set(false);
                }
            });
        }
    }

    public void saveNow(OrderManager orderManager) {
        Map<Integer, OrderManager.OrderSnapshot> snapshot = orderManager.snapshotOrders();
        YamlConfiguration config = new YamlConfiguration();
        ConfigurationSection ordersSection = config.createSection("orders");
        for (OrderManager.OrderSnapshot order : snapshot.values()) {
            ConfigurationSection section = ordersSection.createSection(String.valueOf(order.id()));
            section.set("ownerUuid", order.ownerUuid().toString());
            section.set("ownerName", order.ownerName());
            section.set("material", order.material().name());
            section.set("totalQuantity", order.totalQuantity());
            section.set("remainingQuantity", order.remainingQuantity());
            section.set("pricePerItem", order.pricePerItem());
            section.set("totalEscrow", order.totalEscrow());
            section.set("totalPaid", order.totalPaid());
            section.set("createdTimestamp", order.createdTimestamp());
            section.set("expirationTimestamp", order.expirationTimestamp());
            section.set("status", order.status().name());
            section.set("storedItems", new ArrayList<>(order.storedItems()));
            List<String> trustedRaw = new ArrayList<>();
            for (UUID uuid : order.trustedPlayers()) {
                trustedRaw.add(uuid.toString());
            }
            section.set("trustedPlayers", trustedRaw);
        }
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            File tempFile = new File(dataFile.getParentFile(), "orders.yml.tmp");
            config.save(tempFile);
            try {
                Files.move(tempFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(tempFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save orders.yml: " + ex.getMessage());
        }
    }
}
