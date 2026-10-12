package my.reqqpe.rseller.menu;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import me.clip.placeholderapi.PlaceholderAPI;
import my.reqqpe.rseller.Main;
import my.reqqpe.rseller.managers.MenuManager;
import my.reqqpe.rseller.utils.Colorizer;
import my.reqqpe.rseller.utils.HeadUtil;
import my.reqqpe.rseller.utils.ModelDataUtil;
import my.reqqpe.rseller.utils.SyntaxParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.BiConsumer;

public abstract class AbstractMenu {


    protected final Main plugin;
    protected FileConfiguration guiConfig;
    protected final Int2ObjectMap<String> slotToItemId = new Int2ObjectOpenHashMap<>();
    protected IntList specialSlots = new IntArrayList();
    protected final Int2ObjectMap<UpdatableItem> updatableItems = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, List<BukkitTask>> updateTasks = new HashMap<>();

    // Animation system
    private final Set<UUID> animatingPlayers = new HashSet<>();
    private final Map<UUID, BukkitTask> animationTasks = new HashMap<>();


    public AbstractMenu(Main plugin) {
        this.plugin = plugin;
    }

    public record UpdatableItem(int slot, String name, List<String> lore) {
    }

    // Animation state holder
    private record SlotItem(int slot, ItemStack item) {}


    protected abstract FileConfiguration getGuiConfig();

    protected abstract String getMenuId();


    public boolean isAnimating(UUID uuid) {
        return animatingPlayers.contains(uuid);
    }


    public void openMenu(Player player) {
        this.guiConfig = getGuiConfig();

        String title = Colorizer.color(guiConfig.getString("title", "ElytrixSeller"));
        int size = guiConfig.getInt("size", 54);
        if (size % 9 != 0 || size < 9 || size > 54) {
            size = 54;
        }
        Inventory inv = Bukkit.createInventory(new CustomInventoryHolder(getMenuId()), size, title);

        boolean animEnabled = guiConfig.getBoolean("animation.enabled", true);
        int animDelay = guiConfig.getInt("animation.delay", 1);
        String animSound = guiConfig.getString("animation.sound", "");

        if (animEnabled && animDelay > 0) {
            player.openInventory(inv);
            loadItemsAnimated(inv, player, animDelay, animSound);
        } else {
            loadItems(inv, player);
            player.openInventory(inv);
            startItemUpdates(player, inv);
            onAnimationComplete(player, inv);
        }
    }


    protected void loadItemsAnimated(Inventory inv, Player player, int delay, String soundName) {
        slotToItemId.clear();
        updatableItems.clear();

        IntList specialSlotsList = parseSlotList(guiConfig.getStringList("special-slots"));
        this.specialSlots = new IntArrayList(specialSlotsList);
        Set<Integer> usedSlots = new HashSet<>(specialSlotsList);
        ConfigurationSection itemsSection = guiConfig.getConfigurationSection("items");

        List<SlotItem> pendingItems = new ArrayList<>();

        if (itemsSection != null) {
            for (String itemId : itemsSection.getKeys(false)) {
                String path = "items." + itemId;
                IntList itemSlots = slotORslots(path);
                IntList slotsToRemove = new IntArrayList();
                IntList validSlots = new IntArrayList();

                for (int slot : itemSlots) {
                    if (slot < 0 || slot >= inv.getSize()) {
                        slotsToRemove.add(slot);
                        plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuSlotOutOfBounds()
                                .replace("{menu}", getMenuId())
                                .replace("{slot}", String.valueOf(slot))
                                .replace("{item}", itemId)
                                .replace("{max}", String.valueOf(inv.getSize() - 1)));
                        continue;
                    }
                    if (specialSlotsList.contains(slot) || usedSlots.contains(slot)) {
                        slotsToRemove.add(slot);
                        plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuSlotOccupied()
                                .replace("{menu}", getMenuId())
                                .replace("{slot}", String.valueOf(slot))
                                .replace("{item}", itemId));
                        continue;
                    }
                    validSlots.add(slot);
                }

                itemSlots.removeAll(slotsToRemove);

                if (validSlots.isEmpty()) {
                    plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuNoSlots()
                            .replace("{menu}", getMenuId())
                            .replace("{item}", itemId));
                    continue;
                }

                ItemStack item = buildItemStack(path, player, inv, itemId);
                if (item == null) continue;

                boolean isUpdatable = guiConfig.getBoolean(path + ".update", false);
                String nameTemplate = guiConfig.getString(path + ".name", "");
                List<String> loreTemplate = guiConfig.getStringList(path + ".lore");

                for (int slot : validSlots) {
                    pendingItems.add(new SlotItem(slot, item));
                    slotToItemId.put(slot, itemId);

                    if (isUpdatable) {
                        updatableItems.put(slot, new UpdatableItem(slot, nameTemplate, loreTemplate));
                    }
                }
            }
        }

        if (pendingItems.isEmpty()) {
            startItemUpdates(player, inv);
            onAnimationComplete(player, inv);
            return;
        }

        // Sort items by distance from center for wave animation effect
        int invSize = inv.getSize();
        int cols = 9;
        int centerRow = (invSize / cols) / 2;
        int centerCol = cols / 2;

        pendingItems.sort((a, b) -> {
            int rowA = a.slot() / cols;
            int colA = a.slot() % cols;
            int rowB = b.slot() / cols;
            int colB = b.slot() % cols;
            double distA = Math.sqrt(Math.pow(rowA - centerRow, 2) + Math.pow(colA - centerCol, 2));
            double distB = Math.sqrt(Math.pow(rowB - centerRow, 2) + Math.pow(colB - centerCol, 2));
            return Double.compare(distA, distB);
        });

        // Start animation
        UUID playerId = player.getUniqueId();
        animatingPlayers.add(playerId);

        Sound sound = null;
        if (soundName != null && !soundName.isEmpty()) {
            try {
                sound = Sound.valueOf(soundName.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        final Sound finalSound = sound;
        final int totalItems = pendingItems.size();

        // Use faster delay for smoother animation, place multiple items per tick for large menus
        final int effectiveDelay = Math.max(1, delay);
        final int itemsPerTick = totalItems > 20 ? 2 : 1;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int index = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancelAnimation(playerId);
                    return;
                }

                if (index >= totalItems) {
                    cancelAnimation(playerId);
                    startItemUpdates(player, inv);
                    onAnimationComplete(player, inv);
                    return;
                }

                int placed = 0;
                while (index < totalItems && placed < itemsPerTick) {
                    SlotItem slotItem = pendingItems.get(index);
                    inv.setItem(slotItem.slot(), slotItem.item());
                    placed++;
                    index++;
                }

                if (finalSound != null) {
                    float progress = (float) index / totalItems;
                    float pitch = 1.2f + (progress * 0.6f);
                    float volume = 0.2f + (progress * 0.15f);
                    player.playSound(player.getLocation(), finalSound, volume, pitch);
                }
            }
        }, 0L, effectiveDelay);

        animationTasks.put(playerId, task);
    }


    private void cancelAnimation(UUID playerId) {
        animatingPlayers.remove(playerId);
        BukkitTask task = animationTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }


    private ItemStack buildItemStack(String path, Player player, Inventory inv, String itemId) {
        String matString = guiConfig.getString(path + ".material", "STONE");
        ItemStack item;

        if (matString.toLowerCase().startsWith("basehead-")) {
            String base64 = matString.substring("basehead-".length());
            item = HeadUtil.getCustomHead(base64);
        } else {
            Material material = Material.matchMaterial(matString);
            if (material == null) {
                plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuUnknownMaterial()
                        .replace("{menu}", getMenuId())
                        .replace("{item}", itemId)
                        .replace("{material}", matString));
                return null;
            }
            item = new ItemStack(material);
        }

        Integer customModelData = null;
        if (guiConfig.contains(path + ".model-data")) {
            customModelData = guiConfig.getInt(path + ".model-data");
        }

        int amount = guiConfig.getInt(path + ".amount", 1);
        if (amount <= 0 || amount >= 64) {
            amount = 1;
        }
        item.setAmount(amount);

        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String name = guiConfig.getString(path + ".name", "");
            meta.setDisplayName(Colorizer.color(replacePlaceholders(player, name, inv)));

            List<String> lore = guiConfig.getStringList(path + ".lore");
            if (!lore.isEmpty()) {
                meta.setLore(Colorizer.colorizeAll(replacePlaceholders(player, lore, inv)));
            }
            if (customModelData != null) {
                ModelDataUtil.setModelData(meta, customModelData);
            }

            List<ItemFlag> itemFlags = new ArrayList<>();
            for (String s : guiConfig.getStringList(path + ".item-flags")) {
                try {
                    itemFlags.add(ItemFlag.valueOf(s.toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Неизвестный ItemFlag: " + s);
                }
            }
            meta.addItemFlags(itemFlags.toArray(new ItemFlag[0]));

            item.setItemMeta(meta);
        }

        ConfigurationSection enchantsSection = guiConfig.getConfigurationSection(path + ".enchants");
        if (enchantsSection != null) {
            for (String enchantName : enchantsSection.getKeys(false)) {
                try {
                    int level = enchantsSection.getInt(enchantName);
                    Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
                    if (enchantment == null) {
                        plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuUnknownEnchant()
                                .replace("{menu}", getMenuId())
                                .replace("{item}", itemId)
                                .replace("{enchant}", enchantName));
                        continue;
                    }
                    if (level <= 0) {
                        plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuInvalidEnchantLevel()
                                .replace("{menu}", getMenuId())
                                .replace("{item}", itemId)
                                .replace("{level}", String.valueOf(level)));
                        continue;
                    }
                    item.addUnsafeEnchantment(enchantment, level);
                } catch (Exception e) {
                    plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuEnchantError()
                            .replace("{menu}", getMenuId())
                            .replace("{enchant}", enchantName)
                            .replace("{level}", String.valueOf(enchantsSection.getInt(enchantName, -1)))
                            .replace("{item}", itemId)
                            .replace("{error}", e.getMessage()));
                }
            }
        }

        return item;
    }


    protected void loadItems(Inventory inv, Player player) {
        slotToItemId.clear();
        updatableItems.clear();

        IntList specialSlotsList = parseSlotList(guiConfig.getStringList("special-slots"));
        this.specialSlots = new IntArrayList(specialSlotsList);
        Set<Integer> usedSlots = new HashSet<>(specialSlotsList);
        ConfigurationSection itemsSection = guiConfig.getConfigurationSection("items");

        if (itemsSection != null) {
            for (String itemId : itemsSection.getKeys(false)) {
                String path = "items." + itemId;
                IntList itemSlots = slotORslots(path);
                IntList slotsToRemove = new IntArrayList();
                IntList validSlots = new IntArrayList();

                for (int slot : itemSlots) {
                    if (slot < 0 || slot >= inv.getSize()) {
                        slotsToRemove.add(slot);
                        plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuSlotOutOfBounds()
                                .replace("{menu}", getMenuId())
                                .replace("{slot}", String.valueOf(slot))
                                .replace("{item}", itemId)
                                .replace("{max}", String.valueOf(inv.getSize() - 1)));
                        continue;
                    }
                    if (specialSlotsList.contains(slot) || usedSlots.contains(slot)) {
                        slotsToRemove.add(slot);
                        plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuSlotOccupied()
                                .replace("{menu}", getMenuId())
                                .replace("{slot}", String.valueOf(slot))
                                .replace("{item}", itemId));
                        continue;
                    }
                    validSlots.add(slot);
                }

                itemSlots.removeAll(slotsToRemove);

                if (validSlots.isEmpty()) {
                    plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuNoSlots()
                            .replace("{menu}", getMenuId())
                            .replace("{item}", itemId));
                    continue;
                }

                String matString = guiConfig.getString(path + ".material", "STONE");
                ItemStack item;

                if (matString.toLowerCase().startsWith("basehead-")) {
                    String base64 = matString.substring("basehead-".length());
                    item = HeadUtil.getCustomHead(base64);
                } else {
                    Material material = Material.matchMaterial(matString);
                    if (material == null) {
                        plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuUnknownMaterial()
                                .replace("{menu}", getMenuId())
                                .replace("{item}", itemId)
                                .replace("{material}", matString));
                        continue;
                    }
                    item = new ItemStack(material);
                }

                Integer customModelData = null;
                if (guiConfig.contains(path + ".model-data")) {
                    customModelData = guiConfig.getInt(path + ".model-data");
                }

                int amount = guiConfig.getInt(path + ".amount", 1);
                if (amount <= 0 || amount >= 64) {
                    amount = 1;
                }
                item.setAmount(amount);

                ItemMeta meta = item.getItemMeta();

                if (meta != null) {
                    String name = guiConfig.getString(path + ".name", "");
                    meta.setDisplayName(Colorizer.color(replacePlaceholders(player, name, inv)));

                    List<String> lore = guiConfig.getStringList(path + ".lore");
                    if (!lore.isEmpty()) {
                        meta.setLore(Colorizer.colorizeAll(replacePlaceholders(player, lore, inv)));
                    }
                    if (customModelData != null) {
                        ModelDataUtil.setModelData(meta, customModelData);
                    }

                    List<ItemFlag> itemFlags = new ArrayList<>();

                    for (String s : guiConfig.getStringList(path + ".item-flags")) {
                        try {
                            itemFlags.add(ItemFlag.valueOf(s.toUpperCase()));
                        } catch (IllegalArgumentException ex) {
                            plugin.getLogger().warning("Неизвестный ItemFlag: " + s);
                        }
                    }

                    meta.addItemFlags(
                            itemFlags.toArray(new ItemFlag[0])
                    );

                    item.setItemMeta(meta);
                }

                ConfigurationSection enchantsSection = guiConfig.getConfigurationSection(path + ".enchants");
                if (enchantsSection != null) {
                    for (String enchantName : enchantsSection.getKeys(false)) {
                        try {
                            int level = enchantsSection.getInt(enchantName);
                            Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(enchantName.toLowerCase()));
                            if (enchantment == null) {
                                plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuUnknownEnchant()
                                        .replace("{menu}", getMenuId())
                                        .replace("{item}", itemId)
                                        .replace("{enchant}", enchantName));
                                continue;
                            }
                            if (level <= 0) {
                                plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuInvalidEnchantLevel()
                                        .replace("{menu}", getMenuId())
                                        .replace("{item}", itemId)
                                        .replace("{level}", String.valueOf(level)));
                                continue;
                            }
                            item.addUnsafeEnchantment(enchantment, level);
                        } catch (Exception e) {
                            plugin.getLogger().warning(plugin.getMessageConfig().getConsoleMenuEnchantError()
                                    .replace("{menu}", getMenuId())
                                    .replace("{enchant}", enchantName)
                                    .replace("{level}", String.valueOf(enchantsSection.getInt(enchantName, -1)))
                                    .replace("{item}", itemId)
                                    .replace("{error}", e.getMessage()));
                        }
                    }
                }

                for (int slot : validSlots) {
                    inv.setItem(slot, item);
                    slotToItemId.put(slot, itemId);

                    if (guiConfig.getBoolean(path + ".update", false)) {
                        String name = guiConfig.getString(path + ".name", "");
                        List<String> lore = guiConfig.getStringList(path + ".lore");
                        updatableItems.put(slot, new UpdatableItem(slot, name, lore));
                    }
                }
            }
        }
    }


    protected void onAnimationComplete(Player player, Inventory inv) {
        // Override in subclasses to run logic after animation finishes
    }


    protected void runMainActions(Player player, String cmd) {
        if (cmd == null || cmd.isEmpty()) return;

        int bracketEnd = cmd.indexOf(']');
        if (bracketEnd == -1 || cmd.charAt(0) != '[') {
            player.sendMessage(Colorizer.color(cmd));
            return;
        }

        String type = cmd.substring(1, bracketEnd).toLowerCase();
        if (type.isEmpty()) return;
        String value = cmd.length() > bracketEnd + 1 ? cmd.substring(bracketEnd + 1).trim() : "";

        switch (type) {
            case "close" -> player.closeInventory();

            case "player" -> {
                value = PlaceholderAPI.setPlaceholders(player, value);
                player.performCommand(value);
            }

            case "text" -> {
                value = PlaceholderAPI.setPlaceholders(player, Colorizer.color(value));
                player.sendMessage(value);
            }

            case "message" -> {
                value = PlaceholderAPI.setPlaceholders(player, Colorizer.color(value));
                player.sendMessage(value);
            }

            case "opengui" -> {
                AbstractMenu menu = MenuManager.getMenu(value);
                if (menu != null) {
                    menu.openMenu(player);
                } else {
                    plugin.getLogger().warning(
                            plugin.getMessageConfig()
                                    .getConsoleMenuNotFound()
                                    .replace("{id}", value)
                    );
                }
            }

            case "sound" -> {
                String[] parts = value.split(";");
                try {
                    Sound sound = Sound.valueOf(parts[0].toUpperCase());
                    float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1F;
                    float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1F;

                    player.playSound(player.getLocation(), sound, volume, pitch);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning(
                            plugin.getMessageConfig()
                                    .getConsoleSoundUnknown()
                                    .replace("{sound}", parts[0])
                    );
                } catch (Exception e) {
                    plugin.getLogger().warning(plugin.getMessageConfig().getConsoleSoundError());
                    e.printStackTrace();
                }
            }

            case "sell" -> {
                String[] args = value.split(";");

                int amount = 1;
                if (args.length > 1) {
                    switch (args[1].toLowerCase()) {
                        case "all" -> amount = Integer.MAX_VALUE;
                        default -> {
                            try {
                                amount = Math.max(1, Integer.parseInt(args[1]));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }

                plugin.getSellManager().sellItem(player, args[0], amount);
            }

            default -> player.sendMessage(Colorizer.color(cmd));
        }
    }


    protected String replacePlaceholders(Player player, String text, Inventory inventory) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }


    protected List<String> replacePlaceholders(Player player, List<String> list, Inventory inventory) {

        List<String> result = new ArrayList<>();
        for (String text : list) {
            result.add(replacePlaceholders(player, text, inventory));
        }

        return result;
    }


    public void closeMenu(Player player, InventoryCloseEvent e) {
        cancelItemUpdates(player);
        cancelAnimation(player.getUniqueId());
    }

    public void handleClick(Player player, InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof CustomInventoryHolder holder)) return;
        if (!holder.id().equals(getMenuId())) return;

        int rawSlot = e.getRawSlot();
        Inventory clickedInv = e.getClickedInventory();
        Inventory menuInv = e.getInventory();

        if (clickedInv != menuInv || rawSlot >= menuInv.getSize()) return;

        // Block all clicks during animation to prevent bugs
        if (isAnimating(player.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        if (specialSlots.contains(rawSlot) && handleSpecialSlotClick(player, e)) {
            return;
        }

        e.setCancelled(true);

        String itemId = slotToItemId.get(rawSlot);
        if (itemId == null) return;

        ConfigurationSection itemSection = guiConfig.getConfigurationSection("items." + itemId);
        if (itemSection == null) return;

        String clickType;
        if (e.isShiftClick()) {
            if (e.isLeftClick()) {
                clickType = "left_shift_click";
            } else if (e.isRightClick()) {
                clickType = "right_shift_click";
            } else {
                return;
            }
        } else {
            if (e.isLeftClick()) {
                clickType = "left_click";
            } else if (e.isRightClick()) {
                clickType = "right_click";
            } else {
                return;
            }
        }

        List<String> requirementSections = List.of(
                "click_requaments",
                clickType + "_requaments"
        );

        for (String sectionName : requirementSections) {
            ConfigurationSection reqSection = itemSection.getConfigurationSection(sectionName);
            if (reqSection == null) continue;

            boolean passed = true;

            for (String key : reqSection.getKeys(false)) {
                if (key.equalsIgnoreCase("deny_actions")) {
                    continue;
                }

                ConfigurationSection entry = reqSection.getConfigurationSection(key);
                if (entry == null) continue;

                String syntax = replacePlaceholders(
                        player,
                        entry.getString("syntax", ""),
                        menuInv
                );

                SyntaxParser.Type type = SyntaxParser.Type.valueOf(
                        entry.getString("type", "AUTO").toUpperCase()
                );

                if (!SyntaxParser.parse(syntax, type)) {
                    passed = false;
                    break;
                }
            }

            if (!passed) {
                for (String action : reqSection.getStringList("deny_actions")) {
                    executeAction(player, action);
                }
                return;
            }
        }

        List<String> actions = new ArrayList<>();
        actions.addAll(itemSection.getStringList("click_actions"));
        actions.addAll(itemSection.getStringList(clickType + "_actions"));

        for (String action : actions) {
            executeAction(player, action);
        }
    }

    protected boolean handleSpecialSlotClick(Player player, InventoryClickEvent e) {
        e.setCancelled(true);
        return false;
    }


    protected void executeAction(Player player, String action) {

        Inventory inv = player.getOpenInventory().getTopInventory();
        if (!(inv.getHolder() instanceof CustomInventoryHolder holder)) return;
        if (!holder.id().equals(getMenuId())) return;

        runMainActions(player, action);
    }


    protected void startItemUpdates(Player player, Inventory inv) {
        int interval = guiConfig.getInt("update_interval", -1);
        if (interval <= 0 || updatableItems.isEmpty()) return;

        List<BukkitTask> tasks = new ArrayList<>();

        for (Map.Entry<Integer, UpdatableItem> entry : updatableItems.entrySet()) {
            int slot = entry.getKey();
            UpdatableItem itemData = entry.getValue();

            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!player.isOnline()) return;
                if (!player.getOpenInventory().getTopInventory().equals(inv)) return;

                ItemStack item = inv.getItem(slot);
                if (item == null || item.getType() == Material.AIR) return;

                ItemMeta meta = item.getItemMeta();
                boolean changed = false;

                String newName = Colorizer.color(replacePlaceholders(player, itemData.name(), inv));
                if (!newName.equals(meta.hasDisplayName() ? meta.getDisplayName() : "")) {
                    meta.setDisplayName(newName);
                    changed = true;
                }

                List<String> newLore = Colorizer.colorizeAll(replacePlaceholders(player, itemData.lore(), inv));
                if (!newLore.equals(meta.hasLore() ? meta.getLore() : new ArrayList<>())) {
                    meta.setLore(newLore);
                    changed = true;
                }

                if (changed) {
                    item.setItemMeta(meta);
                    inv.setItem(slot, item);
                    player.updateInventory();
                }

            }, interval, interval);

            tasks.add(task);
        }

        cancelItemUpdates(player);
        updateTasks.put(player.getUniqueId(), tasks);
    }


    protected void cancelItemUpdates(Player player) {
        List<BukkitTask> tasks = updateTasks.remove(player.getUniqueId());
        if (tasks != null) {
            tasks.forEach(BukkitTask::cancel);
        }
    }


    protected IntList slotORslots(String path) {
        IntList slots = new IntArrayList();

        int slot = guiConfig.getInt(path + ".slot", -1);
        if (slot != -1) {
            slots.add(slot);
            return slots;
        }

        if (guiConfig.isList(path + ".slots")) {
            List<?> slotsConfig = guiConfig.getList(path + ".slots");
            if (!slotsConfig.isEmpty() && slotsConfig.get(0) instanceof String) {
                slots.addAll(parseSlotList((List<String>) slotsConfig));
            } else {
                slots.addAll(guiConfig.getIntegerList(path + ".slots"));
            }
            return slots;
        }

        return slots;
    }


    protected static IntList parseSlotList(List<String> list) {
        IntList result = new IntArrayList();
        for (String str : list) {
            if (str.contains("-")) {
                String[] parts = str.split("-");
                int start = Integer.parseInt(parts[0]);
                int end = Integer.parseInt(parts[1]);
                for (int i = start; i <= end; i++) {
                    result.add(i);
                }
            } else {
                result.add(Integer.parseInt(str));
            }
        }
        return result;
    }
}
