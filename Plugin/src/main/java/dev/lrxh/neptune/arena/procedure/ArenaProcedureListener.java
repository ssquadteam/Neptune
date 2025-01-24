package dev.lrxh.neptune.arena.procedure;

import dev.lrxh.neptune.API;
import dev.lrxh.neptune.arena.Arena;
import dev.lrxh.neptune.arena.ArenaService;
import dev.lrxh.neptune.arena.impl.StandAloneArena;
import dev.lrxh.neptune.arena.menu.ArenaCreateMenu;
import dev.lrxh.neptune.arena.menu.ArenaManagementMenu;
import dev.lrxh.neptune.profile.impl.Profile;
import dev.lrxh.neptune.utils.CC;
import lombok.AllArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;

@AllArgsConstructor
public class ArenaProcedureListener implements Listener {

    @EventHandler
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        Profile profile = API.getProfile(player);
        String input = event.getMessage();

        if (input.equalsIgnoreCase("Cancel") && !profile.getArenaProcedure().getType().equals(ArenaProcedureType.NONE)) {
            event.setCancelled(true);
            player.sendMessage(CC.success("Canceled Procedure"));
            profile.getArenaProcedure().setType(ArenaProcedureType.NONE);
            profile.getArenaProcedure().setArena(null);
            return;
        }

        switch (profile.getArenaProcedure().getType()) {
            case CREATE -> {
                event.setCancelled(true);
                profile.getArenaProcedure().setType(ArenaProcedureType.NONE);

                if (ArenaService.get().getArenaByName(input) != null) {
                    player.sendMessage(CC.error("Arena already exists"));
                    return;
                }

                new ArenaCreateMenu(input).open(player);
            }
            case RENAME -> {
                event.setCancelled(true);
                profile.getArenaProcedure().setType(ArenaProcedureType.NONE);
                profile.getArenaProcedure().getArena().setDisplayName(input);
                player.sendMessage(CC.success("Renamed arena"));
                new ArenaManagementMenu(profile.getArenaProcedure().getArena()).open(player);
                profile.getArenaProcedure().setArena(null);
            }
            case SET_SPAWN_RED -> {
                if (!input.equalsIgnoreCase("Done")) return;
                event.setCancelled(true);
                if (!profile.getArenaProcedure().getArena().isSetup()) {
                    profile.getArenaProcedure().setType(ArenaProcedureType.SET_SPAWN_BLUE);
                    player.sendMessage(CC.success("Set arena red spawn"));
                    profile.getArenaProcedure().getArena().setRedSpawn(player.getLocation());
                    player.sendMessage(CC.info("Go to the spawn of the &9blue &7player and type &aDone"));
                } else {
                    profile.getArenaProcedure().setType(ArenaProcedureType.NONE);
                    profile.getArenaProcedure().getArena().setRedSpawn(player.getLocation());
                    new ArenaManagementMenu(profile.getArenaProcedure().getArena()).open(player);
                    profile.getArenaProcedure().setArena(null);
                    player.sendMessage(CC.success("Set arena red &aspawn"));
                }
            }
            case SET_SPAWN_BLUE -> {
                if (!input.equalsIgnoreCase("Done")) return;
                event.setCancelled(true);
                profile.getArenaProcedure().setType(ArenaProcedureType.NONE);
                Arena arena = profile.getArenaProcedure().getArena();
                if (!arena.isSetup()) {
                    if (arena instanceof StandAloneArena) {
                        arena.setBlueSpawn(player.getLocation());
                        profile.getArenaProcedure().setType(ArenaProcedureType.SET_SPAWN_MIN);
                        player.sendMessage(CC.info("Go to the lowest edge of the arena and type &aDone"));
                        return;
                    } else {
                        player.sendMessage(CC.success("Arena setup complete"));
                    }
                } else {
                    player.sendMessage(CC.success("Set arena blue spawn"));
                }

                arena.setBlueSpawn(player.getLocation());
                new ArenaManagementMenu(arena).open(player);
                profile.getArenaProcedure().setArena(null);
            }
            case SET_SPAWN_MAX -> {
                if (!input.equalsIgnoreCase("Done")) return;
                event.setCancelled(true);
                profile.getArenaProcedure().setType(ArenaProcedureType.NONE);
                StandAloneArena arena = (StandAloneArena) profile.getArenaProcedure().getArena();
                if (!arena.isSetup()) {
                    player.sendMessage(CC.success("Arena setup complete"));
                } else {
                    player.sendMessage(CC.success("Set arena max position"));
                }
                arena.setMax(player.getLocation());
                new ArenaManagementMenu(profile.getArenaProcedure().getArena()).open(player);
                profile.getArenaProcedure().setArena(null);
            }
            case SET_SPAWN_MIN -> {
                if (!input.equalsIgnoreCase("Done")) return;
                event.setCancelled(true);
                profile.getArenaProcedure().setType(ArenaProcedureType.NONE);
                StandAloneArena arena = (StandAloneArena) profile.getArenaProcedure().getArena();
                if (!arena.isSetup()) {
                    arena.setMin(player.getLocation());
                    profile.getArenaProcedure().setType(ArenaProcedureType.SET_SPAWN_MAX);
                    player.sendMessage(CC.info("Go to the highest edge of the arena and type &aDone"));
                    return;
                }
                arena.setMin(player.getLocation());
                player.sendMessage(CC.success("Set arena min position"));
                new ArenaManagementMenu(profile.getArenaProcedure().getArena()).open(player);
                profile.getArenaProcedure().setArena(null);
            }
        }

        ArenaService.get().saveArenas();
    }
}
