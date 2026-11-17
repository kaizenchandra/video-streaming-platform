package com.example.streaming.processing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ProcessRunner {
    private static final ThreadLocal<Set<ProcessHandle>> survivors =
            ThreadLocal.withInitial(HashSet::new);

    public static Set<ProcessHandle> takeSurvivors() {
        var result =
                survivors.get().stream()
                        .filter(ProcessHandle::isAlive)
                        .collect(java.util.stream.Collectors.toSet());
        survivors.remove();
        return result;
    }

    public static String run(List<String> args, Duration timeout) throws Exception {
        if (!new File(args.getFirst()).canExecute()) throw new IOException("Executable unavailable");
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = new ByteArrayOutputStream();
        Thread reader =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (var in = p.getInputStream()) {
                                        byte[] b = new byte[4096];
                                        int n;
                                        while ((n = in.read(b)) != -1) {
                                            synchronized (output) {
                                                if (output.size() < 131072)
                                                    output.write(b, 0, Math.min(n, 131072 - output.size()));
                                            }
                                        }
                                    } catch (IOException ignored) {
                                    }
                                });
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new TimeoutException("Media process timeout");
            reader.join(2000);
            if (p.exitValue() != 0) throw new IOException("Media process exited " + p.exitValue());
            synchronized (output) {
                return output.toString(java.nio.charset.StandardCharsets.UTF_8);
            }
        } finally {
            var processes = new HashSet<ProcessHandle>();
            processes.add(p.toHandle());
            try {
                p.descendants().forEach(processes::add);
            } catch (RuntimeException ignored) {
            }
            processes.forEach(ProcessHandle::destroyForcibly);
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (ProcessHandle process : processes) {
                try {
                    process.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException ignored) {
                }
                if (process.isAlive()) survivors.get().add(process);
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
