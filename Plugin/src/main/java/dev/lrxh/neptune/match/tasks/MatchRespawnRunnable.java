package dev.lrxh.neptune.match.tasks;

import dev.lrxh.neptune.Neptune;
import dev.lrxh.neptune.configs.impl.MessagesLocale;
import dev.lrxh.neptune.match.Match;
import dev.lrxh.neptune.match.impl.participant.Participant;
import dev.lrxh.neptune.match.impl.participant.ParticipantColor;
import dev.lrxh.neptune.providers.clickable.Replacement;
import dev.lrxh.neptune.providers.tasks.NeptuneRunnable;
import dev.lrxh.neptune.utils.PlayerUtil;
import dev.lrxh.sounds.Sound;

public class MatchRespawnRunnable extends NeptuneRunnable {
    private final Neptune plugin;

    private final Match match;
    private final Participant participant;
    private int respawnTimer = 3;

    public MatchRespawnRunnable(Match match, Participant participant, Neptune plugin) {
        this.match = match;
        this.participant = participant;
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.getMatchManager().matches.contains(match)) {
            stop(plugin);

            return;
        }

        if (respawnTimer == 3) {
            PlayerUtil.doVelocityChange(participant.getPlayerUUID());
            PlayerUtil.reset(participant.getPlayerUUID());
            participant.setSpectator();
        }

        if (participant.getPlayer() == null) return;
        if (respawnTimer == 0) {
            if (participant.getColor().equals(ParticipantColor.RED)) {
                participant.teleport(match.getArena().getRedSpawn());
            } else {
                participant.teleport(match.getArena().getBlueSpawn());
            }

            match.setupPlayer(participant.getPlayerUUID());
            participant.setDead(false);
            stop(plugin);
            return;
        }

        match.playSound(Sound.UI_BUTTON_CLICK);

        participant.sendTitle(MessagesLocale.MATCH_RESPAWN_TITLE_HEADER.getString().replace("<timer>", String.valueOf(respawnTimer)),
                MessagesLocale.MATCH_RESPAWN_TITLE_FOOTER.getString().replace("<timer>", String.valueOf(respawnTimer)),
                100);
        participant.sendMessage(MessagesLocale.MATCH_RESPAWN_TIMER, new Replacement("<timer>", String.valueOf(respawnTimer)));

        respawnTimer--;
    }
}
