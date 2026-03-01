package dev.mrshawn.deathmessages.commands;

import dev.mrshawn.deathmessages.api.PlayerCtx;
import dev.mrshawn.deathmessages.config.Settings;
import dev.mrshawn.deathmessages.config.UserData;
import dev.mrshawn.deathmessages.config.files.Config;
import dev.mrshawn.deathmessages.enums.Permission;
import dev.mrshawn.deathmessages.utils.ComponentUtil;
import dev.mrshawn.deathmessages.utils.Util;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class CommandBlacklist extends DeathMessagesCommand {

    @Override
    public String command() {
        return "blacklist";
    }

    @Override
    public void onCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(Permission.DEATHMESSAGES_COMMAND_BLACKLIST.getValue())) {
            ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.No-Permission"));
            return;
        }
        if (args.length == 0) {
            ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Help"));
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (args.length != 1) {
                ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Help"));
                return;
            }
            showBlacklist(sender);
            return;
        }

        final BlacklistTarget target;
        if (action.equals("deathmessage")) {
            target = BlacklistTarget.VICTIM;
        } else if (action.equals("killmessage")) {
            target = BlacklistTarget.KILLER;
        } else {
            ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Help"));
            return;
        }

        if (args.length < 2 || args.length > 3) {
            ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Help"));
            return;
        }

        String rawTargetName = args[1];
        Long durationMs = null;
        if (args.length == 3) {
            durationMs = parseDurationMs(args[2]);
            if (durationMs == null) {
                ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Help"));
                return;
            }
        }

        setBlacklist(sender, rawTargetName, target, durationMs);
    }

    private void setBlacklist(CommandSender sender, String rawTargetName, BlacklistTarget target, Long durationMs) {
        Player onlineTarget = Bukkit.getPlayer(rawTargetName);
        String targetName = (onlineTarget != null) ? onlineTarget.getDisplayName() : rawTargetName;

        // Saved-User-Data disabled: only online players can be handled.
        if (!Settings.getInstance().getConfig().getBoolean(Config.SAVED_USER_DATA.getPath())) {
            if (onlineTarget != null && onlineTarget.isOnline()) {
                PlayerCtx playerCtx = PlayerCtx.of(onlineTarget.getUniqueId());
                if (playerCtx == null) {
                    ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Username-None-Existent")
                            .replaceText(Util.replace("%player%", targetName)));
                    return;
                }

                if (durationMs != null) {
                    applyTimedOnline(playerCtx, target, durationMs);
                    ComponentUtil.sendMessage(sender, formatBlacklistAdd(targetName, target, formatDuration(durationMs)));
                    return;
                }

                if (isActive(playerCtx, target)) {
                    clearOnlineBlacklist(playerCtx, target);
                    ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Blacklist-Remove")
                            .replaceText(Util.replace("%player%", targetName)));
                } else {
                    setOnlinePermanent(playerCtx, target, true);
                    ComponentUtil.sendMessage(sender, formatBlacklistAdd(targetName, target, "permanent"));
                }
                return;
            }

            ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Username-None-Existent")
                    .replaceText(Util.replace("%player%", targetName)));
            return;
        }

        FileConfiguration userData = UserData.getInstance().getConfig();
        for (Map.Entry<String, Object> entry : userData.getValues(false).entrySet()) {
            String uuid = entry.getKey();
            String username = userData.getString(uuid + ".username");
            if (username == null || !username.equalsIgnoreCase(rawTargetName)) {
                continue;
            }

            PlayerCtx playerCtx = null;
            try {
                playerCtx = PlayerCtx.of(UUID.fromString(uuid));
            } catch (IllegalArgumentException ignored) {
            }

            if (durationMs != null) {
                if (playerCtx != null) {
                    applyTimedOnline(playerCtx, target, durationMs);
                } else {
                    long until = safeUntil(durationMs);
                    setOfflineUntil(userData, uuid, target, until);
                    UserData.getInstance().save();
                }
                ComponentUtil.sendMessage(sender, formatBlacklistAdd(targetName, target, formatDuration(durationMs)));
                return;
            }

            if (isActive(userData, uuid, playerCtx, target)) {
                if (playerCtx != null) {
                    clearOnlineBlacklist(playerCtx, target);
                } else {
                    clearOfflineBlacklist(userData, uuid, target);
                    UserData.getInstance().save();
                }
                ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Blacklist-Remove")
                        .replaceText(Util.replace("%player%", targetName)));
            } else {
                if (playerCtx != null) {
                    setOnlinePermanent(playerCtx, target, true);
                } else {
                    setOfflinePermanent(userData, uuid, target, true);
                    UserData.getInstance().save();
                }
                ComponentUtil.sendMessage(sender, formatBlacklistAdd(targetName, target, "permanent"));
            }
            return;
        }

        ComponentUtil.sendMessage(sender, Util.formatMessage("Commands.DeathMessages.Sub-Commands.Blacklist.Username-None-Existent")
                .replaceText(Util.replace("%player%", rawTargetName)));
    }

    private void showBlacklist(CommandSender sender) {
        List<Component> lines = new ArrayList<>();
        lines.add(Util.convertFromLegacy("&aBlacklist List").replaceText(Util.PREFIX));

        if (Settings.getInstance().getConfig().getBoolean(Config.SAVED_USER_DATA.getPath())) {
            FileConfiguration userData = UserData.getInstance().getConfig();
            for (Map.Entry<String, Object> entry : userData.getValues(false).entrySet()) {
                String uuid = entry.getKey();
                String username = userData.getString(uuid + ".username");
                if (username == null || username.isEmpty()) {
                    continue;
                }

                PlayerCtx playerCtx = null;
                try {
                    playerCtx = PlayerCtx.of(UUID.fromString(uuid));
                } catch (IllegalArgumentException ignored) {
                }

                String deathMessageState = stateOf(userData, uuid, playerCtx, BlacklistTarget.VICTIM);
                String killMessageState = stateOf(userData, uuid, playerCtx, BlacklistTarget.KILLER);
                if (deathMessageState == null && killMessageState == null) {
                    continue;
                }

                lines.add(Util.convertFromLegacy("&7- &e" + username
                        + "&7 | deathmessage: " + (deathMessageState == null ? "&aoff" : deathMessageState)
                        + "&7, killmessage: " + (killMessageState == null ? "&aoff" : killMessageState)));
            }
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerCtx playerCtx = PlayerCtx.of(player.getUniqueId());
                if (playerCtx == null) {
                    continue;
                }

                String deathMessageState = stateOf(playerCtx, BlacklistTarget.VICTIM);
                String killMessageState = stateOf(playerCtx, BlacklistTarget.KILLER);
                if (deathMessageState == null && killMessageState == null) {
                    continue;
                }

                lines.add(Util.convertFromLegacy("&7- &e" + player.getName()
                        + "&7 | deathmessage: " + (deathMessageState == null ? "&aoff" : deathMessageState)
                        + "&7, killmessage: " + (killMessageState == null ? "&aoff" : killMessageState)));
            }
        }

        if (lines.size() == 1) {
            lines.add(Util.convertFromLegacy("&7(Empty)"));
        }

        for (Component line : lines) {
            ComponentUtil.sendMessage(sender, line);
        }
    }

    private enum BlacklistTarget {
        VICTIM,
        KILLER
    }

    private boolean isActive(PlayerCtx playerCtx, BlacklistTarget target) {
        return target == BlacklistTarget.KILLER ? playerCtx.isKillerBlacklisted() : playerCtx.isBlacklisted();
    }

    private boolean isActive(FileConfiguration userData, String uuid, PlayerCtx playerCtx, BlacklistTarget target) {
        if (playerCtx != null) {
            return isActive(playerCtx, target);
        }
        long now = System.currentTimeMillis();
        if (target == BlacklistTarget.KILLER) {
            boolean permanent = userData.getBoolean(uuid + ".is-killer-blacklisted", false);
            long until = userData.getLong(uuid + ".killer-blacklisted-until", 0L);
            return permanent || until > now;
        }
        boolean permanent = userData.getBoolean(uuid + ".is-blacklisted", false);
        long until = userData.getLong(uuid + ".blacklisted-until", 0L);
        return permanent || until > now;
    }

    private void setOnlinePermanent(PlayerCtx playerCtx, BlacklistTarget target, boolean value) {
        if (target == BlacklistTarget.KILLER) {
            playerCtx.setKillerBlacklisted(value);
            if (value) {
                playerCtx.setKillerBlacklistedUntil(0L);
            }
            return;
        }

        playerCtx.setBlacklisted(value);
        if (value) {
            playerCtx.setBlacklistedUntil(0L);
        }
    }

    private void setOfflinePermanent(FileConfiguration userData, String uuid, BlacklistTarget target, boolean value) {
        if (target == BlacklistTarget.KILLER) {
            userData.set(uuid + ".is-killer-blacklisted", value);
            if (value) {
                userData.set(uuid + ".killer-blacklisted-until", 0L);
            }
            return;
        }

        userData.set(uuid + ".is-blacklisted", value);
        if (value) {
            userData.set(uuid + ".blacklisted-until", 0L);
        }
    }

    private void applyTimedOnline(PlayerCtx playerCtx, BlacklistTarget target, long durationMs) {
        long until = safeUntil(durationMs);
        if (target == BlacklistTarget.KILLER) {
            playerCtx.setKillerBlacklisted(false);
            playerCtx.setKillerBlacklistedUntil(until);
            return;
        }
        playerCtx.setBlacklisted(false);
        playerCtx.setBlacklistedUntil(until);
    }

    private void setOfflineUntil(FileConfiguration userData, String uuid, BlacklistTarget target, long until) {
        if (target == BlacklistTarget.KILLER) {
            userData.set(uuid + ".is-killer-blacklisted", false);
            userData.set(uuid + ".killer-blacklisted-until", until);
            return;
        }

        userData.set(uuid + ".is-blacklisted", false);
        userData.set(uuid + ".blacklisted-until", until);
    }

    private void clearOnlineBlacklist(PlayerCtx playerCtx, BlacklistTarget target) {
        if (target == BlacklistTarget.KILLER) {
            playerCtx.setKillerBlacklisted(false);
            playerCtx.setKillerBlacklistedUntil(0L);
            return;
        }
        playerCtx.setBlacklisted(false);
        playerCtx.setBlacklistedUntil(0L);
    }

    private void clearOfflineBlacklist(FileConfiguration userData, String uuid, BlacklistTarget target) {
        if (target == BlacklistTarget.KILLER) {
            userData.set(uuid + ".is-killer-blacklisted", false);
            userData.set(uuid + ".killer-blacklisted-until", 0L);
            return;
        }
        userData.set(uuid + ".is-blacklisted", false);
        userData.set(uuid + ".blacklisted-until", 0L);
    }

    private String stateOf(PlayerCtx playerCtx, BlacklistTarget target) {
        long now = System.currentTimeMillis();
        if (target == BlacklistTarget.KILLER) {
            if (playerCtx.isKillerBlacklisted()) {
                long until = playerCtx.getKillerBlacklistedUntil();
                if (until > now) {
                    return "&e" + formatDuration(until - now);
                }
                return "&cpermanent";
            }
            return null;
        }

        if (playerCtx.isBlacklisted()) {
            long until = playerCtx.getBlacklistedUntil();
            if (until > now) {
                return "&e" + formatDuration(until - now);
            }
            return "&cpermanent";
        }
        return null;
    }

    private String stateOf(FileConfiguration userData, String uuid, PlayerCtx playerCtx, BlacklistTarget target) {
        if (playerCtx != null) {
            return stateOf(playerCtx, target);
        }

        long now = System.currentTimeMillis();
        if (target == BlacklistTarget.KILLER) {
            boolean permanent = userData.getBoolean(uuid + ".is-killer-blacklisted", false);
            long until = userData.getLong(uuid + ".killer-blacklisted-until", 0L);
            if (permanent) {
                return "&cpermanent";
            }
            if (until > now) {
                return "&e" + formatDuration(until - now);
            }
            return null;
        }

        boolean permanent = userData.getBoolean(uuid + ".is-blacklisted", false);
        long until = userData.getLong(uuid + ".blacklisted-until", 0L);
        if (permanent) {
            return "&cpermanent";
        }
        if (until > now) {
            return "&e" + formatDuration(until - now);
        }
        return null;
    }

    private String formatDuration(long remainingMs) {
        long sec = Math.max(1L, remainingMs / 1000L);
        long days = sec / 86400L;
        sec %= 86400L;
        long hours = sec / 3600L;
        sec %= 3600L;
        long minutes = sec / 60L;
        sec %= 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (sec > 0 || sb.length() == 0) sb.append(sec).append("s");
        return sb.toString().trim();
    }

    private Component formatBlacklistAdd(String targetName, BlacklistTarget target, String durationLabel) {
        return Util.convertFromLegacy("%prefix%&aBlacklist &e" + targetName + " &7(" + getTargetName(target) + ") &afor &e" + durationLabel + "&a!")
                .replaceText(Util.PREFIX);
    }

    private String getTargetName(BlacklistTarget target) {
        return target == BlacklistTarget.KILLER ? "killmessage" : "deathmessage";
    }

    private long safeUntil(long durationMs) {
        long now = System.currentTimeMillis();
        if (durationMs >= Long.MAX_VALUE - now) {
            return Long.MAX_VALUE;
        }
        return now + durationMs;
    }

    private Long parseDurationMs(String raw) {
        if (raw.length() < 2) {
            return null;
        }

        String number = raw.substring(0, raw.length() - 1);
        char unit = raw.charAt(raw.length() - 1);
        long value;
        try {
            value = Long.parseLong(number);
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (value <= 0) {
            return null;
        }

        long multiplier;
        switch (Character.toLowerCase(unit)) {
            case 's':
                multiplier = 1000L;
                break;
            case 'm':
                multiplier = 60_000L;
                break;
            case 'h':
                multiplier = 3_600_000L;
                break;
            case 'd':
                multiplier = 86_400_000L;
                break;
            default:
                return null;
        }

        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }
}
