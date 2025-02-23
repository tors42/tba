package tba.api;

public interface TransformerProvider {
    String name();
    Class<? extends Event> fromEventType();
    Class<? extends Event> toEventType();
    ConfigProvider configProvider(UI ui);
    Transformer of(Config config);
}
