package dev.lrxh.neptune.kit.procedure;

import dev.lrxh.neptune.API;
import dev.lrxh.neptune.Neptune;
import dev.lrxh.neptune.hotbar.HotbarService;
import dev.lrxh.neptune.kit.Kit;
import dev.lrxh.neptune.kit.KitService;
import dev.lrxh.neptune.kit.menu.KitManagementMenu;
import dev.lrxh.neptune.kit.menu.KitsManagementMenu;
import dev.lrxh.neptune.profile.impl.Profile;
import dev.lrxh.neptune.utils.CC;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.math.NumberUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;

import java.util.Arrays;

public class KitProcedureListener implements Listener {
    @EventHandler
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        Profile profile = API.getProfile(player);
        String input = event.getMessage();

        if (input.equalsIgnoreCase("Cancel") && !profile.getKitProcedure().getType().equals(KitProcedureType.NONE)) {
            event.setCancelled(true);
            player.sendMessage(CC.success("Canceled Procedure"));
            profile.getKitProcedure().setType(KitProcedureType.NONE);
            profile.getKitProcedure().setKit(null);
            return;
        }

        switch (profile.getKitProcedure().getType()) {
            case CREATE -> {
                event.setCancelled(true);
                profile.getKitProcedure().setType(KitProcedureType.NONE);
                Kit kit = new Kit(input, player);

                if (KitService.get().add(kit)) {
                    player.sendMessage(CC.error("Kit already exists"));
                    return;
                }

                player.sendMessage(CC.success("Created kit"));
                new KitsManagementMenu().open(player);
            }
            case RENAME -> {
                event.setCancelled(true);
                profile.getKitProcedure().setType(KitProcedureType.NONE);
                profile.getKitProcedure().getKit().setDisplayName(input);
                player.sendMessage(CC.success("Renamed kit"));
                new KitManagementMenu(profile.getKitProcedure().getKit()).open(player);
            }
            case SET_SLOT -> {
                event.setCancelled(true);
                if (NumberUtils.isCreatable(input) && input.matches("-?\\d+")) {
                    profile.getKitProcedure().setType(KitProcedureType.NONE);
                    profile.getKitProcedure().getKit().setSlot(Integer.parseInt(event.getMessage()));
                    player.sendMessage(CC.success("New Slot set"));
                    new KitManagementMenu(profile.getKitProcedure().getKit()).open(player);
                } else {
                    player.sendMessage(CC.error("Not a valid number, please re-enter"));
                    return;
                }
            }
            case SET_INV -> {
                if (!input.equalsIgnoreCase("Done")) return;
                event.setCancelled(true);
                    profile.getKitProcedure().setType(KitProcedureType.NONE);
                    profile.getKitProcedure().getKit().setItems(Arrays.stream(player.getInventory().getContents()).toList());
                    player.sendMessage(CC.success("Set new inventory"));
                    new KitManagementMenu(profile.getKitProcedure().getKit()).open(player);
                HotbarService.get().giveItems(player);
            }
            case SET_ICON -> {
                if (!input.equalsIgnoreCase("Done")) return;
                event.setCancelled(true);
                Material material = player.getInventory().getItemInMainHand().getType();
                if (!material.equals(Material.AIR)) {
                    profile.getKitProcedure().setType(KitProcedureType.NONE);
                    profile.getKitProcedure().getKit().setIcon(player.getInventory().getItemInMainHand().clone());
                    player.sendMessage(CC.success("Set new icon"));
                    new KitManagementMenu(profile.getKitProcedure().getKit()).open(player);
                } else {
                    player.sendMessage(CC.error("You must be holding an item to set the icon, please try again"));
                    return;
                }
            }
        }
        profile.getKitProcedure().setKit(null);
        KitService.get().saveKits();
    }
}
