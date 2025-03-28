package dev.lrxh.neptune.game.match.impl;

import dev.lrxh.neptune.API;
import dev.lrxh.neptune.Neptune;
import dev.lrxh.neptune.configs.impl.MessagesLocale;
import dev.lrxh.neptune.configs.impl.SettingsLocale;
import dev.lrxh.neptune.feature.hotbar.HotbarService;
import dev.lrxh.neptune.game.arena.Arena;
import dev.lrxh.neptune.game.kit.Kit;
import dev.lrxh.neptune.game.kit.impl.KitRule;
import dev.lrxh.neptune.game.leaderboard.LeaderboardService;
import dev.lrxh.neptune.game.leaderboard.impl.LeaderboardPlayerEntry;
import dev.lrxh.neptune.game.match.Match;
import dev.lrxh.neptune.game.match.impl.participant.DeathCause;
import dev.lrxh.neptune.game.match.impl.participant.Participant;
import dev.lrxh.neptune.game.match.tasks.MatchEndRunnable;
import dev.lrxh.neptune.game.match.tasks.MatchRespawnRunnable;
import dev.lrxh.neptune.game.match.tasks.MatchSecondRoundRunnable;
import dev.lrxh.neptune.profile.data.MatchHistory;
import dev.lrxh.neptune.profile.data.ProfileState;
import dev.lrxh.neptune.profile.impl.Profile;
import dev.lrxh.neptune.providers.clickable.ClickableComponent;
import dev.lrxh.neptune.providers.clickable.Replacement;
import dev.lrxh.neptune.utils.CC;
import dev.lrxh.neptune.utils.DateUtils;
import dev.lrxh.neptune.utils.PlayerUtil;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SoloFightMatch extends Match {

    private final Participant participantA;
    private final Participant participantB;
    // Cache player information for stats persistence when players disconnect
    private String cachedPlayerAUsername;
    private String cachedPlayerBUsername;

    public SoloFightMatch(Arena arena, Kit kit, boolean duel, List<Participant> participants, Participant participantA, Participant participantB, int rounds) {
        super(MatchState.STARTING, arena, kit, participants, rounds, duel, false);
        this.participantA = participantA;
        this.participantB = participantB;
        
        // Cache player usernames at match start
        Profile profileA = API.getProfile(participantA.getPlayerUUID());
        Profile profileB = API.getProfile(participantB.getPlayerUUID());
        if (profileA != null) {
            this.cachedPlayerAUsername = profileA.getUsername();
        }
        if (profileB != null) {
            this.cachedPlayerBUsername = profileB.getUsername();
        }
    }

    @Override
    public void end(Participant loser) {
        state = MatchState.ENDING;
        loser.setLoser(true);
        Participant winner = getWinner();

        // Make sure to reset the arena
        this.resetArena();

        if (!isDuel()) {
            addStats();

            for (String command : SettingsLocale.COMMANDS_AFTER_MATCH_LOSER.getStringList()) {
                if (command.equals("NONE")) continue;
                command = command.replace("<player>", loser.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }

            for (String command : SettingsLocale.COMMANDS_AFTER_MATCH_WINNER.getStringList()) {
                if (command.equals("NONE")) continue;
                command = command.replace("<player>", winner.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }

            forEachPlayer(player -> HotbarService.get().giveItems(player));
        }

        winner.sendTitle(MessagesLocale.MATCH_WINNER_TITLE.getString(),
                MessagesLocale.MATCH_TITLE_SUBTITLE.getString().replace("<player>", MessagesLocale.MATCH_YOU.getString()), 100);

        if (!loser.isLeft() && !loser.isDisconnected()) loser.sendTitle(MessagesLocale.MATCH_LOSER_TITLE.getString(),
                MessagesLocale.MATCH_TITLE_SUBTITLE.getString().replace("<player>", winner.getNameUnColored()), 100);

        removePlaying();

        loser.playKillEffect();

        new MatchEndRunnable(this, plugin).start(0L, 20L, plugin);
    }

    private void removePlaying() {
        for (Participant ignored : participants)
            kit.removePlaying();
    }

    public void addStats() {
        Participant winner = getWinner();
        Participant loser = getLoser();
        Profile winnerProfile = API.getProfile(winner.getPlayerUUID());
        Profile loserProfile = API.getProfile(loser.getPlayerUUID());

        boolean hasWinnerProfile = (winnerProfile != null);
        boolean hasLoserProfile = (loserProfile != null);
        
        // Handle cases where profiles are null due to disconnection
        if (!hasWinnerProfile || !hasLoserProfile) {
            plugin.getLogger().warning("Some profiles are null during stats recording: " + 
                (!hasWinnerProfile ? "Winner profile is null" : "") + 
                (!hasLoserProfile ? "Loser profile is null" : ""));
            
            // Try to use cached data for missing profiles
            if (hasWinnerProfile && !hasLoserProfile) {
                // Winner is still connected, but loser disconnected
                String loserUsername = (loser == participantA) ? cachedPlayerAUsername : cachedPlayerBUsername;
                if (loserUsername != null) {
                    // We can still add stats for the winner at least
                    winnerProfile.getGameData().addHistory(
                            new MatchHistory(true, loserUsername, kit.getDisplayName(), arena.getDisplayName(), DateUtils.getDate()));
                    winnerProfile.getGameData().run(kit, true);
                    plugin.getLogger().info("Recorded win stats for " + winnerProfile.getUsername() + " against disconnected player " + loserUsername);
                }
            } else if (!hasWinnerProfile && hasLoserProfile) {
                // Loser is still connected, but winner disconnected
                String winnerUsername = (winner == participantA) ? cachedPlayerAUsername : cachedPlayerBUsername;
                if (winnerUsername != null) {
                    // We can still add stats for the loser at least
                    loserProfile.getGameData().addHistory(
                            new MatchHistory(false, winnerUsername, kit.getDisplayName(), arena.getDisplayName(), DateUtils.getDate()));
                    loserProfile.getGameData().run(kit, false);
                    plugin.getLogger().info("Recorded loss stats for " + loserProfile.getUsername() + " against disconnected player " + winnerUsername);
                }
            }
            
            // If both profiles are null, we can't do anything
            return;
        }

        // Normal case - both profiles exist
        winnerProfile.getGameData().addHistory(
                new MatchHistory(true, loserProfile.getUsername(), kit.getDisplayName(), arena.getDisplayName(), DateUtils.getDate()));

        loserProfile.getGameData().addHistory(
                new MatchHistory(false, winnerProfile.getUsername(), kit.getDisplayName(), arena.getDisplayName(), DateUtils.getDate()));

        winnerProfile.getGameData().run(kit, true);
        loserProfile.getGameData().run(kit, false);

        forEachParticipantForce(participant -> LeaderboardService.get().addChange
                (new LeaderboardPlayerEntry(participant.getNameUnColored(), participant.getPlayerUUID(), kit)));
    }

    private Participant getLoser() {
        return participantA.isLoser() ? participantA : participantB;
    }

    private Participant getWinner() {
        return participantA.isLoser() ? participantB : participantA;
    }

    @Override
    public void sendEndMessage() {
        Participant winner = getWinner();
        Participant loser = getLoser();

        broadcast(MessagesLocale.MATCH_END_DETAILS_SOLO,
                new Replacement("<loser>", loser.getNameUnColored()),
                new Replacement("<winner>", winner.getNameUnColored()));

        forEachParticipant(participant -> {
            if (MessagesLocale.MATCH_PLAY_AGAIN_ENABLED.getBoolean()) {
                TextComponent playMessage = new ClickableComponent(MessagesLocale.MATCH_PLAY_AGAIN.getString(),
                        "/queue " + kit.getName(),
                        MessagesLocale.MATCH_PLAY_AGAIN_HOVER.getString()).build();

                PlayerUtil.sendMessage(participant.getPlayerUUID(), playMessage);
            }
        });
    }

    @Override
    public void breakBed(Participant participant) {
        participant.setBedBroken(true);
        
        // Play Ender Dragon roar sound to the participant whose bed was broken
        Player player = participant.getPlayer();
        if (player != null) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
        }
    }

    /**
     * Scores a point for the participant in portal goal matches
     *
     * @param participant The participant who scored
     */
    public void scorePoint(Participant participant) {
        // Add a win (point) for the participant
        participant.addWin();

        // Check if this participant has won enough rounds
        if (participant.getRoundsWon() >= rounds) {
            // Get the opponent
            Participant opponent = participant.equals(participantA) ? participantB : participantA;

            // End the match with the opponent as the loser
            this.setEnded(true);
            end(opponent);
        }
    }

    @Override
    public void sendTitle(Participant participant, String header, String footer, int duration) {
        participant.sendTitle(header, footer, duration);
    }

    @Override
    public void onDeath(Participant participant) {
        if (isEnded()) return;
        
        // Hide the player model and mark as dead
        hideParticipant(participant);
        participant.setDead(true);
        
        // Determine killer and send death message
        Participant killer = participantA.getNameColored().equals(participant.getNameColored()) ? participantB : participantA;
        sendDeathMessage(participant);
        
        // Handle disconnected or left players
        if (participant.isDisconnected() || participant.isLeft()) {
            endMatchWithParticipantAsLoser(participant);
            return;
        }
        
        // Handle game mode-specific death behaviors
        if (handleGameModeSpecificDeath(participant, killer)) {
            return; // Death was handled by game mode logic
        }
        
        // Standard death - end the match
        endMatchWithParticipantAsLoser(participant);
    }
    
    /**
     * Handles game mode-specific death behavior
     * @return true if death was fully handled, false if default behavior should continue
     */
    private boolean handleGameModeSpecificDeath(Participant participant, Participant killer) {
        // Reset inventory if the kit requires it
        if (kit.is(KitRule.RESET_INVENTORY_AFTER_DEATH) && !kit.is(KitRule.BRIDGES)) {
            resetPlayerInventory(participant);
        }
        
        // Handle Bridges mode 
        if (kit.is(KitRule.BRIDGES)) {
            handleBridgesDeath(participant);
            return true;
        }
        
        // Handle BedWars mode
        if (kit.is(KitRule.BED_WARS) && !participant.isBedBroken()) {
            killer.setCombo(0);
            new MatchRespawnRunnable(this, participant, plugin).start(0L, 20L, plugin);
            return true;
        }
        
        // Handle multi-round matches
        if (rounds > 1) {
            return handleMultiRoundDeath(participant, killer);
        }
        
        return false;
    }
    
    /**
     * Handles respawning in Bridges mode
     */
    private void handleBridgesDeath(Participant participant) {
        // Check if respawn delay is enabled
        if (kit.is(KitRule.RESPAWN_DELAY)) {
            participant.sendTitle("&cYou Died!", "&eRespawning in 5 seconds...", 40);
            new MatchRespawnRunnable(this, participant, plugin).start(0L, 20L, plugin);
        } else {
            // Instant respawn
            participant.sendTitle("&cYou Died!", "&eRespawning...", 10);
            
            // Reset player inventory
            resetPlayerInventory(participant);
            
            // Handle respawn
            Player deadPlayer = participant.getPlayer();
            List<Player> allPlayers = new ArrayList<>();
            forEachPlayer(allPlayers::add);
            PlayerUtil.handlePlayerRespawn(deadPlayer, participant, getSpawn(participant), null, allPlayers, Neptune.get());
        }
    }
    
    /**
     * Handles death in multi-round matches
     * @return true if death was fully handled, false if match should end
     */
    private boolean handleMultiRoundDeath(Participant participant, Participant killer) {
        // Only score a point for kills if not in Bridges mode
        if (!kit.is(KitRule.BRIDGES)) {
            killer.addWin();
        }
        
        // If killer hasn't won enough rounds yet, start new round
        if (killer.getRoundsWon() < rounds) {
            killer.setCombo(0);
            state = MatchState.STARTING;
            new MatchSecondRoundRunnable(this, participant, plugin).start(0L, 20L, plugin);
            return true;
        }
        
        return false;
    }
    
    /**
     * Resets a player's inventory and gives them their kit
     */
    private void resetPlayerInventory(Participant participant) {
        PlayerUtil.reset(participant.getPlayer());
        participant.getPlayer().setGameMode(GameMode.SURVIVAL);
        kit.giveLoadout(participant);
        participant.getPlayer().updateInventory();
    }
    
    /**
     * Ends the match with the specified participant as the loser
     */
    private void endMatchWithParticipantAsLoser(Participant participant) {
        // Play sound to last attacker
        if (participant.getLastAttacker() != null) {
            participant.getLastAttacker().playSound(Sound.UI_BUTTON_CLICK);
        }
        
        this.setEnded(true);
        PlayerUtil.doVelocityChange(participant.getPlayerUUID());
        end(participant);
    }

    @Override
    public void onLeave(Participant participant, boolean quit) {
        participant.setDeathCause(DeathCause.DISCONNECT);
        sendDeathMessage(participant);
        setEnded(true);

        // Ensure match state is set to ENDING
        state = MatchState.ENDING;

        // Make sure cached username data is up-to-date before potential profile removal
        Profile profile = API.getProfile(participant.getPlayerUUID());
        if (profile != null) {
            if (participant == participantA) {
                cachedPlayerAUsername = profile.getUsername();
            } else if (participant == participantB) {
                cachedPlayerBUsername = profile.getUsername();
            }
        }

        if (quit) {
            participant.setDisconnected(true);
        } else {
            participant.setLeft(true);
            PlayerUtil.teleportToSpawn(participant.getPlayerUUID());
            if (profile != null) {
                profile.setState(profile.getGameData().getParty() == null ? ProfileState.IN_LOBBY : ProfileState.IN_PARTY);
                PlayerUtil.reset(participant.getPlayer());
                profile.setMatch(null);
            }
        }

        // Always reset the arena when a player leaves to clean up placed blocks
        this.resetArena();

        end(participant);
    }

    @Override
    public void startMatch() {
        state = MatchState.IN_ROUND;
        showPlayerForSpectators();
        playSound(Sound.ENTITY_FIREWORK_ROCKET_BLAST);
        sendTitle(CC.color(MessagesLocale.MATCH_START_TITLE.getString()), MessagesLocale.MATCH_START_HEADER.getString(), 20);
    }
}