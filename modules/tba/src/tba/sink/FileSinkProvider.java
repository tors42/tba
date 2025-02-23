package tba.sink;

import module java.base;
import module tba.api;

public class FileSinkProvider implements SinkProvider {

    @Override
    public Class<? extends Event> eventType() {
        return TextEvent.class;
    }

    @Override
    public String name() {
        return "file";
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
                return Optional.of(new FileSinkConfig(Path.of("tba.file.txt")));
            }

            @Override
            public Optional<Config> noninteractiveConfig(Preferences prefs) {
                return Optional.of(new FileSinkConfig(Path.of(prefs.get("file", "tba.sink.file.txt"))));
            }

            @Override
            public Optional<Config> interactiveConfig() {

                // do some
                // String someValue = ui.readLine("some conf: ");

                return Optional.of(new FileSinkConfig(Path.of("tba.file.txt")));
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

                return Optional.of(new FileSinkConfig(Path.of(prefs.get("file", "tba.sink.file.txt"))));
            }

        };
    }

    record FileSinkConfig(Path path) implements Config {
        @Override
        public void store(Preferences prefs) {
            prefs.put("file", path.toString());
        }
    }

    @Override
    public Sink of(Config config) {
        if (! (config instanceof FileSinkConfig(Path path))) {
            System.out.println("Not FileSinkConfig!");
            return null;
        }

        System.out.println("File Sink: " + path.toAbsolutePath());

        return new Sink() {
            @Override
            public void accept(Event event) {
                if (event instanceof TextEvent(String message)) {
                    try {
                        Files.writeString(path, "%s%n".formatted(message), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                } else {
                    System.out.println("unexpected event, " + event.getClass().getName() + "\n" + event);
                }
            }
            @Override
            public void close() {}
        };
    }
}
