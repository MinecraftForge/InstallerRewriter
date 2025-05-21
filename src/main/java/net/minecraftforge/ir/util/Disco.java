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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraftforge.java_provisioner.api.IJavaInstall;
import net.minecraftforge.java_provisioner.api.IJavaLocator;

public class Disco {
    private final Map<Integer, File> installs = new ConcurrentHashMap<>();
    private final Log log;
    private final IJavaLocator disco;
    private final List<IJavaLocator> locators;

    public Disco(Log log, File cache) {
        this.log = log;
        this.disco = IJavaLocator.disco(cache);
        this.locators = Arrays.asList(
            IJavaLocator.home(),
            IJavaLocator.gradle(),
            disco
        );
    }

    public File find(int version) {
        return installs.computeIfAbsent(version, this::compute);
    }

    private File compute(int version) {
        File ret = null;
        for (IJavaLocator locator : locators) {
            ret = locator.find(version);
            if (ret != null)
                break;
        }

        // Could not find it with a locator, lets try downloading it.
        if (ret == null) {
            IJavaInstall install = disco.provision(version);
            if (install != null)
                ret = install.home();
        }

        if (ret == null) {
            log.error("Failed to find sutable java for version " + version);
            for (var locator : locators) {
                log.error("Locator: " + locator.getClass().getSimpleName());
                try (var l = log.push()) {
                    for (var line : locator.logOutput())
                        log.error(line);
                }
            }
        }

        return ret;
    }
}
