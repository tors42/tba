module teambattle {

    requires tba.api;

    requires chariot;
    requires teambattle.api;

    provides tba.api.SourceProvider
        with tb.source.TeamBattleSourceProvider;

    provides tba.api.TransformerProvider
        with tb.transformer.ToTextEventProvider,
             tb.transformer.ToHttpEventProvider;



}
