package my.reqqpe.rseller.menu;

import my.reqqpe.rseller.Main;
import my.reqqpe.rseller.cache.PlayerDataCache;
import my.reqqpe.rseller.managers.MenuManager;
import my.reqqpe.rseller.utils.Colorizer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class AutoSellCategoryMenu extends AbstractMenu {

    public AutoSellCategoryMenu(Main plugin) {
        super(plugin);
    }

    @Override
    protected FileConfiguration getGuiConfig() {
        return plugin.getAutoSellGUIConfig().getConfig();
    }

    @Override
    protected String getMenuId() {
        return "autoSellGUI";
    }

    @Override
    protected String replaceTitlePlaceholders(Player player, String title) {
        String currentCategoryId = AutoSellMenu.getPlayerCategory(player.getUniqueId());
        if (currentCategoryId != null) {
            String currentCategoryName = plugin.getAutoSellManager().getCategoryName(currentCategoryId);
            title = title.replace("{current_category}", currentCategoryName);
        }
        return super.replaceTitlePlaceholders(player, title);
    }

    @Override
    protected void executeAction(Player player, String action) {
        String actionLower = action.toLowerCase().trim();

        if (actionLower.startsWith("[open_category]")) {
            String categoryId = action.substring("[open_category]".length()).trim();
            if (plugin.getAutoSellManager().getCategories().containsKey(categoryId)) {
                AutoSellMenu.setPlayerCategory(player.getUniqueId(), categoryId);
                AutoSellMenu.setPlayerPage(player.getUniqueId(), categoryId, 1);
                AbstractMenu itemsMenu = MenuManager.getMenu("autoSellItemsGUI");
                if (itemsMenu != null) {
                    itemsMenu.openMenu(player);
                } else {
                    plugin.getLogger().warning("autoSellItemsGUI menu not found!");
                }
            } else {
                plugin.getLogger().warning("AutoSellCategoryMenu: unknown category: " + categoryId);
            }
        } else if (actionLower.equals("[disable_all_autosell]")) {
            plugin.getItemManager().getAll().forEach(item -> {
                PlayerDataCache.getOrCreate(player.getUniqueId()).setAutosell(item.id(), false);
            });
            player.sendMessage(Colorizer.color("&7[&a✓&7] &fАвтоскупка отключена для всех предметов"));
        } else {
            runMainActions(player, action);
        }
    }
}
