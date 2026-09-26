package city.subroutine.sandbox.policy;

import city.subroutine.sandbox.api.PolicyViolation;
import city.subroutine.sandbox.api.SourceUnit;
import city.subroutine.sandbox.compiler.CompilationOutput;
import city.subroutine.sandbox.compiler.InMemoryCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeVerifierTest {

    private static InMemoryCompiler compiler;
    private static BytecodeVerifier verifier;

    @BeforeAll
    static void setUp() {
        compiler = new InMemoryCompiler(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        SandboxPolicy policy = SandboxPolicy.standard("city.player", List.of("city.subroutine.levels.traffic.api"));
        verifier = new BytecodeVerifier(policy, new ReflectiveTypeHierarchy(BytecodeVerifierTest.class.getClassLoader()));
    }

    private static List<PolicyViolation> verify(String className, String code) {
        CompilationOutput output = compiler.compile(List.of(new SourceUnit(className, code)));
        assertTrue(output.success(), () -> "Не скомпилировалось: " + output.diagnostics());
        return verifier.verify(output.classes());
    }

    @Test
    void modernLanguageFeaturesAreAllowed() {
        List<PolicyViolation> violations = verify("city.player.Modern", """
                package city.player;

                import java.util.*;
                import java.util.concurrent.*;
                import java.util.concurrent.atomic.*;
                import java.util.function.*;
                import java.util.stream.*;

                public final class Modern {
                    sealed interface Shape permits Circle, Square {}
                    record Circle(double r) implements Shape {}
                    record Square(double side) implements Shape {}
                    enum Mode { FAST, SAFE }

                    static double area(Shape s) {
                        return switch (s) {
                            case Circle c when c.r() > 10 -> -1;
                            case Circle c -> Math.PI * c.r() * c.r();
                            case Square q -> q.side() * q.side();
                        };
                    }

                    static String mode(Mode m) {
                        return switch (m) { case FAST -> "быстро"; case SAFE -> "надёжно"; };
                    }

                    static final class Res implements AutoCloseable {
                        @Override public void close() {}
                    }

                    public static String run(List<Integer> xs) throws Exception {
                        Function<Integer, Integer> sq = x -> x * x;
                        Supplier<Map<String, Integer>> maps = HashMap::new;
                        String joined = xs.stream().map(sq).map(String::valueOf).collect(Collectors.joining(","));
                        try (Res r = new Res()) {
                            assert xs != null : "xs";
                        }
                        ExecutorService pool = Executors.newFixedThreadPool(2);
                        try {
                            Future<Integer> f = pool.submit(() -> 42);
                            AtomicLong counter = new AtomicLong();
                            counter.incrementAndGet();
                            Thread t = Thread.ofPlatform().name("w").start(() -> {});
                            t.join();
                            StringBuilder sb = new StringBuilder();
                            sb.append(Integer.toHexString(f.get())).append(Optional.of(1).orElseThrow());
                            System.out.println("готово " + joined + sb + area(new Circle(1)) + mode(Mode.FAST)
                                    + new Circle(2).equals(new Circle(2)) + maps.get().size()
                                    + Runtime.getRuntime().availableProcessors() + Mode.valueOf("SAFE"));
                            return joined + counter.get() + switch (joined) { case "a" -> 1; default -> 2; };
                        } finally {
                            pool.shutdown();
                        }
                    }
                }
                """);
        assertEquals(List.of(), violations);
    }

    @Test
    void apiPackageOfLevelIsAllowed() {
        assertEquals(List.of(), verify("city.player.Counter", """
                package city.player;
                import city.subroutine.levels.traffic.api.TrafficCounter;
                public final class Counter implements TrafficCounter {
                    public void register(int s) {}
                    public long ofSector(int s) { return SECTORS; }
                    public long total() { return 0; }
                }
                """));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "new java.io.File(\"/etc/passwd\").exists();",
            "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"));",
            "new java.net.Socket(\"example.com\", 80);",
            "Runtime.getRuntime().exec(\"id\");",
            "new ProcessBuilder(\"id\").start();",
            "System.exit(1);",
            "System.getenv(\"HOME\");",
            "System.getProperty(\"user.home\");",
            "System.setOut(null);",
            "Class.forName(\"java.lang.Runtime\");",
            "String.class.getDeclaredMethods();",
            "Thread.getAllStackTraces();",
            "Thread.currentThread().getContextClassLoader();",
            "Thread.currentThread().getThreadGroup();",
            "java.lang.invoke.MethodHandles.lookup();",
            "Integer.getInteger(\"x\");",
            "System.loadLibrary(\"x\");",
            "java.util.ServiceLoader.load(Runnable.class);",
            "java.util.ResourceBundle.getBundle(\"x\");",
            "new java.io.FileOutputStream(java.io.FileDescriptor.out);",
            "Object o = sun.misc.Unsafe.class;",
            "StackWalker.getInstance();",
            "Runtime.getRuntime().halt(0);",
            "Runtime.getRuntime().addShutdownHook(new Thread());",
    })
    void escapeAttemptsAreRejected(String statement) {
        List<PolicyViolation> violations = verify("city.player.Escape", """
                package city.player;
                public final class Escape {
                    public static void attack() throws Exception {
                        %s
                    }
                }
                """.formatted(statement));
        assertTrue(!violations.isEmpty(), "Не заблокировано: " + statement);
    }

    @Test
    void restrictedStaticMemberCannotBeReachedThroughSubclass() {
        List<PolicyViolation> violations = verify("city.player.Sneaky", """
                package city.player;
                public final class Sneaky {
                    static final class MyThread extends Thread {}
                    public static Object attack() {
                        return MyThread.getAllStackTraces();
                    }
                }
                """);
        assertEquals(1, violations.size(), violations::toString);
        assertEquals(PolicyViolation.Rule.FORBIDDEN_MEMBER, violations.get(0).rule());
        assertTrue(violations.get(0).reference().endsWith("MyThread#getAllStackTraces"));
    }

    @Test
    void restrictedInstanceMemberCannotBeReachedThroughSubclass() {
        List<PolicyViolation> violations = verify("city.player.Sneaky2", """
                package city.player;
                public final class Sneaky2 {
                    static class Base extends Thread {}
                    static final class Leaf extends Base {}
                    public static Object attack() {
                        return new Leaf().getContextClassLoader();
                    }
                }
                """);
        assertTrue(violations.stream().anyMatch(v -> v.rule() == PolicyViolation.Rule.FORBIDDEN_TYPE
                && v.reference().equals("java.lang.ClassLoader")), violations::toString);
    }

    @Test
    void playerOverrideWithSameNameIsAllowed() {
        assertEquals(List.of(), verify("city.player.Worker", """
                package city.player;
                public final class Worker {
                    static final class Job extends Thread {
                        @Override public void run() { System.out.println("работаю"); }
                    }
                    public static void go() throws InterruptedException {
                        Job job = new Job();
                        job.start();
                        job.run();
                        job.join();
                    }
                }
                """));
    }

    @Test
    void classOutsidePlayerPackageIsRejected() {
        List<PolicyViolation> violations = verify("evil.Outside", """
                package evil;
                public final class Outside {}
                """);
        assertEquals(PolicyViolation.Rule.WRONG_PACKAGE, violations.get(0).rule());
    }

    @Test
    void nativeMethodIsRejected() {
        List<PolicyViolation> violations = verify("city.player.Native", """
                package city.player;
                public final class Native { public static native int peek(long address); }
                """);
        assertTrue(violations.stream().anyMatch(v -> v.rule() == PolicyViolation.Rule.NATIVE_METHOD));
    }

    @Test
    void levelSuiteIsNotVisibleToPlayer() {
        List<PolicyViolation> violations = verify("city.player.Cheat", """
                package city.player;
                public final class Cheat {
                    public static long budget() {
                        return city.subroutine.levels.powergrid.PowerGridSuite.class.hashCode();
                    }
                }
                """);
        assertTrue(violations.stream().anyMatch(v -> v.reference().contains("PowerGridSuite")), violations::toString);
    }
}
