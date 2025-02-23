package tba.api;

public interface SourceProvider {
    String name();
    Class<? extends Event> eventType();
    ConfigProvider configProvider(UI ui);
    Source of(Config config);
}
