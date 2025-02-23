package tb.transformer;

import module java.base;

import module tba.api;

import module chariot;

import tba.api.Event;
import teambattle.api.EventRenderer;
import teambattle.api.TeamBattleEvent;

public class ToHttpEventProvider implements TransformerProvider {

    @Override
    public String name() {
        return "teambattle.http";
    }

    @Override
    public Class<? extends Event> fromEventType() {
        return TeamBattleEvent.class;
    }

    @Override
    public Class<? extends Event> toEventType() {
        return HttpEvent.class;
    }

    @Override
    public ConfigProvider configProvider(UI ui) {
        return new ConfigProvider() {

            @Override
            public String name() {
                return "teambattle.http";
            }

            @Override
            public Optional<Config> interactiveConfig() {
                if (! (ui.crudeQuery("Language (sv, en, fr): ") instanceof String lang && !lang.isBlank())) {
                    ui.crudeMessage("Failed to query language");
                    return Optional.empty();
                }
                return Optional.of(TeamBattleTransformerConfig.of(Locale.of(lang)));
            }

            @Override
            public Optional<Config> interactiveConfig(Preferences prefs) {
                String langFromPrefs = prefs.get("lang", null);

                if (langFromPrefs == null) {
                    return interactiveConfig();
                }

                if (! (ui.crudeQuery("Language [%s]: ".formatted(langFromPrefs)) instanceof String lang)) {
                    ui.crudeMessage("Failed to query language");
                    return Optional.empty();
                }

                String langToUse = lang.isBlank() ? langFromPrefs : lang;

                return Optional.of(TeamBattleTransformerConfig.of(Locale.of(langToUse)));
            }

            @Override
            public Optional<Config> noninteractiveConfig() {
                return Optional.of(TeamBattleTransformerConfig.of());
            }

            @Override
            public Optional<Config> noninteractiveConfig(Preferences prefs) {
                return switch(prefs.get("lang", null)) {
                    case String lang -> Optional.of(TeamBattleTransformerConfig.of(Locale.of(lang)));
                    case null -> Optional.of(TeamBattleTransformerConfig.of());
                };
            }

        };
    }

    sealed interface TeamBattleTransformerConfig extends Config {

        static TeamBattleTransformerConfig of() {
            return new Empty();
        }

        static TeamBattleTransformerConfig of(Locale locale) {
            return new WithLocale(locale);
        }

        default Optional<Locale> localeOpt() {
            return switch(this) {
                case Empty() -> Optional.empty();
                case WithLocale(Locale locale) -> Optional.of(locale);
            };
        }

        record Empty() implements TeamBattleTransformerConfig {}
        record WithLocale(Locale locale) implements TeamBattleTransformerConfig {}

        @Override
        default void store(Preferences prefs) {
            localeOpt().ifPresent(l -> prefs.put("lang", l.getLanguage()));
        }
    }

    @Override
    public Transformer of(Config config) {
        if (! (config instanceof TeamBattleTransformerConfig transformerConfig)) {
            System.out.println("Unexcepted config " + config.getClass());
            return null;
        }

        EventRenderer renderer = switch(transformerConfig) {
            case TeamBattleTransformerConfig.WithLocale(Locale locale) -> EventRenderer.ofLocale(locale);
            case TeamBattleTransformerConfig.Empty() -> EventRenderer.of();
        };

        EventRenderer memberFoeRenderer = renderer
            .withMemberReplacer("""
                    <span class="member">%s</span>"""::formatted)
            .withFoeReplacer("""
                    <span class="foe">%s</span>"""::formatted);

        return new ToHttpEvent(memberFoeRenderer);
    }
}
