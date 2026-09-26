package city.subroutine.sandbox.api;

import java.util.List;
import java.util.Objects;

/**
 * Точка входа, которую требует контракт уровня.
 * Уровни не используют «заполни пропуск»: игрок пишет класс целиком, а контракт задаёт только
 * внешнюю форму — сигнатуру метода или интерфейс, который класс обязан реализовать.
 */
public sealed interface EntryPoint permits EntryPoint.MethodEntry, EntryPoint.ContractEntry {

    /** Двоичное имя класса игрока. */
    String className();

    /**
     * Контракт «метод»: public-метод с точной сигнатурой. Статический метод вызывается напрямую,
     * для метода экземпляра на каждый тест создаётся новый объект через public-конструктор без параметров.
     *
     * @param parameterTypes типы параметров в исходной нотации ({@code int[]}, {@code java.lang.String})
     * @param returnType     тип результата в исходной нотации ({@code void}, {@code long})
     */
    record MethodEntry(String className, String methodName, List<String> parameterTypes, String returnType)
            implements EntryPoint {

        public MethodEntry {
            JavaNames.requireBinaryName(className, "MethodEntry.className");
            JavaNames.requireIdentifier(methodName, "MethodEntry.methodName");
            parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes"));
            if (parameterTypes.size() > 255) {
                throw new IllegalArgumentException("Слишком много параметров");
            }
            for (String type : parameterTypes) {
                JavaNames.requireTypeName(type, "MethodEntry.parameterTypes");
                if (type.equals("void")) {
                    throw new IllegalArgumentException("Параметр не может иметь тип void");
                }
            }
            JavaNames.requireTypeName(returnType, "MethodEntry.returnType");
        }

        /** Человекочитаемая сигнатура для сообщений: {@code long totalLoad(int[])}. */
        public String signature() {
            return returnType + " " + methodName + "(" + String.join(", ", parameterTypes) + ")";
        }
    }

    /**
     * Контракт «интерфейс»: неабстрактный public-класс с public-конструктором без параметров,
     * реализующий интерфейс уровня. Проверяет ООП-дизайн (LSP/OCP) через поведение реализации.
     */
    record ContractEntry(String className, String contractInterface) implements EntryPoint {

        public ContractEntry {
            JavaNames.requireBinaryName(className, "ContractEntry.className");
            JavaNames.requireBinaryName(contractInterface, "ContractEntry.contractInterface");
        }
    }
}
