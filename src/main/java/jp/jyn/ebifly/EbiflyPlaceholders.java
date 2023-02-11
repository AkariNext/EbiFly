package jp.jyn.ebifly;

import jp.jyn.ebifly.fly.FlyRepository;
import me.clip.placeholderapi.PlaceholderHook;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class EbiflyPlaceholders extends PlaceholderExpansion {
    FlyRepository flyRepository;

    public EbiflyPlaceholders(FlyRepository flyRepository) {
        this.flyRepository = flyRepository;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null)
            return null;
        if (identifier.equals("time_left")) {
            FlyRepository.FlightStatus fs = flyRepository.flying.get(player.getUniqueId());
            return String.valueOf(flyRepository.getTimeLeft(fs));
        }

        return null;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String identifier) {
        if (offlinePlayer.isOnline()) {
            return onPlaceholderRequest(offlinePlayer.getPlayer(), identifier);
        }
        return "";
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ebifly";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cronree-91";
    }

    @Override
    public @NotNull String getVersion() {
        return "v0.1";
    }
}
