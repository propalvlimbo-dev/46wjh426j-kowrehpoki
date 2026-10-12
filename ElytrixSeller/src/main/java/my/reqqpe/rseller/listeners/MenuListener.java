package my.reqqpe.rseller.listeners;

import my.reqqpe.rseller.Main;
import my.reqqpe.rseller.managers.MenuManager;
import my.reqqpe.rseller.menu.AbstractMenu;
import my.reqqpe.rseller.menu.CustomInventoryHolder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * Полная защита меню ElytrixSeller.
 * <p>
 * Забрать предмет из меню нельзя НИКАКИМ способом:
 * <ul>
 *     <li>обычный клик (забрать в курсор);</li>
 *     <li>shift-клик (быстрый перенос в инвентарь);</li>
 *     <li>клик номером хотбара 1-9;</li>
 *     <li>клавиша Q / Ctrl+Q (выброс предмета);</li>
 *     <li>клавиша F (обмен с офф-хендом);</li>
 *     <li>двойной клик (сбор одинаковых предметов в курсор);</li>
 *     <li>перетаскивание (drag / shift-drag);</li>
 *     <li>воронки, дропперы и любой другой InventoryMoveItem.</li>
 * </ul>
 * Обработчик стоит дважды: на LOWEST (первым отменяет перемещение) и на MONITOR
 * (последним, уже после всех сторонних плагинов — повторно ставит отмену, если
 * кто-то попытался её снять). Кнопки меню при этом продолжают работать.
 * <p>
 * Единственное разрешённое перемещение — ПОЛОЖИТЬ предмет в скупщика
 * (allSellGUI): shift-клик по своему инвентарю или курсором в пустую ячейку.
 * Забрать предмет обратно нельзя — он вернётся сам при закрытии меню.
 */
public class MenuListener implements Listener {

    private final Main plugin;

    public MenuListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        protectClick(e, false);
    }

    /**
     * Последний проход: выполняется уже после всех остальных плагинов и не даёт
     * снять блокировку (предмет всё равно не покинет меню).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClickFinal(InventoryClickEvent e) {
        protectClick(e, true);
    }

    private void protectClick(InventoryClickEvent e, boolean finalPass) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof CustomInventoryHolder holder)) return;

        AbstractMenu menu = MenuManager.getMenu(holder.id());

        // Единственное разрешённое исключение: положить предмет в меню (скупщик)
        boolean depositAllowed = menu != null && menu.isDepositAllowed(player, e);

        if (finalPass) {
            if (!depositAllowed) {
                e.setCancelled(true);
            }
            return;
        }

        ClickType click = e.getClick();
        InventoryAction action = e.getAction();

        // 1. Двойной клик / сбор в курсор — ваниль собирает одинаковые предметы
        //    со всего открытого окна, в том числе из меню. Блокируем всегда.
        if (click == ClickType.DOUBLE_CLICK || action == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        Inventory clicked = e.getClickedInventory();
        int rawSlot = e.getRawSlot();

        // Клик по меню = либо верхний инвентарь, либо любой слот из его диапазона
        boolean clickedTop = clicked != null
                && (clicked.equals(top) || (rawSlot >= 0 && rawSlot < top.getSize()));
        // Всё остальное внутри окна — инвентарь игрока
        boolean clickedBottom = clicked != null && !clickedTop;

        // 2. Клик по меню — перемещение предметов запрещено.
        //    Отменяем ДО логики меню, чтобы предмет не «ушёл» ни в курсор,
        //    ни в инвентарь. Кнопки меню при этом работают.
        if (clickedTop) {
            e.setCancelled(true);
            if (menu != null) {
                // Меню само решает: кнопки — работают, положить предмет в скупщика — можно,
                // забрать предмет — нельзя.
                menu.handleClick(player, e);
            }
            return;
        }

        // 3. Клик по инвентарю игрока, пока открыто меню.
        if (clickedBottom) {
            if (depositAllowed) return;

            switch (action) {
                // Быстрый перенос (shift-клик) — любой такой перенос уходит в меню
                case MOVE_TO_OTHER_INVENTORY -> e.setCancelled(true);
                // Номер хотбара / офф-хенд: опасны, только если под курсором что-то есть
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                    if (hasItem(e.getCursor())) {
                        e.setCancelled(true);
                    }
                }
                default -> {
                }
            }
            return;
        }

        // 4. Клик мимо окна (выброс предмета из-под курсора)
        if (!depositAllowed) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent e) {
        protectDrag(e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDragFinal(InventoryDragEvent e) {
        protectDrag(e);
    }

    private void protectDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;

        InventoryView view = e.getView();
        if (!(view.getTopInventory().getHolder() instanceof CustomInventoryHolder)) return;

        // Любой драг при открытом меню запрещён: часть ячеек может оказаться в меню,
        // а shift-драг умеет вытаскивать предметы из меню в инвентарь игрока.
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent e) {
        // Воронки, дропперы, хопперы и т.п. не могут трогать меню
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

    private static boolean hasItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }
}
