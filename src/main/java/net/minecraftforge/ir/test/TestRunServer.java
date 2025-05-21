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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import com.google.gson.JsonSyntaxException;

import net.minecraftforge.ir.util.Utils;

class TestRunServer extends Test {
    private static final String GLOBAL_LJF = "legacy_java_fixer";
    private static final String GLOBAL_ISJ = "incorrect_server_jar";

    public static TestRunServer load(Report report, Path path) {
        return CacheData.load(report, path);
    }

    private boolean requiresLegacyJavaFixer;
    private String incorrectServerJar;

    TestRunServer(Report report) {
        this(report, Test.RUN_SERVER.version());
    }

    private TestRunServer(Report report, int version) {
        super(report, Test.RUN_SERVER.name(), version);
    }

    @Override
    Object getCacheData() {
        return new CacheData(version, success, message, globals, requiresLegacyJavaFixer, incorrectServerJar);
    }

    public void needsLegacyJavaFixer() {
        this.requiresLegacyJavaFixer = true;
        this.globals.add(GLOBAL_LJF);
    }

    public void incorrectServerJar(String value) {
        if (value == null)
            this.globals.remove(GLOBAL_ISJ);
        else
            this.globals.add(GLOBAL_ISJ);
        this.incorrectServerJar = value;
    }

    protected static class CacheData extends Test.CacheData {
        protected boolean legacy_java_fixer;
        protected String incorrect_server_jar;

        protected CacheData(int version, boolean success, String message, Collection<String> globals, boolean ljf, String jar) {
            super(version, success, message, globals);
            this.legacy_java_fixer = ljf;
            this.incorrect_server_jar = jar;
        }

        private static TestRunServer load(Report report, Path path) {
            convert(report, path);

            var cache = path.resolve(RUN_SERVER.name() + ".json");
            if (!Files.exists(cache))
                return null;

            try (Reader reader = new InputStreamReader(Files.newInputStream(cache), StandardCharsets.UTF_8)) {
                var data = Utils.GSON.fromJson(reader, CacheData.class);
                var instance = new TestRunServer(report, data.version == 0 ? 1 : data.version);
                data.apply(instance);
                return instance;
            } catch (IOException | JsonSyntaxException e) {
                report.log().error("Failed to read cache file %s", path, e);
                return null;
            }
        }

        protected void apply(TestRunServer instance) {
            if (this.legacy_java_fixer)
                instance.needsLegacyJavaFixer();
            if (this.incorrect_server_jar != null)
                instance.incorrectServerJar(this.incorrect_server_jar);
            super.apply(instance);
        }
    }



    // Old, Kill when I nuke my local data
    private static void convert(Report report, Path path) {
        var old = path.resolve("server-run.txt");
        if (!Files.exists(old))
            return;

        try {
            var lines = Files.readAllLines(old, StandardCharsets.UTF_8);
            Boolean success = null;
            String message = null;
            boolean inLog = false;

            var ret = new TestRunServer(report, 1);
            for (var line : lines) {
                if (success == null && line.startsWith("Success: "))
                    success = Boolean.parseBoolean(line.substring(9));
                if (message == null && line.startsWith("Reason:  "))
                    message = line.substring(9);

                if (!inLog && "Needs Legacy Java Fixer".equals(line)) {
                    ret.requiresLegacyJavaFixer = true;
                } else if (!inLog && line.startsWith("Incorrect Server Jar: ")) {
                    ret.incorrectServerJar = line.substring(22);
                } else if (!inLog && "Log:".equals(line)) {
                    inLog = true;
                } else if (inLog) {
                    ret.log(line);
                }
            }

            if (message != null && ("Success".equals(message) || message.isEmpty()))
                message = null;

            if (success == null || success)
                ret.success(message);
            else
                ret.fail(message);

            ret.save(path);
            Files.delete(old);
        } catch (IOException e) {
            report.log().error("Failed to read cache file %s", path, e);
        }
    }
}