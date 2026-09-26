package city.subroutine.sandbox.policy;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Белый список API, доступного коду игрока. Работает на внутренних именах JVM.
 *
 * <p>Модель: тип разрешён, если на него есть явное правило (не DENY) или его пакет в списке разрешённых.
 * Для члена типа проверяются правила <b>всех</b> супертипов владельца: иначе ограничение
 * {@code Thread.getAllStackTraces} обходилось бы через {@code class T extends Thread} и вызов {@code T.getAllStackTraces()}.
 *
 * <p>Запрещено по умолчанию всё, что не перечислено: файлы, сеть, NIO, процессы, рефлексия, загрузчики классов,
 * JNI/FFM, системные свойства и переменные окружения, завершение JVM, подмена System.out/in.
 */
public final class SandboxPolicy {

    enum Access {
        /** Тип целиком запрещён. */
        DENY,
        /** Упоминание типа разрешено, из членов — только перечисленные (пустой список = только сам тип). */
        ALLOW_ONLY,
        /** Тип разрешён, кроме перечисленных членов. */
        DENY_ONLY
    }

    record ClassRule(Access access, Set<String> members) {
    }

    /** Методы Object: javac указывает владельцем статический тип получателя, поэтому они разрешены везде. */
    private static final Set<String> OBJECT_MEMBERS =
            Set.of("equals", "hashCode", "toString", "getClass", "wait", "notify", "notifyAll");

    private static final Set<String> STANDARD_PACKAGES = Set.of(
            "java/lang",
            "java/lang/annotation",
            "java/lang/ref",
            "java/math",
            "java/text",
            "java/time",
            "java/time/format",
            "java/time/temporal",
            "java/util",
            "java/util/function",
            "java/util/stream",
            "java/util/regex",
            "java/util/concurrent",
            "java/util/concurrent/atomic",
            "java/util/concurrent/locks");

    private static final Map<String, ClassRule> STANDARD_RULES = standardRules();

    private final String playerPackage;
    private final Set<String> allowedPackages;
    private final Map<String, ClassRule> rules;

    private SandboxPolicy(String playerPackage, Set<String> allowedPackages, Map<String, ClassRule> rules) {
        this.playerPackage = playerPackage;
        this.allowedPackages = Set.copyOf(allowedPackages);
        this.rules = Map.copyOf(rules);
    }

    /**
     * @param playerPackage      пакет игрока в двоичной форме ({@code city.player})
     * @param apiPackages        пакеты API уровня в двоичной форме
     */
    public static SandboxPolicy standard(String playerPackage, Collection<String> apiPackages) {
        Objects.requireNonNull(playerPackage, "playerPackage");
        Set<String> packages = new HashSet<>(STANDARD_PACKAGES);
        for (String api : apiPackages) {
            packages.add(api.replace('.', '/'));
        }
        return new SandboxPolicy(playerPackage.replace('.', '/'), packages, STANDARD_RULES);
    }

    /** Внутреннее имя пакета игрока: {@code city/player}. */
    public String playerPackageInternal() {
        return playerPackage;
    }

    /** Класс лежит в пакете игрока или его подпакете. */
    public boolean isInPlayerPackage(String internalName) {
        return internalName.startsWith(playerPackage + "/");
    }

    /** Разрешено ли упоминать тип (не из кода игрока) в байткоде игрока. */
    public boolean isTypeAllowed(String internalName) {
        ClassRule rule = rules.get(internalName);
        if (rule != null) {
            return rule.access() != Access.DENY;
        }
        return allowedPackages.contains(packageOf(internalName));
    }

    /**
     * Правило «только перечисленные члены» ограничивает лишь члены, объявленные самим типом:
     * {@code Class} реализует {@code TypeDescriptor}, но это не должно запрещать {@code Class#getName}.
     */
    public boolean restrictsOnlyOwnMembers(String internalType) {
        ClassRule rule = rules.get(internalType);
        return rule != null && rule.access() == Access.ALLOW_ONLY;
    }

    /**
     * Проверка члена по правилу одного конкретного типа из иерархии владельца.
     *
     * @return причина запрета или пусто
     */
    public Optional<String> checkMemberRule(String internalType, String member) {
        ClassRule rule = rules.get(internalType);
        if (rule == null) {
            return Optional.empty();
        }
        String display = internalType.replace('/', '.');
        return switch (rule.access()) {
            case DENY -> Optional.of("тип " + display + " запрещён в песочнице");
            case ALLOW_ONLY -> rule.members().contains(member) || OBJECT_MEMBERS.contains(member)
                    ? Optional.empty()
                    : Optional.of(display + "#" + member + " не входит в список разрешённых членов " + display);
            case DENY_ONLY -> rule.members().contains(member)
                    ? Optional.of(display + "#" + member + " запрещён в песочнице")
                    : Optional.empty();
        };
    }

    /**
     * Фильтр загрузчика классов (второй эшелон защиты после проверки байткода).
     * Пакеты java/lang/invoke и java/lang/runtime нужны JVM для связывания invokedynamic
     * (лямбды, конкатенация строк, records, switch по шаблонам); прямой доступ к их членам закрыт проверкой байткода.
     */
    public boolean isLoadable(String binaryName) {
        String internal = binaryName.replace('.', '/');
        return isTypeAllowed(internal)
                || internal.startsWith("java/lang/invoke/")
                || internal.startsWith("java/lang/runtime/");
    }

    static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static Map<String, ClassRule> standardRules() {
        Map<String, ClassRule> r = new HashMap<>();

        // --- процессы, загрузчики, модули, обход стека, группы потоков, логгеры JVM
        deny(r, "java/lang/ClassLoader", "java/lang/ProcessBuilder", "java/lang/ProcessBuilder$Redirect",
                "java/lang/ProcessBuilder$Redirect$Type", "java/lang/Process", "java/lang/ProcessHandle",
                "java/lang/ProcessHandle$Info", "java/lang/SecurityManager", "java/lang/StackWalker",
                "java/lang/StackWalker$StackFrame", "java/lang/StackWalker$Option", "java/lang/Module",
                "java/lang/ModuleLayer", "java/lang/ModuleLayer$Controller", "java/lang/ThreadGroup",
                "java/lang/Package", "java/lang/System$Logger", "java/lang/System$LoggerFinder",
                "java/lang/ClassValue");
        // --- загрузка ресурсов и сервисов через ClassLoader
        deny(r, "java/util/ServiceLoader", "java/util/ServiceLoader$Provider", "java/util/ResourceBundle",
                "java/util/ResourceBundle$Control");

        // --- System: только время, копирование массивов и вывод (перехватывается песочницей)
        allowOnly(r, "java/lang/System", "nanoTime", "currentTimeMillis", "arraycopy", "identityHashCode",
                "lineSeparator", "out", "err");
        // --- Runtime: только информация о ресурсах
        allowOnly(r, "java/lang/Runtime", "getRuntime", "availableProcessors", "freeMemory", "totalMemory",
                "maxMemory", "gc");
        // --- Class: интроспекция без рефлексии и без доступа к загрузчику
        allowOnly(r, "java/lang/Class", "getName", "getSimpleName", "getTypeName", "getCanonicalName",
                "getPackageName", "isInstance", "isAssignableFrom", "cast", "isArray", "isPrimitive", "isInterface",
                "isEnum", "isRecord", "isSealed", "isAnonymousClass", "isLocalClass", "isMemberClass",
                "getComponentType", "componentType", "arrayType", "getSuperclass", "getInterfaces",
                "desiredAssertionStatus", "getEnumConstants", "getModifiers");
        // --- Thread: всё для уровней про конкурентность, кроме доступа к чужим потокам и опасных операций
        denyOnly(r, "java/lang/Thread", "stop", "suspend", "resume", "setDefaultUncaughtExceptionHandler",
                "getAllStackTraces", "setContextClassLoader", "getContextClassLoader", "enumerate",
                "getThreadGroup", "countStackFrames", "checkAccess");
        // --- чтение системных свойств в обход System.getProperty
        denyOnly(r, "java/lang/Integer", "getInteger");
        denyOnly(r, "java/lang/Long", "getLong");
        denyOnly(r, "java/lang/Boolean", "getBoolean");

        // --- связывание invokedynamic, которое генерирует javac
        allowOnly(r, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "makeConcat");
        allowOnly(r, "java/lang/invoke/LambdaMetafactory", "metafactory", "altMetafactory");
        allowOnly(r, "java/lang/invoke/MethodHandles");
        allowOnly(r, "java/lang/invoke/MethodHandles$Lookup");
        allowOnly(r, "java/lang/invoke/MethodHandle");
        allowOnly(r, "java/lang/invoke/MethodType");
        allowOnly(r, "java/lang/invoke/CallSite");
        allowOnly(r, "java/lang/invoke/TypeDescriptor");
        allowOnly(r, "java/lang/runtime/ObjectMethods", "bootstrap");
        allowOnly(r, "java/lang/runtime/SwitchBootstraps", "typeSwitch", "enumSwitch");

        // --- java.io: только интерфейсы ресурсов и исключения (уровни про try-with-resources) и печать
        allowAll(r, "java/io/Serializable", "java/io/Closeable", "java/io/Flushable", "java/io/IOException",
                "java/io/UncheckedIOException", "java/io/EOFException");
        allowOnly(r, "java/io/PrintStream", "print", "println", "printf", "format", "flush", "append", "write");
        allowAll(r, "java/nio/charset/Charset", "java/nio/charset/StandardCharsets");
        return r;
    }

    private static void deny(Map<String, ClassRule> rules, String... types) {
        for (String type : types) {
            rules.put(type, new ClassRule(Access.DENY, Set.of()));
        }
    }

    private static void allowAll(Map<String, ClassRule> rules, String... types) {
        for (String type : types) {
            rules.put(type, new ClassRule(Access.DENY_ONLY, Set.of()));
        }
    }

    private static void allowOnly(Map<String, ClassRule> rules, String type, String... members) {
        rules.put(type, new ClassRule(Access.ALLOW_ONLY, Set.of(members)));
    }

    private static void denyOnly(Map<String, ClassRule> rules, String type, String... members) {
        rules.put(type, new ClassRule(Access.DENY_ONLY, Set.of(members)));
    }
}
