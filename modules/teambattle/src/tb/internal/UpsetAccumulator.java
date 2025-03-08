package tb.internal;

import module java.base;
import module teambattle.api;

public record UpsetAccumulator(Set<String> userIdWins) implements Accumulator<InternalEvent.GameResult, TeamBattleEvent> {

    public UpsetAccumulator() { this(Set.of()); }

    @Override
    public Result<InternalEvent.GameResult, TeamBattleEvent> accept(InternalEvent.GameResult result) {
        return switch (result) {
            case InternalEvent.Win win -> switch(userIdWins().contains(result.userId())) {
                case true -> new Self<>(this);
                case false -> {
                    var newSet = Stream.concat(userIdWins().stream(), Stream.of(result.userId()))
                        .collect(Collectors.toSet());
                    yield win.ratingDiff() >= 200 && !win.anyProvisional()
                        ? new SelfAndValue<>(new UpsetAccumulator(newSet), new TeamBattleEvent.Upset(result.userId(), result.opponentId()))
                        : new Self<>(new UpsetAccumulator(newSet));
                }
            };
            case InternalEvent.GameResult _ when userIdWins().contains(result.userId()) ->
                new Self<>(new UpsetAccumulator(userIdWins.stream()
                            .filter(Predicate.not(result.userId()::equals))
                            .collect(Collectors.toSet())));
            default -> new Self<>(this);
        };
    }
}
