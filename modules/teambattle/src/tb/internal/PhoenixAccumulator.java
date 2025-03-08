package tb.internal;

import module java.base;
import java.util.concurrent.atomic.AtomicInteger;

import module teambattle.api;

public record PhoenixAccumulator(Map<String, AtomicInteger> userLosses) implements Accumulator<InternalEvent.GameResult, TeamBattleEvent> {

    public PhoenixAccumulator() { this(Map.of()); }

    @Override
    public Result<InternalEvent.GameResult, TeamBattleEvent> accept(InternalEvent.GameResult result) {
        return switch (result) {
            case InternalEvent.Loss _ -> {
                var newMap = new HashMap<>(userLosses);
                var lossCounter = newMap.getOrDefault(result.userId(), new AtomicInteger());
                lossCounter.incrementAndGet();
                newMap.put(result.userId(), lossCounter);
                yield new Self<>(new PhoenixAccumulator(Map.copyOf(newMap)));
            }
            default -> {
                var newMap = new HashMap<>(userLosses);
                var lossCounter = newMap.remove(result.userId());

                int lossCount = lossCounter == null
                    ? 0
                    : lossCounter.intValue();

                yield result instanceof InternalEvent.Win && lossCount >= 3
                    ? new SelfAndValue<>(new PhoenixAccumulator(Map.copyOf(newMap)), new TeamBattleEvent.Phoenix(result.userId(), result.opponentId()))
                    : new Self<>(new PhoenixAccumulator(Map.copyOf(newMap)));
            }
        };
    }
}
