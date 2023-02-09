package jp.jyn.ebifly;

import jp.jyn.ebifly.fly.FlyRepository;
import me.clip.placeholderapi.PlaceholderHook;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EbiflyPlaceholders extends PlaceholderExpansion {
    FlyRepository flyRepository;

    public EbiflyPlaceholders(FlyRepository flyRepository) {
        this.flyRepository = flyRepository;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null)
            return null;
        if (identifier.equals("fly_time_left")) {
            return String.valueOf(flyRepository.getTimeLeft(player));
        }

        return null;
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
