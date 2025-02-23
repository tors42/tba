module teambattle.replay {

    requires tba.api;
    requires teambattle.api;

    provides tba.api.SourceProvider
        with teambattle.replay.TeamBattleReplaySourceProvider;

}
