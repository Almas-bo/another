package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.JavaNames;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Проверка контракта уровня и привязка к коду игрока через {@link MethodHandle}.
 * Классы игрока загружаются без инициализации: статические инициализаторы исполнятся уже внутри
 * первого теста, под его лимитом времени.
 */
final class TargetBinding {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "byte", byte.class, "char", char.class, "short", short.class,
            "int", int.class, "long", long.class, "float", float.class, "double", double.class, "void", void.class);

    private final Class<?> playerClass;
    private final MethodHandle method;
    private final boolean staticMethod;
    private final MethodHandle constructor;

    private TargetBinding(Class<?> playerClass, MethodHandle method, boolean staticMethod, MethodHandle constructor) {
        this.playerClass = playerClass;
        this.method = method;
        this.staticMethod = staticMethod;
        this.constructor = constructor;
    }

    static TargetBinding resolve(EntryPoint entryPoint, SandboxClassLoader loader) throws ContractException {
        Class<?> type = loadPlayerClass(entryPoint.className(), loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle constructor = findNoArgConstructor(type, lookup);
        return switch (entryPoint) {
            case EntryPoint.MethodEntry entry -> bindMethod(entry, type, constructor, lookup, loader);
            case EntryPoint.ContractEntry entry -> bindContract(entry, type, constructor, loader);
        };
    }

    private static TargetBinding bindMethod(EntryPoint.MethodEntry entry, Class<?> type, MethodHandle constructor,
                                            MethodHandles.Lookup lookup, SandboxClassLoader loader)
            throws ContractException {
        Class<?>[] parameters = new Class<?>[entry.parameterTypes().size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = resolveType(entry.parameterTypes().get(i), loader);
        }
        Class<?> returnType = resolveType(entry.returnType(), loader);
        Method found;
        try {
            found = type.getMethod(entry.methodName(), parameters);
        } catch (NoSuchMethodException e) {
            throw new ContractException("В классе " + type.getName() + " не найден public-метод "
                    + entry.signature() + "." + describeCandidates(type, entry.methodName()));
        }
        if (!loader.isPlayerClass(found.getDeclaringClass().getName())) {
            throw new ContractException("Метод " + entry.signature() + " должен быть объявлен в коде игрока, а не унаследован от "
                    + found.getDeclaringClass().getName());
        }
        if (found.getReturnType() != returnType) {
            throw new ContractException("Метод " + entry.methodName() + " должен возвращать " + entry.returnType()
                    + ", а возвращает " + found.getReturnType().getTypeName());
        }
        boolean isStatic = Modifier.isStatic(found.getModifiers());
        if (!isStatic) {
            requireInstantiable(type, constructor);
        }
        try {
            return new TargetBinding(type, lookup.unreflect(found), isStatic, constructor);
        } catch (IllegalAccessException e) {
            throw new ContractException("Метод " + entry.signature() + " недоступен: " + e.getMessage());
        }
    }

    private static TargetBinding bindContract(EntryPoint.ContractEntry entry, Class<?> type, MethodHandle constructor,
                                              SandboxClassLoader loader) throws ContractException {
        Class<?> contract;
        try {
            contract = Class.forName(entry.contractInterface(), false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ContractException("Интерфейс контракта " + entry.contractInterface() + " недоступен");
        }
        if (!contract.isInterface()) {
            throw new ContractException(entry.contractInterface() + " не является интерфейсом");
        }
        if (!contract.isAssignableFrom(type)) {
            throw new ContractException("Класс " + type.getName() + " должен реализовывать интерфейс " + contract.getName());
        }
        requireInstantiable(type, constructor);
        return new TargetBinding(type, null, false, constructor);
    }

    private static Class<?> loadPlayerClass(String name, SandboxClassLoader loader) throws ContractException {
        if (!loader.isPlayerClass(name)) {
            throw new ContractException("Класс " + name + " не найден. Проверьте объявление package и имя public-класса.");
        }
        Class<?> type;
        try {
            type = Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ContractException("Класс " + name + " не удалось загрузить: " + e);
        }
        if (!Modifier.isPublic(type.getModifiers())) {
            throw new ContractException("Класс " + name + " должен быть public");
        }
        if (type.isInterface() || type.isAnnotation()) {
            throw new ContractException(name + " должен быть классом, а не интерфейсом");
        }
        return type;
    }

    private static MethodHandle findNoArgConstructor(Class<?> type, MethodHandles.Lookup lookup) {
        if (Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        try {
            return lookup.findConstructor(type, MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static void requireInstantiable(Class<?> type, MethodHandle constructor) throws ContractException {
        if (Modifier.isAbstract(type.getModifiers())) {
            throw new ContractException("Класс " + type.getName() + " не должен быть abstract");
        }
        if (constructor == null) {
            throw new ContractException("Классу " + type.getName() + " нужен public-конструктор без параметров");
        }
    }

    static Class<?> resolveType(String sourceName, ClassLoader loader) throws ContractException {
        JavaNames.requireTypeName(sourceName, "тип контракта");
        int dims = 0;
        String element = sourceName;
        while (element.endsWith("[]")) {
            element = element.substring(0, element.length() - 2);
            dims++;
        }
        Class<?> type = PRIMITIVES.get(element);
        if (type == null) {
            try {
                type = Class.forName(element, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new ContractException("Тип " + element + " из контракта недоступен");
            }
        }
        for (int i = 0; i < dims; i++) {
            type = type.arrayType();
        }
        return type;
    }

    private static String describeCandidates(Class<?> type, String name) {
        List<String> candidates = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name)) {
                candidates.add((Modifier.isStatic(m.getModifiers()) ? "static " : "")
                        + m.getReturnType().getTypeName() + " " + m.getName() + "("
                        + Arrays.stream(m.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(", "))
                        + ")");
            }
        }
        return candidates.isEmpty() ? "" : " Найдены: " + String.join("; ", candidates) + ".";
    }

    Class<?> playerClass() {
        return playerClass;
    }

    boolean hasMethod() {
        return method != null;
    }

    boolean isStaticMethod() {
        return staticMethod;
    }

    MethodHandle method() {
        return method;
    }

    Object newInstance() throws Throwable {
        if (constructor == null) {
            throw new IllegalStateException("У класса игрока нет доступного конструктора без параметров");
        }
        return constructor.invoke();
    }
}
