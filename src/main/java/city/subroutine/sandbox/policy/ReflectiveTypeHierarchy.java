package city.subroutine.sandbox.policy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Иерархия через {@link Class#forName(String, boolean, ClassLoader)} без инициализации.
 * Вызывается только для типов, уже прошедших проверку {@link SandboxPolicy#isTypeAllowed(String)},
 * то есть для классов JDK и API уровня. Байткод игрока сюда не попадает.
 */
public final class ReflectiveTypeHierarchy implements TypeHierarchy {

    private final ClassLoader loader;
    private final Map<String, Optional<List<String>>> cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Set<String>>> declaredCache = new ConcurrentHashMap<>();

    public ReflectiveTypeHierarchy(ClassLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public Optional<List<String>> directSupertypes(String internalName) {
        return cache.computeIfAbsent(internalName, this::lookup);
    }

    @Override
    public boolean declaresMember(String internalName, String memberName) {
        return declaredCache.computeIfAbsent(internalName, this::declaredMembers)
                .map(members -> members.contains(memberName))
                .orElse(true);
    }

    private Optional<Set<String>> declaredMembers(String internalName) {
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false, loader);
            Set<String> names = new HashSet<>();
            for (Method method : type.getDeclaredMethods()) {
                names.add(method.getName());
            }
            for (Field field : type.getDeclaredFields()) {
                names.add(field.getName());
            }
            if (type.getDeclaredConstructors().length > 0) {
                names.add("<init>");
            }
            return Optional.of(Set.copyOf(names));
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return Optional.empty();
        }
    }

    private Optional<List<String>> lookup(String internalName) {
        try {
            Class<?> type = Class.forName(internalName.replace('/', '.'), false, loader);
            List<String> result = new ArrayList<>();
            if (type.getSuperclass() != null) {
                result.add(internalOf(type.getSuperclass()));
            }
            for (Class<?> iface : type.getInterfaces()) {
                result.add(internalOf(iface));
            }
            return Optional.of(List.copyOf(result));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static String internalOf(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
