package tba.api;

public interface Sink {
    void accept(Event event);

    void close();
}
