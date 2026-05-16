package tb.internal;

import module java.base;
import module teambattle.api;
import module chariot;

public record NoShowAccumulator() implements Accumulator<InternalEvent.GameResult, TeamBattleEvent> {

    public static boolean isNoShow(InternalEvent.GameResult result) {
        return result instanceof InternalEvent.Win(_, _, _, _, _, Some(Game game))
            && game.moves() instanceof Some(String moves)
            && moves.split(" ").length <= 1;
    }

    @Override
    public Result<InternalEvent.GameResult, TeamBattleEvent> accept(InternalEvent.GameResult result) {
        return isNoShow(result)
            ? new SelfAndValue<>(this, new TeamBattleEvent.NoShow(result.userId(), result.opponentId()))
            : new Self<>(this);
    }
}
