package tb.transformer;

import module tba.api;
import module teambattle.api;

public record ToTextEvent(EventRenderer renderer) implements Transformer {

    @Override
    public Event transform(Event event) {
        if (! (event instanceof TeamBattleEvent teamBattleEvent)) {
            System.err.println("Failed to transform " + event.getClass().getName());
            return event;
        }

        String message = renderer.render(teamBattleEvent);

        return new TextEvent(message);
    }
}
