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
package net.minecraftforge.ir.json;

import com.google.gson.JsonObject;
import net.covers1624.quack.maven.MavenNotation;
import net.minecraftforge.ir.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.versioning.ComparableVersion;

//Mostly copied from the Installer, except easily mutable.
public class Version {
    public static Version read(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            return read(stream);
        }
    }

    public static Version read(InputStream stream) throws IOException {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return Utils.GSON.fromJson(reader, Version.class);
        }
    }

    public List<String> _comment_;

    public String id;
    public String time;
    public String releaseTime;
    public String type;
    public String mainClass;
    public String inheritsFrom;
    public JsonObject logging;
    public String minecraftArguments;
    public Map<String, Download> downloads;
    public List<Library> libraries;
    public JavaVersion javaVersion;

    public Map<String, Download> getDownloads() {
        if (downloads == null) {
            downloads = new HashMap<>();
        }
        return downloads;
    }

    public List<Library> getLibraries() {
        if (libraries == null) {
            libraries = new ArrayList<>();
        }
        return libraries;
    }

    private static final ComparableVersion MC_1_17 = new ComparableVersion("1.17");
    private static final ComparableVersion MC_1_18 = new ComparableVersion("1.18");
    private static final ComparableVersion MC_1_20_5 = new ComparableVersion("1.20.5");
    public int getJavaVersion(String version) {
        var ret = javaVersion == null ? 0 : javaVersion.majorVersion;
        if (ret != 0) return ret;
        var ver = new ComparableVersion(version);
        if (ver.compareTo(MC_1_20_5) >= 0) return 21;
        if (ver.compareTo(MC_1_18) >= 0) return 17;
        if (ver.compareTo(MC_1_17) >= 0) return 16;
        return 8;
    }

    public static class JavaVersion {
        public String component;
        public int majorVersion;
    }

    public static class Download {

        public String url;
        public String sha1;
        public Integer size;

        @Override
        public String toString() {
            return "Download[" + this.sha1 + ", " + this.size + ", " + this.url + "]";
        }
    }

    public static class LibraryDownload extends Download {
        public String path;

        @Override
        public String toString() {
            return this.path;
        }
    }

    public static class Library {

        public MavenNotation name;
        public Downloads downloads;

        @Override
        public String toString() {
            return this.name.toString();
        }
    }

    public static class Downloads {

        public LibraryDownload artifact;
        public Map<String, LibraryDownload> classifiers;

        public Map<String, LibraryDownload> getClassifiers() {
            if (classifiers == null) {
                classifiers = new HashMap<>();
            }
            return classifiers;
        }
    }
}
