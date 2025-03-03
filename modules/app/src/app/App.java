package app;

import module java.base;
import module java.desktop;

import module tba.api;
import module chariot;

import java.util.List;

import tba.api.Source;
import tba.api.Sink;
import tba.api.Transformer;

import chariot.model.Arena;
import chariot.model.Team;

import teambattle.http.TeamBattleHttpSinkProvider;
import teambattle.replay.TeamBattleReplaySourceProvider;
import tb.source.TeamBattleSourceProvider;
import tb.transformer.ToHttpEventProvider;

import app.Util.LabelAndField;
import app.sink.HttpEventGUI;

public record App(AppConfig config, Client client, List<ResolvedPipeline> pipelines, JFrame frame) {

    public static void main(String[] args) throws InterruptedException {

        Preferences prefs = Preferences.userRoot().node("tba.app");

        AppConfig config = AppConfig.withSyncOnExit(prefs);
        Client client = Client.load(prefs.node("chariot"));

        SwingUtilities.invokeLater(() -> App.of(config, client));
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
        frame.getContentPane().removeAll();

        JPanel basePanel = new JPanel();
        basePanel.setPreferredSize(new Dimension(400, 200));
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

                List.of((JComponent)Box.createVerticalGlue(), new JLabel("No Team Selected"), buttonPickTeam, (JComponent)Box.createVerticalGlue()).forEach(comp -> {
                    panel.add(comp);
                    comp.setAlignmentX(Component.CENTER_ALIGNMENT);
                });

                basePanel.add(panel, BorderLayout.CENTER);
            }

            case AppConfig.SelectedTeam.TeamIdAndName(String id, String name) -> {
                record Tournament(String id, String name) {}
                var teamComp = LabelAndField.of("Team:", new JLabel(name));
                var tourComp = LabelAndField.of("Tournament:", new JComboBox<Tournament>());

                tourComp.field().setRenderer((_, value, _, _, _) -> new JLabel(switch(value) {
                    case null -> "<No Team Battles today>";
                    case Tournament(_, String tourName) -> tourName;
                    }));

                var teamPanel = Util.pairedComponents(List.of(teamComp, tourComp));

                JPanel panel = new JPanel();
                panel.setLayout(new BorderLayout());
                panel.add(teamPanel, BorderLayout.CENTER);

                JPanel bp = new JPanel();
                bp.setLayout(new BoxLayout(bp, BoxLayout.X_AXIS));

                bp.add(buttonPickTeam);
                buttonPickTeam.setAlignmentX(Component.RIGHT_ALIGNMENT);

                buttonPickTeam.setText("Switch Team");
                buttonPickTeam.setFocusable(false);

                panel.add(bp, BorderLayout.SOUTH);

                basePanel.add(panel, BorderLayout.CENTER);


                if (client.teams().byTeamId(id).orElse(null) instanceof Team team
                    && client.teams().arenaByTeamId(id, 1000).stream()
                        .takeWhile(arena -> arena.tourInfo().status() != TourInfo.Status.finished)
                        .filter(arena -> arena.teamBattle().isPresent())
                        .filter(arena -> arena.tourInfo().startsAt().isBefore(ZonedDateTime.now().plusDays(1)))
                        .sorted(Comparator.comparing(arena -> arena.tourInfo().startsAt()))
                        .map(arena -> new Tournament(arena.id(), arena.tourInfo().name()))
                        .toList() instanceof List<Tournament> tournaments) {

                    tournaments.forEach(tournament -> tourComp.field().addItem(tournament));

                    if (! tournaments.isEmpty()) {
                        startButton.setEnabled(true);
                        startAction.set(callback -> {
                            return switch (tourComp.field().getSelectedIndex()) {
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
                                default -> Thread.ofPlatform().start(() -> {
                                    System.out.println("No Team Battle selected");
                                    callback.run();
                                });
                            };
                        });
                    }
                }
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

                List.of((JComponent)Box.createVerticalGlue(), new JLabel("Simulating a Team Battle With Test Data"), (JComponent)Box.createVerticalGlue()).forEach(comp -> {
                    panel.add(comp);
                    comp.setAlignmentX(Component.CENTER_ALIGNMENT);
                });

                JPanel bp = new JPanel();
                bp.setLayout(new BoxLayout(bp, BoxLayout.X_AXIS));

                List.of((JComponent)Box.createHorizontalGlue(), buttonPickTeam).forEach(comp -> {
                    bp.add(comp);
                    comp.setAlignmentX(Component.RIGHT_ALIGNMENT);
                });
                buttonPickTeam.setFocusable(false);

                outer.add(bp, BorderLayout.SOUTH);


                basePanel.add(outer, BorderLayout.CENTER);
            }
        };

        basePanel.invalidate();
        frame.pack();
        frame.setVisible(true);
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
