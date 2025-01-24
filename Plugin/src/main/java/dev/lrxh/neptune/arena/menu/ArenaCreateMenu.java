package dev.lrxh.neptune.arena.menu;

import dev.lrxh.neptune.arena.Arena;
import dev.lrxh.neptune.arena.menu.button.*;
import dev.lrxh.neptune.match.impl.participant.ParticipantColor;
import dev.lrxh.neptune.providers.menu.Button;
import dev.lrxh.neptune.providers.menu.Filter;
import dev.lrxh.neptune.providers.menu.Menu;
import dev.lrxh.neptune.providers.menu.impl.DisplayButton;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ArenaCreateMenu extends Menu {
    private final String name;

    public ArenaCreateMenu(String name) {
        super("&eSelect Arena type", 45, Filter.FILL);
        this.name = name;
    }

    @Override
    public List<Button> getButtons(Player player) {
        List<Button> buttons = new ArrayList<>();

        buttons.add(new ArenaSelectTypeButton(21, true, name));
        buttons.add(new ArenaSelectTypeButton(23, false, name));
        return buttons;
    }
}
