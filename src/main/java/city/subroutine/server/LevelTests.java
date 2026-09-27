package city.subroutine.server;

import city.subroutine.levels.LevelDefinition;
import city.subroutine.sandbox.protocol.TestDescriptor;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Список тестов уровня для клиента (раскладка города до первого запуска, выбор теста для отладки). */
final class LevelTests {

    private final Map<String, List<TestDescriptor>> cache = new ConcurrentHashMap<>();

    List<TestDescriptor> of(LevelDefinition level) {
        return cache.computeIfAbsent(level.id(), id -> load(level));
    }

    private static List<TestDescriptor> load(LevelDefinition level) {
        try {
            Class<?> type = Class.forName(level.testSuiteClass());
            TestSuite suite = (TestSuite) type.getDeclaredConstructor().newInstance();
            return suite.cases().stream().map(c -> new TestDescriptor(c.id(), c.title())).toList();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Набор тестов уровня " + level.id() + " недоступен", e);
        }
    }
}
