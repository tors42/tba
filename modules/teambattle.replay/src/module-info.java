module teambattle.replay {

    exports teambattle.replay;

    requires transitive tba.api;
    requires transitive teambattle.api;

    provides tba.api.SourceProvider
        with teambattle.replay.TeamBattleReplaySourceProvider;

}
