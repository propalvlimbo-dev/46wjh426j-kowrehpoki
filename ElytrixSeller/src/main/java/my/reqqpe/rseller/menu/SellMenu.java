package my.reqqpe.rseller.menu;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import me.clip.placeholderapi.PlaceholderAPI;
import my.reqqpe.rseller.Main;
import my.reqqpe.rseller.managers.NumberFormatManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SellMenu extends AbstractMenu {
    private final NumberFormatManager numberFormatManager;


    public SellMenu(Main plugin) {
        super(plugin);
        numberFormatManager = plugin.getFormatManager();
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

    /**
     * Единственное разрешённое перемещение предмета внутрь скупщика — shift-клик
     * по своему инвентарю, и только если предмет зарегистрирован в items.yml.
     * Забрать предмет из скупщика нельзя никаким способом: он вернётся игроку
     * сам при закрытии меню.
     */
    @Override
    public boolean allowDepositFromPlayerInventory(Player player, InventoryClickEvent e) {
        ItemStack item = e.getCurrentItem();
        if (!hasItem(item)) return false;

        return plugin.getItemManager().search(item) != null;
    }

    @Override
    public void closeMenu(Player player, InventoryCloseEvent e) {
        Inventory inv = e.getInventory();

        // Возвращаем игроку всё, что он положил в скупщика.
        // Кнопки меню (slotToItemId) не отдаём никогда.
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (slotToItemId.containsKey(slot)) continue;

            ItemStack item = inv.getItem(slot);
            if (!hasItem(item)) continue;

            inv.setItem(slot, null);
            giveOrDrop(player, item);
        }

        super.closeMenu(player, e);
    }

    @Override
    protected boolean handleSpecialSlotClick(Player player, InventoryClickEvent e) {
        int rawSlot = e.getRawSlot();

        if (!specialSlots.contains(rawSlot)) return false;

        // По умолчанию всё запрещено: забрать предмет из скупщика нельзя
        // ни обычным кликом, ни shift-кликом, ни номером хотбара, ни Q, ни F.
        e.setCancelled(true);

        // shift-клик — это перенос в меню, им занимается allowDepositFromPlayerInventory
        if (e.isShiftClick()) return true;

        // Обычный клик: разрешаем только ПОЛОЖИТЬ предмет из курсора в пустую ячейку
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();

        if (!hasItem(cursor)) return true;                              // курсор пуст — предмет не отдаём
        if (hasItem(current)) return true;                              // ячейка занята — предметы не меняем
        if (plugin.getItemManager().search(cursor) == null) return true; // предмет не продаётся — не принимаем

        // Единственное исключение из полной блокировки — положить предмет в скупщика
        e.setCancelled(false);
        return true;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    @Override
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
