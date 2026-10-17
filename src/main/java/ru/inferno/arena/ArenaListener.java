package ru.inferno.arena;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class ArenaListener implements Listener {

    private final ArenaManager mgr;

    public ArenaListener(ArenaManager mgr) {
        this.mgr = mgr;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !mgr.isPrize(b)) return;
        e.setCancelled(true);
        mgr.onPrizeClick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (mgr.isPrize(b)) {
            e.setCancelled(true);
            return;
        }
        if (mgr.protectBreak() && mgr.inArena(b.getLocation()) && !e.getPlayer().hasPermission(InfernoArena.ADMIN_PERM)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (mgr.protectExplosions()) {
            e.blockList().removeIf(b -> mgr.inArena(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (mgr.protectExplosions()) {
            e.blockList().removeIf(b -> mgr.inArena(b.getLocation()));
        }
    }

    /** Фаза 1: вместо смерти «лопается» тотем и страж становится сильнее. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuardDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || !mgr.isGuard(le)) return;
        if (mgr.phaseOf(le) == 1 && le.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            mgr.enrage(le);
        }
    }

    /** Урон стражей фиксирован конфигом (8 / 10), плюс поджог от «Заговора огня II». */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGuardHit(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (mgr.isGuard(e.getEntity()) && mgr.isGuard(damager)) {
            e.setCancelled(true);
            return;
        }
        if (damager instanceof LivingEntity le && mgr.isGuard(le)) {
            e.setDamage(mgr.damageFor(le));
            if (e.getEntity() instanceof Player p) {
                p.setFireTicks(Math.max(p.getFireTicks(), 160)); // огонь II = 8 сек
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        if (!mgr.isGuard(le)) return;
        e.getDrops().clear();
        e.setDroppedExp(mgr.guardExp());
        mgr.guardDied(le);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        mgr.addPlayer(e.getPlayer());
    }
}
