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
 * состояние пула (из /api/pool) вместе с rps/перцентилями.
 *
 * Только JDK: собирается одним файлом, тянется в стенд без maven.
 * Настройки через env: TARGET, STATUS_TARGET, WORKERS, WORK_MS, THINK_MS, REPORT_MS, DURATION_S.
 * STATUS_TARGET (дефолт = TARGET): откуда брать /api/pool для отчёта. Под балансировщиком
 * TARGET — это nginx, и сводка мигала бы между инстансами группы; задавая STATUS_TARGET
 * на конкретный инстанс, отчёт стабильно показывает один пул.
 */
public final class LoadGen {

    private static final String TARGET = env("TARGET", "http://localhost:8080");
    private static final String STATUS_TARGET = env("STATUS_TARGET", TARGET);
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

    /** Отдельный клиент для служебных запросов /api/pool, чтобы не мешать замеру нагрузки. */
    private static final HttpClient META = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        System.out.printf(Locale.ROOT,
                "loadgen -> %s (status -> %s) | workers=%d work=%dms think=%dms report=%dms duration=%s%n",
                TARGET, STATUS_TARGET, WORKERS, WORK_MS, THINK_MS, REPORT_MS, DURATION_S == 0 ? "∞" : DURATION_S + "s");
        System.out.println("колонки: rps p50 p95 max inflight ok err | пул: max total active idle waiting | pg: sessions active idle");

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

    /** Печатает строку состояния: нагрузка + пул + сессии postgres. */
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

            String pool = "n/a";
            String pg = "n/a";
            try {
                // один запрос на окно: /api/pool отдаёт и состояние пула, и сессии postgres
                String json = fetchPoolJson();
                pool = formatPool(section(json, "pool"));
                pg = formatSessions(section(json, "postgres"));
            } catch (IOException e) {
                pool = "n/a (не долетел /api/pool)";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            System.out.printf(Locale.ROOT,
                    "[t=%3ds] rps=%-5d p50=%-4d p95=%-4d max=%-4d inflight=%-3d ok=%-7d err=%-4d | pool: %s | pg: %s%n",
                    (int) (elapsedMs() / 1000), rps, p[0], p[1], p[2], INFLIGHT.get(),
                    OK.sum(), ERRORS.sum(), pool, pg);
        }
    }

    private static String formatPool(String pool) {
        return String.format(Locale.ROOT, "max=%s total=%s active=%s idle=%s waiting=%s",
                num(pool, "maximumPoolSize"), num(pool, "total"), num(pool, "active"),
                num(pool, "idle"), num(pool, "threadsAwaitingConnection"));
    }

    private static String formatSessions(String pg) {
        String error = optionalString(pg, "error");
        if (error != null) {
            return "n/a (" + error + ")";
        }
        return String.format(Locale.ROOT, "sessions=%s active=%s idle=%s",
                num(pg, "sessions"), num(pg, "active"), num(pg, "idle"));
    }

    private static String fetchPoolJson() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(STATUS_TARGET + "/api/pool"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return META.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Вырезает значение объекта по имени с учётом вложенности (скобки). */
    private static String section(String json, String name) {
        int start = json.indexOf("\"" + name + "\":");
        if (start < 0) {
            return "";
        }
        int open = json.indexOf('{', start);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return json.substring(open, i + 1);
                }
            }
        }
        return json.substring(open);
    }

    private static String num(String json, String field) {
        return optionalString(json, field) == null ? "?" : optionalString(json, field);
    }

    /** Значение поля или null, если поля нет (в JSON его могло выкинуть non_null-политика). */
    private static String optionalString(String json, String field) {
        int at = json.indexOf("\"" + field + "\":");
        if (at < 0) {
            return null;
        }
        int i = at + field.length() + 3;
        while (i < json.length() && json.charAt(i) == ' ') {
            i++;
        }
        if (i >= json.length()) {
            return null;
        }
        char c = json.charAt(i);
        if (c == '"' || c == '{' || c == '[') {
            char close = c == '"' ? '"' : (c == '{' ? '}' : ']');
            int end = json.indexOf(close, i + 1);
            return end > 0 ? json.substring(i, end + 1) : null;
        }
        int end = i;
        while (end < json.length() && "-0123456789.".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        return end > i ? json.substring(i, end) : null;
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
