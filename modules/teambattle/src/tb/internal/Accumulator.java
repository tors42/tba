package tb.internal;

public interface Accumulator<T,V> {
    Result<T,V> accept(T element);

    sealed interface Result<T,V> {}
    record Self<T,V>(Accumulator<T,V> accumulator) implements Result<T,V> {}
    record SelfAndValue<T,V>(Accumulator<T,V> accumulator, V value) implements Result<T,V> {}
    record Value<T,V>(V value) implements Result<T,V> {}
}
