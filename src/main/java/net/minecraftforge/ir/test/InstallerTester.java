/*
 * Installer Rewriter
 * Copyright (c) 2021.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.ir.test;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import net.minecraftforge.ir.InstallerFormat;
import net.minecraftforge.ir.InstallerRewriter;
import net.minecraftforge.ir.json.InstallProfile;
import net.minecraftforge.ir.json.Version.Library;
import net.minecraftforge.ir.json.Version.LibraryDownload;
import net.minecraftforge.ir.util.Artifact;
import net.minecraftforge.ir.util.Disco;
import net.minecraftforge.ir.util.Log;
import net.minecraftforge.ir.util.MavenCache;
import net.minecraftforge.ir.util.MinecraftCache;
import net.minecraftforge.ir.util.OS;
import net.minecraftforge.ir.util.Utils;
import net.minecraftforge.util.hash.HashFunction;

import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static net.minecraftforge.ir.InstallerRewriter.*;
import static net.minecraftforge.ir.util.Utils.mkdirs;
import static net.minecraftforge.ir.util.Utils.sneak;

public class InstallerTester {
    static final Log LOGGER = new Log();

    public static void main(String[] args) {
        System.exit(mainI(args));
    }

    public static int mainI(String[] args) {
        var parser = new OptionParser();

                        parser.accepts("test", "Runs the tester instead of the rewriter");
        var helpO     = parser.acceptsAll(asList("h", "help"), "Prints this help.").forHelp();
        var repoO     = parser.acceptsAll(asList("m", "maven"), "Maven repository to download installer from.").withRequiredArg().defaultsTo(InstallerRewriter.FORGE_MAVEN);
        var outputO   = getPathOption(parser, asList("o", "output" ), "output",  "Directory to install tests.");
        var reportsO  = getPathOption(parser, asList("r", "reports"), "reports", "Directory to write reports.");
        var cacheO    = getPathOption(parser, asList("c", "cache"  ), "cache",   "Directory to cache downloads.");
        var artifactO = parser.acceptsAll(asList("a", "artifact"), "Maven notation for the artifact to test.").withRequiredArg().defaultsTo("net.minecraftforge:forge");
        var versionO  = parser.acceptsAll(asList("v", "version"), "Version to test.").withRequiredArg().ofType(ComparableVersion.class);
        var startO    = parser.acceptsAll(asList("s", "start"), "Starting version to test.").withRequiredArg().ofType(ComparableVersion.class);
        var endO      = parser.acceptsAll(asList("e", "end"), "Ending version to test.").withRequiredArg().ofType(ComparableVersion.class);
        var forceO    = parser.acceptsAll(asList("force"), "Forces tasks to rerun as if the cache missed");
        var ltsO      = parser.acceptsAll(asList("lts"), "Only tests using LTS java versions");

        var optSet = parser.parse(args);

        if (optSet.has(helpO)) {
            sneak(() -> parser.printHelpOn(System.err));
            return -1;
        }

        int width = optSet.specs().stream().mapToInt(s -> s.options().getLast().length()).max().orElse(0) + 1;
        var format = "%-" + width + "s: %s";

        var repo = optSet.valueOf(repoO);
        LOGGER.info(format, "maven", repo);
        var artifact = Artifact.from(optSet.valueOf(artifactO));
        LOGGER.info(format, "artifact", artifact);
        var lts = optSet.has(ltsO);
        LOGGER.info(format, "lts", lts);
        var force = optSet.has(forceO);
        LOGGER.info(format, "force", force);

        var builder = new Builder()
            .artifact(artifact)
            .repo    (repo)
            .output  (getPath(format, optSet, outputO ))
            .cache   (getPath(format, optSet, cacheO  ))
            .reports (getPath(format, optSet, reportsO))
            .only    (getVersion(format, optSet, versionO))
            .start   (getVersion(format, optSet, startO  ))
            .end     (getVersion(format, optSet, endO    ))
            ;

        if (force)
            builder.force();

        if (lts)
            builder.lts();

        LOGGER.info("");

        return builder.build().run();
    }

    private static OptionSpec<Path> getPathOption(OptionParser parser, List<String> names, String _default, String description) {
        return parser.acceptsAll(names, description)
            .withRequiredArg()
            .withValuesConvertedBy(new PathConverter())
            .defaultsTo(Paths.get(_default));
    }

    private static Path getPath(String format, OptionSet options, OptionSpec<Path> key) {
        var ret = options.valueOf(key);
        LOGGER.info(format, key.options().getLast(), ret);
        return ret.toAbsolutePath().normalize();
    }

    private static ComparableVersion getVersion(String format, OptionSet options, OptionSpec<ComparableVersion> key) {
        var ret = options.valueOf(key);
        LOGGER.info(format, key.options().getLast(), ret);
        return ret;
    }

    private static class Builder {
        private boolean force = false;
        private boolean lts = false;
        private String repo;
        private Path output;
        private Path cache;
        private Path reports;
        private Artifact artifact = Artifact.from("net.minecraftforge:forge");
        private ComparableVersion only;
        private ComparableVersion start;
        private ComparableVersion end;

        public InstallerTester build() {
            return new InstallerTester(this);
        }

        public Builder force() {
            this.force = true;
            return this;
        }

        public Builder lts() {
            this.lts = true;
            return this;
        }

        public Builder repo(String value) {
            this.repo = value;
            return this;
        }

        public Builder output(Path value) {
            this.output = value;
            return this;
        }

        public Builder cache(Path value) {
            this.cache = value;
            return this;
        }

        public Builder reports(Path value) {
            this.reports = value;
            return this;
        }

        public Builder artifact(Artifact value) {
            this.artifact = value;
            return this;
        }

        public Builder only(ComparableVersion value) {
            this.only = value;
            return this;
        }

        public Builder start(ComparableVersion value) {
            this.start = value;
            return this;
        }

        public Builder end(ComparableVersion value) {
            this.end = value;
            return this;
        }
    }

    private final Builder cfg;
    private final Artifact artifact;
    private final Path reports;
    private final Path cache;

    private final Disco disco;
    private final MinecraftCache mcCache;
    private final MavenCache maven;
    private final Predicate<ComparableVersion> filter;

    private InstallerTester(Builder builder) {
        this.cfg = builder;
        this.artifact = this.cfg.artifact;
        this.reports = this.cfg.reports;
        this.cache = this.cfg.cache;

        this.disco = new Disco(LOGGER, cache.toFile());
        this.mcCache = new MinecraftCache(LOGGER, cache);
        this.maven = new MavenCache(LOGGER, "maven", this.cfg.repo, cache);

        Predicate<ComparableVersion> filter;
        if (this.cfg.only != null) {
            filter = this.cfg.only.toString().indexOf('-') == -1
                ? this.cfg.only::equals
                : v -> v.toString().startsWith(this.cfg.only.toString() + '-');
        } else {
            filter = v -> true;
            if (this.cfg.start != null)
                filter = filter.and(v -> v.compareTo(cfg.start) >= 0);
            if (this.cfg.end != null)
                filter = filter.and(v -> v.compareTo(cfg.end) < 0);
        }
        this.filter = filter;
    }

    private int run() {
        LOGGER.info("Discovering versions");
        var versions = maven.getVersions(artifact).stream()
            .map(ComparableVersion::new)
            //.filter(this.filter)
            .sorted()
            .toList();

        var allReports = loadReports(versions);

        List<Report> reports = new ArrayList<>();
        var filtered = new ArrayList<Report>();
        for (var report : allReports) {
            if (this.filter.test(report.cversion()))
                reports.add(report);
            else
                filtered.add(report);
        }
        reports = downloadInstallers(reports);

        downloadLibraries(reports);
        downloadJava(reports);
        testSide(reports, true);
        testSide(reports, false);

        LOGGER.info("Cleaning output folder");
        for (var report : reports) {
            var dir = this.cfg.output.resolve(report.version());
            try {
                if (Files.list(dir).count() == 0)
                    Utils.delete(dir);
            } catch (Throwable t) {}
        }

        updateGlobals(reports, filtered);

        return 0;
    }

    private List<Report> loadReports(List<ComparableVersion> versions) {
        LOGGER.info("Loading previous reports");
        var ret = new ArrayList<Report>();
        if (Files.exists(reports)) {
            try (var l = LOGGER.push()) {
                var progress = new Progress(LOGGER, versions.size());
                for (var version : versions) {
                    progress.quiet("Loading %s", version);
                    var root = reports.resolve(version.toString());
                    var report = Report.load(LOGGER, root);
                    if (report == null)
                        report = new Report(this.artifact.withVersion(version.toString()), null);
                    ret.add(report);
                }
            }
        } else {
            for (var version : versions)
                ret.add(new Report(this.artifact.withVersion(version.toString()), null));
        }
        return ret;
    }

    private <T extends Supplier<Report>> List<Report> consume(ThreadedTask<T> executor, Consumer<T> custom) {
        var ret = new ArrayList<Report>();
        executor.consume(t -> {
            custom.accept(t);
            var report = t.get();
            try (var k = LOGGER.push()) {
                for (var line : report.logLines()) {
                    if (line.args() == null)
                        LOGGER.log(line.level(), line.message());
                    else
                        LOGGER.log(line.level(), line.message(), line.args());
                }
            }
            ret.add(report);
        });
        executor.shutdown();
        return ret;
    }

    record DownloadInstaller(Report get, boolean existed) implements Supplier<Report> {}
    private List<Report> downloadInstallers(List<Report> reports) {
        LOGGER.info("Downloading installers");
        try (var l = LOGGER.push()) {
            var executor = new ThreadedTask<DownloadInstaller>("download-installers");

            for (var report : reports)
                executor.submit(() -> downloadInstaller(report));

            var steps = new Progress(LOGGER, reports.size());
            var ret = consume(executor, info -> {
                var report = info.get();
                if (!info.existed() && report.hash() != null)
                    steps.step("Downloaded %s", report.version());
                else
                    steps.quiet("Loaded %s", report.version());
                report.dumpLog(LOGGER);
            });

            for (var report : ret) {
                if (report.hash() != null) {
                    report.save(this.reports.resolve(report.version()));
                }
            }

            return ret;
        }
    }

    private static final ComparableVersion FIRST_FORGE_INSTALLER = new ComparableVersion("1.5.2");
    private DownloadInstaller downloadInstaller(Report report) {
        var installer = report.artifact().withClassifier("installer");
        var existed = this.maven.exists(installer);
        var path = "net.minecraftforge:forge".equals(installer.getGroup() + ':' + installer.getName()) && installer.getComparableVersion().compareTo(FIRST_FORGE_INSTALLER) < 0
                ? null
                : this.maven.download(installer);
        if (path == null)
            return new DownloadInstaller(new Report(report.artifact(), null), false);

        try {
            var hash = HashFunction.SHA1.hash(path.toFile());
            if (!hash.equals(report.hash()))
                report = new Report(report.artifact(), hash);

            report.installer(path);

            /* This was to convert old things without re-running everything
            var profile = InstallProfile.read(path);
            if (profile != null) {
                report.format(profile.getFormat());
                report.serverJar(profile.getExecutableJar());
                report.tests.put(Test.INSTALL_CLIENT.name(), Test.INSTALL_CLIENT.version());
                report.tests.put(Test.INSTALL_SERVER.name(), Test.INSTALL_SERVER.version());
                report.tests.put(Test.RUN_SERVER.name(), Test.RUN_SERVER.version());
            }
            */

            if (!this.cfg.force && report.cached())
                return new DownloadInstaller(report, existed);

            var profile = InstallProfile.read(path);
            report.profile(profile);
        } catch (IOException e) {
            report.log().error("Failed to read installer %s", e.getMessage());
        }
        return new DownloadInstaller(report, existed);
    }

    private void updateGlobals(List<Report> reports, List<Report> unmodified) {
        LOGGER.info("Updateing global reports..");
        var globals = new Globals(LOGGER, this.cfg.reports).load();

        for (var report : reports)
            report.save(this.cfg.reports.resolve(report.version()));

        for (var list : Arrays.asList(reports, unmodified)) {
            for (var report : list) {
                var version = report.cversion();
                globals.clear(version);

                if (report.hash() == null)
                    globals.missing(version);
                else {
                    globals.format(version, report.format());
                    if (report.serverJar() == null)
                        globals.noServerJar(version);
                }

                for (var global : report.globals())
                    globals.add(global, version);

                for (var test : report.tests()) {
                    for (var global : test.globals())
                        globals.add(global, version);
                }

                if (!report.isSuccess())
                    globals.fail(version);
            }
        }

        globals.save();
    }

    private void downloadLibraries(List<Report> reports) {
        LOGGER.info("Downloading Libraries");

        record DownloadInfo(Artifact artifact, String url, String sha1) {}
        var downloads = new TreeSet<DownloadInfo>((a, b) -> a.artifact().compareTo(b.artifact()));
        var versions = new TreeSet<String>();

        for (var report : reports) {
            if (report.profile() == null)
                continue;

            versions.add(report.profile().getMinecraftVersion());

            for (var side : new boolean[] { true, false }) {
                for (var lib : report.profile().getLibraries(side)) {
                    var dl = lib.downloads == null ? null : lib.downloads.artifact;
                    if (dl != null && dl.url != null && !dl.url.isEmpty()) {
                        var artifact = cleanArtifact(report.log(), report.version(), lib.name.toString(), dl);
                        if ((dl.url.startsWith(MOJANG_MAVEN) && !this.mcCache.exists(artifact)) && !this.maven.exists(artifact))
                            downloads.add(new DownloadInfo(artifact, dl.url, dl.sha1));
                    }
                }
            }
        }

        try (var l = LOGGER.push()) {
            var executor = new ThreadedTask<String>("download-libraries");

            for (var version : versions) {
                if (!this.mcCache.exists(version, "client.jar")) {
                    executor.submit(() -> {
                        this.mcCache.getDownload(version, "client");
                        return version + " client.jar";
                    });
                }
                if (!this.mcCache.exists(version, "server.jar")) {
                    executor.submit(() -> {
                        this.mcCache.getDownload(version, "server");
                        return version + " server.jar";
                    });
                }
            }
            for (var lib : downloads) {
                executor.submit(() -> {
                    if (lib.url().startsWith(MOJANG_MAVEN)) {
                        if (this.mcCache.download(lib.artifact()) != null)
                            return lib.url();
                    }

                    boolean correctMaven = lib.url().startsWith(this.maven.url()) ||
                            lib.url().startsWith(OLD_FORGE_MAVEN) && FORGE_MAVEN.equals(this.maven.url());

                    if (!correctMaven)
                        return lib.url() + " failed: Unknown maven";

                    if (this.maven.download(lib.artifact()) == null)
                        return lib.url() + " failed";

                    return lib.url();
                });
            }

            var progress = new Progress(LOGGER, executor.size());
            executor.consume(name -> progress.step("Download %s", name));
            executor.shutdown();
        }
    }

    private static Artifact cleanArtifact(Log log, String forgeVersion, String name, LibraryDownload dl) {
        var artifact = Artifact.from(name);
        // The vanilla launcher doesn't support classifiers in the names, so its sometimes encoded weird
        if (!dl.url.endsWith(artifact.getPath())) {
            int s1 = dl.url.lastIndexOf('/');
            int s2 = dl.url.lastIndexOf('/', s1 - 1);

            var filename = dl.url.substring(s1 + 1);
            var version = dl.url.substring(s2 + 1, s1);
            var normal = artifact.getName() + '-' + version;
            int idx = filename.lastIndexOf('.');
            if (!filename.startsWith(normal) || idx == -1) {
                LOGGER.warn("Weird download url in %s: %s", forgeVersion, dl.url);
                return null; // Something is fucky, assume the installer will manually download
            }

            artifact = artifact
                .withVersion(version)
                .withExtension(filename.substring(idx + 1));

            if (idx != normal.length())
                artifact = artifact.withClassifier(filename.substring(normal.length() + 1, idx));
        }

        return artifact;
    }

    /* Convert a java version to the next compatible LTS java version */
    private int lts(int version) {
        if (!this.cfg.lts)
            return version;

        if (version <= 8)
            return 8;
        if (version <= 11)
            return 11;
        if (version <= 17)
            return 17;
        if (version <= 21)
            return 21;
        if (version <= 25)
            return 25;

        return version;
    }

    private void downloadJava(List<Report> reports) {
        LOGGER.info("Locating Java");

        var mcVersions = new HashSet<String>();
        for (var report : reports) {
            if (report.profile() == null)
                continue;
            mcVersions.add(report.profile().getMinecraftVersion());
        }

        var javaVersions = new HashSet<Integer>();
        for (var version : mcVersions) {
            var json = mcCache.getVersion(version);
            if (version == null)
                continue;
            javaVersions.add(lts(json.getJavaVersion(version)));
        }

        if (javaVersions.isEmpty())
            return;

        record Info(int version, File path) {}
        var executor = new ThreadedTask<Info>("java-download");
        for (var version : javaVersions)
            executor.submit(() -> new Info(version, disco.find(version)));

        try (var l = LOGGER.push()) {
            var progress = new Progress(LOGGER, executor.size());
            executor.consume(info -> {
                if (info.path() == null)
                    progress.step("Failed to find Java %d", info.version());
                else
                    progress.step("Java %d: %s", info.version(), info.path());
            });
            executor.shutdown();
        }
    }

    private final AtomicInteger serverPortSupplier = new AtomicInteger(25566);
    private static ThreadLocal<Integer> serverPort = new ThreadLocal<Integer>();

    private void testSide(List<Report> reports, boolean client) {
        LOGGER.info("Testing %s", client ? "Client" : "Server");
        var testInstall = client ? Test.INSTALL_CLIENT : Test.INSTALL_SERVER;
        var testRun = client ? null : Test.RUN_SERVER;

        try (var l = LOGGER.push()) {
            record Info(Report get) implements Supplier<Report> {}
            var executor = new ThreadedTask<Info>("test-side", runner -> () -> {
                serverPort.set(serverPortSupplier.getAndIncrement());
                runner.run();
            });

            for (var report : reports) {
                if (report.profile() == null)
                    continue;

                // V1 doesn't support client install
                if (client && report.profile().getFormat() == InstallerFormat.V1) {
                    var test = new TestInstall(report, true);
                    test.success("Installing Client on Foramt v1 not supported");
                    report.add(test);
                    continue;
                }

                boolean needInstall = this.cfg.force || !report.cached(testInstall);
                boolean needRun = testRun != null && (this.cfg.force || !report.cached(testRun));

                if (!needInstall && !needRun)
                    continue;

                var output = this.cfg.output.resolve(report.version());
                var target = output.resolve(client ? "client" : "server");
                var results = this.cfg.reports.resolve(report.version());

                executor.submit(() -> {
                    //if (needInstall)
                    //    Utils.delete(root);
                    mkdirs(target);
                    copyVanilla(report, target, client);
                    copyLibraries(report, target, client);

                    var install = report.add(runInstall(report, target, client));
                    if (needInstall)
                        install.save(results);

                    if (!install.isSuccess())
                        return new Info(report);

                    if (needRun) {
                        Test run;
                        if (client)
                            run = null;
                        else
                            run = report.add(runServer(report, target));

                        if (run != null) {
                            run.save(results);
                            if (!run.isSuccess())
                                return new Info(report);
                        }
                    }

                    try {
                        Utils.forceDelete(target);
                        try {
                            if (Files.list(output).count() == 0)
                                Utils.delete(output);
                        } catch (Throwable t) {}
                    } catch (Throwable e) {
                        report.log().error("Failed to delete %s", target, e);
                    }

                    return new Info(report);
                });
            }

            var progress = new Progress(LOGGER, executor.size());
            consume(executor, info -> {
                var report = info.get();
                progress.step("Testing %s %s", report.version(), client ? "Client" : "Server");
                try (var l2 = LOGGER.push()) {
                    report.dumpLog(LOGGER);

                    var install = report.get(testInstall);
                    if (install != null) {
                        if (install.isSuccess())
                            LOGGER.info("Install Success");
                        else
                            LOGGER.warn("Install Failed: %s", install.message());
                    }

                    var run = report.get(testRun);
                    if (run != null) {
                        if (run.isSuccess())
                            LOGGER.info("Run Success");
                        else
                            LOGGER.warn("Run Failed: %s", run.message());
                    }
                }
            });
            executor.shutdown();
        }
    }

    private Test runInstall(Report report, Path target, boolean client) {
        var ret = new TestInstall(report, client);
        var side = client ? "Client" : "Server";
        var mcVer = report.profile().getMinecraftVersion();

        report.log().info("Installing %s", side);
        try (var l = report.log().push()) {
            var installerJar = report.installer().toAbsolutePath().toString();

            var version = this.mcCache.getVersion(mcVer);
            var javaHome = version == null ? null : this.disco.find(lts(version.getJavaVersion(mcVer)));

            if (javaHome == null)
                return ret.fail("Failed to find Java install");

            if (client) {
                sneak(() -> Files.write(target.resolve("launcher_profiles.json"),
                    "{ \"profiles\": { } }".getBytes(StandardCharsets.UTF_8))
                );
            }

            int installerExit = Utils.runWaitFor(builder -> {
                ArrayList<String> args = new ArrayList<>(asList(
                    getJavaExecutable(javaHome).toString(),
                    "-jar",
                    installerJar,
                    "--install" + side,
                    target.toAbsolutePath().toString()
                ));

                if (report.profile().getFormat() == InstallerFormat.V2) {
                    args.add("--mirror");
                    args.add(InstallerRewriter.FORGE_MAVEN);
                }

                builder.command(args);
                builder.directory(target.toFile());

                ret.log("Running: " + builder.command().stream().collect(Collectors.joining(" ")));
                ret.log("Working dir: " + target.toAbsolutePath().toString());
                ret.log("");
            }, ret::log);

            if (installerExit == 0)
                ret.success();
            else
                ret.fail("Exit Code %d", installerExit);

            var verPattern = Pattern.quote(report.version());
            Files.walk(target)
                .filter(Files::isRegularFile)
                .map(p -> target.relativize(p).toString().replace('\\', '/').replaceAll(verPattern, "\\[VERSION\\]"))
                .forEach(ret::addFile);
        } catch (Throwable e) {
            ret.error(e);
            report.log().error("Failed %s", e.getMessage());
        }

        return ret;
    }

    private Test runServer(Report report, Path root) {
        var ret = new TestRunServer(report);
        report.log().info("Running Server");
        try (var l = report.log().push()) {
            var serverJar = report.profile().getExecutableJar();

            Files.write(root.resolve("eula.txt"), asList("eula=true"));
            Files.write(root.resolve("server.properties"), asList("server-port=" + serverPort.get()));

            AtomicBoolean finishedStarting = new AtomicBoolean(false);

            var mcVer = report.profile().getMinecraftVersion();
            var version = this.mcCache.getVersion(mcVer);
            var javaHome = version == null ? null : this.disco.find(lts(version.getJavaVersion(mcVer)));
            var javaExe = getJavaExecutable(javaHome).toString();
            List<String> args;

            if (serverJar != null)
                args = asList(javaExe, "-jar", serverJar, "nogui");
            else if (OS.CURRENT == OS.WINDOWS)
                args = asList("cmd", "/c", "run.bat", "nogui");
            else
                args = asList("sh", "-c", "run.sh", "nogui");

            int exit = Utils.runTimeout(60, builder -> {
                builder
                    .command(args)
                    .directory(root.toFile());

                var env = builder.environment();
                env.put("JAVA_HOME", javaHome.getAbsolutePath());
                env.put("PATH",
                    javaHome.getAbsolutePath() + File.separator + "bin"
                    + File.pathSeparator
                    + System.getenv("PATH")
                );

                ret.log("Running: " + builder.command().stream().collect(Collectors.joining(" ")));
                ret.log("Working dir: " + root.toAbsolutePath().toString());
                ret.log("");
            }, (proc, line) -> {
                ret.log(line);
                //System.out.println(line);
                if (line.contains("Starting Minecraft server on") || (line.contains("Done") && line.contains("\"help\""))) {
                    finishedStarting.set(true);
                    Utils.killProcess(proc);
                }
            });

            if (finishedStarting.get())
                ret.success();
            else if (exit == -12345) // Timeout
                ret.fail("Timed out");
            else if (exit != 0)
                ret.fail("Exit code %d", exit);
            else
                ret.success();

            List<String> runLog = ret.getLog();
            for (int i = 0; i < runLog.size(); i++) {
                String s = runLog.get(i);
                /*
                // We run with the version of java the client asks for, so this shouldn't ever happen, we could add a test for this specifically
                // If we have the start of a LJF stack trace, this version requires it.
                if (s.contains("java.util.ConcurrentModificationException")) {
                    String sNext = i + 1 == runLog.size() ? null : runLog.get(i + 1);
                    ret.requiresLegacyJavaFixer = sNext != null && sNext.contains("at java.util.ArrayList$Itr.checkForComodification(ArrayList");
                    ret.exit = "Requires Legacy Java Fixer";
                    break;
                } else */
                if (serverJar != null && s.contains("Could not find or load main class net.minecraft.server.MinecraftServer")) {
                    try (JarFile jar = new JarFile(root.resolve(serverJar).toFile())) {
                        String classpath = jar.getManifest() == null ? "" : jar.getManifest().getMainAttributes().getValue("Class-Path");
                        List<String> missing = new ArrayList<>();

                        for (String dep : classpath.split(" ")) {
                            Path depPath = root.resolve(dep);
                            if (!Files.exists(depPath)) {
                                missing.add(dep);
                                if (dep.contains("minecraft_server")) {
                                    report.log().info("Incorrect server jar: %s", dep);
                                    ret.incorrectServerJar(dep);
                                }
                            }
                        }

                        if (!missing.isEmpty()) {
                            ret.fail("Missing dependencies: " + String.join(", ", missing));
                        }
                    }
                }
            }
        } catch (Throwable e) {
            ret.error(e);
        }

        return ret;
    }

    private static File getJavaExecutable(File javaHome) {
        return new File(javaHome, "bin/java" + OS.CURRENT.exe()).getAbsoluteFile();
    }

    private boolean copyVanilla(Report report, Path root, boolean client) {
        var key = client ? "client" : "server";
        var profile = report.profile();
        var mcver = profile.getMinecraftVersion();
        var cacheJar = mcCache.getDownload(mcver, key);

        var tokens = new HashMap<String, Supplier<String>>();
        tokens.put("MINECRAFT_VERSION", profile::getMinecraftVersion);
        tokens.put("ROOT",        root.toFile()::getAbsolutePath);
        tokens.put("LIBRARY_DIR", root.resolve("libraries").toFile()::getAbsolutePath);

        Path target = Paths.get(Utils.replaceTokens(tokens, profile.getVanillaJarPath(client)));

        try {
            mkdirs(target.getParent());
            Files.copy(cacheJar, target, StandardCopyOption.REPLACE_EXISTING);

            if (client) {
                var json = mcCache.getVersionJson(mcver);
                if (json != null) {
                    target = root.resolve("versions").resolve(mcver).resolve(mcver + ".json");
                    Files.copy(json, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        } catch (IOException e) {
            report.log().error("Failed to download %s jar", key, e);
            return false;
        }
    }

    private boolean copyLibraries(Report report, Path install, boolean client) {
        var libraries = report.profile().getLibraries(client);
        var log = report.log();
        var installL = install.resolve("libraries");

        for (Library lib : libraries) {
            var dl = lib.downloads == null ? null : lib.downloads.artifact;
            if (dl != null && dl.url != null && !dl.url.isEmpty()) {
                var artifact = cleanArtifact(report.log(), report.version(), lib.name.toString(), dl);
                Path path = null;
                if (dl.url.startsWith(MOJANG_MAVEN)) {
                    path = this.mcCache.download(artifact);
                    if (path == null) // Mojang maven is down, try the forge one.
                        path = this.maven.download(artifact);
                } else if (dl.url.startsWith(this.maven.url())) {
                    path = this.maven.download(artifact);
                }

                if (path == null) {
                    log.error("Failed to download library %s", artifact);
                    return false;
                }

                Path target = installL.resolve(dl.path);
                try {
                    mkdirs(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.error("Failed to copy library %s", lib.name);
                    return false;
                }
            }
        }

        return true;
    }
}
