package net.novucs.ftop.manager;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.novucs.ftop.FactionsTopPlugin;
import net.novucs.ftop.PluginService;
import net.novucs.ftop.entity.BlockPos;
import net.novucs.ftop.entity.FactionWorth;
import net.novucs.ftop.util.SplaySet;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class SignManager extends BukkitRunnable implements PluginService, Listener {

    private static final Pattern signRegex = Pattern.compile("\\[f(|actions)top\\]");
    private final FactionsTopPlugin plugin;
    private final Multimap<Integer, BlockPos> signs = HashMultimap.create();
    private final Map<Integer, Double> previous = new HashMap<>();

    public SignManager(FactionsTopPlugin plugin) {
        this.plugin = plugin;
    }

    public void setSigns(Multimap<Integer, BlockPos> signs) {
        this.signs.clear();
        this.signs.putAll(signs);
    }

    @Override
    public void initialize() {
        int ticks = plugin.getSettings().getSignUpdateTicks();
        runTaskTimer(plugin, ticks, ticks);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void terminate() {
        cancel();
        HandlerList.unregisterAll(this);
    }

    @Override
    public void run() {
        SplaySet<FactionWorth> factions = plugin.getWorthManager().getOrderedFactions();

        for (Map.Entry<Integer, Collection<BlockPos>> entry : signs.asMap().entrySet()) {
            // Do nothing if rank is higher than factions size.
            if (entry.getKey() >= factions.size()) continue;

            // Get the faction worth.
            FactionWorth worth = factions.byIndex(entry.getKey());

            // Do not update signs if previous value is unchanged.
            double previousWorth = previous.getOrDefault(entry.getKey(), 0d);
            if (previousWorth == worth.getTotalWorth()) {
                continue;
            }

            previous.put(entry.getKey(), worth.getTotalWorth());

            // Update all signs.
            for (BlockPos pos : entry.getValue()) {
                Block block = pos.getBlock(plugin.getServer());
                if (block == null || !(block.getState() instanceof Sign)) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> removeSign(pos));
                    continue;
                }

                Sign sign = (Sign) block.getState();
                sign.setLine(2, worth.getName());
                sign.setLine(3, plugin.getSettings().getCurrencyFormat().format(worth.getTotalWorth()));
                sign.update();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void registerSign(SignChangeEvent event) {
        // Do nothing if the sign should not be registered.
        if (!event.getPlayer().hasPermission("factionstop.sign.create") ||
                !signRegex.matcher(event.getLine(0).toLowerCase()).find()) {
            return;
        }

        // Attempt to parse the rank for this sign.
        int rank;
        try {
            rank = Integer.parseInt(event.getLine(1));
        } catch (NumberFormatException e) {
            event.getPlayer().sendMessage(ChatColor.RED + "Invalid rank number on line 2!");
            event.setLine(0, ChatColor.DARK_RED + "[FactionsTop]");
            return;
        }

        event.setLine(0, ChatColor.DARK_BLUE + "[FactionsTop]");
        event.setLine(1, "#" + Math.max(rank, 1));

        rank = Math.max(rank - 1, 0);
        SplaySet<FactionWorth> factions = plugin.getWorthManager().getOrderedFactions();

        if (factions.size() > rank) {
            FactionWorth worth = factions.byIndex(rank);
            event.setLine(2, worth.getName());
            event.setLine(3, plugin.getSettings().getCurrencyFormat().format(worth.getTotalWorth()));
        } else {
            event.setLine(2, "-");
            event.setLine(3, "$0.00");
        }

        saveSign(BlockPos.of(event.getBlock()), rank);
    }

    private void saveSign(BlockPos pos, int rank) {
        signs.put(rank, pos);
        plugin.getPersistenceTask().queueCreatedSign(pos, rank);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void unregisterSign(BlockBreakEvent event) {
        // Do nothing if block is not a registered sign.
        BlockPos pos = BlockPos.of(event.getBlock());
        if (!signs.containsValue(pos)) {
            return;
        }

        if (!(event.getBlock().getState() instanceof Sign)) {
            removeSign(pos);
            return;
        }

        if (!event.getPlayer().hasPermission("factionstop.sign.break")) {
            event.getPlayer().sendMessage(plugin.getSettings().getPermissionMessage());
            event.setCancelled(true);
            return;
        }

        removeSign(pos);
    }

    private void removeSign(BlockPos pos) {
        signs.values().remove(pos);
        plugin.getPersistenceTask().queueDeletedSign(pos);
    }
}
