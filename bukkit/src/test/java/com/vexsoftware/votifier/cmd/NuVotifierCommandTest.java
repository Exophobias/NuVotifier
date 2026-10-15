package com.vexsoftware.votifier.cmd;

import com.vexsoftware.votifier.NuVotifierBukkit;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NuVotifierCommandTest {
    @Test void partialStaffSeesOnlyItsActionAndClampedHelp() {
        NuVotifierBukkit plugin = mock(NuVotifierBukkit.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("nuvotifier.reload")).thenReturn(true);
        List<String> output = new ArrayList<>();
        doAnswer(call -> { output.add(ChatColor.stripColor(call.getArgument(0, String.class))); return null; }).when(sender).sendMessage(anyString());
        NuVotifierCommand command = new NuVotifierCommand(plugin);
        for (String[] args : List.of(new String[0], new String[]{"help"}, new String[]{"help", "0"}, new String[]{"help", "99"}, new String[]{"help", "banana"}, new String[]{"help", "staff", "99"})) {
            output.clear();
            command.onCommand(sender, null, "nuvotifier", args);
            assertEquals(3, output.size());
            assertTrue(output.get(0).contains("NuVotifier Staff"));
            assertTrue(output.get(1).contains("reload"));
            assertFalse(String.join("\n", output).contains("testvote"));
        }
        assertEquals(List.of("help", "reload"), command.onTabComplete(sender, null, "nuvotifier", new String[]{""}));
        verifyNoInteractions(plugin);
    }
    @Test void deniedActionsAliasesAndStaffHelpCannotDispatch() {
        NuVotifierBukkit plugin = mock(NuVotifierBukkit.class);
        CommandSender sender = mock(CommandSender.class);
        NuVotifierCommand command = new NuVotifierCommand(plugin);
        command.onCommand(sender, null, "nuvotifier", new String[]{"reload"});
        command.onCommand(sender, null, "nuvotifier", new String[]{"testvote", "someone"});
        command.onCommand(sender, null, "nuvotifier", new String[]{"help", "staff"});
        new NVReloadCmd(plugin).onCommand(sender, null, "nvreload", new String[0]);
        new TestVoteCmd(plugin).onCommand(sender, null, "testvote", new String[]{"someone"});
        assertTrue(command.onTabComplete(sender, null, "nuvotifier", new String[]{"help", ""}).isEmpty());
        verifyNoInteractions(plugin);
    }
    @Test void authorizedRootReloadUsesExistingHandler() {
        NuVotifierBukkit plugin = mock(NuVotifierBukkit.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("nuvotifier.reload")).thenReturn(true);
        when(plugin.reload()).thenReturn(true);
        new NuVotifierCommand(plugin).onCommand(sender, null, "nuvotifier", new String[]{"reload"});
        verify(plugin).reload();
    }
}
