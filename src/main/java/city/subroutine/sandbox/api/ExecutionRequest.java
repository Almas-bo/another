package city.subroutine.sandbox.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Запрос на компиляцию и прогон кода игрока.
 *
 * @param requestId          идентификатор для корреляции с RPC-вызовом движка
 * @param sources            исходники игрока (минимум один)
 * @param entryPoint         контракт уровня
 * @param testSuiteClass     класс набора тестов уровня (реализует {@code city.subroutine.sandbox.testing.TestSuite})
 * @param playerPackage      пакет, в котором обязан лежать весь код игрока
 * @param allowedApiPackages пакеты API уровня, к которым игроку разрешено обращаться (интерфейсы, модели)
 * @param limits             лимиты ресурсов
 */
public record ExecutionRequest(
        String requestId,
        List<SourceUnit> sources,
        EntryPoint entryPoint,
        String testSuiteClass,
        String playerPackage,
        List<String> allowedApiPackages,
        SandboxLimits limits) {

    public static final int MAX_SOURCES = 32;

    public ExecutionRequest {
        Objects.requireNonNull(requestId, "requestId");
        if (requestId.isBlank() || requestId.length() > 128) {
            throw new IllegalArgumentException("requestId должен быть непустым и не длиннее 128 символов");
        }
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty() || sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("Количество исходников должно быть в диапазоне [1, " + MAX_SOURCES + "]");
        }
        Set<String> names = new HashSet<>();
        for (SourceUnit unit : sources) {
            if (!names.add(unit.className())) {
                throw new IllegalArgumentException("Класс объявлен дважды: " + unit.className());
            }
        }
        Objects.requireNonNull(entryPoint, "entryPoint");
        JavaNames.requireBinaryName(testSuiteClass, "testSuiteClass");
        JavaNames.requirePackageName(playerPackage, "playerPackage");
        allowedApiPackages = List.copyOf(Objects.requireNonNull(allowedApiPackages, "allowedApiPackages"));
        for (String pkg : allowedApiPackages) {
            JavaNames.requirePackageName(pkg, "allowedApiPackages");
        }
        Objects.requireNonNull(limits, "limits");
    }

    /** Типичный случай: весь код игрока — одна строка из редактора, класс совпадает с точкой входа. */
    public static ExecutionRequest singleSource(
            String requestId,
            String code,
            EntryPoint entryPoint,
            String testSuiteClass,
            String playerPackage,
            List<String> allowedApiPackages,
            SandboxLimits limits) {
        return new ExecutionRequest(requestId, List.of(new SourceUnit(entryPoint.className(), code)), entryPoint,
                testSuiteClass, playerPackage, allowedApiPackages, limits);
    }

    public int totalSourceChars() {
        long total = 0;
        for (SourceUnit unit : sources) {
            total += unit.code().length();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
