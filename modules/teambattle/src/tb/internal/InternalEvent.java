package tb.internal;

import java.util.List;
import java.util.Set;

import chariot.model.Arena;
import teambattle.api.TeamBattleEvent;

public sealed interface InternalEvent {
    record TimeTick() implements InternalEvent {}

    record TourBegin() implements InternalEvent {}
    record TourEnd() implements InternalEvent {}

    record MemberPoll() implements InternalEvent {}
    record Participants(Set<String> members, Set<String> allParticipants) implements InternalEvent {}

    record GameBegin(String id, String userId, String opponentId) implements InternalEvent {}
    sealed interface GameResult extends InternalEvent {
        String userId();
    }
    record Win(String id, String userId, String opponentId) implements GameResult {}
    record Draw(String id, String userId, String opponentId) implements GameResult {}
    record Loss(String id, String userId, String opponentId) implements GameResult {}

    record TeamPoints(String name, int points) {}
    record Standings(List<TeamPoints> standings) implements InternalEvent {}

    record ArenaUpdate(Arena arena) implements InternalEvent {}

    record Message(TeamBattleEvent teamBattleEvent) implements InternalEvent {}
}
