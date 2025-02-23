package tba.api;

public interface SinkProvider {
    String name();
    Class<? extends Event> eventType();
    ConfigProvider configProvider(UI ui);
    Sink of(Config config);
}
