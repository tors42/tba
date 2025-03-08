package teambattle.internal;

import module java.base;
import module teambattle.api;

import tba.api.Event;

import teambattle.api.TeamBattleEvent.*;

class Test {
    public static void main(String[] args) {

        List<TeamBattleEvent> events = List.of(
                new TourBegin(),
                new Join(List.of("User1")),
                new Join(List.of("User2", "User3")),
                new Join(List.of("User4", "User5", "User6")),
                new FirstBlood("User1","Foe1"),
                new Streak("User1", 2),
                new Streak("User1", 3),
                new Streak("User1", 4),
                new Streak("User1", 5),
                new Streak("User1", 6),
                new Streak("User1", 7),
                new Streak("User1", 8),
                new Avenge("User1", List.of("User1"), "Foe1"),
                new Avenge("User1", List.of("User2"), "Foe1"),
                new Avenge("User1", List.of("User2", "User3"), "Foe1"),
                new Avenge("User1", List.of("User2", "User3", "User4"), "Foe1"),
                new Avenge("User1", List.of("User1", "User2"), "Foe1"),
                new Avenge("User1", List.of("User1", "User2", "User3"), "Foe1"),
                new Avenge("User1", List.of("User1", "User2", "User3", "User4"), "Foe1"),
                new Standings(Map.ofEntries(
                            Map.entry("Team1", Integer.valueOf(145)),
                            Map.entry("Team2", Integer.valueOf(122)),
                            Map.entry("Team3", Integer.valueOf(100)),
                            Map.entry("Team4", Integer.valueOf(90)),
                            Map.entry("Team5", Integer.valueOf(77)),
                            Map.entry("Team6", Integer.valueOf(42)),
                            Map.entry("Team7", Integer.valueOf(30)),
                            Map.entry("Team8", Integer.valueOf(30)),
                            Map.entry("Team9", Integer.valueOf(2)),
                            Map.entry("Team10", Integer.valueOf(0))
                            )),
                new TourEnd());



        record Named(String name, EventRenderer renderer) {}
        List<Named> namedRenderers = List.of(
                new Named("--", EventRenderer.of()),
                new Named("sv", EventRenderer.ofLocale(Locale.of("sv"))),
                new Named("fr", EventRenderer.ofLocale(Locale.of("fr")))
                );

        events.forEach(event -> {
            System.out.format("%s:%n", event.getClass().getSimpleName());
            namedRenderers.forEach(named -> {
                switch(event) {
                    case Standings _ -> System.out.format("%s:%n%s", named.name(), named.renderer().render(event).indent(3));
                    default          -> System.out.format("%s: %s", named.name(), named.renderer().render(event));
                }
                System.out.println();
            });
            System.out.println();
        });
    }
}
