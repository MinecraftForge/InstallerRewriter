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
package net.minecraftforge.ir.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonSyntaxException;

import net.minecraftforge.ir.InstallerRewriter;
import net.minecraftforge.ir.json.Manifest;
import net.minecraftforge.ir.json.Version;
import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.hash.HashFunction;

public class MinecraftCache extends MavenCache {
    private static final String VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final long TIMEOUT = TimeUnit.HOURS.toMillis(24);

    private final Log logger;
    private final Path root;

    private Manifest manifest = null;
    private Map<String, Path> jsons = new ConcurrentHashMap<>();
    private Map<String, Version> versions = new ConcurrentHashMap<>();
    private Map<String, Path> downloads = new ConcurrentHashMap<>();

    public MinecraftCache(Log log, Path root) {
        super(log, "libraries", InstallerRewriter.MOJANG_MAVEN, root);
        this.logger = log;
        this.root = root;
    }

    private static boolean validCache(Path path, String hash) {
        try {
            if (!Files.exists(path))
                return false;
            if (Files.getLastModifiedTime(path).toMillis() >= System.currentTimeMillis() - TIMEOUT)
                return true;
            if (hash == null)
                return false;
            var chash = HashFunction.SHA1.hash(path.toFile());
            return chash.equals(hash);
        } catch (IOException e) {
            return false;
        }
    }

    // Mojang uses buckets, which include the hash in the path, so pull it out
    private static String getHash(String url) {
        int idx1 = url.lastIndexOf('/');
        int idx2 = url.lastIndexOf('/', idx1 - 1);
        return url.substring(idx2, idx1);
    }

    public synchronized Manifest getLauncherManifest() {
        if (this.manifest != null)
            return this.manifest;

        var file = root.resolve("version_manifest.json");
        try {
            if (!validCache(file, null))
                DownloadUtils.downloadFile(file.toFile(), VERSION_MANIFEST);

            if (this.manifest == null) {
                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    this.manifest = Utils.GSON.fromJson(reader, Manifest.class);
                }
            }

            return this.manifest;
        } catch (IOException e) {
            logger.error("Failed to download launcher manifest", e);
            return null;
        }
    }

    private Path versionPath(String version, String file) {
        return root.resolve("versions").resolve(version).resolve(version + file);
    }

    public Path getVersionJson(String version) {
        return this.jsons.computeIfAbsent(version, this::computeVersionJson);
    }

    private Path computeVersionJson(String version) {
        var file = versionPath(version, ".json");
        try {
            var launcher = getLauncherManifest();
            var url = launcher.getUrl(version);
            var hash = getHash(url);

            if (!validCache(file, hash))
                DownloadUtils.downloadFile(true, file.toFile(), url);

            return file;
        } catch (IOException e) {
            logger.error("Failed to download version %s json", version, e);
            return null;
        }
    }

    public Version getVersion(String version) {
        return this.versions.computeIfAbsent(version, this::computeVersion);
    }

    private Version computeVersion(String version) {
        var file = getVersionJson(version);
        if (file == null)
            return null;

        try {
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                return Utils.GSON.fromJson(reader, Version.class);
            }
        } catch (JsonSyntaxException e) {
            logger.error("Failed to download version %s json, corrupt file", version, e);
            try {
                Files.delete(file);
                this.jsons.remove(version);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            return null;
        } catch (IOException e) {
            logger.error("Failed to load version %s json", version, e);
            return null;
        }
    }

    public boolean exists(String version, String suffix) {
        return Files.exists(versionPath(version, '-' + suffix));
    }

    public Path getDownload(String version, String key) {
        return this.downloads.computeIfAbsent(version + '-' + key, k -> computeDownload(version, key));
    }

    private Path computeDownload(String version, String key) {
        var json = getVersion(version);
        if (json == null || json.downloads == null || !json.downloads.containsKey(key))
            return null;

        var dl = json.downloads.get(key);
        int idx = dl.url.lastIndexOf('.');
        var ext = dl.url.substring(idx);

        var path = versionPath(version, '-' + key + ext);

        if (Files.exists(path)) {
            if (dl.sha1 == null || dl.sha1.isEmpty())
                return path;
            try {
                var hash = HashFunction.SHA1.hash(path.toFile());
                if (hash.equals(dl.sha1))
                    return path;
            } catch (IOException e) {
                return path;
            }
        }

        try {
            DownloadUtils.downloadFile(true, path.toFile(), dl.url);
            return path;
        } catch (IOException e) {
            logger.error("Failed to download version %s %s", version, key, e);
            return null;
        }
    }
}
