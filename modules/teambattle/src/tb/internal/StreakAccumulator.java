package tb.internal;

import module java.base;
import module teambattle.api;

public record StreakAccumulator(Map<String, Integer> userIdWins) implements Accumulator<InternalEvent.GameResult, TeamBattleEvent> {

    public StreakAccumulator() { this(Map.of()); }

    @Override
    public Result<InternalEvent.GameResult, TeamBattleEvent> accept(InternalEvent.GameResult result) {
        if (result instanceof InternalEvent.Win) {
            var newMap = new HashMap<>(userIdWins);
            int wins = 1 + userIdWins.getOrDefault(result.userId(), 0);
            newMap.put(result.userId(), wins);
            return wins >= 2
                ? new SelfAndValue<>(new StreakAccumulator(Map.copyOf(newMap)), new TeamBattleEvent.Streak(result.userId(), wins))
                : new Self<>(new StreakAccumulator(Map.copyOf(newMap)));
        } else {
            if (userIdWins.containsKey(result.userId())) {
                var newMap = new HashMap<>(userIdWins);
                newMap.remove(result.userId());
                return new Self<>(new StreakAccumulator(Map.copyOf(newMap)));
            }
        }
        return new Self<>(this);
    }
}
