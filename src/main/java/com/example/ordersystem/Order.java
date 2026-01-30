package com.example.ordersystem;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a buy order stored by the OrderSystem plugin.
 */
public class Order {
    private final int id;
    private final UUID ownerUuid;
    private final String ownerName;
    private final Material material;
    private final long totalQuantity;
    private long remainingQuantity;
    private final double pricePerItem;
    private final double totalEscrow;
    private double totalPaid;
    private final long createdTimestamp;
    private long expirationTimestamp;
    private OrderStatus status;
    private final List<ItemStack> storedItems;
    private final List<UUID> trustedPlayers;

    public Order(int id,
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
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.material = material;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = remainingQuantity;
        this.pricePerItem = pricePerItem;
        this.totalEscrow = totalEscrow;
        this.totalPaid = totalPaid;
        this.createdTimestamp = createdTimestamp;
        this.expirationTimestamp = expirationTimestamp;
        this.status = status;
        this.storedItems = storedItems == null ? new ArrayList<>() : storedItems;
        this.trustedPlayers = trustedPlayers == null ? new ArrayList<>() : trustedPlayers;
    }

    public int getId() {
        return id;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public Material getMaterial() {
        return material;
    }

    public long getTotalQuantity() {
        return totalQuantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public void setRemainingQuantity(long remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public double getPricePerItem() {
        return pricePerItem;
    }

    public double getTotalEscrow() {
        return totalEscrow;
    }

    public double getTotalPaid() {
        return totalPaid;
    }

    public void setTotalPaid(double totalPaid) {
        this.totalPaid = totalPaid;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public long getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public void setExpirationTimestamp(long expirationTimestamp) {
        this.expirationTimestamp = expirationTimestamp;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public List<ItemStack> getStoredItems() {
        return storedItems;
    }

    public List<UUID> getTrustedPlayers() {
        return trustedPlayers;
    }

    public boolean isExpired() {
        return status == OrderStatus.COMPLETED && expirationTimestamp > 0 && Instant.now().toEpochMilli() >= expirationTimestamp;
    }
}
