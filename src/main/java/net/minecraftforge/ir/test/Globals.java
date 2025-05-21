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

import static net.minecraftforge.ir.util.Utils.mkdirs;
import static net.minecraftforge.ir.util.Utils.sneak;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.artifact.versioning.ComparableVersion;

import net.minecraftforge.ir.InstallerFormat;
import net.minecraftforge.ir.util.Log;

class Globals {
    private static final String GLOBAL_MISSING = "no_installer";
    private static final String GLOBAL_V1 = "v1";
    private static final String GLOBAL_V2 = "v2";
    private static final String GLOBAL_FAILS = "failures";
    private static final String GLOBAL_NO_SERVER_JAR = "no_server_jar";

    private final Map<String, Set<ComparableVersion>> globals = new HashMap<>();
    private final Log log;
    private final Path root;

    Globals(Log log, Path root) {
        this.log = log;
        this.root = root.resolve("_global");
    }

    public void clear(ComparableVersion version) {
        globals.values().forEach(s -> s.remove(version));
    }

    public void add(String key, ComparableVersion version) {
        globals.computeIfAbsent(key, k -> new TreeSet<>()).add(version);
    }

    public void missing(ComparableVersion version) {
        add(GLOBAL_MISSING, version);
    }

    public void fail(ComparableVersion version) {
        add(GLOBAL_FAILS, version);
    }

    public void noServerJar(ComparableVersion version) {
        add(GLOBAL_NO_SERVER_JAR, version);
    }

    public void format(ComparableVersion version, InstallerFormat format) {
        if (format == InstallerFormat.V1)
            add(GLOBAL_V1, version);
        else if (format == InstallerFormat.V2)
            add(GLOBAL_V2, version);
        else
            log.warn("Unknown installer version for %s: %s", version, format);
    }

    public Globals load() {
        if (!Files.exists(root))
            return this;

        for (var file : sneak(() -> Files.list(root).toList())) {
            var name = file.getFileName().toString();
            if (!name.endsWith(".txt"))
                continue;

            var global = name.substring(0, name.length() - 4);

            try {
                var set = new TreeSet<ComparableVersion>();
                globals.put(global, set);

                var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (var line : lines)
                    set.add(new ComparableVersion(line));
            } catch (IOException e) {
                log.error("Failed to read global %s", global, e);
            }
        }

        return this;
    }

    public void save() {
        mkdirs(root);

        for (var key : globals.keySet()) {
            var file = root.resolve(key + ".txt");

            try (
                var writer = new PrintWriter(Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            ) {
                for (ComparableVersion version : globals.get(key))
                    writer.println(version);
            } catch (IOException e) {
                log.error("Failed to write global report for '%s'", key, e);
                sneak(e);
            }
        }

    }
}