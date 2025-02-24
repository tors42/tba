package app.sink;

import module tba.api;

import module java.base;
import module java.desktop;

import app.AppConfig.CSSChoice;

import tba.api.Event;

import javax.swing.text.Element;

public class HttpEventGUI implements SinkProvider {

    @Override public String name() { return "app.gui"; }
    @Override public Class<? extends Event> eventType() { return HttpEvent.class; }

    @Override
    public Sink of(Config config) {
        if (! (config instanceof GUIConfig guiConfig)) {
            System.out.println("Unexpected config " + config);
            return null;
        }

        System.out.println("HttpEventGUI: " + guiConfig);

        return new SinkFrame(guiConfig);
    }

    class SinkFrame extends JFrame implements Sink {
        private final JEditorPane editor;

        @Override
        public void accept(Event event) {
            if (! (event instanceof HttpEvent(var html))) {
                System.out.println("Unexpected event " + event);
                return;
            }

            if (! (editor.getDocument() instanceof HTMLDocument htmlDocument
                && htmlDocument.getElement("messages") instanceof Element messages)) {
                System.out.println("No document!");
                return;
            }

            synchronized(editor) {
                try { htmlDocument.insertAfterStart(messages, html);
                } catch (Exception ex) { ex.printStackTrace(); }
            }
        }

        @Override
        public void close() {
            dispose();
        }

        public SinkFrame(GUIConfig config) {
            super(config.title());
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            setMinimumSize(new Dimension(500, 500));
            editor = new JEditorPane();
            HTMLEditorKit kit = new HTMLEditorKit();

            StyleSheet styleSheet = new StyleSheet();

            try (var reader = switch(config.css()) {
                case CSSChoice.Custom(Path path) -> Files.newBufferedReader(path);
                case CSSChoice.BuiltInCSS builtin ->
                    new InputStreamReader(config.getClass().getModule().getResourceAsStream(builtin.resource));
            }) {
                styleSheet.loadRules(reader, null);
                kit.setStyleSheet(styleSheet);
            } catch (IOException ex) { ex.printStackTrace(); }
            editor.setEditable(false);
            editor.setEditorKit(kit);
            editor.setCaret(new DefaultCaret() {
                @Override public void paint(Graphics g) {}
                @Override public boolean isVisible() { return false; }
                @Override public boolean isSelectionVisible() { return false; }
                });
            var defaultDocument = kit.createDefaultDocument();
            editor.setDocument(defaultDocument);
            editor.setText(htmlTemplate.formatted(""));
            JScrollPane scrollPane = new JScrollPane(editor);
            getContentPane().add(scrollPane);
            try { setLocation(MouseInfo.getPointerInfo().getLocation());
            } catch (Exception e) { setLocationRelativeTo(null); }
            setVisible(true);
        }

        static String htmlTemplate = """
            <html>
              <body>
                <div class="messages" id="messages">%s</div>
              </body>
            </html>
            """;
    }

    public record GUIConfig(CSSChoice css, String title) implements Config {

        public static final String defaultTitle = "Team Battle Events";

        @Override
        public void store(Preferences prefs) {
            prefs.put("title", title);
            switch (css()) {
                case CSSChoice.Custom(Path css)
                    -> prefs.put("stylesheetPath", css.toString());
                case CSSChoice.BuiltInCSS builtin
                    -> prefs.put("builtin", builtin.name());
            };
        }
    }

    @Override
    public ConfigProvider configProvider(UI ui) {
        return new ConfigProvider() {
            @Override public String name() { return "app.gui"; }
            @Override public Optional<Config> interactiveConfig() { return noninteractiveConfig(); }
            @Override public Optional<Config> interactiveConfig(Preferences prefs) { return noninteractiveConfig(prefs); }
            @Override
            public Optional<Config> noninteractiveConfig() {
                return Optional.of(new GUIConfig(CSSChoice.BuiltInCSS.colors, GUIConfig.defaultTitle));
            }
            @Override
            public Optional<Config> noninteractiveConfig(Preferences prefs) {
                CSSChoice css = switch(prefs.get("stylesheetPath", null)) {
                    case null -> switch (prefs.get("builtin", CSSChoice.BuiltInCSS.colors.name())) {
                        case "simple" -> CSSChoice.BuiltInCSS.simple;
                        default -> CSSChoice.BuiltInCSS.colors;
                    };
                    case String path -> new CSSChoice.Custom(Path.of(path));
                };
                String title = prefs.get("title", GUIConfig.defaultTitle);
                return Optional.of(new GUIConfig(css, title));
            }
        };
    }

}
