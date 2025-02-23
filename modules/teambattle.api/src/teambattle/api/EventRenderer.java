package teambattle.api;

import module java.base;

import teambattle.api.TeamBattleEvent.*;

public sealed interface EventRenderer {

    String render(TeamBattleEvent teamBattleEvent);

    public static final String resourceBundleBaseName = "teambattle.MessageResources";

    public static EventRenderer of() {
        return new ResourceRenderer(ResourceBundle.getBundle(resourceBundleBaseName));
    }

    public static EventRenderer ofLocale(Locale locale) {
        return new ResourceRenderer(ResourceBundle.getBundle(resourceBundleBaseName, locale));
    }

    default EventRenderer withGlobalNameReplacer(Function<String, String> globalReplacer) {
        return switch(this) { case ResourceRenderer(var bundle, _, var memberReplacer, var foeReplacer)
                            -> new ResourceRenderer(bundle, globalReplacer, memberReplacer, foeReplacer); };
    }

    default EventRenderer withMemberReplacer(Function<String, String> memberReplacer) {
        return switch(this) { case ResourceRenderer(var bundle, var globalReplacer, _, var foeReplacer)
                            -> new ResourceRenderer(bundle, globalReplacer, memberReplacer, foeReplacer); };
    }

    default EventRenderer withFoeReplacer(Function<String, String> foeReplacer) {
        return switch(this) { case ResourceRenderer(var bundle, var globalReplacer, var memberReplacer, _)
                            -> new ResourceRenderer(bundle, globalReplacer, memberReplacer, foeReplacer); };
    }

    record ResourceRenderer(ResourceBundle bundle,
            Function<String, String> globalReplacer,
            Function<String, String> memberReplacer,
            Function<String,String> foeReplacer) implements EventRenderer {

        public ResourceRenderer(ResourceBundle bundle) {
            this(bundle, Function.identity(), Function.identity(), Function.identity());
        }

        public String render(TeamBattleEvent teamBattleEvent) {
            TeamBattleEvent withReplacedNames = replaceNames(teamBattleEvent, globalReplacer().andThen(memberReplacer()), globalReplacer.andThen(foeReplacer()));
            return switch(withReplacedNames) {
                case Join(List<String> members) -> MessageFormat.format(bundle.getString("join"), groupOf(members, () -> bundle.getString("and")), members.size());
                case TourBegin() -> bundle.getString("begin");
                case FirstBlood(var member, var foe)   -> MessageFormat.format(bundle.getString("firstblood"), member, foe);
                case Streak(var member, int winsInRow) -> MessageFormat.format(bundle.getString("streak"), member, winsInRow);
                case Avenge(var member, var avenged, var foe) -> MessageFormat.format(bundle.getString("avenge"), member, foe,
                    groupOf(avenged.stream().filter(m -> !m.equals(member)).toList(), () -> bundle.getString("and")),
                    (Integer) (avenged.stream().filter(m -> !m.equals(member)).findAny().isPresent() ? 1: 0),
                    (Integer) (avenged.contains(member) ? 1 : 0));
                case Standings(Map<String, Integer> teams) -> MessageFormat.format(bundle.getString("standings"),
                    "\n" + String.join("\n", indexed(teams.entrySet().stream()
                                .filter(entry -> entry.getValue() > 0)
                                .map(entry -> "%3d %s".formatted(entry.getValue(), entry.getKey()))
                                .toList()).stream()
                            .map(entry -> "%2d: %s".formatted(entry.index(), entry.value()))
                            .toList()));
                case TourEnd() -> bundle.getString("end");
            };
        }
    }

    static TeamBattleEvent replaceNames(TeamBattleEvent event,
            Function<String, String> memberReplacer,
            Function<String, String> foeReplacer
            ) {
       return switch(event) {
            case Join(List<String> members) -> new Join(members.stream().map(memberReplacer).toList());
            case TourBegin tb -> tb;
            case FirstBlood(var member, var foe)   -> new FirstBlood(memberReplacer.apply(member), foeReplacer.apply(foe));
            case Streak(var member, var winsInRow) -> new Streak(memberReplacer.apply(member), winsInRow);
            case Avenge(var member, List<String> avenged, var foe)
                -> new Avenge(memberReplacer.apply(member), avenged.stream().map(memberReplacer).toList(), foeReplacer.apply(foe));
            case Standings standings -> standings;
            case TourEnd te -> te;
       };
    }

    record Indexed<T>(int index, T value) {}
    static <T> List<Indexed<T>> indexed(List<T> list) { return java.util.stream.IntStream.range(0, list.size()).mapToObj(i -> new Indexed<>(i+1, list.get(i))).toList(); }

    private static String groupOf(List<String> group, Supplier<String> and) {
        return switch (group.size()) {
            case int size when size == 1 -> group.getFirst();
            case int size when size >= 2  -> "%s %s %s".formatted(
                    String.join(", ", group.subList(0, size-1)),
                    and.get(),
                    group.getLast());
            default -> "";
        };
    }
}
