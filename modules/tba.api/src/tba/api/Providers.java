package tba.api;

import java.util.Map;

public record Providers(
        Map<String, SourceProvider> source,
        Map<String, SinkProvider> sink,
        Map<String, TransformerProvider> transformer
        ) {
}
