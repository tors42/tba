package tb.internal;

import module java.base;
import module teambattle.api;

import tba.api.Event;
import tba.api.TextEvent;
import tb.transformer.ToTextEvent;

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


        EventRenderer defaultEventRenderer = EventRenderer.of();
        EventRenderer svEventRenderer = EventRenderer.ofLocale(Locale.of("sv"));
        EventRenderer frEventRenderer = EventRenderer.ofLocale(Locale.of("fr"));


        var defaultLocaleRenderer = new ToTextEvent(defaultEventRenderer);
        var swedishRenderer = new ToTextEvent(svEventRenderer);
        var frenchRenderer = new ToTextEvent(frEventRenderer);

        List<ToTextEvent> renderers = List.of(defaultLocaleRenderer, swedishRenderer, frenchRenderer);

        for (var renderer : renderers) {
            for (var event : events) {
                Event rendered = renderer.transform(event);
                if (rendered instanceof TextEvent(String message)) {
                    System.out.println(message);
                } else {
                    System.out.println("Failed to render, " + rendered);
                }
            }
        }
    }
}
