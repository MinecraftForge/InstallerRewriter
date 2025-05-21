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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

class ThreadedTask<T> {
    public static final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private final LinkedBlockingQueue<CompletableFuture<T>> completed = new LinkedBlockingQueue<>();
    private final Set<CompletableFuture<T>> tasks = new HashSet<>();

    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String name;
    private final ThreadGroup threadGroup;
    private final Function<Runnable, Runnable> wrapper;
    private final ExecutorService executors;

    ThreadedTask(String name) {
        this(name, THREAD_COUNT);
    }

    ThreadedTask(String name, int threads) {
        this(name, threads, Function.identity());
    }

    ThreadedTask(String name, Function<Runnable, Runnable> wrapper) {
        this(name, THREAD_COUNT, wrapper);
    }

    ThreadedTask(String name, int threads, Function<Runnable, Runnable> wrapper) {
        this.name = name;
        this.threadGroup = new ThreadGroup(name);
        this.wrapper = wrapper;
        this.executors = Executors.newFixedThreadPool(threads, this::newThread);
    }

    public int size() {
        return this.tasks.size();
    }

    protected Thread newThread(Runnable r) {
        var ret = new Thread(threadGroup, wrapper.apply(r), name  + '-' + threadNumber.getAndIncrement());
        if (ret.isDaemon())
            ret.setDaemon(false);
        if (ret.getPriority() != Thread.NORM_PRIORITY)
            ret.setPriority(Thread.NORM_PRIORITY);
        return ret;
    }

    public void submit(Supplier<T> task) {
        var future = CompletableFuture.supplyAsync(task, executors);
        tasks.add(future);
        future.whenComplete((result, throwable) -> completed.add(future));
    }

    public void consume(Consumer<T> consumer) {
        while (!tasks.isEmpty()) {
            try {
                var result = completed.take();
                tasks.remove(result);
                consumer.accept(result.get());
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    public List<T> get() {
        var ret = new ArrayList<T>();
        consume(ret::add);
        return ret;
    }

    public void shutdown() {
        this.executors.shutdown();
    }
}
