package com.abatalev.demo.etcdhikari.loadgen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Нагрузчик стенда: N виртуальных потоков долбят /api/work, репортер печатает
 * rps/перцентили вместе с ошибками и очередью.
 *
 * Только JDK: собирается одним файлом, тянется в стенд без maven.
 * Настройки через env: TARGET, WORKERS, WORK_MS, THINK_MS, REPORT_MS, DURATION_S.
 * Состояние пула и сессий базы в отчёт не входят: оно снимается в наблюдении стенда (метрики
 * инстанса и сборщика базы), поэтому отчёт не зависит ни от одной точки наблюдения и печатается
 * одинаково при любом состоянии пула.
 */
public final class LoadGen {

    private static final String TARGET = env("TARGET", "http://localhost:8080");
    private static final int WORKERS = (int) envLong("WORKERS", 4);
    private static final long WORK_MS = envLong("WORK_MS", 25);
    private static final long THINK_MS = envLong("THINK_MS", 0);
    private static final long REPORT_MS = envLong("REPORT_MS", 2000);
    private static final long DURATION_S = envLong("DURATION_S", 0); // 0 = крутится до остановки
    private static final long REQUEST_TIMEOUT_S = envLong("REQUEST_TIMEOUT_S", 30);

    /** Кольцевой буфер л.latency: дешёвая гистограмма без внешних зависимостей. */
    private static final class Latencies {
        private final long[] ring;
        private final AtomicLong index = new AtomicLong();
        private final AtomicLong count = new AtomicLong();

        Latencies(int size) {
            this.ring = new long[size];
        }

        void record(long value) {
            ring[Math.floorMod(index.getAndIncrement(), ring.length)] = value;
            count.incrementAndGet();
        }

        long total() {
            return count.get();
        }

        /**
         * p50/p95/max по последним замерам. Незаполненные слоты кольца равны 0 и после
         * сортировки оказываются в начале — отбрасываем их, иначе перцентили всегда 0.
         */
        long[] percentiles() {
            long[] sorted = Arrays.copyOf(ring, ring.length);
            Arrays.sort(sorted);
            int filled = (int) Math.min(total(), ring.length);
            int offset = ring.length - filled;
            long[] window = Arrays.copyOfRange(sorted, offset, sorted.length);
            int n = window.length;
            return new long[] {window[(int) (n * 0.50)], window[(int) (n * 0.95)], window[n - 1]};
        }
    }

    private static final Latencies LATENCIES = new Latencies(1 << 16);
    private static final LongAdder OK = new LongAdder();
    private static final LongAdder ERRORS = new LongAdder();
    private static final LongAdder REQUESTS = new LongAdder();
    private static final AtomicLong INFLIGHT = new AtomicLong();
    private static final AtomicBoolean SUMMARY_PRINTED = new AtomicBoolean();
    private static final long START_NANOS = System.nanoTime();

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        System.out.printf(Locale.ROOT,
                "loadgen -> %s | workers=%d work=%dms think=%dms report=%dms duration=%s%n",
                TARGET, WORKERS, WORK_MS, THINK_MS, REPORT_MS, DURATION_S == 0 ? "∞" : DURATION_S + "s");
        System.out.println("колонки: rps p50 p95 max inflight ok err");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TARGET + "/api/work?ms=" + WORK_MS))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .GET()
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(LoadGen::printSummary));

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService reporter = Executors.newSingleThreadExecutor();
        CountDownLatch stop = new CountDownLatch(1);
        reporter.submit(() -> reportLoop(stop));
        for (int i = 0; i < WORKERS; i++) {
            workers.submit(() -> workerLoop(client, request, stop));
        }

        try {
            if (DURATION_S > 0) {
                Thread.sleep(DURATION_S * 1000);
            } else {
                // висем до docker stop: SIGTERM вызовет shutdown-hook, который напечатает итог
                new CountDownLatch(1).await();
            }
        } finally {
            stop.countDown();
            workers.shutdown();
            workers.awaitTermination(REQUEST_TIMEOUT_S + 5, TimeUnit.SECONDS);
            reporter.shutdownNow();
        }
        printSummary();
    }

    private static void workerLoop(HttpClient client, HttpRequest request, CountDownLatch stop) {
        while (stop.getCount() > 0) {
            INFLIGHT.incrementAndGet();
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                REQUESTS.increment();
                if (response.statusCode() / 100 == 2) {
                    OK.increment();
                } else {
                    ERRORS.increment();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                REQUESTS.increment();
                ERRORS.increment();
            } finally {
                INFLIGHT.decrementAndGet();
                LATENCIES.record((System.nanoTime() - start) / 1_000_000);
            }
            if (THINK_MS > 0 && !sleep(THINK_MS)) {
                return;
            }
        }
    }

    /** Печатает строку состояния нагрузки: скорость, перцентили, очередь, ошибки. */
    private static void reportLoop(CountDownLatch stop) {
        long windowStart = System.nanoTime();
        long windowRequests = 0;
        try {
            reportLoop(stop, windowStart, windowRequests);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reportLoop(CountDownLatch stop, long startNanos, long startRequests) throws InterruptedException {
        long windowStart = startNanos;
        long windowRequests = startRequests;
        while (!stop.await(REPORT_MS, TimeUnit.MILLISECONDS)) {
            long requests = REQUESTS.sum();
            long rps = (requests - windowRequests) * 1000
                    / Math.max(1, (System.nanoTime() - windowStart) / 1_000_000);
            windowStart = System.nanoTime();
            windowRequests = requests;

            long[] p = LATENCIES.percentiles();

            System.out.printf(Locale.ROOT,
                    "[t=%3ds] rps=%-5d p50=%-4d p95=%-4d max=%-4d inflight=%-3d ok=%-7d err=%-4d%n",
                    (int) (elapsedMs() / 1000), rps, p[0], p[1], p[2], INFLIGHT.get(),
                    OK.sum(), ERRORS.sum());
        }
    }

    private static void printSummary() {
        if (!SUMMARY_PRINTED.compareAndSet(false, true)) {
            return;
        }
        long[] p = LATENCIES.percentiles();
        System.out.printf(Locale.ROOT, "итого: запросов=%d ok=%d err=%d p50=%dms p95=%dms max=%dms%n",
                REQUESTS.sum(), OK.sum(), ERRORS.sum(), p[0], p[1], p[2]);
    }

    private static long elapsedMs() {
        return (System.nanoTime() - START_NANOS) / 1_000_000;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static long envLong(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("LOADGEN: " + name + "=" + value + " не число, беру " + fallback);
            return fallback;
        }
    }
}
