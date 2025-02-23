module teambattle.http {

    requires tba.api;
    requires teambattle.api;

    requires jdk.httpserver;

    provides tba.api.SinkProvider
        with teambattle.http.TeamBattleHttpSinkProvider;

}
