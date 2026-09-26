package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.policy.SandboxPolicy;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;

/**
 * Изолированный загрузчик кода игрока. Классы игрока определяются из байткода в памяти (уже прошедшего
 * {@link city.subroutine.sandbox.policy.BytecodeVerifier}); всё остальное делегируется родителю только
 * если тип разрешён политикой. Ресурсы недоступны. Один загрузчик — один запуск, повторно не используется.
 */
final class SandboxClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> playerClasses;
    private final SandboxPolicy policy;

    SandboxClassLoader(Map<String, byte[]> playerClasses, SandboxPolicy policy, ClassLoader parent) {
        super("player-sandbox", Objects.requireNonNull(parent, "parent"));
        this.playerClasses = Map.copyOf(playerClasses);
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] bytes = playerClasses.get(name);
                if (bytes != null) {
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } else if (policy.isLoadable(name)) {
                    loaded = getParent().loadClass(name);
                } else {
                    throw new ClassNotFoundException("Класс " + name + " недоступен в песочнице");
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }

    @Override
    public URL getResource(String name) {
        return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) {
        return Collections.emptyEnumeration();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return null;
    }

    boolean isPlayerClass(String name) {
        return playerClasses.containsKey(name);
    }
}
