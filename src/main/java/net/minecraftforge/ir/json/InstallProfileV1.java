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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import net.covers1624.quack.maven.MavenNotation;
import net.minecraftforge.ir.InstallerFormat;
import net.minecraftforge.ir.InstallerRewriter;
import net.minecraftforge.ir.util.JarContents;
import net.minecraftforge.ir.util.Utils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class InstallProfileV1 implements InstallProfile {
    public static InstallProfileV1 read(JarContents contents) throws IOException {
        try (Reader reader = new InputStreamReader(contents.getInput(INSTALL_PROFILE), StandardCharsets.UTF_8)) {
            return Utils.GSON.fromJson(reader, InstallProfileV1.class);
        }
    }

    public Install install;
    public VersionInfo versionInfo;
    public List<OptionalLibrary> optionals;

    @Override
    public String getMinecraftVersion() {
        return install.minecraft;
    }

    @Override
    public String getVanillaJarPath(boolean client) {
        return client
            ? "{ROOT}/versions/{MINECRAFT_VERSION}/{MINECRAFT_VERSION}.jar"
            : "{ROOT}/minecraft_server.{MINECRAFT_VERSION}.jar";
    }

    @Override
    public String getExecutableJar() {
        return install.filePath;
    }

    @Override
    public List<Version.Library> getLibraries(boolean client) {
        List<Version.Library> libs = new ArrayList<>();
        for (Library lib : versionInfo.libraries) {
            if (!lib.validFor(client))
                continue;

            Version.Library ret = new Version.Library();
            ret.name = lib.name;
            ret.downloads = new Version.Downloads();
            ret.downloads.artifact = new Version.LibraryDownload();
            ret.downloads.artifact.path = lib.name.toPath();
            ret.downloads.artifact.url = lib.url() + lib.name.toPath();

            if (lib.url() != null)
                libs.add(ret);
        }

        if (optionals == null)
            return libs;

        for (OptionalLibrary opt : optionals) {
            if (!opt.valid())
                continue;

            Version.Library ret = new Version.Library();
            ret.name = MavenNotation.parse(opt.artifact);
            ret.downloads = new Version.Downloads();
            ret.downloads.artifact = new Version.LibraryDownload();
            ret.downloads.artifact.path = ret.name.toPath();
            ret.downloads.artifact.url = opt.maven + ret.name.toPath();

            libs.add(ret);
        }

        return libs;
    }

    @Override
    public InstallerFormat getFormat() {
        return InstallerFormat.V1;
    }

    public static class Install {
        public String profileName;
        public String target;
        public String version;
        public String minecraft;
        public String mirrorList;
        public String path;
        public String modList;
        public String filePath;
        public String welcome;
        public String logo;
        public String urlIcon;
        public boolean stripMeta;
        public boolean hideClient;
        public boolean hideServer;
        public boolean hideExtract;
        public List<TransformInfo> transform;
    }

    public static class VersionInfo {
        public String id;
        public String time;
        public String releaseTime;
        public String type;
        public String minecraftArguments;
        public String mainClass;
        public String inheritsFrom;
        public String jar;

        public List<Library> libraries = new ArrayList<>();
        public JsonArray optionals;
    }

    public static class Library {
        public MavenNotation name;
        public String url;
        public List<String> checksums = new ArrayList<>();
        public Boolean serverreq;
        public Boolean clientreq;
        public JsonArray rules;
        public JsonObject natives;
        public JsonObject extract;

        public boolean validFor(boolean client) {
            return client
                ? (clientreq != null && clientreq)
                : (serverreq != null && serverreq);
        }

        public String url() {
            if (url == null)
                return InstallerRewriter.MOJANG_MAVEN;
            if (url.isEmpty())
                return null;
            return InstallerRewriter.FORGE_MAVEN;
        }
    }

    public static class OptionalLibrary {
        public String name;
        public String artifact;
        public String maven;
        public boolean client;
        public boolean server;
        @SerializedName("default")
        public boolean _default;
        public boolean inject;
        public String desc;
        public String url;

        public boolean valid() {
            return name != null && artifact != null && maven != null;
        }
    }

    public static class TransformInfo {
        public String side;
        public String input;
        public MavenNotation output;
        public String map;
        public boolean append;
        public String maven;
    }
}