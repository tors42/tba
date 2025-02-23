package app;

import module java.desktop;

import java.util.List;

import javax.swing.GroupLayout;

import app.AppConfig.SelectedTeam;
import app.AppConfig.SelectedTeam.*;
import chariot.Client;


public interface Util {

    record LabelAndField<T extends JComponent>(JLabel label, T field) {
        static <T extends JComponent> LabelAndField<T> of(String text, T field) {
            return new LabelAndField<>(new JLabel(text), field);
        }
    }

    static <T extends JComponent> JPanel pairedComponents(List<LabelAndField<?>> pairs) {
        JPanel panel = new JPanel();
        GroupLayout layout = new GroupLayout(panel);
        panel.setLayout(layout);

        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);

        var labelsGroup = layout.createParallelGroup();
        var fieldsGroup = layout.createParallelGroup();

        for (var pair : pairs) {
            labelsGroup.addComponent(pair.label());
            fieldsGroup.addComponent(pair.field());
        }

        layout.setHorizontalGroup(layout.createSequentialGroup()
                .addGroup(labelsGroup)
                .addGroup(fieldsGroup));

        var sequentialGroup = layout.createSequentialGroup();
        for (var pair : pairs) {
            sequentialGroup.addGroup(layout.createParallelGroup()
                    .addComponent(pair.label(), GroupLayout.Alignment.CENTER)
                    .addComponent(pair.field()));
        }

        layout.setVerticalGroup(sequentialGroup);

        return panel;
    }

    static JOptionPane getOptionPane(JComponent component) {
        return switch(component) {
            case JOptionPane pane -> pane;
            case JComponent comp when comp.getParent() instanceof JComponent parent -> getOptionPane(parent);
            default -> null;
        };
    }

    static JButton createButtonForOptionPane(String name) {
        JButton button = new JButton(name);
        button.addActionListener(event -> {
            if (event.getSource() instanceof JComponent comp && getOptionPane(comp) instanceof JOptionPane pane)
                pane.setValue(button);
        });
        return button;
    }


    static AppConfig.SelectedTeam pickTeam(SelectedTeam current, JFrame frame, Client client) {

        JButton buttonOk = Util.createButtonForOptionPane("Ok");
        JButton buttonCancel = Util.createButtonForOptionPane("Cancel");
        buttonOk.setEnabled(false);

        JComboBox<SelectedTeam> comboBox = new JComboBox<>();

        comboBox.setRenderer((_, value, _, _, _) -> {
            return new JLabel(switch(value) {
                case null -> "";
                case None() -> "<No Team>";
                case TeamIdAndName(_, String name) -> name;
                case Replay() -> "<Simulate Test Data>";
            });
        });

        if (current instanceof TeamIdAndName team) {
            comboBox.addItem(team);
        }

        JPanel basePanel = new JPanel();
        JPanel centerPanels = new JPanel();
        JPanel bottomPanel = new JPanel();

        basePanel.setLayout(new BoxLayout(basePanel, BoxLayout.Y_AXIS));

        centerPanels.setLayout(new BoxLayout(centerPanels, BoxLayout.X_AXIS));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        List.of(centerPanels, bottomPanel).forEach(comp -> {
            basePanel.add(comp);
            comp.setAlignmentX(Component.CENTER_ALIGNMENT);
        });

        JPanel byTeamNamePanel = new JPanel();
        JPanel byUserIdPanel = new JPanel();
        JPanel testPanel = new JPanel();

        if (current instanceof Replay) {
            testPanel.setVisible(false);
        }

        List.of(byTeamNamePanel, byUserIdPanel, testPanel).forEach(comp -> {
            comp.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
            centerPanels.add(comp);
            comp.setAlignmentX(Component.LEFT_ALIGNMENT);
        });

        byTeamNamePanel.setLayout(new BoxLayout(byTeamNamePanel, BoxLayout.Y_AXIS));
        JLabel sbtnLabel  = new JLabel("Search by Team Name");
        JTextField sbtnField = new JTextField(10);
        List.of(sbtnLabel, sbtnField).forEach(comp -> {
            byTeamNamePanel.add(comp);
            comp.setAlignmentX(Component.LEFT_ALIGNMENT);
        });
        sbtnField.addActionListener(_ -> {
            String teamNameSearch = sbtnField.getText();
            if (! teamNameSearch.isBlank()) {
                comboBox.removeAllItems();
                client.teams().search(teamNameSearch).stream()
                    .limit(5)
                    .map(team -> new TeamIdAndName(team.id(), team.name()))
                    .forEach(team -> SwingUtilities.invokeLater(() -> comboBox.addItem(team)));
            }
        });

        byUserIdPanel.setLayout(new BoxLayout(byUserIdPanel, BoxLayout.Y_AXIS));
        JLabel sbuiLabel  = new JLabel("Search by User Id");
        JTextField sbuiField = new JTextField(10);
        List.of(sbuiLabel, sbuiField).forEach(comp -> {
            byUserIdPanel.add(comp);
            comp.setAlignmentX(Component.LEFT_ALIGNMENT);
        });

        sbuiField.addActionListener(_ -> {
            String userIdSearch = sbuiField.getText();
            if (! userIdSearch.isBlank()) {
                comboBox.removeAllItems();
                client.teams().byUserId(userIdSearch).stream()
                    .map(team -> new TeamIdAndName(team.id(), team.name()))
                    .forEach(team -> SwingUtilities.invokeLater(() -> comboBox.addItem(team)));
            }
        });

        testPanel.setLayout(new BoxLayout(testPanel, BoxLayout.Y_AXIS));
        JLabel testLabel  = new JLabel("Run with Test Data");
        JButton testButton = new JButton("Test Data");
        List.of(testLabel, testButton).forEach(comp -> {
            testPanel.add(comp);
            comp.setAlignmentX(Component.LEFT_ALIGNMENT);
        });


        comboBox.addItemListener(_ -> {
            buttonOk.setEnabled(comboBox.getSelectedIndex() > -1);
        });

        testButton.addActionListener(_ -> {
            comboBox.removeAllItems();
            comboBox.addItem(new Replay());
        });


        List.of((JComponent)Box.createVerticalStrut(20), comboBox).forEach(comp -> {
            bottomPanel.add(comp);
            comp.setAlignmentX(Component.LEFT_ALIGNMENT);
        });

        int choice = JOptionPane.showOptionDialog(frame, basePanel, "Pick a Team",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{buttonOk, buttonCancel}, buttonOk);

        if (choice == JOptionPane.OK_OPTION) {
            int index = comboBox.getSelectedIndex();
            if (index > -1) {
                return comboBox.getItemAt(index);
            }
        }

        return current;
    }




}
