package my.reqqpe.rseller.economy;

import org.bukkit.entity.Player;

/**
 * CoinsEngine support disabled (repository unavailable).
 * Use VAULT or PLAYERPOINTS economy type instead.
 */
public class CoinsEngineEconomyProvider implements EconomyProvider {

    public CoinsEngineEconomyProvider(String currencyName) {
        throw new UnsupportedOperationException(
                "CoinsEngine support is disabled in this build. Use VAULT or PLAYERPOINTS economy type in config.yml"
        );
    }

    @Override
    public double getBalance(Player player) {
        return 0;
    }

    @Override
    public void deposit(Player player, double amount) {
    }

    @Override
    public void withdraw(Player player, double amount) {
    }
}
