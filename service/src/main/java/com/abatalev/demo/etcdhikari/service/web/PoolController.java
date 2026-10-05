package com.abatalev.demo.etcdhikari.service.web;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abatalev.demo.etcdhikari.service.pool.ManagedPool;

@RestController
@RequestMapping("/api")
public class PoolController {

    private static final Logger log = LoggerFactory.getLogger(PoolController.class);

    private final ManagedPool pool;
    private final JdbcTemplate jdbc;

    public PoolController(ManagedPool pool, JdbcTemplate jdbc) {
        this.pool = pool;
        this.jdbc = jdbc;
    }

    /**
     * Нагрузочная точка: берёт коннект из пула, делает запрос и держит его {@code ms} миллисекунд.
     * Именно на ней видно, как maximumPoolSize из etcd превращается в число сессий в postgres.
     *
     * <p>Это единственная точка доменного API: состояние пула, конфигурации и источника наблюдается
     * в метриках, трассах и журнале, а не здесь.
     */
    @GetMapping("/work")
    public ResponseEntity<WorkResponse> work(@RequestParam(defaultValue = "20") long ms,
            @RequestParam(defaultValue = "true") boolean countRows) {
        if (ms < 0 || ms > 5000) {
            return ResponseEntity.badRequest().body(new WorkResponse(false, 0, 0, 0, null, null, "ms должен быть 0..5000"));
        }

        long startNanos = System.nanoTime();
        try {
            Integer rows = countRows ? jdbc.queryForObject("SELECT count(*) FROM demo_items", Integer.class) : null;
            long dbStart = System.nanoTime();
            sleepInDb(ms);
            long dbMs = (System.nanoTime() - dbStart) / 1_000_000;
            long totalMs = (System.nanoTime() - startNanos) / 1_000_000;

            ManagedPool.Runtime runtime = pool.runtime();
            return ResponseEntity.ok(new WorkResponse(true, totalMs, dbMs, Math.max(0, totalMs - dbMs),
                    rows, new PoolSnapshot(runtime.maximumPoolSize(), runtime.minimumIdle(), runtime.total(),
                            runtime.active(), runtime.idle(), runtime.threadsAwaitingConnection()), null));
        } catch (Exception e) {
            long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug("/api/work упал: {}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new WorkResponse(false, totalMs, 0, 0, null, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** pg_sleep держит коннект занятым — так нагрузка реально упирается в размер пула. */
    private void sleepInDb(long ms) {
        if (ms == 0) {
            return;
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_sleep(?)")) {
                ps.setDouble(1, ms / 1000.0);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
            return null;
        });
    }

    public record PoolSnapshot(int maximumPoolSize, int minimumIdle, int total, int active, int idle,
            int threadsAwaitingConnection) {}

    public record WorkResponse(boolean ok, long durationMs, long dbMs, long queueWaitMs, Integer rows,
            PoolSnapshot pool, String error) {}
}