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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.covers1624.quack.maven.MavenNotation;
import net.minecraftforge.ir.InstallerFormat;
import net.minecraftforge.ir.json.Version.Library;
import net.minecraftforge.ir.util.Utils;

public class InstallProfileV2 implements InstallProfile {
    private final JsonObject json;
    private final Version version;

    public InstallProfileV2(JsonObject json, Path root) throws IOException{
        this.json = json;
        this.version = Version.read(root.resolve(getJson()));
    }

    private String getString(String name) {
        var value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private int getInt(String name) {
        var value = json.get(name);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    public int getSpec() {
        return getInt("spec");
    }

    public String getVersion() {
        return getString("version");
    }

    public String getPath() {
        return getString("path");
    }

    @Override
    public String getMinecraftVersion() {
        return getString("minecraft");
    }

    @Override
    public String getVanillaJarPath(boolean client) {
        if (client)
            return "{ROOT}/versions/{MINECRAFT_VERSION}/{MINECRAFT_VERSION}.jar";

        if (!json.has("serverJarPath"))
            return "{ROOT}/minecraft_server.{MINECRAFT_VERSION}.jar";

        return json.get("serverJarPath").getAsString();
    }

    @Override
    public String getExecutableJar() {
        String path = getPath();
        return path == null ? null : MavenNotation.parse(path).toFileName();
    }

    @Override
    public InstallerFormat getFormat() {
        return InstallerFormat.V2;
    }

    public String getJson() {
        return getString("json");
    }

    @SuppressWarnings("unused")
    public List<Library> getLibraries(boolean client) {
        List<Version.Library> libs = new ArrayList<>();

        libs.addAll(this.version.getLibraries());

        if (!json.has("libraries") || !json.has("processors"))
            return libs;

        Set<MavenNotation> names = new HashSet<>();

        for (JsonElement entry : json.get("processors").getAsJsonArray()) {
            JsonObject obj = entry.getAsJsonObject();
            boolean rightSide = false;

            if (!obj.has("sides")) {
                rightSide = true;
            } else {
                for (JsonElement s : obj.get("sides").getAsJsonArray()) {
                    if (client && s.getAsString().equals("client"))
                        rightSide = true;
                    else if (!client && s.getAsString().equals("server"))
                        rightSide = true;
                }
            }

            rightSide = true; // TODO: [InstallerRewriter] Currently the installer doesn't filter the libraries, so don't do it here.
            if (!rightSide)
                continue;

            for (JsonElement e : obj.get("classpath").getAsJsonArray()) {
                MavenNotation artifact = MavenNotation.parse(e.getAsString()).withClassifier(null).withExtension("jar");
                names.add(artifact);
            }
        }

        if (names.isEmpty())
            return libs;

        for (JsonElement entry : json.get("libraries").getAsJsonArray()) {
            Version.Library lib = Utils.GSON.fromJson(entry, Version.Library.class);
            MavenNotation artifact = lib.name.withClassifier(null).withExtension("jar");

            if (false && !names.contains(artifact)) // TODO: [InstallerRewriter] Currently the installer doesn't filter the libraries, so don't do it here.
                continue;

            libs.add(lib);
        }

        return libs;
    }
}


