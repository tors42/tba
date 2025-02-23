module tba {
    requires tba.api;
    requires java.prefs;

    uses tba.api.SourceProvider;
    uses tba.api.SinkProvider;
    uses tba.api.TransformerProvider;

    provides tba.api.SinkProvider with
        tba.sink.FileSinkProvider;
}
