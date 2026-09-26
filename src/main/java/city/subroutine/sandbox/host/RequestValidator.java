package city.subroutine.sandbox.host;

import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.JavaNames;
import city.subroutine.sandbox.api.SourceUnit;

import java.util.Optional;

/** Проверки запроса до запуска процесса (дешёвый отказ без траты ресурсов песочницы). */
final class RequestValidator {

    private RequestValidator() {
    }

    /** @return причина отказа или пусто */
    static Optional<String> validate(ExecutionRequest request) {
        int chars = request.totalSourceChars();
        if (chars > request.limits().maxSourceChars()) {
            return Optional.of("Исходный код слишком большой: " + chars + " символов при лимите "
                    + request.limits().maxSourceChars());
        }
        String playerPackage = request.playerPackage();
        if (playerPackage.equals("java") || playerPackage.startsWith("java.") || playerPackage.startsWith("javax.")
                || playerPackage.startsWith("jdk.") || playerPackage.startsWith("sun.")
                || playerPackage.startsWith("city.subroutine.sandbox")) {
            return Optional.of("Недопустимый пакет игрока: " + playerPackage);
        }
        for (String api : request.allowedApiPackages()) {
            if (api.equals(playerPackage) || api.startsWith(playerPackage + ".") || playerPackage.startsWith(api + ".")) {
                return Optional.of("Пакет API уровня пересекается с пакетом игрока: " + api);
            }
            if (api.startsWith("city.subroutine.sandbox")) {
                return Optional.of("Внутренние пакеты песочницы не могут быть API уровня: " + api);
            }
        }
        if (JavaNames.packageOf(request.testSuiteClass()).startsWith(playerPackage)
                || request.allowedApiPackages().contains(JavaNames.packageOf(request.testSuiteClass()))) {
            return Optional.of("Набор тестов не может находиться в пакете игрока или API уровня");
        }
        boolean entryDeclared = false;
        for (SourceUnit unit : request.sources()) {
            if (!JavaNames.packageOf(unit.className()).equals(playerPackage)
                    && !JavaNames.packageOf(unit.className()).startsWith(playerPackage + ".")) {
                return Optional.of("Класс " + unit.className() + " должен находиться в пакете " + playerPackage);
            }
            entryDeclared |= unit.className().equals(request.entryPoint().className());
        }
        if (!entryDeclared) {
            return Optional.of("Среди исходников нет класса точки входа " + request.entryPoint().className());
        }
        return Optional.empty();
    }
}
