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
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonSyntaxException;

import net.minecraftforge.ir.util.Utils;

class TestInstall extends Test {
    public static TestInstall loadServer(Report report, Path path) {
        return CacheData.load(report, path, false);
    }

    public static TestInstall loadClient(Report report, Path path) {
        return CacheData.load(report, path, true);
    }

    private Set<String> files = new TreeSet<String>();
    private String failedHash = null;

    TestInstall(Report report, boolean client) {
        this(report, client ? INSTALL_CLIENT : INSTALL_SERVER);
    }

    private TestInstall(Report report, Test.Name<TestInstall> name) {
        this(report, name, name.version());
    }

    private TestInstall(Report report, Test.Name<TestInstall> name, int version) {
        super(report, name.name(), version);
    }

    public void addFile(String file) {
        this.files.add(file);
    }

    public void failedHash(String hash) {
        this.failedHash = hash;
    }

    public String failedHash() {
        return this.failedHash;
    }

    @Override
    Object getCacheData() {
        return new CacheData(version, success, message, globals, files, failedHash);
    }

    protected static class CacheData extends Test.CacheData {
        Collection<String> files;
        String failedHash;

        protected CacheData(int version, boolean success, String message, Collection<String> globals,
            Collection<String> files, String failedHash
        ) {
            super(version, success, message, globals);
            this.files = files;
            this.failedHash = failedHash;
        }

        private static TestInstall load(Report report, Path path, boolean client) {
            convert(report, path, client);

            var name = client ? INSTALL_CLIENT : INSTALL_SERVER;
            var cache = path.resolve(name.name() + ".json");
            if (!Files.exists(cache))
                return null;

            try (Reader reader = new InputStreamReader(Files.newInputStream(cache), StandardCharsets.UTF_8)) {
                var data = Utils.GSON.fromJson(reader, CacheData.class);
                var instance = new TestInstall(report, name, data.version == 0 ? 1 : data.version);
                data.apply(instance);
                return instance;
            } catch (IOException | JsonSyntaxException e) {
                report.log().error("Failed to read cache file %s", path, e);
                return null;
            }
        }

        protected void apply(TestInstall instance) {
            if (files != null)
                files.forEach(instance::addFile);
            if (failedHash != null)
                instance.failedHash(failedHash);
            super.apply(instance);
        }
    }






    // Old, Kill when I nuke my local data
    private static void convert(Report report, Path path, boolean client) {
        var name = client ? INSTALL_CLIENT : INSTALL_SERVER;
        var old = path.resolve(name.name() + ".txt");
        if (!Files.exists(old))
            return;

        try {
            var lines = Files.readAllLines(old, StandardCharsets.UTF_8);
            Boolean success = null;
            String message = null;
            Boolean inFiles = null;
            boolean inLog = false;

            var ret = new TestInstall(report, name, 1);
            for (var line : lines) {
                if (success == null && line.startsWith("Success: "))
                    success = Boolean.parseBoolean(line.substring(9));
                if (message == null && line.startsWith("Reason:  "))
                    message = line.substring(9);

                if (inFiles == null && "Files:".equals(line)) {
                    inFiles = true;
                } else if (inFiles != null && inFiles) {
                    if (line.startsWith("  "))
                        ret.addFile(line.substring(2));
                    else if (line.isEmpty())
                        inFiles = false;
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