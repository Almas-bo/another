package city.subroutine.sandbox.policy;

import java.util.List;
import java.util.Set;

/**
 * Всё, что политике нужно знать о class-файле. Имена — внутренние (JVMS): {@code java/lang/String}.
 *
 * @param superName      суперкласс или {@code null} (только у java/lang/Object)
 * @param classRefs      все CONSTANT_Class (включая массивы в дескрипторной форме)
 * @param memberRefs     все Fieldref/Methodref/InterfaceMethodref
 * @param descriptors    дескрипторы из NameAndType, MethodType, объявлений полей и методов
 */
public record ParsedClass(
        String name,
        String superName,
        List<String> interfaces,
        int accessFlags,
        List<MemberDecl> fields,
        List<MemberDecl> methods,
        Set<String> classRefs,
        Set<MemberRef> memberRefs,
        Set<String> descriptors) {

    public static final int ACC_NATIVE = 0x0100;

    public record MemberDecl(String name, String descriptor, int accessFlags) {
    }

    public enum RefKind { FIELD, METHOD, INTERFACE_METHOD }

    public record MemberRef(RefKind kind, String owner, String name, String descriptor) {
    }

    /** Объявляет ли класс член с таким именем и дескриптором (разрешение ссылки попадёт в этот класс). */
    public boolean declares(String memberName, String descriptor) {
        List<MemberDecl> pool = descriptor.startsWith("(") ? methods : fields;
        for (MemberDecl decl : pool) {
            if (decl.name().equals(memberName) && decl.descriptor().equals(descriptor)) {
                return true;
            }
        }
        return false;
    }
}
