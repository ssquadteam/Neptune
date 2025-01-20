package dev.lrxh.neptune.hotbar;

import dev.lrxh.neptune.API;
import dev.lrxh.neptune.Neptune;
import dev.lrxh.neptune.configs.ConfigManager;
import dev.lrxh.neptune.hotbar.impl.CustomItem;
import dev.lrxh.neptune.hotbar.impl.Hotbar;
import dev.lrxh.neptune.hotbar.impl.Item;
import dev.lrxh.neptune.hotbar.impl.ItemAction;
import dev.lrxh.neptune.kit.Kit;
import dev.lrxh.neptune.kit.KitManager;
import dev.lrxh.neptune.profile.data.ProfileState;
import dev.lrxh.neptune.providers.clickable.Replacement;
import dev.lrxh.neptune.providers.manager.IManager;
import dev.lrxh.neptune.utils.ConfigFile;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

@Getter
public class HotbarManager implements IManager {
    private static HotbarManager instance;
    private final Map<ProfileState, Hotbar> items = new HashMap<>();
    private final Neptune plugin;

    public HotbarManager() {
        this.plugin = Neptune.get();
        loadItems();
    }

    public static HotbarManager get() {
        if (instance == null) instance = new HotbarManager();

        return instance;
    }

    Item getItem(Hotbar inventory, int slot) {
        Item[] slots = inventory.getSlots();
        if (slot >= 0 && slot < slots.length) {
            return slots[slot];
        } else {
            return null;
        }
    }

    public void giveItems(Player player) {
        player.getInventory().clear();
        ProfileState profileState = API.getProfile(player).getState();
        if (profileState.equals(ProfileState.IN_GAME)) return;
        if (profileState.equals(ProfileState.IN_KIT_EDITOR)) return;

        Hotbar inventory = items.get(profileState);

        if (inventory != null) {
            for (int slot = 0; slot <= 8; slot++) {
                Item item = getItemForSlot(inventory, slot);

                if (item != null && item.isEnabled()) {
                    if (!(item instanceof CustomItem)) {
                        if (item.getAction().equals(ItemAction.PLAY_AGAIN)) {
                            Kit kit = KitManager.get().getKitByName(API.getProfile(player).getGameData().getLastKit());
                            if (kit != null) {
                                player.getInventory().setItem(item.getSlot(), item.constructItem(player.getUniqueId(), new Replacement("<kit>", kit.getDisplayName())));
                            }
                            return;
                        }
                    }
                    player.getInventory().setItem(item.getSlot(), item.constructItem(player.getUniqueId()));
                }
            }
        }
    }

    public Item getItemForSlot(Hotbar inventory, int slot) {
        return getItem(inventory, slot);
    }

    public void loadItems() {
        FileConfiguration config = ConfigManager.get().getHotbarConfig().getConfiguration();
        if (config.getConfigurationSection("ITEMS") != null) {
            for (String section : getKeys("ITEMS")) {
                Hotbar inventory = new Hotbar();

                for (String itemName : getKeys("ITEMS." + section)) {
                    String path = "ITEMS." + section + "." + itemName + ".";

                    String displayName = config.getString(path + "NAME");
                    String material = config.getString(path + "MATERIAL");
                    boolean enabled = config.getBoolean(path + "ENABLED");
                    byte slot = (byte) config.getInt(path + "SLOT");

                    try {
                        Item item = new Item(ItemAction.valueOf(itemName), displayName, material, enabled, slot);
                        if (slot >= 0 && slot < inventory.getSlots().length) {
                            inventory.setSlot(slot, item);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }

                    items.put(ProfileState.valueOf(section), inventory);
                }
            }
        }
        if (config.getConfigurationSection("CUSTOM_ITEMS") != null) {
            for (String itemName : getKeys("CUSTOM_ITEMS")) {
                String path = "CUSTOM_ITEMS." + itemName + ".";

                String displayName = config.getString(path + "NAME");
                String material = config.getString(path + "MATERIAL");
                byte slot = (byte) config.getInt(path + "SLOT");
                String command = config.getString(path + "COMMAND");
                ProfileState profileState = ProfileState.valueOf(config.getString(path + "STATE"));

                CustomItem customItem = new CustomItem(displayName, material, slot, command);
                items.get(profileState).addItem(customItem, slot);
            }
        }
    }

    @Override
    public ConfigFile getConfigFile() {
        return ConfigManager.get().getHotbarConfig();
    }
}
