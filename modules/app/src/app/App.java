package app;

import module java.base;
import module tba.api;
import module chariot;

import java.util.List;
import chariot.model.Arena;

import teambattle.http.TeamBattleHttpSinkProvider;
import teambattle.replay.TeamBattleReplaySourceProvider;
import tb.source.TeamBattleSourceProvider;
import tb.transformer.ToHttpEventProvider;

import app.Util.LabelAndField;
import app.sink.HttpEventGUI;

import java.awt.*;
import javax.swing.*;

public record App(AppConfig config, Client client, List<ResolvedPipeline> pipelines, JFrame frame) {

    public static void main(String[] args) throws InterruptedException {

        Preferences prefs = Preferences.userRoot().node("tba.app");

        AppConfig config = AppConfig.withSyncOnExit(prefs);
        Client client = Client.load(prefs.node("chariot"));

        SwingUtilities.invokeLater(() -> App.of(config, client));

        Thread.setDefaultUncaughtExceptionHandler((Thread thread, Throwable throwable) -> {
                var sw = new StringWriter();
                var pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                System.err.println("""
                    Ooops - Uncaught Exception
                    How unfortunate...
                    ==========================
                    Thread.getName()      : %s
                    Thread.threadId()     : %s
                    Throwable.getMessage(): %s
                    ==========================
                    Stack Trace:
                    %s
                    ==========================
                    """.formatted(
                        thread.getName(),
                        thread.threadId(),
                        throwable.getMessage(),
                        sw.toString()
                        ));
        });
    }


    static App of(AppConfig config, Client client) {
        List<ResolvedPipeline> pipelines = Collections.synchronizedList(new ArrayList<>());

        JFrame frame = new JFrame("Team Battle Announcer");
        config.setAppLocation(frame);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        JMenuBar menubar = Menu.build(frame, config);
        frame.setJMenuBar(menubar);

        App app = new App(config, client, pipelines, frame);
        app.relayoutComponents();
        return app;
    }

    void relayoutComponents() {
        frame.setVisible(false);
        frame.setMinimumSize(new Dimension(400, 200));
        frame.getContentPane().removeAll();

        JPanel basePanel = new JPanel();
        basePanel.setLayout(new BorderLayout());
        frame.getContentPane().add(basePanel);

        JPanel buttonPanel = new JPanel();
        JButton startButton = new JButton("Start");
        JButton stopButton = new JButton("Stop");
        JComponent browserComponent = initBrowserComponent();

        List.of(startButton, stopButton, browserComponent).forEach(button -> {
            button.setEnabled(false);
            buttonPanel.add(button);
        });

        var stopAction = new AtomicReference<Runnable>(() -> {});
        var startAction = new AtomicReference<Function<Runnable, Thread>>();

        startButton.addActionListener(_ -> {
            startButton.setEnabled(false);
            if (config.httpEnabled()) {
                browserComponent.setEnabled(true);
                if (browserComponent instanceof JTextField textField) {
                    browserComponent.setVisible(true);
                    var bindAddress = config.bindAddress();
                    String uri = "http://%s:%s/".formatted(bindAddress.getHostString(), bindAddress.getPort());
                    textField.setText(uri);
                    basePanel.validate();
                    basePanel.repaint();
                }
            } else {
                browserComponent.setEnabled(false);
            }
            Runnable callback = () -> {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
            };
            stopButton.setEnabled(true);
            Thread t = startAction.get().apply(callback);
            stopAction.set(() -> {
                t.interrupt();
                stopButton.setEnabled(false);
            });
        });
        stopButton.addActionListener(_ -> stopAction.get().run());
        basePanel.add(buttonPanel, BorderLayout.SOUTH);

        JButton buttonPickTeam = new JButton("Pick a Team");
        buttonPickTeam.addActionListener(_ -> {
            var current = config.selectedTeam();
            AppConfig.SelectedTeam pickedTeam = Util.pickTeam(current, frame, client);

            if (!Objects.equals(pickedTeam, current)) {
                config.storeSelectedTeam(pickedTeam);
                relayoutComponents();
            }
        });

        switch (config.selectedTeam()) {

            case AppConfig.SelectedTeam.None() -> {
                JPanel panel = new JPanel();
                panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

                buttonPickTeam.setText("No Team Selected");

                List.of((JComponent)Box.createVerticalGlue(), buttonPickTeam, (JComponent)Box.createVerticalGlue())
                    .forEach(comp -> {
                        panel.add(comp);
                        comp.setAlignmentX(Component.CENTER_ALIGNMENT);
                    });

                basePanel.add(panel, BorderLayout.CENTER);
            }

            case AppConfig.SelectedTeam.TeamIdAndName(String id, String name) -> {
                record Tournament(String id, String name) {}
                var teamComp = LabelAndField.of("Team:", buttonPickTeam);
                var tourComp = LabelAndField.of("Tournament:", new JComboBox<Tournament>());
                var progressComp = LabelAndField.of("Loading...", new JProgressBar());

                teamComp.field().setText(name);
                teamComp.field().setFocusable(false);

                tourComp.field().setRenderer((_, value, _, _, _) -> new JLabel(switch(value) {
                    case null -> "<No Team Battles today>";
                    case Tournament(_, String tourName) -> tourName;
                    }));
                tourComp.field().setPrototypeDisplayValue(new Tournament("", "T".repeat(30)));

                progressComp.field().setIndeterminate(true);
                progressComp.field().putClientProperty("JProgressBar.style", "circular");

                JPanel teamPanel = Util.pairedComponents(List.of(teamComp, tourComp, progressComp));

                JPanel panel = new JPanel();
                panel.setLayout(new BorderLayout());
                panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                panel.add(teamPanel, BorderLayout.CENTER);

                basePanel.add(panel, BorderLayout.CENTER);

                if (! (client.teams().byTeamId(id).orElse(null) instanceof Team team)) return;

                Thread.ofPlatform().start(() -> {
                    Stream<Tournament> stream = Stream.concat(
                        tourStream(client.teams().arenaByTeamId(id, p -> p.statusStarted()).stream(), Tournament::new),
                        tourStream(client.teams().arenaByTeamId(id, p -> p.statusCreated().max(1000)).stream(), Tournament::new));

                    try {
                        stream.forEach(tournament -> {
                            tourComp.field().addItem(tournament);
                            if (! startButton.isEnabled()) {
                                startButton.setEnabled(true);
                                startAction.set(callback -> {
                                    Thread t = switch (tourComp.field().getSelectedIndex()) {
                                        case int index when index > -1 -> {
                                            Tournament selectedTournament = tourComp.field().getItemAt(index);
                                            yield switch (client.tournaments().arenaById(selectedTournament.id())) {
                                                case Entry(Arena arena) -> startLiveThread(team, arena, callback);
                                                case NoEntry<Arena> nope -> Thread.ofPlatform().start(() -> {
                                                    System.out.println("Failed to lookup arena with id %s - %s".formatted(selectedTournament.id(), nope));
                                                    callback.run();
                                                });
                                            };
                                        }
                                        default -> Thread.ofPlatform().start(() ->  callback.run());
                                    };
                                    stream.close();
                                    return t;
                                });
                            }
                        });
                    } catch (Exception ex) {}
                    progressComp.label().setVisible(false);
                    progressComp.field().setVisible(false);
                });
            }

            case AppConfig.SelectedTeam.Replay() -> {

                if (replayPath != null) {
                    startButton.setEnabled(true);
                    startAction.set(callback -> startReplayThread(config, replayPath, callback));
                }

                JPanel outer = new JPanel(new BorderLayout());

                JPanel panel = new JPanel();

                outer.add(panel, BorderLayout.CENTER);

                panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

                buttonPickTeam.setText("Simulating a Team Battle With Test Data");

                List.of((JComponent)Box.createVerticalGlue(), buttonPickTeam, (JComponent)Box.createVerticalGlue())
                    .forEach(comp -> {
                        panel.add(comp);
                        comp.setAlignmentX(Component.CENTER_ALIGNMENT);
                    });

                buttonPickTeam.setFocusable(false);

                basePanel.add(outer, BorderLayout.CENTER);
            }
        };

        basePanel.invalidate();
        frame.pack();
        frame.setVisible(true);
    }

    private <T> Stream<T> tourStream(Stream<ArenaLight> arenaStream, BiFunction<String, String, T> constructor) {
        return arenaStream.filter(arena ->
                arena.teamBattle().isPresent() &&
                arena.tourInfo().startsAt().isBefore(ZonedDateTime.now().plusDays(1)))
            .sorted(Comparator.comparing(arena -> arena.tourInfo().startsAt()))
            .map(arena -> constructor.apply(arena.id(), arena.tourInfo().name()));
    }

    private JComponent initBrowserComponent() {
        if (Desktop.isDesktopSupported()
            && Desktop.getDesktop() instanceof Desktop desktop
            && desktop.isSupported(Desktop.Action.BROWSE)) {

            var button = new JButton("Web Browser");
            button.addActionListener(_ -> {
                var bindAddress = config.bindAddress();
                String uri = "http://%s:%s/".formatted(bindAddress.getHostString(), bindAddress.getPort());
                try {
                    desktop.browse(URI.create(uri));
                } catch (IOException ex) { System.out.println("Failed to browse uri - " + uri); }
            });

            return button;
        } else {
            var textField = new JTextField();
            textField.setEditable(false);
            textField.setVisible(false);
            return textField;
        }
    }

    Thread startLiveThread(Team team, Arena arena, Runnable callback) {
        return Thread.ofPlatform().start(() -> {
            System.out.println(team.name() + " " + arena.tourInfo().name());
            clearPipelines();

            SourceProvider sourceProvider = new TeamBattleSourceProvider();
            Config sourceConfig = new TeamBattleSourceProvider.TeamBattleSourceConfig(team, arena, client);
            Source source = sourceProvider.of(sourceConfig);

            TransformerProvider transformerProvider = new ToHttpEventProvider();
            Config transformerConfig = ToHttpEventProvider.TeamBattleTransformerConfig.of();
            Transformer transformer = transformerProvider.of(transformerConfig);

            SinkProvider sinkProvider = new HttpEventGUI();
            Config sinkConfig = new HttpEventGUI.GUIConfig(config.cssChoice(), arena.tourInfo().name());
            Sink sink = sinkProvider.of(sinkConfig);

            pipelines.add(new ResolvedPipeline(source, List.of(transformer), sink));

            if (config.httpEnabled()) {
                pipelines.add(buildHttpPipeline(source, transformer));
            }

            TBA.runPipelines(pipelines);

            callback.run();
        });
    }

    Thread startReplayThread(AppConfig config, Path replayPath, Runnable callback) {
        return Thread.ofPlatform().start(() -> {
            clearPipelines();

            Preferences replayConfig = config.prefs().node("replay.source");
            replayConfig.put("path", replayPath.toAbsolutePath().toString());

            SourceProvider sourceProvider = new TeamBattleReplaySourceProvider();
            Optional<Config> configOpt = TeamBattleReplaySourceProvider.createConfig(replayPath.toAbsolutePath().toString(), true);
            Source source = configOpt.map(sourceProvider::of).orElseThrow(() -> new RuntimeException("Failed to create replay source"));

            TransformerProvider transformerProvider = new ToHttpEventProvider();
            Config transformerConfig = ToHttpEventProvider.TeamBattleTransformerConfig.of();
            Transformer transformer = transformerProvider.of(transformerConfig);

            SinkProvider sinkProvider = new HttpEventGUI();
            Config sinkConfig = new HttpEventGUI.GUIConfig(config.cssChoice(), "Replay");
            Sink sink = sinkProvider.of(sinkConfig);

            pipelines.add(new ResolvedPipeline(source, List.of(transformer), sink));

            if (config.httpEnabled()) {
                pipelines.add(buildHttpPipeline(source, transformer));
            }

            TBA.runPipelines(pipelines);

            callback.run();
        });
    }

    private ResolvedPipeline buildHttpPipeline(Source source, Transformer transformer) {
        Path cssPath = switch(config.cssChoice()) {
            case AppConfig.CSSChoice.Custom(Path path) -> path;
            case AppConfig.CSSChoice.BuiltInCSS builtin -> {
                try {
                    // Use builtin from "app" instead of from "http.sink"
                    var tmp = Files.createTempFile("app-builtin-for-http.sink-", ".css");
                    tmp.toFile().deleteOnExit();
                    try (var os = Files.newOutputStream(tmp);
                         var is = App.class.getModule().getResourceAsStream(builtin.resource)) {
                        is.transferTo(os);
                    }
                    yield tmp;
                } catch (IOException ex) {
                    System.out.println("Failed to make app builtin css available for http.sink - " + ex.getMessage());
                    throw new UncheckedIOException(ex);
                }
            }
        };

        SinkProvider httpSinkProvider = new TeamBattleHttpSinkProvider();
        var bindAddress = config.bindAddress();
        var address = TeamBattleHttpSinkProvider.HttpSinkConfig.address(bindAddress.getAddress(), bindAddress.getPort());
        Config httpSinkConfig = TeamBattleHttpSinkProvider.HttpSinkConfig.of(address, cssPath);
        Sink httpSink = httpSinkProvider.of(httpSinkConfig);
        return new ResolvedPipeline(source, List.of(transformer), httpSink);
    }

    void clearPipelines() {
        synchronized(pipelines) {
            for (var pipeline : pipelines) {
                pipeline.sink().close();
            }
            pipelines.clear();
        }
    }

    static Path replayPath = null;

    static {
        try {
            Path tmp = Files.createTempFile("app-builtin-for-replay-", ".data");
            tmp.toFile().deleteOnExit();
            try (var os = Files.newOutputStream(tmp);
                 var is = App.class.getModule().getResourceAsStream("/replay.data")) {
                is.transferTo(os);
            }
            replayPath = tmp;
        } catch (IOException ex) { System.out.println("Failed to make app builtin replay.data available for replay - " + ex.getMessage()); }
    }

}
