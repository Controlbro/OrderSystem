package com.example.ordersystem;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Main plugin entry point for OrderSystem.
 */
public class OrderSystemPlugin extends JavaPlugin implements Listener, TabCompleter {
    private Economy economy;
    private OrderManager orderManager;
    private StorageManager storageManager;
    private GUIManager guiManager;
    private final Map<UUID, OrderCreationSession> creationSessions = new HashMap<>();
    private final Map<UUID, SearchSession> searchSessions = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        orderManager = new OrderManager(this);
        storageManager = new StorageManager(this);
        storageManager.loadOrders(orderManager);
        guiManager = new GUIManager(this, orderManager, economy);
        Bukkit.getPluginManager().registerEvents(guiManager, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("orders").setExecutor(this);
        getCommand("orders").setTabCompleter(this);
        if (getCommand("order") != null) {
            getCommand("order").setExecutor(this);
            getCommand("order").setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            orderManager.removeExpiredOrders();
            storageManager.requestSaveAsync(orderManager);
        }, 20L * 60L, 20L * 60L * 30L);
    }

    @Override
    public void onDisable() {
        if (storageManager != null && orderManager != null) {
            storageManager.saveNow(orderManager);
        }
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public void setSelectedMaterial(Player player, Material material) {
        OrderCreationSession session = creationSessions.computeIfAbsent(player.getUniqueId(), key -> new OrderCreationSession());
        session.setMaterial(material);
        session.setStep(OrderCreationSession.Step.QUANTITY);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        OrderCreationSession session = creationSessions.get(player.getUniqueId());
        SearchSession searchSession = searchSessions.get(player.getUniqueId());
        if (session == null && searchSession == null) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (session != null) {
                handleChatInput(player, session, event.getMessage());
            } else if (searchSession != null) {
                handleSearchInput(player, searchSession, event.getMessage());
            }
        });
    }

    private void handleChatInput(Player player, OrderCreationSession session, String message) {
        if (message.equalsIgnoreCase("cancel")) {
            cancelCreate(player);
            return;
        }
        try {
            if (session.getStep() == OrderCreationSession.Step.MATERIAL) {
                if (message.equalsIgnoreCase("gui")) {
                    guiManager.openMaterialSelector(player, false);
                    return;
                }
                Material material = guiManager.findExactMaterial(message);
                if (material == null) {
                    player.sendMessage(ChatColor.RED + "No exact material found.");
                    sendMaterialSuggestions(player, message);
                    player.sendMessage(ChatColor.GRAY + "Type another material name, 'gui' to browse, or 'cancel' to stop.");
                    return;
                }
                session.setMaterial(material);
                session.setStep(OrderCreationSession.Step.QUANTITY);
                player.sendMessage(ChatColor.YELLOW + "Selected material: " + guiManager.formatMaterialName(material));
                player.sendMessage(ChatColor.GRAY + "Enter quantity in chat.");
                return;
            }
            if (session.getStep() == OrderCreationSession.Step.QUANTITY) {
                long quantity = Long.parseLong(message);
                if (quantity <= 0) {
                    player.sendMessage(ChatColor.RED + "Quantity must be positive.");
                    return;
                }
                session.setQuantity(quantity);
                session.setStep(OrderCreationSession.Step.TOTAL_PRICE);
                player.sendMessage(ChatColor.GRAY + "Enter the total price you're paying in chat.");
                return;
            }
            if (session.getStep() == OrderCreationSession.Step.TOTAL_PRICE) {
                double totalPrice = Double.parseDouble(message);
                if (totalPrice <= 0) {
                    player.sendMessage(ChatColor.RED + "Price must be positive.");
                    return;
                }
                double pricePerItem = totalPrice / session.getQuantity();
                session.setTotalPrice(totalPrice);
                session.setPricePerItem(pricePerItem);
                double listingFee = getConfig().getDouble("listing-fee", 1000D);
                guiManager.openConfirmCreation(player, session, listingFee);
            }
        } catch (NumberFormatException ex) {
            player.sendMessage(ChatColor.RED + "Invalid number.");
        }
    }

    private void handleSearchInput(Player player, SearchSession session, String message) {
        if (message.equalsIgnoreCase("cancel")) {
            searchSessions.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Search cancelled.");
            return;
        }
        if (message.equalsIgnoreCase("gui")) {
            guiManager.openMaterialSelector(player, true);
            return;
        }
        Material material = guiManager.findExactMaterial(message);
        if (material == null) {
            player.sendMessage(ChatColor.RED + "No exact material found.");
            sendMaterialSuggestions(player, message);
            player.sendMessage(ChatColor.GRAY + "Type another material name, 'gui' to browse, or 'cancel' to stop.");
            return;
        }
        guiManager.openOrderBoard(player, 1, material, session.ownerFilter());
        searchSessions.remove(player.getUniqueId());
    }

    public void beginSearch(Player player, UUID ownerFilter) {
        searchSessions.put(player.getUniqueId(), new SearchSession(ownerFilter));
        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Type a material name to search, or 'gui' to browse.");
        player.sendMessage(ChatColor.GRAY + "Type 'cancel' to stop.");
    }

    public Optional<UUID> consumeSearchOwnerFilter(Player player) {
        SearchSession session = searchSessions.remove(player.getUniqueId());
        return session == null ? Optional.empty() : Optional.ofNullable(session.ownerFilter());
    }

    public void confirmCreate(Player player) {
        OrderCreationSession session = creationSessions.get(player.getUniqueId());
        if (session == null || session.getMaterial() == null) {
            player.sendMessage(ChatColor.RED + "No order creation in progress.");
            player.closeInventory();
            return;
        }
        if (!player.hasPermission("ordersystem.create")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to create orders.");
            player.closeInventory();
            return;
        }
        int maxOrders = getMaxOrders(player);
        if (maxOrders >= 0 && orderManager.getActiveOrderCount(player.getUniqueId()) >= maxOrders) {
            player.sendMessage(ChatColor.RED + "You have reached your max active orders.");
            player.closeInventory();
            return;
        }
        double escrow = session.getTotalPrice();
        double listingFee = getConfig().getDouble("listing-fee", 1000D);
        double total = escrow + listingFee;
        if (!economy.has(player, total)) {
            player.sendMessage(ChatColor.RED + "You need $" + NumberFormatter.formatCompact(total) + " to create this order.");
            player.closeInventory();
            return;
        }
        economy.withdrawPlayer(player, total);
        Order order = orderManager.createOrder(player, session.getMaterial(), session.getQuantity(), session.getPricePerItem());
        storageManager.requestSaveAsync(orderManager);
        player.sendMessage(ChatColor.GREEN + "Order created! ID: " + order.getId());
        player.closeInventory();
        creationSessions.remove(player.getUniqueId());
    }

    public void cancelCreate(Player player) {
        creationSessions.remove(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(ChatColor.RED + "Order creation cancelled.");
    }

    private int getMaxOrders(Player player) {
        int max = -1;
        for (int i = 1; i <= 100; i++) {
            if (player.hasPermission("ordersystem.maxorders." + i)) {
                max = Math.max(max, i);
            }
        }
        return max;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (args.length == 0) {
            guiManager.openOrderBoard(player, 1, null);
            return true;
        }
        if (args[0].equalsIgnoreCase("create")) {
            creationSessions.put(player.getUniqueId(), new OrderCreationSession());
            player.sendMessage(ChatColor.GRAY + "Type a material name to create an order, or 'gui' to browse.");
            player.sendMessage(ChatColor.GRAY + "Type 'cancel' to stop.");
            return true;
        }
        if (args[0].equalsIgnoreCase("collect")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /orders collect <id>");
                return true;
            }
            try {
                int id = Integer.parseInt(args[1]);
                Optional<Order> order = orderManager.getOrder(id);
                if (order.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Order not found.");
                    return true;
                }
                if (!orderManager.canCollect(player, order.get())) {
                    player.sendMessage(ChatColor.RED + "You are not trusted to collect items.");
                    return true;
                }
                guiManager.openCollectGUI(player, order.get(), 1);
            } catch (NumberFormatException ex) {
                player.sendMessage(ChatColor.RED + "Invalid order ID.");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("trust")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /orders trust <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            List<Order> ownedOrders = orderManager.getOrdersSorted().stream()
                    .filter(order -> order.getOwnerUuid().equals(player.getUniqueId()))
                    .toList();
            if (ownedOrders.isEmpty()) {
                player.sendMessage(ChatColor.RED + "You have no orders to trust players on.");
                return true;
            }
            for (Order order : ownedOrders) {
                orderManager.addTrustedPlayer(order, target);
            }
            storageManager.requestSaveAsync(orderManager);
            player.sendMessage(ChatColor.GREEN + target.getName() + " can now collect items for your orders.");
            return true;
        }
        player.sendMessage(ChatColor.RED + "Unknown subcommand.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create", "collect", "trust").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trust")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private void sendMaterialSuggestions(Player player, String input) {
        List<Material> suggestions = guiManager.suggestMaterials(input, 5);
        if (!suggestions.isEmpty()) {
            String suggestionList = suggestions.stream()
                    .map(guiManager::formatMaterialName)
                    .collect(Collectors.joining(", "));
            player.sendMessage(ChatColor.YELLOW + "Did you mean: " + suggestionList + "?");
        }
    }

    private record SearchSession(UUID ownerFilter) {
    }
}
