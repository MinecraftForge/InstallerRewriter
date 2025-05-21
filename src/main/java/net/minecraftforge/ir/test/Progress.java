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

import java.util.concurrent.TimeUnit;

import net.minecraftforge.ir.util.Log;

class Progress {
    private static final long INTERVAL = TimeUnit.SECONDS.toMillis(1);
    private final Log log;
    private final String prefix;
    private int current = 1;
    private int expected;
    private long lastMessage;

    Progress(Log log, int expected) {
        this(log, expected, false);
    }

    Progress(Log log, int expected, boolean quiet) {
        this.log = log;
        this.expected = expected;
        this.prefix = "[%" + Integer.toString(expected).length() + "d/" + this.expected + "] ";
    }

    private String prefix() {
        lastMessage = System.currentTimeMillis();
        return String.format(prefix, current++);
    }

    public void step() {
        log.info(prefix());
    }

    public void quiet() {
        if (System.currentTimeMillis() < lastMessage + INTERVAL)
            current++;
        else
            log.info(prefix());
    }

    public void step(String message) {
        log.info(prefix() + message);
    }

    public void quiet(String message) {
        if (System.currentTimeMillis() < lastMessage + INTERVAL)
            current++;
        else
            log.info(prefix() + message);
    }

    public void step(String message, Object... args) {
        log.info(prefix() + message, args);
    }

    public void quiet(String message, Object... args) {
        if (System.currentTimeMillis() < lastMessage + INTERVAL)
            current++;
        else
            log.info(prefix() + message, args);
    }
}
