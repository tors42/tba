package teambattle.replay;

import module java.base;
import module tba.api;
import module teambattle.api;

public class TeamBattleReplaySourceProvider implements SourceProvider {

    @Override public Class<? extends Event> eventType() { return TeamBattleEvent.class; }
    @Override public String name() { return "teambattle.replay"; }

    @Override
    public ConfigProvider configProvider(UI ui) {
        return new ConfigProvider() {

            @Override public String name() { return "teambattle.replay"; }

            @Override
            public Optional<Config> interactiveConfig() {
                // filechooser, directory listing, etc etc
                String filename = ui.crudeQuery("Path: ");
                return createConfig(filename, true);
            }

            @Override
            public Optional<Config> interactiveConfig(Preferences prefs) {
                String _ = prefs.get("path", null);
                Boolean speed = prefs.getBoolean("speedup", true);
                String filename = ui.crudeQuery("Filename: ");
                return createConfig(filename, speed);
            }

            @Override
            public Optional<Config> noninteractiveConfig() {
                return createConfig("input.data", true);
            }

            @Override
            public Optional<Config> noninteractiveConfig(Preferences prefs) {
                String filename = prefs.get("path", null);
                Boolean speedup = prefs.getBoolean("speedup", true);
                return createConfig(filename, speedup);
            }
        };
    }

    public static Optional<Config> createConfig(String filename, boolean speedup) {
        if (! (filename instanceof String str)) return Optional.empty();
        Path path = Path.of(str);
        if (! Files.exists(path)) return Optional.empty();

        List<TimedEvent> events = new ArrayList<>();

        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            while(ois.readObject() instanceof TimedEvent event) events.add(event);
        } catch (EOFException eof) {

            // lol, look at this!!!!!!
            // Expected code path, by catching an exception...
            // Alrighty then...
            return Optional.of(new TeamBattleReplaySourceConfig(path, events, speedup));

        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return Optional.empty();
    }

    public record TeamBattleReplaySourceConfig(Path path, List<TimedEvent> events, boolean speedup) implements Config {
        @Override
        public void store(Preferences prefs) {
            prefs.put("path", path.toString());
            prefs.putBoolean("speedup", speedup);
        }
    }

    @Override
    public Source of(Config config) {
        if (! (config instanceof TeamBattleReplaySourceConfig(Path input, List<TimedEvent> events, boolean speedup))) {
            System.out.println("Unknown config! " + config);
            return null;
        }

        System.out.println("""
                Path: %s
                Events: %d
                """.formatted(input, events.size()));

        return new Source() {

            @Override
            public Stream<? extends Event> events() {
                Spliterator<TeamBattleEvent> spliterator = new Spliterator<TeamBattleEvent>() {

                    int index = 0;
                    ZonedDateTime previousTime = null;

                    @Override
                    public boolean tryAdvance(Consumer<? super TeamBattleEvent> action) {
                        if (events.isEmpty()) return false;

                        TimedEvent event = events.get(index);

                        Duration delay = switch(previousTime) {
                            case null -> Duration.ZERO;
                            case ZonedDateTime zdt -> switch(Duration.between(zdt, event.zdt())) {
                                case Duration duration when !speedup -> duration;
                                case Duration duration -> Duration.ofSeconds(Math.min(5, duration.toSeconds()));
                            };
                        };

                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }

                        action.accept(event.tbe());

                        if (previousTime != null || event.tbe() instanceof TeamBattleEvent.TourBegin) {
                            previousTime = event.zdt();
                        }

                        index++;
                        return index < events.size();
                    }

                    @Override public Spliterator<TeamBattleEvent> trySplit() { return null; }
                    @Override public long estimateSize() { return events.size(); }
                    @Override public int characteristics() { return SIZED; }
                };

                return StreamSupport.stream(spliterator, false);
            }

            @Override
            public void run() {}
        };
    }
}
