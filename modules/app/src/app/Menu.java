package app;

import module java.base;
import module java.desktop;

import java.util.List;

import javax.swing.filechooser.FileFilter;

import app.AppConfig.CSSChoice;
import app.Util.LabelAndField;

public class Menu {

    public static JMenuBar build(JFrame frame, AppConfig config) {
        JMenuBar menubar = new JMenuBar();

        JMenu settingsMenu = new JMenu("Settings");
        JMenuItem customCSS = createCustomCSSItem(frame, config);
        JMenuItem httpServer = createHttpServerItem(frame, config);

        settingsMenu.add(customCSS);
        settingsMenu.add(httpServer);
        menubar.add(settingsMenu);

        return menubar;
    }


    static String readBuiltInCSS(String resource) {
        try (var is = Menu.class.getModule().getResourceAsStream(resource);
             var os = new ByteArrayOutputStream()) {
            is.transferTo(os);
            return os.toString();
        } catch (IOException ex) { System.out.println("Failed to preview CSS - " + ex.getMessage()); }
        return null;
    }

    static String readCSSFromPath(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) { System.out.println("Failed to preview CSS - " + ex.getMessage()); }
        return null;
    }

    static JMenuItem createCustomCSSItem(JFrame frame, AppConfig config) {

        JMenuItem menuItem = new JMenuItem("Custom CSS");
        JButton buttonOk = Util.createButtonForOptionPane("Ok");
        JButton buttonCancel = Util.createButtonForOptionPane("Cancel");

        ButtonGroup buttonGroup = new ButtonGroup();
        JRadioButton rbBuiltinColors = new JRadioButton("Colors (Built-In)");
        JRadioButton rbBuiltinSimple = new JRadioButton("Simple (Built-In)");
        JRadioButton rbCustomCSS = new JRadioButton("File: ");
        buttonGroup.add(rbBuiltinColors);
        buttonGroup.add(rbBuiltinSimple);
        buttonGroup.add(rbCustomCSS);

        JTextArea cssTextArea = new JTextArea(22, 40);
        cssTextArea.setEditable(false);
        cssTextArea.setCaret(new DefaultCaret() {
            @Override public void paint(Graphics g) {}
            @Override public boolean isVisible() { return false; }
            @Override public boolean isSelectionVisible() { return false; }
            });

        JScrollPane cssScrollPane = new JScrollPane(cssTextArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        String builtinColorsCssString = readBuiltInCSS(CSSChoice.BuiltInCSS.colors.resource);
        String builtinSimpleCssString = readBuiltInCSS(CSSChoice.BuiltInCSS.simple.resource);

        AtomicReference<Path> selectedPath = new AtomicReference<>();

        Runnable pathUpdated = () -> {
            Path path = selectedPath.get();
            if (path != null && Files.exists(path)) {
                // only enable ok if file exists
                buttonOk.setEnabled(true);
                if (readCSSFromPath(path) instanceof String css) {
                    cssTextArea.setText(css);
                    cssTextArea.setCaretPosition(0);
                }
            }
        };

        rbCustomCSS.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                pathUpdated.run();
            }
        });

        rbBuiltinColors.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                // always enable ok if built-in is selected
                buttonOk.setEnabled(true);
                cssTextArea.setText(builtinColorsCssString);
                cssTextArea.setCaretPosition(0);
            }
        });

        rbBuiltinSimple.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                // always enable ok if built-in is selected
                buttonOk.setEnabled(true);
                cssTextArea.setText(builtinSimpleCssString);
                cssTextArea.setCaretPosition(0);
            }
        });


        JButton buttonChooseFile = new JButton("Choose File");

        JFileChooser cssFileChooser = new JFileChooser();

        cssFileChooser.setFileFilter(new FileFilter() {
           @Override public boolean accept(File f) { return f.getName().toLowerCase().endsWith(".css"); }
           @Override public String getDescription() { return "CSS / StyleSheet (.css) files"; }
           });
        cssFileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        buttonChooseFile.addActionListener(_ -> {

            config.cssCustomPath().ifPresent(path -> cssFileChooser.setSelectedFile(path.toFile()));

            int option = cssFileChooser.showOpenDialog(frame);

            if (option == JFileChooser.APPROVE_OPTION) {
                Path chosenFile = cssFileChooser.getSelectedFile().toPath();
                selectedPath.set(chosenFile);
                rbCustomCSS.setEnabled(true);
                rbCustomCSS.setText(chosenFile.getFileName().toString());
                if (! rbCustomCSS.isSelected()) {
                    rbCustomCSS.doClick();
                } else {
                    pathUpdated.run();
                }
            }
        });


        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel fileAndButtonPanel = new JPanel();
        fileAndButtonPanel.setLayout(new BoxLayout(fileAndButtonPanel, BoxLayout.X_AXIS));
        List.of(rbCustomCSS, buttonChooseFile).forEach(component -> {
            panel.add(component);
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
        });
        fileAndButtonPanel.add(rbCustomCSS);
        fileAndButtonPanel.add(buttonChooseFile);

        List.of(rbBuiltinColors, rbBuiltinSimple,
                //rbCustomCSS, buttonChooseFile,
                fileAndButtonPanel,
                cssScrollPane)
            .forEach(component -> {
                panel.add(component);
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
            });



        menuItem.addActionListener(_ -> {

            Optional<Path> customPath = config.cssCustomPath();
            if (customPath.isPresent()) {
                rbCustomCSS.setText(customPath.get().getFileName().toString());
            } else {
                rbCustomCSS.setText("<No file selected>");
                rbCustomCSS.setEnabled(false);
            }

            config.cssCustomPath().ifPresent(selectedPath::set);

            switch (config.cssChoice()) {
                case CSSChoice.BuiltInCSS.colors -> {
                    rbBuiltinColors.setSelected(true);
                    cssTextArea.setText(builtinColorsCssString);
                    cssTextArea.setCaretPosition(0);
                }
                case CSSChoice.BuiltInCSS.simple -> {
                    rbBuiltinSimple.setSelected(true);
                    cssTextArea.setText(builtinSimpleCssString);
                    cssTextArea.setCaretPosition(0);
                }
                case CSSChoice.Custom(Path path) -> {
                    if (Files.exists(path)) {
                        rbCustomCSS.setText(path.getFileName().toString());
                        rbCustomCSS.setSelected(true);
                        rbCustomCSS.setEnabled(true);
                        cssTextArea.setText(readCSSFromPath(path));
                        cssTextArea.setCaretPosition(0);
                    } else {
                        rbCustomCSS.setSelected(false);
                        rbCustomCSS.setEnabled(false);
                    }
                }
            };

            buttonOk.setEnabled(rbBuiltinColors.isSelected() || rbBuiltinSimple.isSelected() || rbCustomCSS.isSelected());

            int choice = JOptionPane.showOptionDialog(frame, panel, "Configure CSS",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[]{buttonOk, buttonCancel}, buttonOk);

            if (choice == JOptionPane.OK_OPTION) {
                if (rbBuiltinColors.isSelected()) {
                    config.storeCSSChoice(CSSChoice.BuiltInCSS.colors);
                } else if (rbBuiltinSimple.isSelected()) {
                    config.storeCSSChoice(CSSChoice.BuiltInCSS.simple);
                } else if (rbCustomCSS.isSelected()) {
                    config.storeCSSChoice(new CSSChoice.Custom(selectedPath.get()));
                }
            }
        });

        return menuItem;
    }

    static JMenuItem createHttpServerItem(JFrame frame, AppConfig config) {
        JMenuItem menuItem = new JMenuItem("HTTP Server");

        // todo, add document listeners (?) on address and port,
        // for validation to enable/disable ok button.
        JButton buttonOk = Util.createButtonForOptionPane("Ok");
        JButton buttonCancel = Util.createButtonForOptionPane("Cancel");

        var checkbox = LabelAndField.of("Enable HTTP Server", new JCheckBox());
        var address = LabelAndField.of("Bind Address", new JTextField(16));
        var port = LabelAndField.of("Port", new JTextField(8));

        checkbox.field().addItemListener(event -> {
            boolean selected = event.getStateChange() == ItemEvent.SELECTED;
            address.field().setEnabled(selected);
            port.field().setEnabled(selected);
        });


        JPanel panel = Util.pairedComponents(List.of(checkbox, address, port));

        menuItem.addActionListener(_ -> {

            InetSocketAddress isa = config.bindAddress();
            address.field().setText(isa.getHostString());
            port.field().setText(String.valueOf(isa.getPort()));

            checkbox.field().setSelected(config.httpEnabled());
            address.field().setEnabled(config.httpEnabled());
            port.field().setEnabled(config.httpEnabled());

            int choice = JOptionPane.showOptionDialog(frame, panel, "Configure HTTP Server",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[]{buttonOk, buttonCancel}, buttonOk);

            if (choice == JOptionPane.OK_OPTION) {
                config.storeHttpEnabled(checkbox.field().isSelected());
                if (checkbox.field().isSelected()) {
                    config.storeBindAddress(address.field().getText(), Integer.parseInt(port.field().getText()));
                }
            }
        });

        return menuItem;
    }


}
