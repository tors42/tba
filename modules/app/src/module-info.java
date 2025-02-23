module app {

    requires tba.api;

    requires chariot;

    requires java.prefs;
    requires java.desktop;

    provides tba.api.SinkProvider with app.sink.HttpEventGUI;

    uses tba.api.SourceProvider;
    uses tba.api.SinkProvider;
    uses tba.api.TransformerProvider;

}
