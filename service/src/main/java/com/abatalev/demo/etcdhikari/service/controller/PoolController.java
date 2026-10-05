package com.abatalev.demo.etcdhikari.service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.abatalev.demo.etcdhikari.service.service.WorkService;

@RestController
@RequestMapping("/api")
public class PoolController {

    private static final Logger log = LoggerFactory.getLogger(PoolController.class);

    private final WorkService work;

    public PoolController(WorkService work) {
        this.work = work;
    }

    /**
     * Нагрузочная точка: берёт коннект из пула, делает запрос и держит его {@code ms} миллисекунд.
     * Именно на ней видно, как maximumPoolSize из etcd превращается в число сессий в postgres.
     *
     * <p>Это единственная точка доменного API, и она отвечает только измерениями: состояние пула,
     * конфигурации и источника наблюдается в метриках, трассах и журнале, а не здесь. Второго
     * источника состояния быть не должно — иначе одно и то же число читается двумя способами и
     * даёт два значения, снятых в разные моменты.
     *
     * <p>Здесь только HTTP: разбор запроса, диапазон, форма ответа. Замер живёт в
     * {@link WorkService}, SQL — в dao, а границы измерений заданы там, а не здесь.
     */
    @GetMapping("/work")
    public ResponseEntity<WorkResponse> work(@RequestParam(defaultValue = "20") long ms,
            @RequestParam(defaultValue = "true") boolean countRows) {
        if (ms < 0 || ms > 5000) {
            return ResponseEntity.badRequest().body(new WorkResponse(false, 0, 0, 0, null, "ms должен быть 0..5000"));
        }

        long startNanos = System.nanoTime();
        try {
            WorkService.Measurement m = work.run(ms, countRows);
            return ResponseEntity.ok(new WorkResponse(true, m.durationMs(), m.dbMs(), m.queueWaitMs(),
                    m.rows(), null));
        } catch (Exception e) {
            // Отказа измерение не возвращает, поэтому общее время считает тот, кто ловит исключение.
            long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug("/api/work упал: {}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new WorkResponse(false, totalMs, 0, 0, null, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    public record WorkResponse(boolean ok, long durationMs, long dbMs, long queueWaitMs, Integer rows,
            String error) {}
}