package tba.api;

import java.util.List;

public record PipelineConfig(
        ProviderAndConfig<SourceProvider> source,
        List<ProviderAndConfig<TransformerProvider>> transformers,
        ProviderAndConfig<SinkProvider> sink) {}
