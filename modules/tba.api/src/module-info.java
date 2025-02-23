module tba.api {
    exports tba.api;

    requires transitive java.prefs;

    provides tba.api.SinkProvider with tba.api.ConsoleSinkProvider;

    uses tba.api.SinkProvider;
    uses tba.api.SourceProvider;
    uses tba.api.TransformerProvider;
}
