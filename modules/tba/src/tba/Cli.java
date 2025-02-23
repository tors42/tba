package tba;

import module tba.api;
import module java.base;

// yagni yagni yagni
public class Cli {

    static Providers providers = TBA.loadProviders();

    public static void main(String[] args) {
        System.out.println("Launching App!");

        // hmm, modes how to run?
        //  - non-interactive, abort on missing info (but if all is present and good, just go!)
        //  - non-interactive, query on missing info
        //  - interactive, always query/confirm (even if info is present and good.)

        System.out.println(TBA.debug(providers));

        UI ui = new UI() {};

        ui.crudeMessage("This is a crude message!");

        switch(args.length) {
            case 0 -> runFromExistingConfig(ui);
            case int _ when args[0].equals("replay") -> runReplay___forever(ui);
            case int _ -> wipeAndRun(ui, args[0]);
        }
    }


    static void runFromExistingConfig(UI ui) {
        ui.crudeMessage("runFromExistingConfig!");
        Preferences prefs = Preferences.userRoot().node("tba.tmp");

        String magicNumber = "1"; // very magical
        List<PipelineConfig> pipelines = _parseConfigMagicKey(prefs, magicNumber);

        _resolveAndRunPipelines(ui, pipelines);
    }

    static void wipeAndRun(UI ui, String teamId) {
        ui.crudeMessage("wipeAndRun!");
        Preferences prefs = wipeAndRegenerateHardcodedConfigForTeamId(teamId);

        String magicNumber = "1"; // very magical
        List<PipelineConfig> pipelines = _parseConfigMagicKey(prefs, magicNumber);

        _resolveAndRunPipelines(ui, pipelines);
    }

    static void runReplay___forever(UI ui) {
        ui.crudeMessage("runReplay___forever!");

        Preferences prefs = Preferences.userRoot().node("tmp.tba");

        String magicNumber = "2"; // very very magical
        List<PipelineConfig> pipelines = _parseConfigMagicKey(prefs, magicNumber);

        Optional<String> replayInput = pipelines.stream()
                .map(PipelineConfig::source)
                .filter(source -> source.provider().name().equals("teambattle.replay"))
                .map(source -> source.config().get("path", "input.data"))
                .findFirst();

        if (! replayInput.map(Path::of).map(Files::exists).orElse(false)){
            ui.crudeMessage("But you said replay! You said it! Why you has no input.data!?");
            return;
        }

        if (pipelines.isEmpty()) {
            ui.crudeMessage("Expected some pipelines, even used very very magical number, but still nah!??");
            return;
        }

        while (true) { // And I think it's gonna be a long long time...

            List<ResolvedPipeline> resolvedPipelines = TBA.resolvePipelines(pipelines, ui);

            if (resolvedPipelines.isEmpty()) {
                ui.crudeMessage("So close! Was just about to resolve them pipelines, and then suddenly they all gone?! Awww!");
                return;
            }

            TBA.runPipelines(resolvedPipelines);

            try { Thread.sleep(Duration.ofSeconds(10));
                // Did I fall asleep?
            } catch (InterruptedException onlyForALittleWhile) {
                Thread.currentThread().interrupt();
                // Should I go now?
                break;
            }

            for (var resolvedPipeline : resolvedPipelines) {
                resolvedPipeline.sink().close();
            }
        }
    }


    static void _resolveAndRunPipelines(UI ui, List<PipelineConfig> pipelines) {
        if (pipelines.isEmpty()) {
            ui.crudeMessage("Expected some pipelines, for reals, but didn't find any! Like what's that about?");
            return;
        }

        List<ResolvedPipeline> resolvedPipelines = TBA.resolvePipelines(pipelines, ui);

        if (resolvedPipelines.isEmpty()) {
            ui.crudeMessage("Tried to resolve them pipelines, but couldn't do it, not gonna lie!");
            return;
        }

        TBA.runPipelines(resolvedPipelines);
    }

    static List<PipelineConfig> _parseConfigMagicKey(Preferences prefs, String magicKey) {
        return parseConfig(prefs)
            .entrySet().stream()
            .filter(entry -> entry.getKey().equals(magicKey))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(List.of());
    }

    private static void printPreferences(UI ui, Preferences prefs) {
        try {
            ui.crudeMessage("This is a rendition of the Preferences\n" + String.join("", preferencesAsString(prefs, 0).toList()));
        } catch (BackingStoreException ex) {}
    }


    static Stream<String> preferencesAsString(Preferences prefs, int indent) throws BackingStoreException {
        Stream<String> nodeStream = Stream.of(prefs.name().indent(indent));

        nodeStream = Arrays.stream(prefs.keys())
            .sorted()
            .map(s -> "%s=%s".formatted(s, prefs.get(s, "<>")).indent(indent + 2))
            .map(Stream::of)
            .reduce(nodeStream, Stream::concat);

        Stream<String> childrenStream = Stream.of();
        for (String child : prefs.childrenNames()) {
            childrenStream = Stream.concat(childrenStream, preferencesAsString(prefs.node(child), indent + 2));
        }

        return Stream.concat(nodeStream, childrenStream);
    }


    static Map<String, List<PipelineConfig>> parseConfig(Preferences prefs) {
        var sourceMap      = parseProviderConfig(providers.source(), prefs.node("sources"));
        var sinkMap        = parseProviderConfig(providers.sink(), prefs.node("sinks"));
        var transformerMap = parseProviderConfig(providers.transformer(), prefs.node("transformers"));

        Preferences pipelinePrefs = prefs.node("pipelines");

        if (! (_childrenNames(pipelinePrefs) instanceof List<String> names)) return Map.of();

        return names.stream()
            .map(name -> _parsePipelines(pipelinePrefs.node(name), sourceMap, sinkMap, transformerMap))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toMap(NamedPipelineConfig::name, NamedPipelineConfig::pipelines));
    }

    record NamedPipelineConfig(String name, List<PipelineConfig> pipelines) {}

    static Optional<NamedPipelineConfig> _parsePipelines(Preferences pipelineConfig,
            Map<String, ProviderAndConfig<SourceProvider>> sourceMap,
            Map<String, ProviderAndConfig<SinkProvider>> sinkMap,
            Map<String, ProviderAndConfig<TransformerProvider>> transformerMap
            ) {
        if (! (_childrenNames(pipelineConfig) instanceof List<String> names)) return Optional.empty();

        List<Optional<PipelineConfig>> configs = names.stream()
            .map(name -> _parsePipeline(pipelineConfig.node(name), sourceMap, sinkMap, transformerMap))
            .toList();

        if (configs.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }

        return Optional.of(new NamedPipelineConfig(pipelineConfig.name(), configs.stream().map(Optional::get).toList()));
    }

    static Optional<PipelineConfig> _parsePipeline(Preferences pipelineConfig,
            Map<String, ProviderAndConfig<SourceProvider>> sourceMap,
            Map<String, ProviderAndConfig<SinkProvider>> sinkMap,
            Map<String, ProviderAndConfig<TransformerProvider>> transformerMap
            ) {

        // Source and Sink are mandatory, transformers are optional
        // Make sure Source and Sink are present
        if (! (pipelineConfig.get("sourceId", null) instanceof String sourceId
            && sourceMap.get(sourceId) instanceof ProviderAndConfig<SourceProvider> source
            && pipelineConfig.get("sinkId", null) instanceof String sinkId
            && sinkMap.get(sinkId) instanceof ProviderAndConfig<SinkProvider> sink
            )) {
            return Optional.empty();
        }

        return _checkForAndParseTransformers(pipelineConfig, transformerMap)
            .map(transformers -> TBA.pipelineConfig(source, transformers, sink));
    }


    private static Optional<List<ProviderAndConfig<TransformerProvider>>> _checkForAndParseTransformers(
            Preferences pipelineConfig,
            Map<String, ProviderAndConfig<TransformerProvider>> transformerMap) {

        try {
            if (! pipelineConfig.nodeExists("transformers")) {
                return Optional.of(List.of());
            }
        } catch (BackingStoreException bse) {
            bse.printStackTrace();
            return Optional.empty();
        }

        Preferences node = pipelineConfig.node("transformers");

        if (! (_childrenNames(node) instanceof List<String> names)) return Optional.empty();
        List<Optional<ProviderAndConfig<TransformerProvider>>> transformers = names.stream()
            .map(name -> _parseTransformer(node.node(name), transformerMap))
            .toList();
        if (transformers.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        return Optional.of(transformers.stream().map(Optional::get).toList());
    }

    private static Optional<ProviderAndConfig<TransformerProvider>> _parseTransformer(
            Preferences node,
            Map<String, ProviderAndConfig<TransformerProvider>> transformerMap) {
        if (node.get("transformerId", null) instanceof String transformerId
            && transformerMap.get(transformerId) instanceof ProviderAndConfig<TransformerProvider> transformer) {
            return Optional.of(transformer);
        }
        return Optional.empty();
    }

    private static List<String> _childrenNames(Preferences parent) {
        try {
            return Arrays.stream(parent.childrenNames()).toList();
        } catch (BackingStoreException bse) {
            bse.printStackTrace();
            return null;
        }
    }

    static <T> Map<String, ProviderAndConfig<T>> parseProviderConfig(Map<String, T> providers, Preferences providersPrefs) {
        if (! (_childrenNames(providersPrefs) instanceof List<String> names)) return Map.of();

        return names.stream()
            .filter(id -> providers.containsKey(providersPrefs.node(id).get("provider", "")))
            .collect(Collectors.toMap(Function.identity(),
                        id -> TBA.providerAndConfig(providers.get(providersPrefs.node(id).get("provider", "")), providersPrefs.node(id).node("config"))));
    }

    static Preferences wipeAndRegenerateHardcodedConfigForTeamId(String teamId) {
        try {
            Preferences root = Preferences.userRoot().node("tmp.tba");
            root.removeNode();
            root.flush();
            root = Preferences.userRoot().node("tmp.tba");

            _generateSomeHardcodedConfig(root, teamId);

            root.flush();
            return root;
        } catch (BackingStoreException ex) {
            throw new RuntimeException(ex);
        }
    }

    static void _generateSomeHardcodedConfig(Preferences root, String teamId) {

        Preferences sourcesPrefs = root.node("sources");
        Preferences transformersPrefs = root.node("transformers");
        Preferences sinksPrefs = root.node("sinks");
        Preferences pipelinesPrefs = root.node("pipelines");

        // Sources
        Preferences source1prefs = sourcesPrefs.node("1");
        source1prefs.put("provider", "teambattle");
        Preferences tbSource1Prefs = source1prefs.node("config");
        tbSource1Prefs.put("teamId", teamId);

        Preferences source2prefs = sourcesPrefs.node("2");
        source2prefs.put("provider", "teambattle.replay");
        Preferences tbSource2Prefs = source2prefs.node("config");
        tbSource2Prefs.put("path", "input.data");



        // Transformers
        Preferences tf1prefs = transformersPrefs.node("1");
        tf1prefs.put("provider", "teambattle.text");
        Preferences _ = tf1prefs.node("config");

        Preferences tf2prefs = transformersPrefs.node("2");
        tf2prefs.put("provider", "teambattle.text");
        Preferences tbTf2prefs = tf2prefs.node("config");
        tbTf2prefs.put("lang", "en");

        Preferences tf3prefs = transformersPrefs.node("3");
        tf3prefs.put("provider", "teambattle.text");
        Preferences tbTf3prefs = tf3prefs.node("config");
        tbTf3prefs.put("lang", "sv"); // lol

        Preferences tf4prefs = transformersPrefs.node("4");
        tf4prefs.put("provider", "teambattle.http");
        Preferences tbTf4prefs = tf4prefs.node("config");
        tbTf4prefs.put("lang", "en");

        Preferences tf5prefs = transformersPrefs.node("5");
        tf5prefs.put("provider", "teambattle.http");
        Preferences tbTf5prefs = tf5prefs.node("config");
        tbTf5prefs.put("lang", "sv"); // lol


        // Sinks
        Preferences sink1Prefs = sinksPrefs.node("1");
        sink1Prefs.put("provider", "console");
        Preferences _ = sink1Prefs.node("config");

        Preferences sink2Prefs = sinksPrefs.node("2");
        sink2Prefs.put("provider", "file");
        Preferences fileSink2prefs = sink2Prefs.node("config");
        fileSink2prefs.put("file", "file.1.txt");

        Preferences sink3Prefs = sinksPrefs.node("3");
        sink3Prefs.put("provider", "file");
        Preferences fileSink3prefs = sink3Prefs.node("config");
        fileSink3prefs.put("file", "file.2.txt");

        Preferences sink4Prefs = sinksPrefs.node("4");
        sink4Prefs.put("provider", "http");
        Preferences httpSink4prefs = sink4Prefs.node("config");
        httpSink4prefs.put("bindAddress", "0.0.0.0");
        httpSink4prefs.putInt("port", 8080);


        // Pipelines
        Preferences pipeline1Prefs = pipelinesPrefs.node("1");

        Preferences p1_pipe1Prefs = pipeline1Prefs.node("1");

        p1_pipe1Prefs.put("sourceId", "1");
        p1_pipe1Prefs.put("sinkId", "1");

        Preferences p1_p1_transformers = p1_pipe1Prefs.node("transformers");
        Preferences p1_p1_tf1 = p1_p1_transformers.node("1");
        p1_p1_tf1.put("transformerId", "1");

        Preferences p1_pipe2Prefs = pipeline1Prefs.node("2");
        p1_pipe2Prefs.put("sourceId", "1");
        p1_pipe2Prefs.put("sinkId", "2");

        Preferences p1_p2_transformers = p1_pipe2Prefs.node("transformers");
        Preferences p1_p2_tf1 = p1_p2_transformers.node("1");
        p1_p2_tf1.put("transformerId", "2");


        Preferences p1_pipe3Prefs = pipeline1Prefs.node("3");
        p1_pipe3Prefs.put("sourceId", "1");
        p1_pipe3Prefs.put("sinkId", "3");

        Preferences p1_p3_transformers = p1_pipe3Prefs.node("transformers");
        Preferences p1_p3_tf1 = p1_p3_transformers.node("1");
        p1_p3_tf1.put("transformerId", "3");

        Preferences p1_pipe4Prefs = pipeline1Prefs.node("4");
        p1_pipe4Prefs.put("sourceId", "1");
        p1_pipe4Prefs.put("sinkId", "4");

        Preferences p1_p4_transformers = p1_pipe4Prefs.node("transformers");
        Preferences p1_p4_tf1 = p1_p4_transformers.node("1");
        p1_p4_tf1.put("transformerId", "4");



        Preferences pipeline2Prefs = pipelinesPrefs.node("2");

        Preferences p2_pipe1Prefs = pipeline2Prefs.node("1");

        p2_pipe1Prefs.put("sourceId", "2");
        p2_pipe1Prefs.put("sinkId", "1");

        Preferences p2_p1_transformers = p2_pipe1Prefs.node("transformers");
        Preferences p2_p1_tf1 = p2_p1_transformers.node("1");
        p2_p1_tf1.put("transformerId", "1");


        Preferences p2_pipe2Prefs = pipeline2Prefs.node("2");

        p2_pipe2Prefs.put("sourceId", "2");
        p2_pipe2Prefs.put("sinkId", "4");

        Preferences p2_p2_transformers = p2_pipe2Prefs.node("transformers");
        Preferences p2_p2_tf1 = p2_p2_transformers.node("1");
        p2_p2_tf1.put("transformerId", "4");

    }


}
