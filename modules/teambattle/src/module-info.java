module teambattle {

    exports tb.source;
    exports tb.transformer;

    requires transitive tba.api;
    requires transitive chariot;
    requires transitive teambattle.api;

    provides tba.api.SourceProvider
        with tb.source.TeamBattleSourceProvider;

    provides tba.api.TransformerProvider
        with tb.transformer.ToTextEventProvider,
             tb.transformer.ToHttpEventProvider;

}
