module teambattle.api {
    requires tba.api;

    exports teambattle.api;
    exports teambattle.spi;

    uses teambattle.spi.MessageResourcesProvider; // allow other modules implement locale support
}
