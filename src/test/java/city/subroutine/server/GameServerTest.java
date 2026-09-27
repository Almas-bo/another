package city.subroutine.server;

import city.subroutine.sandbox.host.DefaultCodeRunnerService;
import city.subroutine.sandbox.host.RunnerConfig;
import city.subroutine.server.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сквозной тест HTTP API на реальном сервере и реальных процессах-песочницах. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class GameServerTest {

    private static GameServer server;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        RunnerConfig config = RunnerConfig.defaults()
                .withWorkerClasspath(List.of(Path.of("target", "classes")))
                .withPrewarmedWorkers(1);
        server = new GameServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                new DefaultCodeRunnerService(config));
        server.start();
        base = "http://127.0.0.1:" + server.port() + "/api/v1";
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void levelsAreListedInCampaignOrder() throws Exception {
        Map<String, Object> body = get("/levels", 200);
        List<?> levels = (List<?>) body.get("levels");
        assertEquals(6, levels.size());
        Map<?, ?> first = (Map<?, ?>) levels.get(0);
        assertEquals("powergrid-01", first.get("id"));
        assertEquals(7, ((List<?>) first.get("tests")).size());
    }

    @Test
    void levelDetailContainsContractAndStarter() throws Exception {
        Map<String, Object> level = get("/levels/water-01", 200);
        assertTrue(((String) level.get("contractText")).contains("interface FlowMeter"));
        assertEquals("contract", ((Map<?, ?>) level.get("entryPoint")).get("kind"));
    }

    @Test
    void runReturnsStructuredResult() throws Exception {
        Map<String, Object> body = post("/levels/powergrid-01/run",
                Json.obj("code", Files.readString(Path.of("examples/powergrid/PowerGrid.java"))), 200);
        Map<?, ?> result = (Map<?, ?>) body.get("result");
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(7, ((List<?>) result.get("tests")).size());
    }

    @Test
    void checkReportsCompilationErrorWithoutRunningTests() throws Exception {
        Map<String, Object> body = post("/levels/powergrid-01/check",
                Json.obj("code", "package city.player;\npublic final class PowerGrid {\n  int x = ;\n}\n"), 200);
        Map<?, ?> result = (Map<?, ?>) body.get("result");
        assertEquals("COMPILATION_ERROR", result.get("status"));
        Map<?, ?> diagnostic = (Map<?, ?>) ((List<?>) result.get("diagnostics")).get(0);
        assertEquals(3L, diagnostic.get("line"));
        assertTrue(((List<?>) result.get("tests")).isEmpty());
    }

    @Test
    void checkAcceptsValidCode() throws Exception {
        Map<String, Object> body = post("/levels/powergrid-01/check",
                Json.obj("code", Files.readString(Path.of("examples/powergrid/PowerGridBuggy.java"))), 200);
        assertEquals("COMPILED", ((Map<?, ?>) body.get("result")).get("status"));
    }

    @Test
    void debugReturnsTraceWithLocals() throws Exception {
        Map<String, Object> body = post("/levels/powergrid-01/debug", Json.obj(
                "code", Files.readString(Path.of("examples/powergrid/PowerGrid.java")),
                "testId", "basic-sum"), 200);
        Map<?, ?> trace = (Map<?, ?>) body.get("trace");
        List<?> steps = (List<?>) trace.get("steps");
        assertFalse(steps.isEmpty());
        Map<?, ?> last = (Map<?, ?>) steps.get(steps.size() - 1);
        assertEquals("totalLoad", last.get("method"));
        assertTrue(((List<?>) last.get("locals")).stream()
                        .anyMatch(v -> "total".equals(((Map<?, ?>) v).get("name")) && "60".equals(((Map<?, ?>) v).get("value"))),
                () -> "Нет total = 60 в " + last);
        Map<?, ?> result = (Map<?, ?>) body.get("result");
        assertEquals(1, ((List<?>) result.get("tests")).size());
    }

    @Test
    void errorsAreJson() throws Exception {
        assertEquals("unknown_level", errorCode(post("/levels/nope/run", Json.obj("code", "x"), 404)));
        assertEquals("method_not_allowed", errorCode(get("/levels/powergrid-01/run", 405)));
        assertEquals("bad_request", errorCode(post("/levels/powergrid-01/run", Json.obj("code", 42), 400)));
        assertEquals("unknown_test", errorCode(post("/levels/powergrid-01/debug",
                Json.obj("code", "x", "testId", "nope"), 400)));
        HttpResponse<String> malformed = client.send(HttpRequest.newBuilder(URI.create(base + "/levels/powergrid-01/run"))
                .POST(HttpRequest.BodyPublishers.ofString("{oops")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, malformed.statusCode());
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        String huge = "x".repeat(GameServer.MAX_BODY_BYTES + 10);
        assertEquals("too_large", errorCode(post("/levels/powergrid-01/run", Json.obj("code", huge), 413)));
    }

    private static String errorCode(Map<String, Object> body) {
        return (String) ((Map<?, ?>) body.get("error")).get("code");
    }

    private static Map<String, Object> get(String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return Json.parseObject(response.body());
    }

    private static Map<String, Object> post(String path, Object body, int expectedStatus) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return Json.parseObject(response.body());
    }
}
