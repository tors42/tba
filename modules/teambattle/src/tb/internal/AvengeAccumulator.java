package tb.internal;

import module java.base;
import module teambattle.api;

import tb.internal.InternalEvent.GameResult;
import tb.internal.InternalEvent.Loss;
import tb.internal.InternalEvent.Win;

public record AvengeAccumulator(Map<String, Set<String>> opponentVictims) implements Accumulator<GameResult, TeamBattleEvent> {

    public AvengeAccumulator() { this(Map.of()); }

    @Override
    public Result<GameResult, TeamBattleEvent> accept(GameResult result) {
        return switch(result) {
            case Win(_, String userId, String opponentId, _, _) -> {
                var victims = opponentVictims.getOrDefault(opponentId, Set.of());
                if (! victims.isEmpty()) {
                    var newMap = new HashMap<>(opponentVictims());
                    newMap.remove(opponentId);
                    yield new SelfAndValue<>(new AvengeAccumulator(newMap), new TeamBattleEvent.Avenge(userId, victims.stream().sorted().toList(), opponentId));
                }
                yield new Self<>(this);
            }
            case Loss(_, String userId, String opponentId) -> {
                var victims = opponentVictims().getOrDefault(opponentId, Set.of());
                if (! victims.contains(userId)) {
                    var newMap = new HashMap<>(opponentVictims());
                    victims = new HashSet<>(victims);
                    victims.add(userId);
                    newMap.put(opponentId, victims);
                    yield new Self<>(new AvengeAccumulator(newMap));
                }
                yield new Self<>(this);
            }
            default -> new Self<>(this);
        };
    }
}
