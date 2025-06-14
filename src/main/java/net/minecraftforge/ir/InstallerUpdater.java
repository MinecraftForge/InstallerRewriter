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
package net.minecraftforge.ir;

import static net.minecraftforge.ir.util.JarContents.MANIFEST;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.stream.Collectors;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.artifact.versioning.ComparableVersion;

import com.google.gson.JsonObject;

import net.covers1624.quack.maven.MavenNotation;
import net.minecraftforge.ir.util.JarContents;
import net.minecraftforge.ir.util.Log;
import net.minecraftforge.ir.util.Utils;
import net.minecraftforge.util.download.DownloadUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;

class InstallerUpdater {
    private static final Log LOGGER = new Log();
    private static final String INSTALL_PROFILE = "install_profile.json";
    @SuppressWarnings("unchecked")
    private Set<String>[] blacklist = new Set[]{ new TreeSet<String>(), new TreeSet<String>() };
    private JarContents[] latestJars = new JarContents[2];

    boolean loadInstallerData(Path cache) {
        LOGGER.info("Download all installer seeds");
        List<String> versions = getVersions(cache);
        if (versions == null)
            return false;
        LOGGER.info("  Found " + versions.size() + " installers");
        for (String version : versions) {
            if (!processSeed(cache, version))
                return false;
        }

        for (int x = 0; x < blacklist.length; x++) {
            LOGGER.debug((x + 1) + ".x Blacklist:");
            blacklist[x].forEach(l -> LOGGER.debug("    " + l));
            LOGGER.debug("");
        }

        LOGGER.info("Resolving latest Forge installer..");
        String[] latest = new String[2];
        for (String ver : versions) {
            if (ver.startsWith("1."))
                latest[0] = ver;
            else if (ver.startsWith("2."))
                latest[1] = ver;
        }

        for (int x = 0; x < latest.length; x++) {
            LOGGER.info("Latest " + (x + 1) + ".x: " + latest[x]);
            Path path = cache.resolve(getInstallerBase(latest[x]));
            if (!Files.exists(path)) {
                LOGGER.error("Missing latest installer version: " + latest[x]);
                return false;
            }

            try {
                latestJars[x] = JarContents.loadJar(path.toFile());
            } catch (IOException e) {
                LOGGER.error("Failed to load " + path, e);
                return false;
            }
        }

        return true;
    }

    private List<String> getVersions(Path cache) {
        String root = "net/minecraftforge/installer/";
        String metadataPath = root + "maven-metadata.xml";
        Path metadataFile = cache.resolve(metadataPath);
        try {
            DownloadUtils.downloadFile(metadataFile.toFile(), InstallerRewriter.FORGE_MAVEN + metadataPath);
        } catch (IOException e) {
            LOGGER.error("Failed to download " + InstallerRewriter.FORGE_MAVEN + metadataPath, e);
            return null;
        }

        try {
            List<String> versions = Utils.parseVersions(metadataFile);
            Collections.sort(versions, (l, r) -> {
                ComparableVersion cl = new ComparableVersion(l);
                ComparableVersion cr = new ComparableVersion(r);
                return cl.compareTo(cr);
            });
            return versions;
        } catch (IOException e) {
            LOGGER.error("Failed to parse " + metadataFile, e);
            return null;
        }
    }

    private static String getInstallerBase(String version) {
        String path = "net/minecraftforge/installer/" + version + "/installer-" + version;
        if ("1.0".equals(version))
            path += ".jar";
        else if (version.startsWith("2.2"))
            path += "-fatjar.jar";
        else
            path += "-shrunk.jar";
        return path;
    }

    private boolean processSeed(Path cache, String version) {
        // 1.5-snapshot is a snapshot and honestly dont think it was used either, so maybe TODO?
        if (version.endsWith("-SNAPSHOT"))
            return true;

        String path = getInstallerBase(version);

        Path target = cache.resolve(path);
        try {
            DownloadUtils.downloadFile(cache.resolve(path).toFile(), InstallerRewriter.FORGE_MAVEN + path);
        } catch (IOException e) {
            LOGGER.error("Failed to download " + InstallerRewriter.FORGE_MAVEN + path, e);
            return false;
        }

        Set<String> bl = blacklist[version.startsWith("1.") ? 0 : 1];
        try (ZipFile zf = new ZipFile(target.toFile())) {
            Enumeration<? extends ZipEntry> enu = zf.entries();
            while (enu.hasMoreElements()) {
                ZipEntry ent = enu.nextElement();
                bl.add(ent.getName());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to parse " + target.toFile().getAbsolutePath(), e);
            return false;
        }
        return true;
    }

    private void add(Set<String> whitelist, JsonObject json, String key) {
        String data = Utils.getAsString(json, key, null);
        if (data != null)
            whitelist.add(JarContents.sanitize(data));
    }

    InstallerFormat post(MavenNotation installer, JarContents jar, InstallerFormat format, InstallerFormat originalFormat) throws IOException {
        if (!jar.contains(INSTALL_PROFILE))
            return format;

        JarContents newJar = this.latestJars[format == InstallerFormat.V1 ? 0 : 1];
        Set<String> whitelist = new HashSet<>();
        whitelist.add(INSTALL_PROFILE);
        whitelist.add(MANIFEST);

        JsonObject json = null;
        try (InputStream is = jar.getInput(INSTALL_PROFILE)) {
            json = Utils.GSON.fromJson(new InputStreamReader(is), JsonObject.class);
        } catch (IOException e) {
            LOGGER.error("Failed to parse " + INSTALL_PROFILE, e);
            return format;
        }

        switch (format) {
            case V1:
                JsonObject install = json.getAsJsonObject("install");
                add(whitelist, install, "logo");
                break;
            case V2:
                add(whitelist, json, "json");
                add(whitelist, json, "logo");
                break;
        }

        if (originalFormat != format || !newJar.sameData(jar, whitelist)) {
            Set<String> blacklist = this.blacklist[originalFormat == InstallerFormat.V1 ? 0 : 1];
            for (String file : jar.getFiles()) {
                if (blacklist.contains(file) && !whitelist.contains(file) && !JarContents.isSignature(file))
                    jar.delete(file);
            }

            jar.merge(newJar, false);
        }

        if (jar.contains(MANIFEST) && newJar.contains(MANIFEST)) { // Should always be true, but if not, then the above merge would of injected ours.
            boolean changed = false;
            Manifest oman = loadManifest(jar);
            Manifest nman = loadManifest(newJar);

            changed = merge(oman.getMainAttributes(), nman.getMainAttributes());
            for (Entry<String, Attributes> ent : nman.getEntries().entrySet()) {
                Attributes oattrs = oman.getAttributes(ent.getKey());
                if (oattrs == null) {
                    oman.getEntries().put(ent.getKey(), ent.getValue());
                    changed = true;
                } else
                    changed |= merge(oattrs, ent.getValue());
            }

            if (changed) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                try {
                    oman.write(os);
                    os.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                jar.write(MANIFEST, os.toByteArray());

                List<String> files = jar.getFiles().stream()
                .filter(JarContents::isSignature)
                .collect(Collectors.toList());
                files.forEach(jar::delete);
            }
        }
        return format;
    }

    private static boolean merge(Attributes left, Attributes right) {
        boolean changed = false;
        for (Object oKey : right.keySet()) {
            Name name = (Name)oKey;
            String existing = left.getValue(name);
            String expected = right.getValue(name);
            if (existing == null || !existing.equals(expected)) {
                changed = true;
                left.put(name, expected);
                changed = true;
            }
        }
        return changed;
    }

    private static Manifest loadManifest(JarContents jar) {
        try (InputStream is = jar.getInput(MANIFEST)) {
            return new Manifest(is);
        } catch (IOException e) {
            LOGGER.error("Failed to read " + MANIFEST, e);
            return null;
        }
    }

    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .readTimeout(Duration.ofMinutes(5))
            .connectTimeout(Duration.ofMinutes(5))
            .build();
    private static final Map<String, Boolean> recentHeadRequests = new HashMap<>();
    public static boolean headRequest(URL url) throws IOException {
        // OkHttp does not handle the file protocol.
        if (url.getProtocol().equals("file")) {
            try {
                return Files.exists(Paths.get(url.toURI()));
            } catch (URISyntaxException e) {
                throw new RuntimeException("What.", e);
            }
        }

        Boolean recent = recentHeadRequests.get(url.toString());
        if (recent != null) {
            return recent;
        }
        Request request = new Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .build();
        try (var response = HTTP_CLIENT.newCall(request).execute()) {
            recent = response.isSuccessful();
            recentHeadRequests.put(url.toString(), recent);
            return recent;
        }
    }
}
