package tba.api;

import java.util.prefs.Preferences;

public record ProviderAndConfig<T>(T provider, Preferences config) {}
