package tba.api;

import java.util.Optional;
import java.util.prefs.Preferences;

public interface ConfigProvider {

    String name();

    Optional<Config> noninteractiveConfig();
    Optional<Config> noninteractiveConfig(Preferences prefs);

    Optional<Config> interactiveConfig();
    Optional<Config> interactiveConfig(Preferences prefs);

}
