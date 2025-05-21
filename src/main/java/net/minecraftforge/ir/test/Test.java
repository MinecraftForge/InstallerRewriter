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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.zip.GZIPOutputStream;
import net.minecraftforge.ir.util.Utils;

abstract class Test {
    public record Name<T extends Test>(String name, int version, BiFunction<Report, Path, T> factory) {}
    public static final Name<TestInstall> INSTALL_SERVER = new Name<>("server-install", 1, TestInstall::loadServer);
    public static final Name<TestInstall> INSTALL_CLIENT = new Name<>("client-install", 1, TestInstall::loadClient);
    public static final Name<TestRunServer> RUN_SERVER = new Name<>("server-run", 1, TestRunServer::load);
    public static final List<Name<?>> TESTS = List.of(INSTALL_SERVER, INSTALL_CLIENT, RUN_SERVER);

    private final Report report;
    private final String name;
    protected final int version;

    protected boolean success = false;
    protected String message;
    protected Set<String> globals = new HashSet<>();

    private List<String> log = new ArrayList<>();

    protected static class CacheData {
        boolean success;
        String message;
        Collection<String> globals;
        int version;

        protected CacheData(int version, boolean success, String message, Collection<String> globals) {
            this.success = success;
            this.message = message;
            this.globals = globals;
            this.version = version;
        }

        protected void apply(Test t) {
            t.success = success;
            t.message = message;
            t.globals.clear();
            if (globals != null)
                t.globals.addAll(globals);
        }
    }

    protected Test(Report report, String name, int version) {
        this.report = report;
        this.name = name;
        this.version = version;
    }

    abstract Object getCacheData();

    public void save(Path path) {
        Utils.mkdirs(path);
        var data = getCacheData();
        try (var output = new FileWriter(path.resolve(name() + ".json").toFile())) {
            Utils.GSON.toJson(data, data.getClass(), output);
        } catch (IOException e) {
            report.log().error("Failed to write cache file", e);
        }

        if (!this.log.isEmpty()) {
            var log = path.resolve(name() + ".log.gz");
            try (var output = Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 var zip = new GZIPOutputStream(output)
            ){
                for (var line : this.log) {
                    zip.write(line.getBytes(StandardCharsets.UTF_8));
                    zip.write('\n');
                }
            } catch (IOException e) {
                report.log().error("Failed to write report %s", name(), e);
            }
        }
    }

    public Test success() {
        return this.success(null);
    }

    public Test success(String string, Object... args) {
        this.success = true;
        this.message = string == null ? null : String.format(string, args);
        return this;
    }

    public Test fail(String string, Object... args) {
        this.success = false;
        if (string == null) {
            this.message = null;
        } else {
            this.message = String.format(string, args);
            report.log().error(this.message);
        }
        return this;
    }

    public Test error(Throwable t) {
        success = false;
        var sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(sw.toString());
        return this;
    }

    public String message() {
        return message;
    }

    public String name() {
        return this.name;
    }

    public int version() {
        return this.version;
    }

    public boolean isSuccess() {
        return success;
    }

    public List<String> getLog() {
        return log;
    }

    public synchronized void log(String line) {
        log.add(line);
    }

    public Collection<String> globals() {
        return globals == null ? Collections.emptyList() : new TreeSet<>(globals);
    }
}