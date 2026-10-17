package ru.inferno.arena;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class InfernoArena extends JavaPlugin implements TabExecutor {

    public static final String ADMIN_PERM = "inferno.admin";
    private static final List<String> ADMIN_SUBS = List.of("info", "start", "stop", "reload", "tp");

    private ArenaManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File schem = new File(getDataFolder(), getConfig().getString("arena.schematic", "inferno_sky_arena.schem"));
        if (!schem.exists()) {
            saveResource("inferno_sky_arena.schem", false);
        }
        manager = new ArenaManager(this);
        getServer().getPluginManager().registerEvents(new ArenaListener(manager), this);

        PluginCommand cmd = getCommand("arena");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        manager.start();
        getLogger().info("Небесная Арена Инферно загружена. Тьма ждёт 18:00 МСК.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Обычные игроки видят только информацию. Админ-подкоманды для них не существуют.
        if (args.length == 0 || !sender.hasPermission(ADMIN_PERM)) {
            manager.sendInfo(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start": {
                Player here = null;
                if (args.length > 1 && args[1].equalsIgnoreCase("here")) {
                    if (sender instanceof Player p) {
                        here = p;
                    } else {
                        sender.sendMessage(manager.m("admin-only-player"));
                        return true;
                    }
                }
                String err = manager.startEvent(here == null ? null : here.getLocation());
                sender.sendMessage(err == null ? manager.m("admin-started") : manager.m("admin-error", "error", err));
                return true;
            }
            case "stop": {
                boolean ok = manager.stopEvent();
                sender.sendMessage(ok ? manager.m("admin-stopped") : manager.m("admin-not-active"));
                return true;
            }
            case "reload": {
                reloadConfig();
                manager.reloadSettings();
                sender.sendMessage(manager.m("admin-reloaded"));
                return true;
            }
            case "tp": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(manager.m("admin-only-player"));
                    return true;
                }
                sender.sendMessage(manager.teleportTo(p) ? manager.m("admin-tp") : manager.m("admin-not-active"));
                return true;
            }
            default:
                manager.sendInfo(sender);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String s : ADMIN_SUBS) {
                if (s.startsWith(pre)) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start") && "here".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            out.add("here");
        }
        return out;
    }
}
