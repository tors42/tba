package tb.internal;

import module java.base;

import chariot.model.Arena;
import teambattle.api.TeamBattleEvent;

public sealed interface InternalEvent {
    record TimeTick() implements InternalEvent {}

    record TourBegin() implements InternalEvent {}
    record TourEnd() implements InternalEvent {}

    record PlayingMember(String userId, String gameId) {}
    record MemberPoll(Set<PlayingMember> playingMembers) implements InternalEvent {}

    record ParticipantStatus(String userId, boolean withdraw) {}
    record Participants(Set<ParticipantStatus> members, Set<ParticipantStatus> allParticipants) implements InternalEvent {
        public Set<String> memberIds() { return members.stream().map(ParticipantStatus::userId).collect(Collectors.toSet()); }
        public Set<String> allParticipantIds() { return allParticipants.stream().map(ParticipantStatus::userId).collect(Collectors.toSet()); }
    }

    record GameBegin(String id, String userId, String opponentId) implements InternalEvent {}
    sealed interface GameResult extends InternalEvent {
        String gameId();
        String userId();
        String opponentId();
    }
    record Win(String gameId, String userId, String opponentId, int ratingDiff, boolean anyProvisional) implements GameResult {}
    record Draw(String gameId, String userId, String opponentId) implements GameResult {}
    record Loss(String gameId, String userId, String opponentId) implements GameResult {}

    record TeamPoints(String name, int points) {}
    record Standings(List<TeamPoints> standings) implements InternalEvent {}

    record ArenaUpdate(Arena arena) implements InternalEvent {}

    record Message(TeamBattleEvent teamBattleEvent) implements InternalEvent {}
}
