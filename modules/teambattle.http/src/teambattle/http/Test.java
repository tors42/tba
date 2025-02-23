package teambattle.http;

import java.util.List;

import tba.api.Event;
import teambattle.api.TeamBattleEvent;

public class Test {

    public static void main(String[] args) {

        var sinkProvider = new TeamBattleHttpSinkProvider();
        var configProvider = sinkProvider.configProvider(null);

        var config = configProvider.noninteractiveConfig().get();

        var sink = sinkProvider.of(config);

        List<TeamBattleEvent> events = List.of(
                new TeamBattleEvent.TourBegin(),
                new TeamBattleEvent.Join(List.of("Foo")),
                new TeamBattleEvent.Join(List.of("Joo")),
                new TeamBattleEvent.Join(List.of("Doo")),
                new TeamBattleEvent.FirstBlood("Foo", "Bar"),
                new TeamBattleEvent.TourEnd()
                );

        for (var event : events) {
            sink.accept(event);
        }

    }

}
