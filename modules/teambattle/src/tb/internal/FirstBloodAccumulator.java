package tb.internal;

import tb.internal.InternalEvent.GameResult;
import tb.internal.InternalEvent.Win;

import teambattle.api.TeamBattleEvent;

public record FirstBloodAccumulator(boolean done) implements Accumulator<GameResult, TeamBattleEvent> {

    public FirstBloodAccumulator() { this(false); }

    @Override
    public Result<GameResult, TeamBattleEvent> accept(GameResult result) {
        if (! done && result instanceof Win(_, var userId, var opponentId)) {
            return new Value<>(new TeamBattleEvent.FirstBlood(userId, opponentId));
        }
        return new Self<>(this);
    }
}
