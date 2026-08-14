package com.jinloes.prpilot.review;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs buffered subprocesses with bounded output, timeout, and dedicated blocking-I/O threads. */
final class BoundedProcessRunner {
    static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    private static final int IO_THREADS = 4;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final Semaphore SHARED_IO_PERMITS = new Semaphore(IO_THREADS);
    private static final ExecutorService SHARED_IO_EXECUTOR =
            new ThreadPoolExecutor(
                    IO_THREADS,
                    IO_THREADS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "pr-pilot-process-io-" + THREAD_SEQUENCE.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    private final ProcessStarter processStarter;
    private final ExecutorService ioExecutor;
    private final Semaphore ioPermits;
    private final int maxOutputBytes;

    BoundedProcessRunner() {
        this(ProcessBuilder::start);
    }

    BoundedProcessRunner(ProcessStarter processStarter) {
        this(processStarter, SHARED_IO_EXECUTOR, SHARED_IO_PERMITS, DEFAULT_MAX_OUTPUT_BYTES);
    }

    BoundedProcessRunner(
            ProcessStarter processStarter, ExecutorService ioExecutor, int maxOutputBytes) {
        this(processStarter, ioExecutor, new Semaphore(1), maxOutputBytes);
    }

    BoundedProcessRunner(
            ProcessStarter processStarter,
            ExecutorService ioExecutor,
            Semaphore ioPermits,
            int maxOutputBytes) {
        this.processStarter = Objects.requireNonNull(processStarter);
        this.ioExecutor = Objects.requireNonNull(ioExecutor);
        this.ioPermits = Objects.requireNonNull(ioPermits);
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    ProcessResult run(ProcessBuilder processBuilder, long timeout, TimeUnit unit)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(processBuilder);
        Objects.requireNonNull(unit);
        if (timeout < 1) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        processBuilder.redirectErrorStream(true);
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        if (!ioPermits.tryAcquire(timeout, unit)) {
            throw new IOException("Process I/O executor is saturated.");
        }
        if (deadlineNanos - System.nanoTime() <= 0) {
            ioPermits.release();
            throw new TimeoutException("Timed out waiting for process I/O capacity.");
        }

        Process process = null;
        Future<DrainResult> outputFuture = null;
        try {
            process = processStarter.start(processBuilder);
            Process drainProcess = process;
            try {
                outputFuture = ioExecutor.submit(() -> drain(drainProcess.getInputStream()));
            } catch (RejectedExecutionException exception) {
                throw new IOException("Process I/O executor is saturated.", exception);
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0 || !process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("Process timed out.");
            }

            DrainResult drained;
            if (outputFuture.isDone()) {
                drained = completedOutput(outputFuture);
            } else {
                long remainingDrainNanos = deadlineNanos - System.nanoTime();
                if (remainingDrainNanos <= 0) {
                    throw new TimeoutException("Timed out draining process output.");
                }
                drained = output(outputFuture, remainingDrainNanos);
            }
            return new ProcessResult(
                    process.exitValue(),
                    drained.bytes().toString(StandardCharsets.UTF_8),
                    drained.truncated());
        } catch (InterruptedException exception) {
            if (process != null) {
                terminate(process, outputFuture);
            }
            Thread.currentThread().interrupt();
            throw exception;
        } catch (IOException | TimeoutException exception) {
            if (process != null) {
                terminate(process, outputFuture);
            }
            throw exception;
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process, outputFuture);
            }
            if (process != null) {
                closeProcessStreams(process);
            }
            if (outputFuture != null && !outputFuture.isDone()) {
                outputFuture.cancel(true);
            }
            ioPermits.release();
        }
    }

    private DrainResult drain(InputStream input) throws IOException {
        ByteArrayOutputStream retained =
                new ByteArrayOutputStream(Math.min(maxOutputBytes, 16 * 1024));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = maxOutputBytes - retained.size();
            int toRetain = Math.min(read, Math.max(0, remaining));
            if (toRetain > 0) {
                retained.write(buffer, 0, toRetain);
            }
            if (toRetain < read) {
                truncated = true;
            }
        }
        return new DrainResult(retained, truncated);
    }

    private static DrainResult output(Future<DrainResult> outputFuture, long remainingNanos)
            throws IOException, InterruptedException, TimeoutException {
        try {
            return outputFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            throw outputFailure(exception);
        }
    }

    private static DrainResult completedOutput(Future<DrainResult> outputFuture)
            throws IOException, InterruptedException {
        try {
            return outputFuture.get();
        } catch (ExecutionException exception) {
            throw outputFailure(exception);
        }
    }

    private static IOException outputFailure(ExecutionException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof IOException ioException
                ? ioException
                : new IOException("Failed to drain process output.", cause);
    }

    private static void terminate(Process process, Future<?> outputFuture) {
        process.destroyForcibly();
        closeProcessStreams(process);
        if (outputFuture != null) {
            outputFuture.cancel(true);
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeProcessStreams(Process process) {
        close(process.getInputStream());
        close(process.getErrorStream());
        close(process.getOutputStream());
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort during process teardown.
        }
    }

    record ProcessResult(int exitCode, String output, boolean outputTruncated) {}

    private record DrainResult(ByteArrayOutputStream bytes, boolean truncated) {}

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }
}
