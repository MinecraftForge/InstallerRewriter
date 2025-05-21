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

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.Level;
import org.apache.maven.artifact.versioning.ComparableVersion;

import com.google.gson.JsonSyntaxException;

import net.minecraftforge.ir.InstallerFormat;
import net.minecraftforge.ir.json.InstallProfile;
import net.minecraftforge.ir.util.Artifact;
import net.minecraftforge.ir.util.Log;
import net.minecraftforge.ir.util.Utils;

public class Report {
    private static final String CACHE_JSON = "cache.json";

    private static Map<String, Integer> TESTS = Map.of(
        Test.INSTALL_CLIENT.name(), Test.INSTALL_CLIENT.version(),
        Test.INSTALL_SERVER.name(), Test.INSTALL_SERVER.version(),
        Test.RUN_SERVER.name(), Test.RUN_SERVER.version()
    );

    private final Artifact notation;
    private final String hash;

    private final Set<String> globals = new HashSet<>();
    public final Map<String, Integer> tests = new ConcurrentHashMap<>();
    private final Map<String, Test> reports = new ConcurrentHashMap<>();
    public record LogLine(Level level, String message, Object[] args) {};
    private final Log log = new Log(new Log.Handler() {
        @Override
        public void log(Level level, String message) {
            logLines.add(new LogLine(level, message, null));
        }

        @Override
        public void log(Level level, String message, Object[] args) {
            logLines.add(new LogLine(level, message, args));
        }
    });

    private List<LogLine> logLines = new ArrayList<>();
    private Path installer;
    private InstallProfile profile;
    private InstallerFormat format;
    private String serverJar;

    Report(Artifact notation, String hash) {
        this.notation = notation;
        this.hash = hash;
    }

    public Artifact artifact() {
        return notation;
    }

    public Log log() {
        return log;
    }

    public String version() {
        return notation.getVersion();
    }

    public ComparableVersion cversion() {
        return this.notation.getComparableVersion();
    }

    public String hash() {
        return hash;
    }

    public Path installer() {
        return installer;
    }

    public void installer(Path value) {
        this.installer = value;
    }

    public InstallProfile profile() {
        return profile;
    }

    public void profile(InstallProfile value) {
        this.profile = value;
        this.serverJar = this.profile.getExecutableJar();
        format(this.profile.getFormat());
    }

    public void format(InstallerFormat format) {
        this.format = format;
    }

    public InstallerFormat format() {
        return this.format;
    }

    public void serverJar(String value) {
        this.serverJar = value;
    }

    public String serverJar() {
        return this.serverJar;
    }

    public boolean cached() {
        return TESTS.equals(tests);
    }

    public boolean cached(Test.Name<?> test) {
        return tests.containsKey(test.name()) && tests.get(test.name()) == test.version();
    }

    public int version(Test.Name<?> test) {
        var value = tests.get(test.name());
        return value == null ? 0 : value;
    }

    @SuppressWarnings("unchecked")
    public <T extends Test> T get(Test.Name<T> test) {
        if (test == null) return null;
        return (T)reports.get(test.name());
    }

    public List<Test> tests() {
        return this.reports.values().stream().toList();
    }

    public Collection<String> globals() {
        return globals == null ? Collections.emptyList() : new TreeSet<>(globals);
    }

    public void save(Path root) {
        Utils.mkdirs(root);
        try (var output = new FileWriter(root.resolve(CACHE_JSON).toFile())) {
            var data = new CacheData(notation, hash, globals, tests, format, serverJar);
            Utils.GSON.toJson(data, CacheData.class, output);
        } catch (IOException e) {
            InstallerTester.LOGGER.error("Failed to write cache file", e);
        }
    }

    public static Report load(Log log, Path root) {
        return CacheData.load(log, root);
    }

    public boolean isSuccess() {
        return reports.values().stream().allMatch(Test::isSuccess);
    }

    public Test add(Test value) {
        if (value != null) {
            reports.put(value.name(), value);
            tests.put(value.name(), value.version());
        }
        return value;
    }

    public void addGlobal(String name) {
        globals.add(name);
    }

    public List<LogLine> logLines() {
        var ret = logLines;
        this.logLines = new ArrayList<>();
        return ret;
    }

    public void dumpLog(Log logger) {
        for (var line : logLines()) {
            if (line.args() == null)
                logger.log(line.level(), line.message());
            else
                logger.log(line.level(), line.message(), line.args());
        }
    }

    private record CacheData(
        Artifact notation,
        String hash,
        Set<String> globals,
        Map<String, Integer> tests,
        InstallerFormat format,
        String serverJar
    ) {
        public static Report load(Log log, Path path) {
            var cache = path.resolve(CACHE_JSON);
            if (!Files.exists(cache))
                return null;

            try (Reader reader = new InputStreamReader(Files.newInputStream(cache), StandardCharsets.UTF_8)) {
                var data = Utils.GSON.fromJson(reader, CacheData.class);
                var ret = new Report(data.notation, data.hash);
                if (data.globals != null)
                    ret.globals.addAll(data.globals);
                if (data.tests != null)
                    ret.tests.putAll(data.tests);
                for (var name : Test.TESTS)
                    ret.add(name.factory().apply(ret, path));
                if (data.format != null)
                    ret.format(data.format);
                ret.serverJar = data.serverJar;
                return ret;
            } catch (IOException | JsonSyntaxException e) {
                log.error("Failed to read cache file %s", path, e);
                return null;
            }
        }
    }
}