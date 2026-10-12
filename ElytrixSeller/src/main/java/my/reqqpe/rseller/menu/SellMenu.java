package my.reqqpe.rseller.menu;


import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import me.clip.placeholderapi.PlaceholderAPI;
import my.reqqpe.rseller.Main;
import my.reqqpe.rseller.managers.NumberFormatManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SellMenu extends AbstractMenu implements org.bukkit.event.Listener {
    private final NumberFormatManager numberFormatManager;


    public SellMenu(Main plugin) {
        super(plugin);
        numberFormatManager = plugin.getFormatManager();
    }

    @org.bukkit.event.EventHandler
    public void onShiftClickToMenu(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getInventory().getHolder() instanceof CustomInventoryHolder holder)) return;
        if (!holder.id().equals(getMenuId())) return;

        // Обрабатываем shift-click из инвентаря игрока в special-slots меню
        if (e.isShiftClick() && e.getClickedInventory() == player.getInventory()) {
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;

            // Проверяем что предмет зарегистрирован
            if (plugin.getItemManager().search(item) == null) {
                e.setCancelled(true);
            }
        }
    }


    @Override
    protected FileConfiguration getGuiConfig() {
        return plugin.getAllSellGUIConfig().getConfig();
    }


    @Override
    protected String getMenuId() {
        return "allSellGUI";
    }


    @Override
    public void openMenu(Player player) {
        super.openMenu(player);
    }

    @EventHandler
    public void onInventoryClickUpdate(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getInventory().getHolder() instanceof CustomInventoryHolder holder)) return;
        if (!holder.id().equals(getMenuId())) return;

        // Update preview on shift-click from player inventory
        if (e.isShiftClick() && e.getClickedInventory() == player.getInventory()) {
            Inventory inv = player.getOpenInventory().getTopInventory();
            inv.getViewers().forEach(viewer -> {
                if (viewer instanceof Player p) {
                    p.updateInventory();
                }
            });
        }
    }

    @Override
    protected String replacePlaceholders(Player player, String text, Inventory inventory) {
        if (text == null || text.isEmpty()) return "";


        if (!(inventory.getHolder() instanceof CustomInventoryHolder holder)) return text;
        if (!holder.id().equals(getMenuId())) return text;


        var result = plugin.getSellManager().calculateSellPreview(player, inventory, new IntArrayList(specialSlots));
        String coinsFormatted = numberFormatManager.format("mainGUI.sell_price", result.coins());
        String pointsFormatted = numberFormatManager.format("mainGUI.sell_points", result.points());

        text = text.replace("{sell_price}", coinsFormatted);
        text = text.replace("{sell_points}", pointsFormatted);

        text = PlaceholderAPI.setPlaceholders(player, text);

        return text;
    }


    @Override
    protected List<String> replacePlaceholders(Player player, List<String> list, Inventory inventory) {
        List<String> replaced = new ArrayList<>();

        for (String s : list) {
            replaced.add(replacePlaceholders(player, s, inventory));
        }

        return replaced;
    }

    @Override
    public void closeMenu(Player player, InventoryCloseEvent e) {
        Inventory inv = e.getInventory();

        for (int slot : specialSlots) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
                for (ItemStack leftover : leftovers.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }
        super.closeMenu(player, e);
    }

    @Override
    protected boolean handleSpecialSlotClick(Player player, InventoryClickEvent e) {
        int rawSlot = e.getRawSlot();

        if (specialSlots.contains(rawSlot)) {
            // Проверяем попытку размещения предмета из инвентаря игрока
            ItemStack cursor = e.getCursor();
            ItemStack current = e.getCurrentItem();
            boolean hasCursor = cursor != null && cursor.getType() != Material.AIR;
            boolean hasCurrent = current != null && current.getType() != Material.AIR;

            // Разрешаем размещение только в пустой слот из курсора (обычный клик)
            if (hasCursor && !hasCurrent && !e.isShiftClick()) {
                // Проверяем что предмет зарегистрирован
                if (plugin.getItemManager().search(cursor) != null) {
                    e.setCancelled(false);
                } else {
                    e.setCancelled(true);
                }
            } else {
                // Всё остальное блокируем
                e.setCancelled(true);
            }
            return true;
        }

        return false;
    }

    protected void executeAction(Player player, String action) {
        if (action.equalsIgnoreCase("[sell]")) {
            Inventory inv = player.getOpenInventory().getTopInventory();
            if (!(inv.getHolder() instanceof CustomInventoryHolder holder)) return;
            if (!holder.id().equals(getMenuId())) return;

            IntList sellSlots = parseSlotList(guiConfig.getStringList("special-slots"));
            plugin.getSellManager().menuSellItems(player, inv, sellSlots);
        } else {
            runMainActions(player, action);
        }
    }
}