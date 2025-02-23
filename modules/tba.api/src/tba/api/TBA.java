package tba.api;

import module java.base;
import java.util.prefs.Preferences;

public interface TBA {

    static Providers loadProviders() {
        Map<String, SourceProvider> sourceProviders =
            loadNamedProviders(SourceProvider.class, SourceProvider::name);
        Map<String, SinkProvider> sinkProviders =
            loadNamedProviders(SinkProvider.class, SinkProvider::name);
        Map<String, TransformerProvider> transformerProviders =
            loadNamedProviders(TransformerProvider.class, TransformerProvider::name);

        return new Providers(sourceProviders, sinkProviders, transformerProviders);
    }

    static <T> Map<String, T> loadNamedProviders(Class<T> cls, Function<T, String> name) {
        return ServiceLoader.load(cls).stream()
            .map(ServiceLoader.Provider<T>::get)
            .collect(Collectors.toMap(name::apply, Function.identity(),
                        (sp1, sp2) -> { System.out.println("Duplicate providers. Picking %s Dropping %s".formatted(
                                    sp1.getClass().getName(), sp2.getClass().getName()));
                        return sp1; }));
    }


    static <T> ProviderAndConfig<T> providerAndConfig(T provider, Preferences config) {
        return new ProviderAndConfig<>(provider, config);
    }


    static PipelineConfig pipelineConfig(
            ProviderAndConfig<SourceProvider> source,
            ProviderAndConfig<TransformerProvider> transformer,
            ProviderAndConfig<SinkProvider> sink) {
        return pipelineConfig(source, List.of(transformer), sink);
    }


    static PipelineConfig pipelineConfig(
            ProviderAndConfig<SourceProvider> source,
            List<ProviderAndConfig<TransformerProvider>> transformers,
            ProviderAndConfig<SinkProvider> sink) {
        return new PipelineConfig(source, transformers, sink);
    }

    static List<ResolvedPipeline> resolvePipelines(List<PipelineConfig> unresolvedPipelines) {
        return resolvePipelines(unresolvedPipelines, new UI(){
            @Override public String crudeQuery(String prompt) { return ""; }
            @Override public void crudeMessage(String message) { System.out.println(message); }
        });
    }

    static List<ResolvedPipeline> resolvePipelines(List<PipelineConfig> unresolvedPipelines, UI ui) {
        List<ResolvedPipeline> resolvedPipelines = new ArrayList<>();

        // The same Source might be used in multiple pipelines,
        // but we only want to initialize a Source once,
        // so when we resolve the Source we put it in a map for reuse,
        // (and same for the other providers),
        // so here are the maps for the providers results:

        Map<ProviderAndConfig<SourceProvider>, Source> resolvedSources = new HashMap<>();
        Map<ProviderAndConfig<SinkProvider>, Sink> resolvedSinks = new HashMap<>();
        Map<ProviderAndConfig<TransformerProvider>, Transformer> resolvedTransformers = new HashMap<>();


        // Each pipeline has Source, List<Transformer> and Sink,
        // so let's start resolving!
        for (PipelineConfig pipelineConfig : unresolvedPipelines) {

            Source source = resolvedSources
                .computeIfAbsent(pipelineConfig.source(), pac -> {
                    SourceProvider provider = pac.provider();
                    Preferences configPrefs = pac.config();
                    Optional<Config> optConfig = provider
                        .configProvider(ui)
                        .noninteractiveConfig(configPrefs);
                    optConfig.ifPresent(config -> config.store(configPrefs));
                    return optConfig.map(provider::of).orElse(null);
                });

            if (source == null) {
                ui.crudeMessage("No Source - " + pipelineConfig.source());
                continue;
            }

            List<Optional<Transformer>> optTransformers = pipelineConfig.transformers().stream()
                .map(transformerConfig -> {
                    Transformer transformer = resolvedTransformers
                        .computeIfAbsent(transformerConfig, pac -> {
                            TransformerProvider provider = pac.provider();
                            Preferences configPrefs = pac.config();
                            Optional<Config> optConfig = provider
                                .configProvider(ui)
                                .noninteractiveConfig(configPrefs);
                            optConfig.ifPresent(config -> config.store(configPrefs));

                            return optConfig.map(provider::of).orElse(null);
                        });
                    if (transformer == null) {
                        ui.crudeMessage("No Transformer - " + transformerConfig);
                    }
                    return Optional.ofNullable(transformer);
                })
            .toList();

            if (optTransformers.stream().anyMatch(Optional::isEmpty)) {
                continue;
            }

            List<Transformer> transformers = optTransformers.stream().map(Optional::get).toList();

            Sink sink = resolvedSinks
                .computeIfAbsent(pipelineConfig.sink(), pac -> {
                    SinkProvider provider = pac.provider();
                    Preferences configPrefs = pac.config();
                    Optional<Config> optConfig = provider
                        .configProvider(ui)
                        .noninteractiveConfig(configPrefs);
                    optConfig.ifPresent(config -> config.store(configPrefs));
                    return optConfig.map(provider::of).orElse(null);
                });

            if (sink == null) {
                ui.crudeMessage("No Sink - " + pipelineConfig.sink());
                continue;
            }

            resolvedPipelines.add(new ResolvedPipeline(source, transformers, sink));
        }
        return resolvedPipelines;
    }


    static void runPipelines(List<ResolvedPipeline> pipelines) {

        try (var outerScope = new StructuredTaskScope<>()) {

            for (ResolvedPipeline pipeline : pipelines) {

                outerScope.fork(() -> {
                    try (var innerScope = new StructuredTaskScope<>()) {
                        Stream<? extends Event> events = pipeline.source().events();

                        for (Transformer transformer : pipeline.transformers()) {
                            events = events.map(transformer::transform);
                        }

                        events.forEach(event -> innerScope.fork(() -> { pipeline.sink().accept(event); return null; }));
                        innerScope.join();
                    }
                    return null;
                });
            }

            pipelines.stream()
                .map(ResolvedPipeline::source)
                .distinct()
                .forEach(source -> outerScope.fork(() -> { source.run(); return null; } ));

            outerScope.join();

        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    static <T> String debug(Providers providers) {
        return String.join("\n",
                debug("source providers", providers.source()),
                debug("sink providers", providers.sink()),
                debug("transformer providers", providers.transformer()));
    }

    static <T> String debug(String heading, Map<String, T> map) {
        return String.join("\n", heading,
                map.entrySet().stream()
                .map(entry -> "\t%-20s : %s".formatted(entry.getKey(), entry.getValue().getClass().getName()))
                .sorted()
                .collect(Collectors.joining("\n")));
    }

}
