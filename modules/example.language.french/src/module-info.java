module example.language.french {

    requires teambattle.api;

    exports pkg.example;

    provides teambattle.spi.MessageResourcesProvider
        with  pkg.example.ExampleMessagesProvider;

}
