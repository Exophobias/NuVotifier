package com.vexsoftware.votifier.cmd;

import com.vexsoftware.votifier.NuVotifierBukkit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** Permission-filtered documentation; action handlers independently enforce their permissions. */
public final class NuVotifierCommand implements TabExecutor {
    private final NuVotifierBukkit plugin;
    public NuVotifierCommand(NuVotifierBukkit plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
        } else if (args[0].equalsIgnoreCase("reload")) {
            new NVReloadCmd(plugin).onCommand(sender, command, label, Arrays.copyOfRange(args, 1, args.length));
        } else if (args[0].equalsIgnoreCase("testvote")) {
            new TestVoteCmd(plugin).onCommand(sender, command, label, Arrays.copyOfRange(args, 1, args.length));
        } else {
            sender.sendMessage(ChatColor.RED + "Unknown command.");
            help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        if (sender.hasPermission("nuvotifier.reload")) lines.add("/nuvotifier reload|/nvreload - reload configuration and the vote listener");
        if (sender.hasPermission("nuvotifier.testvote")) lines.add("/nuvotifier testvote <username> [key=value...] - dispatch a test vote");
        if (lines.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to do this!");
            return;
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "---------- " + ChatColor.GREEN + "NuVotifier Staff" + ChatColor.DARK_GRAY + " ----------");
        for (String line : lines) {
            String[] parts = line.split(" - ", 2);
            sender.sendMessage(ChatColor.GREEN + parts[0] + ChatColor.DARK_GRAY + " - " + ChatColor.GRAY + parts[1]);
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "----------------------------------------");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        boolean reload = sender.hasPermission("nuvotifier.reload");
        boolean testvote = sender.hasPermission("nuvotifier.testvote");
        if (args.length == 1) {
            options.add("help");
            if (reload) options.add("reload");
            if (testvote) options.add("testvote");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("help") && (reload || testvote)) {
            options.add("staff");
            options.add("1");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
