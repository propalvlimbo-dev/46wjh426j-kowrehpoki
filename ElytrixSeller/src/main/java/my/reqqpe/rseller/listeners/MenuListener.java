package my.reqqpe.rseller.listeners;

import my.reqqpe.rseller.Main;
import my.reqqpe.rseller.managers.MenuManager;
import my.reqqpe.rseller.menu.AbstractMenu;
import my.reqqpe.rseller.menu.CustomInventoryHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;

public class MenuListener implements Listener {

    private final Main plugin;

    public MenuListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!(e.getInventory().getHolder() instanceof CustomInventoryHolder holder)) return;

        String id = holder.id();
        AbstractMenu menu = MenuManager.getMenu(id);
        if (menu == null) return;

        Player player = (Player) e.getWhoClicked();
        Inventory menuInv = e.getInventory();
        Inventory clickedInv = e.getClickedInventory();

        // === ПОЛНАЯ БЛОКИРОВКА ИЗВЛЕЧЕНИЯ ИЗ МЕНЮ ===

        // 1. Shift-click из верхнего инвентаря (меню) в инвентарь игрока — ВСЕГДА блокируем
        if (e.isShiftClick() && clickedInv == menuInv) {
            e.setCancelled(true);
            return;
        }

        // 2. Обычный клик по верхнему инвентарю — пусть обработает меню,
        //    но handleSpecialSlotClick сам решит что разрешить
        if (clickedInv == menuInv) {
            menu.handleClick(player, e);
            return;
        }

        // 3. Shift-click из инвентаря игрока в меню — только для allSellGUI в special-slots
        if (e.isShiftClick() && clickedInv == player.getInventory()) {
            if (!id.equals("allSellGUI")) {
                e.setCancelled(true);
            }
            // Для allSellGUI — пусть обработает handleClick который проверит special-slots
            return;
        }

        // 4. Обычный клик по инвентарю игрока при открытом меню — блокируем (чтобы не вытащить курсором)
        if (clickedInv == player.getInventory() && e.getCursor() != null && e.getCursor().getType().name().endsWith("_HEAD")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!(e.getInventory().getHolder() instanceof CustomInventoryHolder holder)) return;

        AbstractMenu menu = MenuManager.getMenu(holder.id());
        if (menu == null) return;

        // Блокируем drag в верхнем инвентаре (меню)
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot < e.getInventory().getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder() instanceof CustomInventoryHolder) {
            e.setCancelled(true);
        }
        if (e.getDestination().getHolder() instanceof CustomInventoryHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof CustomInventoryHolder holder)) return;
        AbstractMenu menu = MenuManager.getMenu(holder.id());
        if (menu == null) return;

        Player player = (Player) e.getPlayer();
        menu.closeMenu(player, e);
    }
}
