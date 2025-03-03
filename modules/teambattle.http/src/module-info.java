module teambattle.http {

    exports teambattle.http;

    requires transitive tba.api;
    requires teambattle.api;

    requires jdk.httpserver;

    provides tba.api.SinkProvider
        with teambattle.http.TeamBattleHttpSinkProvider;

}
