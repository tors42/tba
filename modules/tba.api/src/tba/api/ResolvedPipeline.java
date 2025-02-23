package tba.api;

import java.util.List;

public record ResolvedPipeline(Source source, List<Transformer> transformers, Sink sink) {}
