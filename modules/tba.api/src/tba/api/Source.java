package tba.api;

import java.util.stream.Stream;

public interface Source {

    Stream<? extends Event> events();

    void run();

}
