package app;

import module java.base;
import module java.prefs;
import module java.desktop;

record AppConfig(Preferences prefs) {

    static AppConfig withSyncOnExit(Preferences prefs) {
        AppConfig config = new AppConfig(prefs);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> config.sync()));
        return config;
    }

    public boolean httpEnabled() {
        return prefs.getBoolean("httpEnabled", true);
    }

    public InetSocketAddress bindAddress() {
        String bindAddress = prefs.get("httpAddress", "127.0.0.1");
        int port = prefs.getInt("httpPort", 8080);
        return new InetSocketAddress(InetAddress.ofLiteral(bindAddress), port);
    }

    public SelectedTeam selectedTeam() {
        return switch(prefs.node("selectedTeam")) {
            case Preferences teamNode when teamNode.getBoolean("replay", false)
                -> new SelectedTeam.Replay();
            case Preferences teamNode when teamNode.get("teamId", null) instanceof String teamId
                -> new SelectedTeam.TeamIdAndName(teamId, teamNode.get("teamName", teamId));
            default -> new SelectedTeam.None();
        };
    }

    public CSSChoice cssChoice() {
        return switch(prefs.get("cssChoice", "builtin")) {
            case String choice when choice.equals("custom") && prefs.get("cssCustomPath", null) instanceof String path
                    -> new CSSChoice.Custom(Path.of(path));
            default -> new CSSChoice.BuiltIn();
        };
    }

    public Optional<Path> cssCustomPath() {
        return Optional.ofNullable(prefs.get("cssCustomPath", null)).map(Path::of);
    }

    public void storeHttpEnabled(boolean enabled) {
        prefs.putBoolean("httpEnabled", enabled);
    }

    public void storeBindAddress(String address, int port) {
        prefs.put("httpAddress", address);
        prefs.putInt("httpPort", port);
    }

    public void storeCSSChoice(CSSChoice choice) {
        switch (choice) {
            case CSSChoice.BuiltIn() -> prefs.put("cssChoice", "builtin");
            case CSSChoice.Custom(Path path) -> {
                prefs.put("cssChoice", "custom");
                storeCSSCustomPath(path);
            }
        };
    }

    public void storeCSSCustomPath(Path path) {
        prefs.put("cssCustomPath", path.toAbsolutePath().toString());
    }

    public void storeSelectedTeam(SelectedTeam selection) {
        Preferences teamNode = prefs.node("selectedTeam");
        switch (selection) {
            case SelectedTeam.None() -> teamNode.putBoolean("replay", false);
            case SelectedTeam.TeamIdAndName(String id, String name) -> {
                teamNode.put("teamId", id);
                teamNode.put("teamName", name);
                teamNode.putBoolean("replay", false);
            }
            case SelectedTeam.Replay() -> teamNode.putBoolean("replay", true);
        };
    }

    sealed interface SelectedTeam {
        record None() implements SelectedTeam {}
        record TeamIdAndName(String id, String name) implements SelectedTeam {}
        record Replay() implements SelectedTeam {}
    }

    sealed interface CSSChoice {
        record BuiltIn() implements CSSChoice {}
        record Custom(Path path) implements CSSChoice {}
    }

    void sync() {
        try {
            prefs.sync();
        } catch (BackingStoreException ex) {
            System.out.println("Failed to store preferences - " + ex.getMessage());
        }
    }

    public void setAppLocation(JFrame frame) {
        if (MouseInfo.getPointerInfo() instanceof PointerInfo pi && pi.getLocation() instanceof Point location) {
            frame.setLocation(location);
        } else {
            frame.setLocationRelativeTo(null);
        }
    }

}
