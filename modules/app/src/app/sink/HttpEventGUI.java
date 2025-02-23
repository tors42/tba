package app.sink;

import module tba.api;

import module java.base;
import module java.desktop;

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
                case CSS.External(Path css) -> Files.newBufferedReader(css);
                case CSS.Internal(String cssResource)
                    -> new InputStreamReader(config.getClass().getModule().getResourceAsStream(cssResource));
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

    sealed interface CSS {
        record External(Path css) implements CSS {}
        record Internal(String cssResource) implements CSS {}
    }

    public record GUIConfig(CSS css, String title) implements Config {

        public static final String defaultTitle = "Team Battle Events";
        public static final String defaultCSSResource = "/stylesheet.css";

        @Override
        public void store(Preferences prefs) {
            prefs.put("title", title);
            switch (css()) {
                case CSS.External(Path css)
                    -> prefs.put("stylesheetPath",css.toString());
                case CSS.Internal _ -> {}
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
                return Optional.of(new GUIConfig(new CSS.Internal(GUIConfig.defaultCSSResource), GUIConfig.defaultTitle));
            }
            @Override
            public Optional<Config> noninteractiveConfig(Preferences prefs) {
                CSS css = switch(prefs.get("stylesheetPath", null)) {
                    case null        -> new CSS.Internal(GUIConfig.defaultCSSResource);
                    case String path -> new CSS.External(Path.of(path));
                };
                String title = prefs.get("title", GUIConfig.defaultTitle);
                return Optional.of(new GUIConfig(css, title));
            }
        };
    }

}
