package tb.transformer;

import module java.base;
import module tba.api;
import module teambattle.api;

import teambattle.api.TeamBattleEvent.*;

public record ToHttpEvent(EventRenderer renderer) implements Transformer {

    @Override
    public Event transform(Event event) {
        if (! (event instanceof TeamBattleEvent teamBattleEvent)) {
            System.err.println("Failed to transform " + event.getClass().getName());
            return event;
        }

        String message = renderEventAsHtml(teamBattleEvent, renderer);

        return new HttpEvent(message);
    }


    static String renderEventAsHtml(TeamBattleEvent event, EventRenderer renderer) {
        String outer = """
            <div class="%s">INNER</div>""".formatted(cssClassOfTeamBattleEvent(event));

        String inner = switch(event) {
            case Join _,
                 TourBegin _,
                 TourEnd _,
                 FirstBlood _,
                 Streak _,
                 Upset _,
                 Avenge _ -> "<div>%s</div>".formatted(renderer.render(event));

            case Standings(Map<String, Integer> teams) -> """
              <div>
                  <table>
                      <tr>
                          <th>#</th> <th>Score</th> <th>Team</th>
                      </tr>

                      %s

                  </table>
              </div>
            """.formatted(String.join("\n", indexed(
                            teams.entrySet().stream()
                                .filter(entry -> entry.getValue() > 0)
                                .map(entry -> "<td>%d</td><td>%s</td>".formatted(entry.getValue(), entry.getKey()))
                                .toList()
                            ).stream()
                             .map(entry -> "<tr><td>%d</td>%s</tr>".formatted(entry.index() + 1, entry.value()))
                             .toList()));
        };

        String html = outer.replace("INNER", inner);
        return html;
    }

    static String cssClassOfTeamBattleEvent(TeamBattleEvent event) {
        return switch (event) {
            default -> event.getClass().getSimpleName().toLowerCase();
        };
    }

    record Indexed<T>(int index, T value) {}

    static <T> List<Indexed<T>> indexed(List<T> list) {
        return IntStream.range(0, list.size()).mapToObj(i -> new Indexed<>(i, list.get(i))).toList();
    }

}
