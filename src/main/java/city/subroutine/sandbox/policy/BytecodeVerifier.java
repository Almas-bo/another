package city.subroutine.sandbox.policy;

import city.subroutine.sandbox.api.PolicyViolation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Статическая проверка байткода игрока перед загрузкой. Не исполняет ни одной инструкции игрока.
 *
 * <ol>
 *   <li>Все классы лежат в пакете игрока, native-методов нет.</li>
 *   <li>Каждый упомянутый тип (CONSTANT_Class, дескрипторы) — либо класс игрока, либо разрешён политикой.</li>
 *   <li>Каждая ссылка на член: если она разрешается в класс игрока — разрешена; иначе проверяются правила
 *       всех супертипов владельца.</li>
 * </ol>
 */
public final class BytecodeVerifier {

    private final SandboxPolicy policy;
    private final TypeHierarchy trustedHierarchy;

    public BytecodeVerifier(SandboxPolicy policy, TypeHierarchy trustedHierarchy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.trustedHierarchy = Objects.requireNonNull(trustedHierarchy, "trustedHierarchy");
    }

    /**
     * @param classes двоичное имя → байткод
     * @return нарушения, отсортированные по классу; пустой список — код допущен к загрузке
     */
    public List<PolicyViolation> verify(Map<String, byte[]> classes) {
        List<PolicyViolation> violations = new ArrayList<>();
        Map<String, ParsedClass> parsed = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : new TreeMap<>(classes).entrySet()) {
            try {
                ParsedClass pc = ClassFileScanner.parse(entry.getValue());
                if (!pc.name().equals(entry.getKey().replace('.', '/'))) {
                    violations.add(new PolicyViolation(PolicyViolation.Rule.MALFORMED_CLASS, entry.getKey(),
                            pc.name(), "Имя класса в байткоде не совпадает с ожидаемым"));
                    continue;
                }
                parsed.put(pc.name(), pc);
            } catch (MalformedClassException e) {
                violations.add(new PolicyViolation(PolicyViolation.Rule.MALFORMED_CLASS, entry.getKey(),
                        entry.getKey(), e.getMessage()));
            }
        }
        Resolver resolver = new Resolver(parsed);
        for (ParsedClass pc : parsed.values()) {
            verifyClass(pc, resolver, violations);
        }
        return List.copyOf(violations);
    }

    private void verifyClass(ParsedClass pc, Resolver resolver, List<PolicyViolation> out) {
        String display = binary(pc.name());
        if (!policy.isInPlayerPackage(pc.name())) {
            out.add(new PolicyViolation(PolicyViolation.Rule.WRONG_PACKAGE, display, display,
                    "Весь код должен находиться в пакете " + binary(policy.playerPackageInternal())));
            return;
        }
        for (ParsedClass.MemberDecl method : pc.methods()) {
            if ((method.accessFlags() & ParsedClass.ACC_NATIVE) != 0) {
                out.add(new PolicyViolation(PolicyViolation.Rule.NATIVE_METHOD, display,
                        display + "#" + method.name(), "native-методы запрещены"));
            }
        }

        Set<String> mentioned = new LinkedHashSet<>();
        try {
            for (String ref : pc.classRefs()) {
                ClassFileScanner.collectClassRefTypes(ref, mentioned);
            }
            for (String descriptor : pc.descriptors()) {
                ClassFileScanner.collectDescriptorTypes(descriptor, mentioned);
            }
        } catch (MalformedClassException e) {
            out.add(new PolicyViolation(PolicyViolation.Rule.MALFORMED_CLASS, display, display, e.getMessage()));
            return;
        }
        Set<String> forbiddenTypes = new HashSet<>();
        for (String type : mentioned) {
            if (!resolver.isPlayerType(type) && !policy.isTypeAllowed(type)) {
                forbiddenTypes.add(type);
                out.add(new PolicyViolation(PolicyViolation.Rule.FORBIDDEN_TYPE, display, binary(type),
                        "Тип " + binary(type) + " недоступен в песочнице"));
            }
        }

        for (ParsedClass.MemberRef ref : pc.memberRefs()) {
            String owner = ref.owner();
            if (owner.startsWith("[")) {
                continue; // clone()/length у массивов; типы элементов проверены выше
            }
            if (forbiddenTypes.contains(owner)) {
                continue; // уже сообщено как FORBIDDEN_TYPE
            }
            Optional<String> reason = resolver.checkMember(owner, ref.name(), ref.descriptor());
            reason.ifPresent(r -> out.add(new PolicyViolation(PolicyViolation.Rule.FORBIDDEN_MEMBER, display,
                    binary(owner) + "#" + ref.name(), r)));
        }
    }

    private static String binary(String internal) {
        return internal.replace('/', '.');
    }

    /** Разрешение ссылок с учётом классов игрока (из байткода) и доверенных типов (из JVM). */
    private final class Resolver {

        private final Map<String, ParsedClass> player;
        private final Map<String, List<String>> closureCache = new HashMap<>();

        Resolver(Map<String, ParsedClass> player) {
            this.player = player;
        }

        boolean isPlayerType(String internal) {
            return player.containsKey(internal);
        }

        Optional<String> checkMember(String owner, String name, String descriptor) {
            // 1. Цепочка суперклассов игрока: если член объявлен там — ссылка разрешится в код игрока.
            String current = owner;
            while (current != null && player.containsKey(current)) {
                ParsedClass pc = player.get(current);
                if (pc.declares(name, descriptor)) {
                    return Optional.empty();
                }
                current = pc.superName();
            }
            // 2. Иначе член унаследован от доверенного типа — применяем правила всех супертипов.
            //    Запреты (DENY, DENY_ONLY) наследуются всегда; белый список супертипа — только для
            //    членов, которые этот супертип объявляет сам.
            for (String type : closure(owner)) {
                if (player.containsKey(type)) {
                    continue;
                }
                if (!type.equals(owner) && policy.restrictsOnlyOwnMembers(type)
                        && !trustedHierarchy.declaresMember(type, name)) {
                    continue;
                }
                Optional<String> reason = policy.checkMemberRule(type, name);
                if (reason.isPresent()) {
                    return reason;
                }
            }
            return Optional.empty();
        }

        /** Сам тип и все его супертипы (классы и интерфейсы). */
        private List<String> closure(String type) {
            List<String> cached = closureCache.get(type);
            if (cached != null) {
                return cached;
            }
            List<String> order = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(type);
            while (!queue.isEmpty()) {
                String next = queue.poll();
                if (!seen.add(next)) {
                    continue;
                }
                order.add(next);
                queue.addAll(directSupertypes(next));
            }
            List<String> result = List.copyOf(order);
            closureCache.put(type, result);
            return result;
        }

        private List<String> directSupertypes(String type) {
            ParsedClass pc = player.get(type);
            if (pc != null) {
                List<String> result = new ArrayList<>();
                if (pc.superName() != null) {
                    result.add(pc.superName());
                }
                result.addAll(pc.interfaces());
                return result;
            }
            if (!policy.isTypeAllowed(type) && !policy.isLoadable(type)) {
                return List.of(); // запрещённые типы не загружаем даже для анализа
            }
            return trustedHierarchy.directSupertypes(type).orElse(List.of());
        }
    }
}
