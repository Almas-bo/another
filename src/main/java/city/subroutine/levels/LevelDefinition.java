package city.subroutine.levels;

import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.SandboxLimits;

import java.util.List;
import java.util.Objects;

/** Серверное описание уровня: контракт, набор тестов, разрешённый API и лимиты. */
public record LevelDefinition(
        String id,
        String title,
        EntryPoint entryPoint,
        String testSuiteClass,
        String playerPackage,
        List<String> allowedApiPackages,
        SandboxLimits limits) {

    public LevelDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(entryPoint, "entryPoint");
        Objects.requireNonNull(testSuiteClass, "testSuiteClass");
        Objects.requireNonNull(playerPackage, "playerPackage");
        allowedApiPackages = List.copyOf(allowedApiPackages);
        Objects.requireNonNull(limits, "limits");
    }

    /** Запрос из кода, пришедшего из редактора игрока. */
    public ExecutionRequest request(String requestId, String playerCode) {
        return ExecutionRequest.singleSource(requestId, playerCode, entryPoint, testSuiteClass, playerPackage,
                allowedApiPackages, limits);
    }
}
