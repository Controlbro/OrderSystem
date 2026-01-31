package com.example.ordersystem;

import org.bukkit.Material;

/**
 * Tracks a player's order creation flow through chat prompts.
 */
public class OrderCreationSession {
    public enum Step {
        MATERIAL,
        QUANTITY,
        TOTAL_PRICE
    }

    private Step step = Step.MATERIAL;
    private Material material;
    private long quantity;
    private double pricePerItem;
    private double totalPrice;

    public Step getStep() {
        return step;
    }

    public void setStep(Step step) {
        this.step = step;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) {
        this.material = material;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public double getPricePerItem() {
        return pricePerItem;
    }

    public void setPricePerItem(double pricePerItem) {
        this.pricePerItem = pricePerItem;
    }

    public double getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(double totalPrice) {
        this.totalPrice = totalPrice;
    }
}
