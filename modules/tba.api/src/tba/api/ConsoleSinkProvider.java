package tba.api;

import module java.base;
import module java.prefs;

public class ConsoleSinkProvider implements SinkProvider {

    @Override
    public Class<? extends Event> eventType() {
        return TextEvent.class;
    }

    @Override
    public String name() {
        return "console";
    }

    @Override
    public ConfigProvider configProvider(UI ui) {
        return new ConfigProvider() {
            @Override
            public String name() {
                return "console";
            }

            @Override
            public Optional<Config> noninteractiveConfig() {
                return Optional.of(new ConsoleSinkConfig());
            }

            @Override
            public Optional<Config> noninteractiveConfig(Preferences prefs) {
                return Optional.of(new ConsoleSinkConfig());
            }

            @Override
            public Optional<Config> interactiveConfig() {

                // do some
                // String someValue = ui.readLine("some conf: ");


                return Optional.of(new ConsoleSinkConfig());
            }

            @Override
            public Optional<Config> interactiveConfig(Preferences prefs) {
                // do some
                // String defaultValue = prefs.get("someconf", null);
                // if (defaultValue != null) {
                //  String someValue = ui.readLine("some conf [%s]: ", defaultValue);
                // } else {
                //  String someValue = ui.readLine("some conf: ");
                // }

                return Optional.of(new ConsoleSinkConfig());
            }

        };
    }

    record ConsoleSinkConfig() implements Config {
        @Override
        public void store(Preferences prefs) {

        }
    }

    @Override
    public Sink of(Config config) {
        return new Sink() {
            @Override
            public void accept(Event event) {
                if (event instanceof TextEvent(String message)) {
                    System.out.println(message);
                } else {
                    System.out.println("unexpected event, " + event.getClass().getName() + "\n" + event);
                }
            }

            @Override
            public void close() {}
        };
    }
}
