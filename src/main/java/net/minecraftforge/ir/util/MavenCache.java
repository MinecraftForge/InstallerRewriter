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

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import net.minecraftforge.util.download.DownloadUtils;
import net.minecraftforge.util.hash.HashFunction;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.minecraftforge.ir.util.Utils.sneak;

public class MavenCache {
    private static final long TIMEOUT = TimeUnit.HOURS.toMillis(1);
    private final Log log;
    private final String name;
    private final String repo;

    private final Path cache;

    public MavenCache(Log log, String name, String repo, Path root) {
        this.log = log;
        this.name = name;
        this.repo = repo;
        this.cache = root.resolve(this.name);
    }

    public String url() {
        return this.repo;
    }

    public Path download(Artifact artifact) {
        return download(false, artifact.getPath());
    }

    public Path getChanging(Artifact artifact) {
        return download(true, artifact.getPath());
    }

    public Path getMeta(Artifact artifact) {
        return download(true, artifact.getFolder() + "/maven-metadata.xml");
    }

    public boolean exists(Artifact artifact) {
        var path = artifact.getPath();
        synchronized (path.intern()) {
            var target = cache.resolve(path);
            return Files.exists(target);
        }
    }

    protected boolean validCache(Path path, boolean changing) {
        try {
            return !changing || Files.getLastModifiedTime(path).toMillis() >= System.currentTimeMillis() - TIMEOUT;
        } catch (IOException e) {
            return false;
        }
    }

    protected Map<HashFunction, String> getRemoteHashes(String path) {
        var ret = new HashMap<HashFunction, String>();
        for (var func : new HashFunction[] { HashFunction.SHA1 }) {
            try {
                String rhash = DownloadUtils.downloadString(repo + path + '.' + func.extension());
                if (rhash == null)
                    continue;
                ret.put(func, rhash);
                // Only care about the first hash the server returns, be it valid or not
                break;
            } catch (IOException e) {
                // 404 or whatever means no hash
            }
        }
        return ret;
    }

    protected Path download(boolean changing, String path) {
        var lock = path.intern();
        synchronized (lock) {
            var target = cache.resolve(path);

            if (Files.exists(target)) {
                boolean invalidHash = false;
                if (!validCache(target, changing)) {
                    var hashes = getRemoteHashes(path);
                    for (var entry : hashes.entrySet()) {
                        try {
                            var rhash = entry.getValue();
                            var chash = entry.getKey().hash(target.toFile());
                            if (!chash.equals(rhash)) {
                                log.info("Outdated cached file: %s", target.toAbsolutePath());
                                log.info("Expected: %s", rhash);
                                log.info("Actual:   %s", chash);
                                invalidHash = true;
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Could not download " + repo + path + ", Error reading cached file", e);
                        }
                    }
                }

                if (!invalidHash)
                    return target;

                try {
                    Files.delete(target);
                } catch (IOException e) {
                    return sneak(e);
                }
            }

            try {
                //log.info("Downloading %s", this.repo + path);
                DownloadUtils.downloadFile(true, target.toFile(), this.repo + path);
                return target;
            } catch (FileNotFoundException e) {
                return null;
            } catch (IOException e) {
                return sneak(e);
            }
        }
    }

    public List<String> getVersions(Artifact artifact) {
        var meta = getMeta(artifact);
        try (var input = Files.newInputStream(meta)) {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
            NodeList lst = doc.getElementsByTagName("version");
            List<String> ret = new ArrayList<>();
            for (int x = 0; x < lst.getLength(); x++)
                ret.add(lst.item(x).getTextContent());
            return ret;
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new RuntimeException("Failed to parse " + meta.toAbsolutePath(), e);
        }
    }
}
