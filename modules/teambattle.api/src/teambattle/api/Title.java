package teambattle.api;

public sealed interface Title {
    record None() implements Title {}
    record Custom(String value) implements Title {}
    enum FIDE implements Title {
        GM,WGM,IM,WIM,FM,WFM,CM,WCM
    }
}
