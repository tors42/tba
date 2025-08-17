void main(String[] args) throws Exception {

    String version   = args.length > 0 ? args[0] : "0.0.1-SNAPSHOT";
    String timestamp = args.length > 1 ? args[1] : ZonedDateTime.now()
        .withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    String runtime   = args.length > 2 ? args[2] : "local";
    Path jmodsPath   = args.length > 3 ? Path.of(args[3]) : Path.of(System.getProperty("java.home")).resolve("jmods");

    ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
    ToolProvider jar   = ToolProvider.findFirst("jar").orElseThrow();
    ToolProvider jlink = ToolProvider.findFirst("jlink").orElseThrow();

    Path outDir = Path.of("out");
    del(outDir);

    Path libDir = Path.of("lib");
    Path modulesSrc = Path.of("modules");
    Path modulesOut = outDir.resolve("modules");
    Path jars = outDir.resolve("jars");
    Path chariot = libDir.resolve("chariot.jar");
    Path metaInf = outDir.resolve("META-INF");

    for (Path dir : List.of(libDir, metaInf)) Files.createDirectories(dir);

    Files.createDirectories(libDir);
    Files.copy(Path.of("LICENSE"), metaInf.resolve("LICENSE"));

    if (! Files.exists(chariot))
        Files.copy(URI.create(
                    "https://repo1.maven.org/maven2/io/github/tors42/chariot/0.1.21/chariot-0.1.21.jar"
                    ).toURL().openStream(), chariot);

    List<String> modules = List.of(
                "tba.api",
                "tba",
                "app",
                "teambattle.api",
                "teambattle",
                "teambattle.replay",
                "teambattle.http",
                "example.language.french");

    for (String module : modules) {
        String filenamePrefix = module + "-" + version;

        run(javac,
                "--enable-preview",
                "--release", "24",
                "--module-path", libDir,
                "--module-source-path", modulesSrc.resolve("*", "src"),
                "--module", module,
                "-d", modulesOut
           );

        Path resourcesDir = switch(modulesSrc.resolve(module).resolve("resources")) {
            case Path path when Files.exists(path) -> path;
            case Path _ -> Files.createDirectories(modulesOut.resolve(module).resolve("resources"));
        };

        run(jar,
                "--create",
                "--date", timestamp,
                "--module-version", version,
                "--no-manifest",
                "--file", jars.resolve(filenamePrefix + ".jar"),
                "-C", outDir, "META-INF",
                "-C", resourcesDir, ".",
                "-C", modulesOut.resolve(module), "."
           );
    }


    createRuntime(
            jlink,
            List.of(jmodsPath, jars, libDir),
            outDir.resolve("runtime").resolve(runtime),
            modules,
            "tba=app/app.App");

}

void createRuntime(ToolProvider jlink, List<Path> modulePath, Path output, List<String> modules, String launcher) {
    run(jlink,
            "--add-options", " --enable-preview",
            "--compress", "zip-9",
            "--no-man-pages",
            "--no-header-files",
            "--module-path", String.join(File.pathSeparator, modulePath.stream().map(Path::toString).toList()),
            "--add-modules", String.join(",", modules),
            "--launcher", launcher,
            "--output", output
       );
}

void del(Path dir) {
    if (dir.toFile().exists()) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Collections.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

void run(ToolProvider tool, Object... args) {
    String[] stringArgs = Arrays.stream(args).map(Object::toString).toArray(String[]::new);
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    int exitCode = tool.run(new PrintWriter(out), new PrintWriter(err), stringArgs);

    if (exitCode != 0) {
        out.flush();
        err.flush();
        System.err.format("""
                %s exited with code %d
                args:   %s
                stdout: %s
                stderr: %s%n""",
                tool, exitCode, String.join(" ", stringArgs),
                out, err);
        System.exit(exitCode);
    }
}
