package tb.source;

import module tba.api;
import module teambattle.api;
import module chariot;

import tba.api.Event;

import chariot.model.Arena;
import chariot.model.Team;

public class TeamBattleSourceProvider implements SourceProvider {

    @Override
    public Class<? extends Event> eventType() {
        return TeamBattleEvent.class;
    }

    @Override
    public String name() {
        return "teambattle";
    }

    @Override
    public ConfigProvider configProvider(UI ui) {
        return new TUISourceConfigProvider(ui);
    }

    public record TeamBattleSourceConfig(Team team, Arena arena, Client client) implements Config {
        @Override
        public void store(Preferences prefs) {
            prefs.put("teamId", team.id());
            prefs.put("tourId", arena.id());
            Preferences chariotPrefs = prefs.node("chariot");
            client.store(chariotPrefs);
        }
    }

    @Override
    public Source of(Config config) {
        if (! (config instanceof TeamBattleSourceConfig(Team team, Arena arena, Client client))) {
            System.out.println("Unknown config! " + config);
            return null;
        }

        System.out.println("""
                Team: %s
                https://lichess.org/team/%s

                Arena: %s
                https://lichess.org/tournament/%s
                """.formatted(team.name(), team.id(), arena.tourInfo().name(), arena.id()));

        return new Tour(team, arena, client);
    }

}
