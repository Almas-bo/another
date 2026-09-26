package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Загрузка набора тестов уровня (доверенный код) через загрузчик обвязки, не через загрузчик игрока. */
final class SuiteLoader {

    static final class SuiteException extends Exception {

        private static final long serialVersionUID = 1L;

        SuiteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private SuiteLoader() {
    }

    static List<TestCase> load(String suiteClass, ClassLoader trustedLoader) throws SuiteException {
        try {
            Class<?> type = Class.forName(suiteClass, true, trustedLoader);
            if (!TestSuite.class.isAssignableFrom(type)) {
                throw new SuiteException(suiteClass + " не реализует " + TestSuite.class.getName(), null);
            }
            TestSuite suite = (TestSuite) type.getDeclaredConstructor().newInstance();
            List<TestCase> cases = List.copyOf(suite.cases());
            if (cases.isEmpty()) {
                throw new SuiteException("Набор тестов " + suiteClass + " пуст", null);
            }
            Set<String> ids = new HashSet<>();
            for (TestCase testCase : cases) {
                if (!ids.add(testCase.id())) {
                    throw new SuiteException("Повторяющийся id теста: " + testCase.id(), null);
                }
            }
            return cases;
        } catch (SuiteException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new SuiteException("Не удалось загрузить набор тестов " + suiteClass + ": " + e, e);
        }
    }
}
