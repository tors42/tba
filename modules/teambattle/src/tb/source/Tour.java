package tb.source;

import module java.base;
import java.io.ObjectOutputStream;

import module tba.api;
import module teambattle.api;
import module chariot;

import chariot.model.Arena;
import chariot.model.Team;
import chariot.model.User;
import tb.internal.*;
import tb.internal.InternalEvent.*;

public class Tour implements Source {

    State currentState;
    boolean done = false;
    List<Queue<TeamBattleEvent>> externalQueues = new CopyOnWriteArrayList<>();

    final ObjectOutputStream oos;
    final Map<String, User> cache = new ConcurrentHashMap<>();
    final Function<String, String> nameRenderer = id -> {
        try {
            User cachedUser = cache.computeIfAbsent(id, _ -> switch(currentState.base().client().users().byId(id)) {
                case Entry(var user) -> user;
                default -> null;
            });
            return cachedUser == null ? id : cachedUser.name();
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    };


    public Tour(Team team, Arena arena, Client client) {
        currentState = new Initial(new Base(team, arena, client));

        oos = initializeSerializationMaybe(team, arena);
    }

    public Stream<TeamBattleEvent> events() {
        var queue = new ArrayBlockingQueue<TeamBattleEvent>(4096);

        externalQueues.add(queue);

        Stream<TeamBattleEvent> stream = StreamSupport.stream(new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super TeamBattleEvent> consumer) {
                while (! done) {
                    try {
                        if (queue.poll(1, TimeUnit.SECONDS) instanceof TeamBattleEvent event) {
                            consumer.accept(event);
                            return true;
                        }
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
                return false;
            }
            @Override public long estimateSize() { return Long.MAX_VALUE; }
            @Override public Spliterator<TeamBattleEvent> trySplit() { return null; }
            @Override public int characteristics() { return ORDERED|NONNULL; }
        }, false);

        stream.onClose(() -> externalQueues.remove(queue));
        return stream;
    }

    @Override
    public void run() {

        var internalEventQueue = new ArrayBlockingQueue<InternalEvent>(16184);
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> internalEventQueue.offer(new TimeTick()), 1, 1, TimeUnit.SECONDS);

        while (! Thread.currentThread().isInterrupted()) {
            try {
                var event = internalEventQueue.take();

                currentState = switch(event) {

                    case TimeTick() -> switch(currentState) {
                        case Initial state    -> tickInitial(state, internalEventQueue);
                        case NotStarted state -> tickNotStarted(state, internalEventQueue);
                        case Running state    -> tickRunning(state, internalEventQueue);
                        case Ended state      -> tickEnded(state, internalEventQueue);
                    };

                    case ArenaUpdate(var arena) -> currentState.withArena(arena);

                    case TourBegin()            -> {
                        internalEventQueue.offer(new Message(new TeamBattleEvent.TourBegin()));
                        yield currentState;
                    }

                    case TourEnd()            -> {
                        internalEventQueue.offer(new Message(new TeamBattleEvent.TourEnd()));
                        // Thread.currentThread().interrupt() ? // TODO
                        yield currentState;
                    }

                    // hmm, possibly only when Large monitor? Not needed for Small
                    case MemberPoll() -> {
                        if (! (currentState instanceof Running running)) yield currentState;

                        // TODO

                        // if large tournament,
                        // check if any members are not actively monitored (i.e "waiting"),
                        // and them to gamemeta-stream and move them to "playing"

                        yield running;
                    }

                    case Participants(var members, var allParticipants) -> {
                        if (! (currentState instanceof State.WithData state)) yield currentState;

                        State.WithData nextState = state;

                        if (! members.isEmpty()) {
                            Set<String> newMembers = new HashSet<>(members);
                            newMembers.removeAll(state.data().members());
                            if (! newMembers.isEmpty()) {

                                nextState = state.withMembers(Set.copyOf(members));

                                // hmm, identify if we've recently went from Initial to Running,
                                // then these reinforcements are actually the initial poll, not "joiners" showing up "after a while"...
                                // Need something in the type system, i.e "Set<String> members" might not cut it?
                                // Would need some initial state to differ between empty set and no set at all?
                                internalEventQueue.offer(new Message(new TeamBattleEvent.Join(newMembers.stream().sorted().toList())));
                            }
                        }

                        yield switch(nextState) {
                            case Running running -> {
                                ResultsMonitor updatedMonitor = switch(running.monitor()) {
                                    case Small(var oldStream, var oldUsers) -> {
                                        if (oldUsers.equals(allParticipants)) yield running.monitor();

                                        var updatedAllParticipants = Set.copyOf(allParticipants);
                                        if (updatedAllParticipants.size() <= 300) {

                                            oldStream.close();

                                            // TODO, error check / retry
                                            var res = running.base().client().games().gameInfosByUserIds(updatedAllParticipants);
                                            var newStream = res.stream();

                                            var membersRef = running.data().members();

                                            Thread.ofPlatform().daemon(false).name("small-monitor-" + updatedAllParticipants.size()).start(
                                                    () -> {
                                                        try {
                                                            newStream.forEach(gameMeta -> {
                                                                if (gameMeta.status().status() > Enums.Status.started.status()) {
                                                                    record IdColor(String id, Enums.Color color) {}
                                                                    var white = new IdColor(gameMeta.players().white().userId(), Enums.Color.white);
                                                                    var black = new IdColor(gameMeta.players().black().userId(), Enums.Color.black);
                                                                    if (membersRef.contains(white.id()) || membersRef.contains(black.id())) {
                                                                        var member = membersRef.contains(white.id()) ? white : black;
                                                                        var opponent = member.equals(white) ? black : white;

                                                                        internalEventQueue.offer(switch(gameMeta.winner()) {
                                                                            case Some(var color) when color == member.color
                                                                                -> new Win(gameMeta.id(), member.id(), opponent.id());
                                                                            case Some(_) -> new Loss(gameMeta.id(), member.id(), opponent.id());
                                                                            case Empty() -> new Draw(gameMeta.id(), member.id(), opponent.id());
                                                                        });
                                                                      }
                                                                }
                                                            });
                                                        } catch (UncheckedIOException unchecked) {
                                                            // Stream.forEach(...)
                                                            // We are expecting to be closed when new members join.
                                                            // Some verification that the unchecked was caused by that should be added,
                                                            // ie if (expectedClose) { silently exit } else { retry stream - the close was unexpected! }
                                                        }
                                                    });


                                            yield new Small(newStream, updatedAllParticipants);

                                        } else {
                                            // TODO, handle Large monitor
                                            yield running.monitor();
                                        }
                                    }

                                    case Large(var stream, var gameIds, var streamId) -> {
                                        // TODO, handle Large monitor
                                        yield running.monitor();
                                    }
                                };

                                yield running.withMonitor(updatedMonitor).withMembers(Set.copyOf(members));
                            }

                            default -> state.withMembers(Set.copyOf(members));
                        };
                    }

                    case GameResult result -> {
                        if (! (currentState instanceof Running running)) yield currentState;

                        var accumulatorsAndValues = runAccumulators(running.resultAccumulators(), result);

                        if (! accumulatorsAndValues.values().isEmpty()) {

                            //var reducedEmit = accumulatorsAndEmits.emits().stream()
                            //    .reduce();

                            accumulatorsAndValues.values().forEach(emit -> internalEventQueue.offer(new Message(emit)));
                        }

                        var updatedAccumulators = List.copyOf(accumulatorsAndValues.accumulators());

                        // That a result came in,
                        // must mean that a game is now over,
                        // so a player should also be moved from "playing" to "waiting" (hmm, if Large monitor, yeah?)

                        yield running.withResultAccumulators(updatedAccumulators);
                    }

                    case Standings(List<InternalEvent.TeamPoints> standings) -> {
                        internalEventQueue.offer(new Message(new TeamBattleEvent.Standings(standings.stream()
                                        .collect(Collectors.toMap(
                                                InternalEvent.TeamPoints::name,
                                                InternalEvent.TeamPoints::points)))));
                        yield currentState;
                    }

                    case Message(TeamBattleEvent eventWithIds) -> {

                        TeamBattleEvent eventWithNames = EventRenderer.replaceNames(eventWithIds, nameRenderer, nameRenderer);

                        for (var queue : externalQueues) {
                            queue.add(eventWithNames);
                        }

                        if (oos != null) {
                            try {
                                oos.writeObject(new TimedEvent(ZonedDateTime.now(), eventWithNames));
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                        yield currentState;
                    }

                    default -> currentState;
                };

            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        done = true;
    }


    record Base(Team team, Arena arena, Client client) {
        public Base withArena(Arena updated) {
            return new Base(team, updated, client);
        }
    }
    record Data(Base base, Set<String> members, List<Accumulator<Void, Runnable>> tickAccumulators) {
        public Data withBase(Base updated) {
            return new Data(updated, members, tickAccumulators);
        }
        public Data withMembers(Set<String> updated) {
            return new Data(base, updated, tickAccumulators);
        }
        public Data withTickAccumulators(List<Accumulator<Void, Runnable>> updated) {
            return new Data(base, members, updated);
        }
    }

    sealed interface ResultsMonitor {}
    record Small(Stream<GameMeta> byUserIds, Set<String> userIds) implements ResultsMonitor {}
    record Large(Stream<GameMeta> byGameIds, Set<String> gameIds, String streamId) implements ResultsMonitor {}

    // - ArenaUpdate
    // - TeamMembersUpdate
    // - StandingsUpdate
    // - if large MembersGamePoll

    sealed interface State {
        Base base();
        sealed interface WithData extends State {
            Data data();
            default Base base() { return data().base(); }
            default WithData withMembers(Set<String> updated) {
                return switch (this) {
                    case NotStarted state -> new NotStarted(state.data().withMembers(updated));
                    case Running state    -> new Running(state.data().withMembers(updated), state.monitor(), state.resultAccumulators());
                    case Ended state      -> new Ended(state.data().withMembers(updated));
                };
            }
        }

        default State withArena(Arena updated) {
            return switch (this) {
                case Initial state    -> new Initial(state.base().withArena(updated));
                case NotStarted state -> new NotStarted(state.data().withBase(state.data().base().withArena(updated)));
                case Running state    -> new Running(state.data().withBase(state.data().base().withArena(updated)), state.monitor(), state.resultAccumulators());
                case Ended state      -> new Ended(state.data().withBase(state.data().base().withArena(updated)));
            };
        }
    }

    record Initial(Base base) implements State {}
    record NotStarted(Data data) implements State.WithData {}
    record Running(Data data, ResultsMonitor monitor, List<Accumulator<InternalEvent.GameResult, TeamBattleEvent>> resultAccumulators) implements State.WithData {
        public Running withResultAccumulators(List<Accumulator<GameResult, TeamBattleEvent>> updated) {
            return new Running(data(), monitor(), updated);
        }
        public Running withMonitor(ResultsMonitor updated) {
            return new Running(data(), updated, resultAccumulators());
        }
    }
    record Ended(Data data) implements State.WithData {}


    State tickInitial(Initial initial, Queue<InternalEvent> queue) {
        Base base = initial.base();
        Arena arena = base.arena();
        return switch (ZonedDateTime.now()) {

            // Normal flow
            case ZonedDateTime now when now.isBefore(arena.tourInfo().startsAt())
                -> new NotStarted(new Data(base, Set.of(),
                            List.of(
                                new RepeatableAction(60,     arenaUpdate(base.client(), arena, queue)),
                                new RepeatableAction(60, 60, members(base.client(), arena, base.team(), queue))
                                )));

            // Old tournament
            case ZonedDateTime now when now.isAfter(arena.tourInfo().startsAt().plus(arena.duration()))
                -> new Ended(new Data(base, Set.of(), List.of()));

            // Ongoing tournament
            case ZonedDateTime _
                -> new Running(new Data(base, Set.of(),
                            List.of(
                                new RepeatableAction(60, arenaUpdate(base.client(), arena, queue)),
                                new RepeatableAction(60, 60, members(base.client(), arena, base.team(), queue)),
                                new RepeatableAction(60*20, 60*20, standings(base.client(), arena, queue))
                                )),
                        new Small(Stream.of(), Set.of()),
                        List.of(new StreakAccumulator(), new AvengeAccumulator()) // resultAccumulators
                        );
        };
    }

    State tickNotStarted(NotStarted notStarted, Queue<InternalEvent> queue) {
        Data data = notStarted.data();
        Arena arena = data.base().arena();

        var accumulatorsAndActions = runAccumulators(data.tickAccumulators(), null);

        // TODO, consider adding events which represent the action,
        // and let the main loop handle the events,
        // i.e trigger the action in some way
        // Launchign virtual thread for now...
        accumulatorsAndActions.values().forEach(Thread.ofVirtual()::start);

        var updatedTickAccumulators = List.copyOf(accumulatorsAndActions.accumulators());

        if (! ZonedDateTime.now().isAfter(arena.tourInfo().startsAt())) {
            return new NotStarted(data.withTickAccumulators(updatedTickAccumulators));
        }

        queue.offer(new TourBegin());

        updatedTickAccumulators = Stream.concat(
                updatedTickAccumulators.stream(),
                Stream.of( new RepeatableAction(60*20, standings(data.base().client(), arena, queue)))
                ).toList();

        return new Running(data.withTickAccumulators(updatedTickAccumulators),
                new Small(Stream.of(), Set.of()),
                List.of(new FirstBloodAccumulator(), new StreakAccumulator(), new AvengeAccumulator())
                );
    }


    State tickRunning(Running running, Queue<InternalEvent> queue) {
        Data data = running.data();
        Arena arena = data.base().arena();

        var accumulatorsAndActions = runAccumulators(data.tickAccumulators(), null);

        // TODO, consider adding events which represent the action,
        // and let the main loop handle the events,
        // i.e trigger the action in some way
        // Launching virtual thread for now...
        accumulatorsAndActions.values().forEach(Thread.ofVirtual()::start);

        if (ZonedDateTime.now().isAfter(arena.tourInfo().startsAt().plus(arena.duration()))) {
            queue.offer(new TourEnd());
            return new Ended(new Data(data.base(), running.data().members(), List.of()));
        }

        var updatedTickAccumulators = List.copyOf(accumulatorsAndActions.accumulators());

        return new Running(data.withTickAccumulators(updatedTickAccumulators), running.monitor(), running.resultAccumulators());
    }

    State tickEnded(Ended ended, Queue<InternalEvent> queue) {

        return ended;
    }


    static Runnable standings(Client client, Arena arena, Queue<InternalEvent> queue) {
        return () -> queue.offer(new Standings(client.tournaments().teamBattleResultsById(arena.id()).stream()
                    .map(ts -> new InternalEvent.TeamPoints(arena.teamBattle()
                            .get().teams().stream()
                            .filter(teamInfo -> teamInfo.id().equals(ts.teamId()))
                            .findFirst()
                            .map(Arena.TeamInfo::name)
                            .orElse(ts.teamId()),
                            ts.score())
                        )
                    .toList()));
    }

    static Runnable arenaUpdate(Client client, Arena arena, Queue<InternalEvent> queue) {
        return () -> client.tournaments().arenaById(arena.id()).ifPresent(updatedArena -> queue.offer(new ArenaUpdate(updatedArena)));
    }

    static final Collector<ArenaResult, ?, Set<String>> resultNameToIdCollector =
        Collectors.mapping(ArenaResult::username, Collectors.mapping(name -> name.toLowerCase(Locale.ROOT), Collectors.toSet()));

    static Runnable members(Client client, Arena arena, Team team, Queue<InternalEvent> queue) {
        return () -> queue.offer(client.tournaments().resultsByArenaId(arena.id()).stream()
                .collect(Collectors.teeing(
                        Collectors.filtering(res -> res.team() instanceof Some(var teamId) && teamId.equals(team.id()),
                            resultNameToIdCollector),
                        resultNameToIdCollector,
                        Participants::new)));
    }


    record AccumulatorsAndValues<T,V>(List<Accumulator<T, V>> accumulators, List<V> values) {}

    <T, V> AccumulatorsAndValues<T,V> runAccumulators(List<Accumulator<T, V>> accumulators, T value) {
        return accumulators.stream()
            .map(resAcc -> resAcc.accept(value))
            .reduce(new AccumulatorsAndValues<>(new ArrayList<>(), new ArrayList<>()),
                    (accsAndActions, result) -> {
                        switch(result) {
                            case Accumulator.Self(var accumulator) -> accsAndActions.accumulators().add(accumulator);
                            case Accumulator.SelfAndValue(var accumulator, var action) -> {
                                accsAndActions.accumulators().add(accumulator);
                                accsAndActions.values().add(action);
                            }
                            case Accumulator.Value(var action) -> accsAndActions.values().add(action);
                        };
                        return accsAndActions;
                    },
                    (p1, _) -> p1);
    }

    private static ObjectOutputStream initializeSerializationMaybe(Team team, Arena arena) {
        ObjectOutputStream objectOutputStream = null;
        // Set environment variable TBA_SERIALIZE to create recording of events during team battle,
        // which can be used for teambattle.replay for development/debugging/testdata.
        if (System.getenv("TBA_SERIALIZE") instanceof String) {
            try {
                objectOutputStream = new ObjectOutputStream(new FileOutputStream(team.id() + "." + + arena.tourInfo().startsAt().toInstant().toEpochMilli() + ".data"));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return objectOutputStream;
    }

}
