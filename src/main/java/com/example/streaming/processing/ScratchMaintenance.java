package com.example.streaming.processing;

import com.example.streaming.shared.Db;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local-volume janitor entrypoint. Legacy adoption is performed only by the drained-worker wrapper.
 */
public final class ScratchMaintenance {
    public static void main(String[] args) {
        boolean adopt = java.util.Arrays.asList(args).contains("--adopt-legacy");
        if (adopt && !java.util.Arrays.asList(args).contains("--workers-drained"))
            throw new IllegalArgumentException("Legacy adoption requires all old workers to be drained");
        var env = System.getenv();
        var ds =
                new DriverManagerDataSource(
                        env.getOrDefault("DB_URL", "jdbc:postgresql://postgres:5432/streaming"),
                        env.getOrDefault("DB_USER", "streaming"),
                        env.getOrDefault("DB_PASSWORD", "local-development-only"));
        var space =
                new ScratchSpace(
                        new Db(new JdbcTemplate(ds)),
                        new TransactionTemplate(new DataSourceTransactionManager(ds)),
                        env.getOrDefault("MEDIA_TEMP", "/tmp/streaming"),
                        Long.parseLong(env.getOrDefault("SCRATCH_RETENTION_SECONDS", "86400")),
                        false,
                        adopt);
        int count = space.runOnce();
        System.out.println("{\"purged\":" + count + ",\"volume\":\"" + space.volume() + "\"}");
    }
}
