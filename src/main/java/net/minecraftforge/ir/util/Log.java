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

import java.io.Closeable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.StringFormatterMessageFactory;

public class Log implements Closeable {
    private static Class<?> getCallerClass() {
        StackTraceElement ste = Thread.currentThread().getStackTrace()[3];
        try {
            return Class.forName(ste.getClassName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static Handler wrap(Logger logger) {
        return new Handler() {
            @Override
            public void log(Level level, String message) {
                logger.log(level, message);
            }

            @Override
            public void log(Level level, String message, Object[] args) {
                logger.log(level, message, args);
            }
        };
    }

    private final Handler handler;
    private int tabLevel = 0;

    public Log() {
        this(wrap(LogManager.getLogger(getCallerClass(), StringFormatterMessageFactory.INSTANCE)));
    }

    public Log(Handler handler) {
        this.handler = handler;
    }

    @Override
    public void close() {
        tabLevel--;
    }

    public Log push() {
        tabLevel++;
        return this;
    }

    public Log pop() {
        tabLevel--;
        return this;
    }

    private String getIndent() {
        return "  ".repeat(tabLevel);
    }

    public void log(Level level, String message) {
        handler.log(level, getIndent() + message);
    }

    public void log(Level level, String message, Object[] args) {
        handler.log(level, getIndent() + message, args);
    }

    public void info(String message) {
        log(Level.INFO, message);
    }

    public void info(String message, Object... args) {
        log(Level.INFO, message, args);
    }

    public void warn(String message) {
        log(Level.WARN, message);
    }

    public void warn(String message, Object... args) {
        log(Level.WARN, message, args);
    }

    public void error(String message) {
        log(Level.ERROR, message);
    }

    public void error(String message, Object... args) {
        log(Level.ERROR, message, args);
    }

    public void debug(String message) {
        log(Level.DEBUG, message);
    }

    public void debug(String message, Object... args) {
        log(Level.DEBUG, message, args);
    }

    public static interface Handler {
        void log(Level level, String message);
        void log(Level level, String message, Object[] args);
    }
}
