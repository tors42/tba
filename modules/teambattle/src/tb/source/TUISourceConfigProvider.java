package tb.source;

import module java.base;

import module tba.api;
import module chariot;

import chariot.model.Arena;

public record TUISourceConfigProvider(UI ui) implements ConfigProvider {

    @Override
    public String name() {
        return "teambattle";
    }

    @Override
    public Optional<Config> interactiveConfig() {

        Client client = Client.basic();

        if (! (ui.crudeQuery("Team ID: ") instanceof String teamId && !teamId.isBlank())) {
            ui.crudeMessage("Failing for now, will be more helpful in future...");
            return Optional.empty();
        }

        var teamResult = client.teams().byTeamId(teamId);
        if (! (teamResult instanceof Entry(Team team))) {
            ui.crudeMessage(teamResult + "\nFailed to find team, will be more helpful in future...");
            return Optional.empty();
        }

        if (! (lookupArenaByTeamIdOrArenaId(teamId, Optional.empty(), client, ui, true) instanceof Arena arena)) {

            return Optional.empty();
        }

        return Optional.of(new TeamBattleSourceProvider.TeamBattleSourceConfig(team, arena, client));
    }


    @Override
    public Optional<Config> interactiveConfig(Preferences prefs) {

        if (! (prefs.get("teamId", null) instanceof String teamId)) {
            System.out.println("No teamId in provided preferences - querying as if no preferences were provided...");
            return interactiveConfig();
        }

        Client client = Client.load(prefs.node("chariot"));

        var teamResult = client.teams().byTeamId(teamId);
        if (! (teamResult instanceof Entry(Team team))) {
            ui.crudeMessage(teamResult + "\nFailed to find team, will be more helpful in future...");
            return Optional.empty();
        }

        if (! (lookupArenaByTeamIdOrArenaId(team.id(), Optional.ofNullable(prefs.get("tourId", null)), client, ui, true) instanceof Arena arena)) {
            return Optional.empty();
        }

        return Optional.of(new TeamBattleSourceProvider.TeamBattleSourceConfig(team, arena, client));
    }

    @Override
    public Optional<Config> noninteractiveConfig(Preferences prefs) {
        if (! (prefs.get("teamId", null) instanceof String teamId)) {
            System.out.println("No teamId in provided preferences - (non-interactive)");
            return Optional.empty();
        }

        Client client = Client.load(prefs.node("chariot"));

        var teamResult = client.teams().byTeamId(teamId);
        if (! (teamResult instanceof Entry(Team team))) {
            ui.crudeMessage(teamResult + "\nFailed to find team! (non-interactive)");
            return Optional.empty();
        }

        if (! (lookupArenaByTeamIdOrArenaId(team.id(), Optional.ofNullable(prefs.get("tourId", null)), client, ui, false) instanceof Arena arena)) {
            return Optional.empty();
        }

        return Optional.of(new TeamBattleSourceProvider.TeamBattleSourceConfig(team, arena, client));
    }

    @Override
    public Optional<Config> noninteractiveConfig() {
        return Optional.empty();
    }



    private static Arena lookupArenaByTeamIdOrArenaId(String teamId, Optional<String> arenaId, Client client, UI ui, boolean interactive) {

        var arenaLightResult = client.teams().arenaByTeamId(teamId);
        if (! (arenaLightResult instanceof Entries(Stream<ArenaLight> stream))) {
            ui.crudeMessage(arenaLightResult + "\nFailed to find tournament");
            if (interactive) {
                ui.crudeMessage("Will be more helpful in future, i.e try resolve the problem interactively");
            }
            return null;
        }

        List<ArenaLight> nonFinishedTeamBattles = stream
            .limit(5)
            .filter(arena -> arena.tourInfo().status() != TourInfo.Status.finished)
            .filter(arena -> arena.teamBattle().isPresent())
            .toList();

        int size = nonFinishedTeamBattles.size();
        if (size == 0) {

            if (arenaId.isPresent()) {
                if (client.tournaments().arenaById(arenaId.get()) instanceof Entry(Arena arena)) {
                    if (arena.tourInfo().status() != TourInfo.Status.finished) {
                        return arena;
                    }
                }
            }

            ui.crudeMessage("Didn't find non-finished team battle!");
            if (interactive) {
                ui.crudeMessage("Will be more helpful in future, i.e try resolve the problem interactively");
            }
            return null;
        }

        if (size > 1) {

            if (arenaId.isPresent()) {
                if (client.tournaments().arenaById(arenaId.get()) instanceof Entry(Arena arena)) {
                    if (arena.tourInfo().status() != TourInfo.Status.finished) {
                        return arena;
                    }
                }
            }

            ui.crudeMessage("Found more than 1 non-finished team battle!");
            if (interactive) {
                ui.crudeMessage("Will be more helpful in future, i.e try resolve the problem interactively");
            }
            return null;
        }

        var arenaResult = client.tournaments().arenaById(nonFinishedTeamBattles.getFirst().id());
        if (! (arenaResult instanceof Entry(Arena arena))) {
            ui.crudeMessage(arenaResult + "\nFailed to lookup tournament by id");
            if (interactive) {
                ui.crudeMessage("Will be more helpful in future, i.e try resolve the problem interactively");
            }
            return null;
        }

        return arena;
    }

}
