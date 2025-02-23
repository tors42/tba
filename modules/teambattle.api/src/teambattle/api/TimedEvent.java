package teambattle.api;

import java.io.Serializable;
import java.time.ZonedDateTime;

public record TimedEvent(ZonedDateTime zdt, TeamBattleEvent tbe) implements Serializable {}
