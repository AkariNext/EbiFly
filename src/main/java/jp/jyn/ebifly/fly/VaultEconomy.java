package jp.jyn.ebifly.fly;

import jp.jyn.ebifly.config.MainConfig;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;

public class VaultEconomy {
    private final Economy economy;

    public VaultEconomy(MainConfig config) {
        if (Bukkit.getServer().getPluginManager().getPlugin("Vault") == null) {
            throw new IllegalStateException("Vault not found");
        }

        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            throw new IllegalStateException("Vault Economy is not available");
        }

        this.economy = rsp.getProvider();
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer getOfflinePlayer(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        var m = Pattern.compile(
            "^(\\p{XDigit}{8})-?(\\p{XDigit}{4})-?(\\p{XDigit}{4})-?(\\p{XDigit}{4})-?(\\p{XDigit}{12})$"
        ).matcher(value);

        return m.matches()
            ? Bukkit.getOfflinePlayer(UUID.fromString(m.replaceFirst("$1-$2-$3-$4-$5")))
            : Bukkit.getOfflinePlayer(value);
    }

    public String format(double value) {
        return economy.format(value);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (Double.compare(amount, 0.0d) <= 0) {
            throw new IllegalArgumentException("amount is 0 or less: " + amount);
        }

        // サーバーの残金がないからと言って特に何か出来るわけじゃない(どっちみち停止を要求されたflyは止める他ない)ので、あろうがなかろうが入金(返金)処理は続行する
        return economy.depositPlayer(player, amount).type == EconomyResponse.ResponseType.SUCCESS;
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (Double.compare(amount, 0.0d) <= 0) {
            throw new IllegalArgumentException("amount is 0 or less: " + amount);
        }

        if (economy.has(player, amount) &&
            economy.withdrawPlayer(player, amount).type == EconomyResponse.ResponseType.SUCCESS) {
            return true;
        }
        return false;
    }
}
