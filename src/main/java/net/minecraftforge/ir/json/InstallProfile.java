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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;

import net.minecraftforge.ir.InstallerFormat;
import net.minecraftforge.ir.json.Version.Library;
import net.minecraftforge.ir.util.Utils;

public interface InstallProfile {
    public static final String INSTALL_PROFILE = "install_profile.json";

    @SuppressWarnings("unchecked")
    public static InstallProfile read(Path path) throws IOException {
        try (var fs = FileSystems.newFileSystem(path)) {
           var root = fs.getPath("/");
           var profilePath = root.resolve(INSTALL_PROFILE);

           if (!Files.exists(profilePath))
               return null;

           JsonObject profile = null;
           try (var stream = new InputStreamReader(Files.newInputStream(profilePath), StandardCharsets.UTF_8)) {
               profile = Utils.GSON.fromJson(stream, JsonObject.class);
           }

           Map<String, ?> map = Utils.GSON.fromJson(profile, Map.class);

           if (map.containsKey("install") && map.containsKey("versionInfo"))
               return Utils.GSON.fromJson(profile, InstallProfileV1.class);

           return new InstallProfileV2(Utils.GSON.fromJson(profile, JsonObject.class), root);
        }
    }

    public String getMinecraftVersion();
    public String getVanillaJarPath(boolean client);
    public String getExecutableJar();
    public List<Library> getLibraries(boolean client);
    public InstallerFormat getFormat();
}
