package tb.internal;

public record RepeatableAction(int ticks, int ticksToTrigger, Runnable action) implements Accumulator<Void, Runnable> {

    public RepeatableAction(int ticksToTrigger, Runnable action) {
        this(0, ticksToTrigger, action);
    }

    @Override
    public Result<Void, Runnable> accept(Void v) {
        return switch(ticks+1) {
            case int nextTicks when nextTicks >= ticksToTrigger
                -> new SelfAndValue<>(new RepeatableAction(0, ticksToTrigger, action), action);
            case int nextTicks
                -> new Self<>(new RepeatableAction(nextTicks, ticksToTrigger, action));
        };
    }
}
