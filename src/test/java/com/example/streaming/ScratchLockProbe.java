package com.example.streaming;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Separate process required to observe the OS lock, rather than the JVM overlap registry.
 */
public final class ScratchLockProbe {
    public static void main(String[] args) throws Exception {
        try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            System.exit(lock == null ? 2 : 0);
        }
    }
}
