package teambattle.api;

import module tba.api;
import module java.base;

public sealed interface TeamBattleEvent extends Event, Serializable {

    record Join(List<String> members) implements TeamBattleEvent {
        public Join {
            members = List.copyOf(members);
            if (members.isEmpty()) throw new IllegalArgumentException("member list can't be empty");
        }
    }
    record TourBegin() implements TeamBattleEvent {}
    record FirstBlood(String member, String foe) implements TeamBattleEvent {}
    record Streak(String member, int winsInRow) implements TeamBattleEvent {}
    record Upset(String member, String foe) implements TeamBattleEvent {}
    record Avenge(String member, List<String> avenged, String foe) implements TeamBattleEvent {
        public Avenge {
            avenged = List.copyOf(avenged);
            if (avenged.isEmpty()) throw new IllegalArgumentException("avenged list can't be empty");
        }
    }
    record Standings(Map<String, Integer> teams) implements TeamBattleEvent {
        public Standings {
            teams = Collections.unmodifiableMap(teams.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getValue, Comparator.reverseOrder()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (k1,_) -> k1, LinkedHashMap::new)));
        }
    }
    record TourEnd() implements TeamBattleEvent {}

}
