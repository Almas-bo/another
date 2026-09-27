package city.subroutine.server;

import city.subroutine.levels.LevelCatalog;
import city.subroutine.levels.LevelDefinition;
import city.subroutine.sandbox.api.CodeRunnerService;
import city.subroutine.sandbox.api.DebugResult;
import city.subroutine.sandbox.api.ExecutionMode;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.ExecutionResult;
import city.subroutine.sandbox.host.DefaultCodeRunnerService;
import city.subroutine.sandbox.host.RunnerConfig;
import city.subroutine.server.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Игровой сервер: HTTP/1.1 + JSON API v1 поверх {@link CodeRunnerService}. Клиент — Unity (UnityWebRequest).
 *
 * <pre>
 * GET  /api/v1/health
 * GET  /api/v1/levels
 * GET  /api/v1/levels/{id}
 * POST /api/v1/levels/{id}/run    {"code": "..."}                  → {"result": ExecutionResult}
 * POST /api/v1/levels/{id}/check  {"code": "..."}                  → {"result": ExecutionResult} (без тестов)
 * POST /api/v1/levels/{id}/debug  {"code": "...", "testId": "..."} → {"result": …, "trace": DebugTrace}
 * </pre>
 *
 * По умолчанию слушает только 127.0.0.1: сервер исполняет присланный код (в песочнице) и не предназначен
 * для открытой сети без дополнительной аутентификации и ОС-изоляции.
 */
public final class GameServer implements AutoCloseable {

    public static final String API_VERSION = "1";
    public static final int DEFAULT_PORT = 8787;
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger LOG = Logger.getLogger(GameServer.class.getName());
    private static final Pattern LEVEL_PATH = Pattern.compile("^/api/v1/levels/([a-z0-9-]{1,64})(?:/(run|check|debug))?/?$");

    private final HttpServer http;
    private final CodeRunnerService runner;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final LevelTests levelTests = new LevelTests();

    public GameServer(InetSocketAddress address, CodeRunnerService runner) throws IOException {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.http = HttpServer.create(address, 64);
        http.setExecutor(executor);
        http.createContext("/api/v1/", this::handle);
        http.createContext("/", exchange -> send(exchange, 404, error("not_found", "Неизвестный путь")));
    }

    public void start() {
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(0);
        executor.shutdownNow();
        runner.close();
    }

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        String host = "127.0.0.1";
        int prewarm = 2;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--prewarm" -> prewarm = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Неизвестный аргумент: " + args[i]);
                    System.err.println("Использование: GameServer [--port 8787] [--host 127.0.0.1] [--prewarm 2]");
                    System.exit(2);
                }
            }
        }
        RunnerConfig config = RunnerConfig.defaults().withPrewarmedWorkers(prewarm);
        GameServer server = new GameServer(new InetSocketAddress(InetAddress.getByName(host), port),
                new DefaultCodeRunnerService(config));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-shutdown"));
        server.start();
        System.out.println("Subroutine City: сервер песочницы слушает http://" + host + ":" + server.port()
                + "/api/v1 (уровней: " + LevelCatalog.all().size() + "). Ctrl+C — остановка.");
    }

    // ------------------------------------------------------------------------------------------ маршрутизация

    private void handle(HttpExchange exchange) {
        try {
            route(exchange);
        } finally {
            exchange.close();
        }
    }

    /** Все ошибки превращаются в JSON-ответ до закрытия обмена. */
    private void route(HttpExchange exchange) {
        try {
            addCors(exchange);
            String method = exchange.getRequestMethod();
            if (method.equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/health") || path.equals("/api/v1/health/")) {
                requireMethod(method, "GET");
                send(exchange, 200, Json.obj("status", "ok", "apiVersion", API_VERSION,
                        "levels", LevelCatalog.all().size()));
                return;
            }
            if (path.equals("/api/v1/levels") || path.equals("/api/v1/levels/")) {
                requireMethod(method, "GET");
                send(exchange, 200, Json.obj("levels", LevelCatalog.all().stream()
                        .map(l -> ApiMapper.levelSummary(l, levelTests.of(l))).toList()));
                return;
            }
            Matcher matcher = LEVEL_PATH.matcher(path);
            if (!matcher.matches()) {
                throw new ApiException(404, "not_found", "Неизвестный путь: " + path);
            }
            LevelDefinition level = LevelCatalog.find(matcher.group(1))
                    .orElseThrow(() -> new ApiException(404, "unknown_level", "Нет уровня " + matcher.group(1)));
            String action = matcher.group(2);
            if (action == null) {
                requireMethod(method, "GET");
                send(exchange, 200, ApiMapper.levelDetail(level, levelTests.of(level)));
                return;
            }
            requireMethod(method, "POST");
            Map<String, Object> body = readBody(exchange);
            String code = requireString(body, "code");
            ExecutionRequest request = level.request(UUID.randomUUID().toString(), code);
            switch (action) {
                case "run" -> {
                    ExecutionResult result = runner.execute(request);
                    send(exchange, 200, Json.obj("result", ApiMapper.result(result)));
                }
                case "check" -> {
                    ExecutionResult result = runner.execute(request.withMode(ExecutionMode.CHECK));
                    send(exchange, 200, Json.obj("result", ApiMapper.result(result)));
                }
                case "debug" -> {
                    String testId = requireString(body, "testId");
                    if (levelTests.of(level).stream().noneMatch(t -> t.id().equals(testId))) {
                        throw new ApiException(400, "unknown_test", "В уровне нет теста " + testId);
                    }
                    DebugResult result = runner.debug(request, testId);
                    send(exchange, 200, ApiMapper.debug(result));
                }
                default -> throw new ApiException(404, "not_found", "Неизвестное действие " + action);
            }
        } catch (ApiException e) {
            sendQuietly(exchange, e.status, error(e.code, e.getMessage()));
        } catch (IOException e) {
            LOG.log(Level.FINE, "Клиент отключился", e);
        } catch (Json.JsonException | IllegalArgumentException e) {
            sendQuietly(exchange, 400, error("bad_request", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendQuietly(exchange, 503, error("interrupted", "Сервер останавливается"));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Ошибка обработки запроса", e);
            sendQuietly(exchange, 500, error("internal", "Внутренняя ошибка сервера: " + e.getClass().getSimpleName()));
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new ApiException(405, "method_not_allowed", "Ожидался метод " + expected);
        }
    }

    private static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ApiException(413, "too_large", "Тело запроса больше " + MAX_BODY_BYTES + " байт");
            }
            return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static String requireString(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof String value)) {
            throw new ApiException(400, "bad_request", "Поле '" + field + "' должно быть строкой");
        }
        return value;
    }

    private static Map<String, Object> error(String code, String message) {
        return Json.obj("error", Json.obj("code", code, "message", message));
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendQuietly(HttpExchange exchange, int status, Object body) {
        try {
            send(exchange, status, body);
        } catch (IOException | RuntimeException ignored) {
            // клиент отключился или заголовки уже отправлены
        }
    }

    static final class ApiException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final int status;
        final String code;

        ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
