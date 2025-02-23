package tba.api;

public interface UI {

    default String crudeQuery(String prompt) {
        return System.console().readLine("%s: " + prompt);
    }

    default void crudeMessage(String message) {
        System.out.println(message);
    }

}
