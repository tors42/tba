package teambattle.http;

import module java.base;

import module tba.api;
import module jdk.httpserver;

public class TeamBattleHttpSinkProvider implements SinkProvider {

    @Override public Class<? extends Event> eventType() { return HttpEvent.class; }
    @Override public String name() { return "http"; }

    @Override
    public ConfigProvider configProvider(UI ui) {
        return new ConfigProvider() {
            @Override public String name() { return "http"; }
            @Override public Optional<Config> interactiveConfig() { return Optional.empty(); }
            @Override public Optional<Config> interactiveConfig(Preferences prefs) { return Optional.empty(); }

            @Override
            public Optional<Config> noninteractiveConfig() {
                return Optional.of(HttpSinkConfig.of(HttpSinkConfig.address(InetAddress.ofLiteral("127.0.0.1"), 8080), "/teambattle/http/style.css"));
            }

            @Override
            public Optional<Config> noninteractiveConfig(Preferences prefs) {
                String bindAddress = prefs.get("bindAddress", null);
                int port = prefs.getInt("port", -1);
                String stylesheetPath = prefs.get("stylesheetPath", null);

                HttpSinkConfig.Address address = bindAddress != null && port != -1
                    ? HttpSinkConfig.address(InetAddress.ofLiteral(bindAddress), port)
                    : HttpSinkConfig.address(InetAddress.ofLiteral("127.0.0.1"), 8080);

                return stylesheetPath == null
                    ? Optional.of(HttpSinkConfig.of(address, "/teambattle/http/style.css"))
                    : Optional.of(HttpSinkConfig.of(address, Path.of(stylesheetPath)));
            }
        };
    }


    public sealed interface HttpSinkConfig extends Config {

        static Address address(InetAddress address, int port) {
            return new Address(address, port);
        }

        static HttpSinkConfig of(Address address, String cssResource) {
            return new InternalCSS(cssResource, address);
        }

        static HttpSinkConfig of(Address address, Path css) {
            return new ExternalCSS(css, address);
        }

        record Address(InetAddress bindAddress, int port) {}

        record ExternalCSS(Path css, Address address) implements HttpSinkConfig {}
        record InternalCSS(String cssResource, Address address) implements HttpSinkConfig {}

        Address address();

        @Override
        default public void store(Preferences prefs) {
            prefs.put("bindAddress", address().bindAddress().getHostAddress());
            prefs.putInt("port", address().port);

            switch (this) {
                case ExternalCSS(Path css, _) -> prefs.put("stylesheetPath", css.toString());
                case InternalCSS(_, _) -> {}
            };
        }

    }

    record NameAndInt(String name, int value) {}

    static Map<String, Integer> parseNameAndInts(String query) {
        if (query == null) {
            return Map.of();
        }
        return Arrays.stream(query.split("&"))
            .<NameAndInt>mapMulti((s, mapper) -> {
                String[] arr = s.split("=");
                try {
                    mapper.accept(new NameAndInt(arr[0], Integer.parseInt(arr[1])));
                } catch (Exception e) {}
            })
        .collect(Collectors.toMap(NameAndInt::name, NameAndInt::value));
    }

    record Indexed<T>(int index, T value) {}

    static <T> List<Indexed<T>> indexed(List<T> list) {
        return IntStream.range(0, list.size()).mapToObj(i -> new Indexed<>(i, list.get(i))).toList();
    }

    @Override
    public Sink of(Config config) {
        if (! (config instanceof HttpSinkConfig sinkConfig)) {
            System.out.println("Unexcepted config " + config.getClass());
            return null;
        }

        System.out.println("HttpServer: " + sinkConfig);

        List<HttpEvent> events = Collections.synchronizedList(new ArrayList<>());

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(sinkConfig.address().bindAddress, sinkConfig.address().port), 0);
        } catch (IOException e) {
            e.printStackTrace();
            throw new UncheckedIOException(e);
        }

        byte[] styleSheet;
        try {
            styleSheet = switch(sinkConfig) {
                case HttpSinkConfig.InternalCSS(String cssResource, _) -> getClass().getModule().getResourceAsStream(cssResource).readAllBytes();
                case HttpSinkConfig.ExternalCSS(Path css, _)           -> Files.readAllBytes(css);
            };
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        byte[] script;
        try {
            script = getClass().getModule().getResourceAsStream("/teambattle/http/script.js").readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", exchange -> {
            String requestPath = exchange.getRequestURI().getPath();

            switch (requestPath) {
                case "/", "/index.html" -> {
                  String responseBody = """
                        <!DOCTYPE html>
                        <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                            </head>
                            <body>
                                <h1>Team Battle Announcer</h1>
                                <h2>Configuration</h2>
                                It is possible to limit the number of messages shown, with the query parameter <b>max</b>.<br>
                                It is possible to change the direction of messages shown, with the query parameter <b>reverse</b>.<br>
                                <br>
                                <a href="/app">/app</a><br>
                                <br>
                                <a href="/app?max=10">/app?max=10</a><br>
                                <br>
                                <a href="/app?max=5">/app?max=5</a><br>
                                <br>
                                <a href="/app?max=5&reverse=1">/app?max=5&reverse=1</a><br>
                            </body>
                        </html>
                        """;
                    response(responseBody, 200, exchange, "text/html");
                }

                case "/app" -> {
                    Map<String,Integer> inparams = parseNameAndInts(exchange.getRequestURI().getQuery());

                    Integer max = inparams.getOrDefault("max", null);
                    Integer reverse = inparams.getOrDefault("reverse", null);

                    String scriptBlock = "";
                    if (max != null || reverse != null) { // || lastId != null) {
                        scriptBlock = "<script>\n";
                        if (max != null)
                            scriptBlock += "  const max = %d;\n".formatted(max);
                        if (reverse != null)
                            scriptBlock += "  const reverse = %d;\n".formatted(reverse);
                        scriptBlock += "</script>";
                    }

                    String responseBody = """
                        <!DOCTYPE html>
                        <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                                <link rel="stylesheet" href="/style.css"/>
                                <script src="/script.js" async="async"></script>
                                %s
                            </head>
                            <body>
                                <div class="messages" id="messages"></div>
                            </body>
                        </html>
                        """.formatted(scriptBlock);

                    response(responseBody, 200, exchange, "text/html");
                }

                case "/messages" -> {

                    List<HttpEvent> eventsCopy = List.of();
                    synchronized(events) {
                        eventsCopy = List.copyOf(events);
                    }

                    Map<String,Integer> inparams = parseNameAndInts(exchange.getRequestURI().getQuery());

                    int lastSeenId = inparams.getOrDefault("lastSeenId", 0);
                    if (lastSeenId < 0 || lastSeenId > eventsCopy.size()) lastSeenId = 0;

                    List<HttpEvent> eventsToShow = eventsCopy.stream()
                        .skip(lastSeenId)
                        .toList();

                    String messages = eventsToShow.stream()
                        .map(HttpEvent::html)
                        .collect(Collectors.joining("\n"));

                    response(messages.getBytes(), 200, exchange, List.of(
                                HeaderAndValue.contentType("text/html"),
                                HeaderAndValue.of("numEvents", String.valueOf(eventsCopy.size()))));
                }

                case "/style.css" -> response(styleSheet, 200, exchange, "text/css");
                case "/script.js" -> response(script, 200, exchange, "text/javascript");

                default -> responseOnlyStatus(404, exchange);

            };
        });

        server.start();

        return new Sink() {
            @Override
            public void accept(Event event) {
                if (! (event instanceof HttpEvent httpEvent)) {
                    System.out.println("Unexpected event " + event.getClass());
                    return;
                }
                synchronized(events) {
                    events.add(httpEvent);
                }
            }

            @Override
            public void close() {
                server.stop(1);
            }
        };
    }

    static void response(String message, int status, HttpExchange exchange, String mimeType) throws IOException {
        response(message.getBytes(), status, exchange, List.of(HeaderAndValue.contentType(mimeType)));
    }

    static void response(byte[] bytes, int status, HttpExchange exchange, String mimeType) throws IOException {
        response(bytes, status, exchange, List.of(HeaderAndValue.contentType(mimeType)));
    }

    record HeaderAndValue(String header, List<String> value) {
        static HeaderAndValue contentType(String value) { return new HeaderAndValue("content-type", List.of(value)); }
        static HeaderAndValue of(String header, String value) { return new HeaderAndValue(header, List.of(value)); }
    }

    static void response(byte[] bytes, int status, HttpExchange exchange, List<HeaderAndValue> headers) throws IOException {
        exchange.getResponseHeaders().putAll(headers.stream().collect(Collectors.toMap(HeaderAndValue::header, HeaderAndValue::value)));
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }

    static void responseOnlyStatus(int status, HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

}
