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

import java.io.File;
import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Represents a Maven artifact coordinates
 */
public class Artifact implements Comparable<Artifact>, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern SEMI = Pattern.compile(":");

    // group:name[:version][:classifier][@extension]
    private final String group;
    private final String name;
    private final String version;
    private final String classifier;
    private final String ext;

    // Cached after building the first time we're asked
    // Transient field so these aren't serialized
    private transient String folder;
    private transient String path;
    private transient String file;
    private transient String fullDescriptor;
    private transient ComparableVersion comparableVersion;
    private transient Boolean isSnapshot;

    /**
     * Parses a descriptor into an artifact.
     *
     * @param descriptor The descriptor to parse
     * @return The created artifact
     *
     * @throws ArrayIndexOutOfBoundsException If the descriptor is invalid
     */
    public static Artifact from(String descriptor) {
        return parse(descriptor);
    }

    private static Artifact from(String group, String name, String version, String classifier, String ext) {
        return new Artifact(group, name, version, classifier, ext);
    }

    private Artifact(String group, String name, String version, String classifier, String ext) {
        this.group = group;
        this.name = name;
        this.version = version;
        this.classifier = classifier;
        this.ext = ext != null ? ext : "jar";
    }

    /**
     * @return The path of this artifact, localized for the system using {@link File#separatorChar}.
     *
     * @see #getPath()
     */
    public String getLocalPath() {
        return getPath().replace('/', File.separatorChar);
    }

    /** @return The descriptor of this artifact */
    public String getDescriptor() {
        if (fullDescriptor == null) {
            var buf = new StringBuilder();
            buf.append(this.group).append(':').append(this.name);
            if (this.version != null) {
                buf.append(':').append(this.version);
                if (this.classifier != null)
                    buf.append(':').append(this.classifier);
                if (ext != null && !"jar".equals(this.ext))
                    buf.append('@').append(this.ext);
            }
            this.fullDescriptor = buf.toString();
        }
        return fullDescriptor;
    }

    public String getPath() {
        if (path == null)
            this.path = this.getFolder() + '/' + getFilename();
        return path;
    }

    public String getFolder() {
        if (this.folder == null) {
            var buff = new StringBuilder();
            buff.append(group.replace('.', '/'))
                .append('/').append(name);
            if (version != null)
                buff.append('/').append(version);
            this.folder = buff.toString();
        }
        return this.folder;
    }

    /** @return The file name of this artifact */
    public String getFilename() {
        if (this.version == null)
            return null;

        if (file == null) {
            var buf = new StringBuilder();
            buf.append(name).append('-').append(version);
            if (this.classifier != null)
                buf.append('-').append(classifier);
            buf.append('.').append(ext);
            this.file = buf.toString();
        }

        return file;
    }

    /** @return {@code true} if this artifact is a snapshot version */
    public boolean isSnapshot() {
        if (isSnapshot == null)
            this.isSnapshot = version != null && version.toLowerCase(Locale.ROOT).endsWith("-snapshot");
        return isSnapshot;
    }

    /** @return The group of this artifact */
    public String getGroup() {
        return group;
    }
    /** @return The name of this artifact */
    public String getName() {
        return name;
    }
    /** @return The version of this artifact */
    public String getVersion() {
        return version;
    }
    /** @return The classifier of this artifact */
    public String getClassifier() {
        return classifier;
    }
    /** @return The extension of this artifact (defaults to {@code jar}) */
    public String getExtension() {
        return ext;
    }

    /** @return A new instance with the group changed */
    public Artifact withGroup(String group) {
        return Artifact.from(group, name, version, classifier, ext);
    }
    /** @return A new instance with the name changed */
    public Artifact withName(String name) {
        return Artifact.from(group, name, version, classifier, ext);
    }
    /** @return A new instance with the version changed */
    public Artifact withVersion(String version) {
        return Artifact.from(group, name, version, classifier, ext);
    }
    /** @return A new instance with the classifier changed */
    public Artifact withClassifier(String classifier) {
        return Artifact.from(group, name, version, classifier, ext);
    }
    /** @return A new instance with the extension changed */
    public Artifact withExtension(String ext) {
        return Artifact.from(group, name, version, classifier, ext);
    }

    @Override
    public String toString() {
        return getDescriptor();
    }

    @Override
    public int hashCode() {
        return getDescriptor().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Artifact o &&
            this.getDescriptor().equals(o.getDescriptor());
    }

    public ComparableVersion getComparableVersion() {
        if (version != null && comparableVersion == null)
            this.comparableVersion = new ComparableVersion(version);
        return comparableVersion;
    }

    @Override
    public int compareTo(Artifact o) {
        var ret = compare(this.getDescriptor(), o.getDescriptor());
        if (ret == 0)
            ret = compare(this.getComparableVersion(), o.getComparableVersion());
        if (ret == 0)
            ret = compare(this.classifier, o.classifier);
        if (ret == 0)
            ret = compare(this.ext, o.ext);
        return ret;
    }

    private static <S extends Comparable<S>> int compare(S a, S b) {
        if (a == null)
            return b == null ? 0 : -1;
        else if (b == null)
            return 1;
        return a.compareTo((S) b);
    }

    private static Artifact parse(String descriptor) {
        String group, name;
        String version = null, ext = null, classifier = null;

        var pts = SEMI.split(descriptor);
        group = pts[0];
        name = pts[1];

        if (pts.length > 2) { // We have a version
            int last = pts.length - 1;
            int idx = pts[last].indexOf('@');
            if (idx != -1) { // we have an extension
                ext = pts[last].substring(idx + 1);
                pts[last] = pts[last].substring(0, idx);
            }

            version = pts[2];

            if (pts.length > 3) // We have a classifier
                classifier = pts[3];
        }

        return Artifact.from(group, name, version, classifier, ext);
    }
}
