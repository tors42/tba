package tb.source;

import module java.base;

import module tba.api;
import module teambattle.api;
import module chariot;

import chariot.model.Arena;
import chariot.model.Team;
import chariot.model.User;
import tb.internal.*;
import tb.internal.InternalEvent.*;

public class Tour implements Source {

    boolean done = false;
    State currentState;

    final List<Queue<TeamBattleEvent>> externalQueues = new CopyOnWriteArrayList<>();
    final AtomicInteger gameStreamCount = new AtomicInteger();
    final int maxNumberOfGamesPerStream;
    final ObjectOutputStream oos;
    final Map<String, User> cache = new ConcurrentHashMap<>();

    public Tour(Team team, Arena arena, Client client) {
        currentState = new Initial(new Base(team, arena, client));
        maxNumberOfGamesPerStream = switch(client) {
            case ClientAuth _ -> 1000;
            case Client _     ->  500;
        };
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

                    case MemberPoll memberPoll -> {
                        if (! (currentState instanceof Running running)) yield currentState;

                        if (running.monitor() instanceof Large monitor) {

                            Set<String> existingMembersAndMembersWithSuccessfullyAddedGames = new HashSet<>(monitor.currentlyMonitoredMemberIds());

                            List<StreamMeta> nonExpiredMetas = monitor.metas().stream()
                                .<StreamMeta>mapMulti( (meta, mapper) -> {
                                    if (meta.gameIdStatus().size() == maxNumberOfGamesPerStream
                                        && meta.gameIdStatus().values().stream().allMatch(Boolean::booleanValue)) {
                                        meta.byGameIds().close();
                                    } else {
                                        mapper.accept(meta);
                                    }
                                })
                                .toList();

                            int remainingRoom = switch(nonExpiredMetas) {
                                case List<StreamMeta> list when list.isEmpty() -> 0;
                                case List<StreamMeta> list -> maxNumberOfGamesPerStream - list.getLast().gameIdStatus().size();
                            };

                            List<PlayingMember> membersWithNewlyStartedGamesAsList = memberPoll.playingMembers().stream().toList();

                            List<PlayingMember> membersToFillRemaining = membersWithNewlyStartedGamesAsList.stream()
                                .limit(remainingRoom)
                                .toList();

                            if (! membersToFillRemaining.isEmpty()) {
                                StreamMeta metaWithRemaining = nonExpiredMetas.getLast();
                                Set<String> gameIds = membersToFillRemaining.stream()
                                    .map(PlayingMember::gameId)
                                    .collect(Collectors.toSet());
                                switch (currentState.base().client().games().addGameIdsToStream(metaWithRemaining.streamId(), gameIds)) {
                                    case Fail(int status, var err) -> System.err.println("""
                                            Failed to add %d games to (already holding %d) stream %s
                                            %d - %s
                                            Hoping for better luck in the future.""".formatted(
                                                gameIds.size(), metaWithRemaining.gameIdStatus().size(), metaWithRemaining.streamId(),
                                                status, err));
                                    default -> {
                                        existingMembersAndMembersWithSuccessfullyAddedGames.addAll(
                                                membersToFillRemaining.stream()
                                                .map(PlayingMember::userId)
                                                .collect(Collectors.toSet()));
                                        for (String gameId : gameIds) {
                                            metaWithRemaining.gameIdStatus().put(gameId, false);
                                        }
                                    }
                                }
                            }

                            List<List<PlayingMember>> membersToCreateNewBatchesFor = membersWithNewlyStartedGamesAsList.stream()
                                .skip(remainingRoom)
                                .gather(Gatherers.windowFixed(maxNumberOfGamesPerStream))
                                .toList();


                            List<StreamMeta> newMetas = membersToCreateNewBatchesFor.stream().map(batch -> {
                                String streamId = "stream-games-by-ids-%03d".formatted(gameStreamCount.incrementAndGet());
                                Set<String> gameIds = batch.stream().map(PlayingMember::gameId).collect(Collectors.toSet());

                                Stream<GameMeta> byGameIds = switch (currentState.base().client().games().gameInfosByGameIds(streamId, gameIds)) {
                                    case Entries(var stream) -> {
                                        existingMembersAndMembersWithSuccessfullyAddedGames.addAll(
                                                batch.stream().map(PlayingMember::userId).collect(Collectors.toSet()));
                                        yield stream;
                                    }
                                    case Fail(int status, var err) -> {
                                        System.err.println("""
                                                Failed to open stream %s for %d game ids.
                                                %d - %s
                                                """.formatted(streamId, gameIds.size(), status, err));
                                        yield Stream.of();
                                    }
                                };

                                Thread.ofPlatform().name("large-monitor-%s".formatted(streamId)).start(() -> {
                                    try {
                                        byGameIds
                                            .map(this::resultOfMember)
                                            .filter(Optional::isPresent)
                                            .map(Optional::get)
                                            .forEach(internalEventQueue::offer);
                                    } catch (UncheckedIOException unchecked) {
                                        // Stream.forEach(...)
                                        // We are expecting to be closed final game result of his stream has been reported.
                                        // Some verification that the unchecked was caused by that should be added,
                                        // ie if (expectedClose) { silently exit } else { retry stream - the close was unexpected! }
                                    }});

                                Map<String, Boolean> gameIdStatus = gameIds.stream()
                                    .collect(Collectors.toMap(Function.identity(),  _ -> Boolean.FALSE));

                                StreamMeta meta = new StreamMeta(byGameIds, gameIdStatus, streamId);
                                return meta;
                            }).toList();

                            running = running.withMonitor(new Large(
                                        Stream.concat(nonExpiredMetas.stream(), newMetas.stream()).toList(),
                                        existingMembersAndMembersWithSuccessfullyAddedGames));
                        }


                        if (System.getenv("DEBUG_MEMBERS") instanceof String _) {
                            memberPoll.playingMembers().stream()
                                .sorted(Comparator.comparing(PlayingMember::userId))
                                .forEach(pm -> System.out.println("https://lichess.org/%s started by %s".formatted(pm.gameId(), pm.userId())));

                            if (running.monitor() instanceof Large(_, var monitored)) {
                                System.out.println("Members with ongoing games:\n%s".formatted(
                                            monitored.stream().sorted().toList()));
                                System.out.println("Members without games:\n%s".formatted(
                                            currentMembers().stream()
                                                .filter(Predicate.not(monitored::contains))
                                                .sorted().toList()));
                            }
                        }

                        yield running;
                    }

                    case Participants participants -> {
                        Set<String> members = participants.memberIds();
                        Set<String> allParticipants = participants.allParticipantIds();

                        if (! (currentState instanceof WithData state)) yield currentState;

                        WithData nextState = state.withMembers(new Members.Some(Set.copyOf(members)));

                        List<String> newMembers = switch (state.data().members()) {
                            case Members.Some(var previousMembers) -> members.stream()
                                .filter(Predicate.not(previousMembers::contains))
                                .sorted()
                                .toList();
                            default -> List.of();
                        };


                        if (! newMembers.isEmpty()) {
                            // Don't show new members as "recently" joined,
                            // if we have just started the announce for this team battle.
                            if (state.data().members() instanceof Members.Some) {
                                internalEventQueue.offer(new Message(new TeamBattleEvent.Join(newMembers)));
                            }
                        }

                        yield switch(nextState) {
                            case Running running -> {
                                ResultsMonitor updatedMonitor = switch(running.monitor()) {
                                    case Small(Stream<GameMeta> oldStream, Set<String> oldUsers) -> {
                                        if (oldUsers.equals(allParticipants)) yield running.monitor();

                                        // Participant update, close the now outdated stream
                                        oldStream.close();

                                        var updatedAllParticipants = Set.copyOf(allParticipants);
                                        if (updatedAllParticipants.size() <= 300) {
                                            Stream<GameMeta> newStream = switch(running.base().client().games().gameInfosByUserIds(updatedAllParticipants)) {
                                                case Entries(var stream) -> stream;
                                                case Fail(int status, var err) -> {
                                                    System.err.println("""
                                                            Failed to open game stream of %d users...
                                                            %d - %s
                                                            Hoping for better luck in a minute.""".formatted(updatedAllParticipants.size(), status, err));
                                                    yield Stream.of();
                                                }
                                            };

                                            Thread.ofPlatform().name("small-monitor-" + updatedAllParticipants.size()).start(() -> {
                                                try {
                                                    newStream
                                                        .map(this::resultOfMember)
                                                        .filter(Optional::isPresent)
                                                        .map(Optional::get)
                                                        .forEach(internalEventQueue::offer);
                                                } catch (UncheckedIOException unchecked) {
                                                    // Stream.forEach(...)
                                                    // We are expecting to be closed when new members join.
                                                    // Some verification that the unchecked was caused by that should be added,
                                                    // ie if (expectedClose) { silently exit } else { retry stream - the close was unexpected! }
                                                }});

                                            yield new Small(newStream, updatedAllParticipants);

                                        } else {
                                            // We just now grew from Small to Large,
                                            // switch mode to start performing MemberPoll

                                            Set<String> membersToPoll = participants.members().stream()
                                                .filter(Predicate.not(ParticipantStatus::withdraw))
                                                .map(ParticipantStatus::userId)
                                                .collect(Collectors.toSet());

                                            initiateMemberPoll(membersToPoll, internalEventQueue, running.base().client());

                                            yield new Large(List.of(), Set.of());
                                        }
                                    }

                                    case Large(_, Set<String> currentlyMonitoredMemberIds) ->  {

                                        Set<String> membersToPoll = participants.members().stream()
                                            .filter(Predicate.not(ParticipantStatus::withdraw))
                                            .filter(participant -> ! currentlyMonitoredMemberIds.contains(participant.userId()))
                                            .map(ParticipantStatus::userId)
                                            .collect(Collectors.toSet());

                                        initiateMemberPoll(membersToPoll, internalEventQueue, running.base().client());

                                        yield running.monitor();
                                    }
                                };

                                yield running.withMonitor(updatedMonitor);
                            }

                            default -> nextState;
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

                        // A result just came in...
                        // If we have a Large monitor, it means that a "currently playing" user should be removed,
                        // so they will be probed for new games!
                        if (running.monitor() instanceof Large(List<StreamMeta> metas, Set<String> currentlyMonitoredMemberIds)) {

                            Set<String> remainingUserIds = currentlyMonitoredMemberIds.stream()
                                .filter(id -> ! id.equals(result.userId()))
                                .collect(Collectors.toSet());

                            // Mutate gameIdStatus map...
                            for (StreamMeta streamMeta : metas) {
                                if (streamMeta.gameIdStatus().containsKey(result.gameId())) {
                                    streamMeta.gameIdStatus().put(result.gameId(), true);
                                }
                            }

                            running = running.withMonitor(new Large(metas, remainingUserIds));
                        }


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

                        TeamBattleEvent eventWithNames = EventRenderer.replaceNames(eventWithIds, this::nameRenderer, this::nameRenderer);

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

    Set<String> currentMembers() {
        return switch(currentState) {
            case WithData withData -> switch (withData.data().members()) {
                case Members.Some(Set<String> members) -> members;
                case Members.Unset() -> Set.of();
            };
            default -> Set.of();
        };
    }

    String nameRenderer(String id) {
        try {
            User cachedUser = cache.computeIfAbsent(id, _ -> switch(currentState.base().client().users().byId(id)) {
                case Entry(var user) -> user;
                default -> null;
            });
            return cachedUser == null ? id : cachedUser.name();
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    Optional<GameResult> resultOfMember(GameMeta gameMeta) {
        if (gameMeta.status().status() > Enums.Status.started.status()) {
            record IdColor(String id, Enums.Color color, int rating, boolean provisional) {}
            var whiteInfo = gameMeta.players().white();
            var blackInfo = gameMeta.players().black();
            var white = new IdColor(whiteInfo.userId(), Enums.Color.white, whiteInfo.rating(), whiteInfo.provisional());
            var black = new IdColor(blackInfo.userId(), Enums.Color.black, blackInfo.rating(), blackInfo.provisional());

            Set<String> memberSet = currentMembers();

            if (memberSet.contains(white.id()) || memberSet.contains(black.id())) {
                var member = memberSet.contains(white.id()) ? white : black;
                var opponent = member.equals(white) ? black : white;

                return Optional.of(switch(gameMeta.winner()) {
                    case Some(var color) when color == member.color -> new Win(gameMeta.id(), member.id(), opponent.id(),
                            opponent.rating() - member.rating(), whiteInfo.provisional() || blackInfo.provisional());
                    case Some(_) -> new Loss(gameMeta.id(), member.id(), opponent.id());
                    case Empty() -> new Draw(gameMeta.id(), member.id(), opponent.id());
                });
            }
        }
        return Optional.empty();
    }

    void initiateMemberPoll(Set<String> membersToPoll, BlockingQueue<InternalEvent> internalEventQueue, Client client) {
        if (membersToPoll.isEmpty()) {
            internalEventQueue.offer(new MemberPoll(Set.of()));
        } else {
            Thread.ofPlatform().name("large-monitor-poll-" + membersToPoll.size()).start(() ->
                    internalEventQueue.offer(new MemberPoll(switch(client.users()
                                .statusByIds(membersToPoll, p -> p.withGameIds())) {
                        case Entries(var stream) -> stream
                            .filter(UserStatus::online)
                            .filter(member -> member.playingGameId().isPresent())
                            .map(member -> new PlayingMember(member.id(), member.playingGameId().get()))
                            .collect(Collectors.toSet());
                        case Fail(int status, var err) -> {
                            System.err.println("""
                                    Failed to poll %d members for game info
                                    %d - %s
                                    Hoping for better luck in a minute.""".formatted(
                                        membersToPoll.size(),
                                        status, err));
                            yield Set.of();
                        }
                    })));
        }
    }

    record Base(Team team, Arena arena, Client client) {
        public Base withArena(Arena updated) {
            return new Base(team, updated, client);
        }
    }

    sealed interface Members {
        record Unset() implements Members {}
        record Some(Set<String> members) implements Members {}
    }

    record Data(Base base, Members members, List<Accumulator<Void, Runnable>> tickAccumulators) {
        public Data withBase(Base updated) {
            return new Data(updated, members, tickAccumulators);
        }
        public Data withMembers(Members updated) {
            return new Data(base, updated, tickAccumulators);
        }
        public Data withTickAccumulators(List<Accumulator<Void, Runnable>> updated) {
            return new Data(base, members, updated);
        }
    }

    sealed interface ResultsMonitor {}
    record Small(Stream<GameMeta> byUserIds, Set<String> userIds) implements ResultsMonitor {}
    record Large(List<StreamMeta> metas, Set<String> currentlyMonitoredMemberIds) implements ResultsMonitor {}

    record StreamMeta(Stream<GameMeta> byGameIds, Map<String, Boolean> gameIdStatus, String streamId) {}

    sealed interface State permits Initial, WithData {
        Base base();

        default State withArena(Arena updated) {
            return switch (this) {
                case Initial state    -> new Initial(state.base().withArena(updated));
                case NotStarted state -> new NotStarted(state.data().withBase(state.data().base().withArena(updated)));
                case Running state    -> new Running(state.data().withBase(state.data().base().withArena(updated)), state.monitor(), state.resultAccumulators());
                case Ended state      -> new Ended(state.data().withBase(state.data().base().withArena(updated)));
            };
        }
    }

    sealed interface WithData extends State {
        Data data();
        default Base base() { return data().base(); }
        default WithData withMembers(Members updated) {
            return switch (this) {
                case NotStarted state -> new NotStarted(state.data().withMembers(updated));
                case Running state    -> new Running(state.data().withMembers(updated), state.monitor(), state.resultAccumulators());
                case Ended state      -> new Ended(state.data().withMembers(updated));
            };
        }
    }

    record Initial(Base base) implements State {}
    record NotStarted(Data data) implements WithData {}
    record Running(Data data, ResultsMonitor monitor, List<Accumulator<InternalEvent.GameResult, TeamBattleEvent>> resultAccumulators) implements WithData {
        public Running withResultAccumulators(List<Accumulator<GameResult, TeamBattleEvent>> updated) {
            return new Running(data(), monitor(), updated);
        }
        public Running withMonitor(ResultsMonitor updated) {
            return new Running(data(), updated, resultAccumulators());
        }
    }
    record Ended(Data data) implements WithData {}


    State tickInitial(Initial initial, Queue<InternalEvent> queue) {
        Base base = initial.base();
        Arena arena = base.arena();
        return switch (ZonedDateTime.now()) {

            // Normal flow
            case ZonedDateTime now when now.isBefore(arena.tourInfo().startsAt())
                -> new NotStarted(new Data(base, new Members.Some(Set.of()),
                            List.of(
                                new RepeatableAction(60,     arenaUpdate(base.client(), arena, queue)),
                                new RepeatableAction(60, 60, members(base.client(), arena, base.team(), queue))
                                )));

            // Old tournament
            case ZonedDateTime now when now.isAfter(arena.tourInfo().startsAt().plus(arena.duration()))
                -> new Ended(new Data(base, new Members.Unset(), List.of()));

            // Ongoing tournament
            case ZonedDateTime _
                -> new Running(new Data(base, new Members.Unset(),
                            List.of(
                                new RepeatableAction(60, arenaUpdate(base.client(), arena, queue)),
                                new RepeatableAction(60, 60, members(base.client(), arena, base.team(), queue)),
                                new RepeatableAction(60*20, 60*20, standings(base.client(), arena, queue))
                                )),
                        new Small(Stream.of(), Set.of()),
                        List.of(new StreakAccumulator(), new UpsetAccumulator(), new PhoenixAccumulator(), new AvengeAccumulator())
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
                List.of(new FirstBloodAccumulator(), new StreakAccumulator(), new UpsetAccumulator(), new PhoenixAccumulator(), new AvengeAccumulator())
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

    static final Collector<ArenaResult, ?, Set<ParticipantStatus>> resultToParticipantStatusCollector =
        Collectors.mapping(result -> new ParticipantStatus(result.username().toLowerCase(Locale.ROOT), result.withdraw()), Collectors.toSet());

    static Runnable members(Client client, Arena arena, Team team, Queue<InternalEvent> queue) {
        return () -> {
            switch (client.tournaments().resultsByArenaId(arena.id())) {
                case Entries(var stream) -> queue.offer(stream
                        .collect(Collectors.teeing(
                                Collectors.filtering(res -> res.team() instanceof Some(var teamId) && teamId.equals(team.id()),
                                    resultToParticipantStatusCollector),
                                resultToParticipantStatusCollector,
                                Participants::new)));
                case Fail(int status, var err) -> System.err.println("Arena results lookup failed - %d %s".formatted(status, err));
            };
        };
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
