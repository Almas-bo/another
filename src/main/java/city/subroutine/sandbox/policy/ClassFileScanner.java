package city.subroutine.sandbox.policy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Минимальный парсер class-файла (JVMS §4.1–4.6): constant pool, заголовок, поля, методы.
 * Без внешних зависимостей (ASM не нужен, а java.lang.classfile в Java 21 ещё preview).
 * Атрибуты не разбираются: всё, что JVM может разрешить при исполнении, проходит через constant pool.
 */
public final class ClassFileScanner {

    private static final int MAGIC = 0xCAFEBABE;
    /** Java 21. Более новые версии javac с {@code --release 21} не выдаёт. */
    private static final int MAX_MAJOR_VERSION = 65;

    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_FLOAT = 4;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_STRING = 8;
    private static final int CONSTANT_FIELDREF = 9;
    private static final int CONSTANT_METHODREF = 10;
    private static final int CONSTANT_INTERFACE_METHODREF = 11;
    private static final int CONSTANT_NAME_AND_TYPE = 12;
    private static final int CONSTANT_METHOD_HANDLE = 15;
    private static final int CONSTANT_METHOD_TYPE = 16;
    private static final int CONSTANT_DYNAMIC = 17;
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;
    private static final int CONSTANT_MODULE = 19;
    private static final int CONSTANT_PACKAGE = 20;

    private ClassFileScanner() {
    }

    public static ParsedClass parse(byte[] bytes) throws MalformedClassException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return parse(in);
        } catch (IOException | RuntimeException e) {
            throw new MalformedClassException("Не удалось разобрать class-файл: " + e.getMessage(), e);
        }
    }

    private static ParsedClass parse(DataInputStream in) throws IOException, MalformedClassException {
        if (in.readInt() != MAGIC) {
            throw new MalformedClassException("Неверная сигнатура class-файла");
        }
        in.readUnsignedShort(); // minor
        int major = in.readUnsignedShort();
        if (major > MAX_MAJOR_VERSION) {
            throw new MalformedClassException("Неподдерживаемая версия class-файла: " + major);
        }

        int count = in.readUnsignedShort();
        int[] tags = new int[count];
        String[] utf8 = new String[count];
        int[] refA = new int[count];
        int[] refB = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case CONSTANT_UTF8 -> utf8[i] = in.readUTF();
                case CONSTANT_INTEGER, CONSTANT_FLOAT -> in.readInt();
                case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                    in.readLong();
                    i++; // занимает два слота
                }
                case CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
                        refA[i] = in.readUnsignedShort();
                case CONSTANT_FIELDREF, CONSTANT_METHODREF, CONSTANT_INTERFACE_METHODREF, CONSTANT_NAME_AND_TYPE,
                     CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> {
                    refA[i] = in.readUnsignedShort();
                    refB[i] = in.readUnsignedShort();
                }
                case CONSTANT_METHOD_HANDLE -> {
                    refA[i] = in.readUnsignedByte();
                    refB[i] = in.readUnsignedShort();
                }
                default -> throw new MalformedClassException("Неизвестный тег constant pool: " + tag + " в #" + i);
            }
        }

        ConstantPool pool = new ConstantPool(tags, utf8, refA, refB);
        Set<String> classRefs = new LinkedHashSet<>();
        Set<ParsedClass.MemberRef> memberRefs = new LinkedHashSet<>();
        Set<String> descriptors = new HashSet<>();
        for (int i = 1; i < count; i++) {
            switch (tags[i]) {
                case CONSTANT_CLASS -> classRefs.add(pool.utf8(refA[i]));
                case CONSTANT_FIELDREF, CONSTANT_METHODREF, CONSTANT_INTERFACE_METHODREF -> {
                    ParsedClass.RefKind kind = switch (tags[i]) {
                        case CONSTANT_FIELDREF -> ParsedClass.RefKind.FIELD;
                        case CONSTANT_METHODREF -> ParsedClass.RefKind.METHOD;
                        default -> ParsedClass.RefKind.INTERFACE_METHOD;
                    };
                    String owner = pool.className(refA[i]);
                    int nat = pool.expect(refB[i], CONSTANT_NAME_AND_TYPE);
                    memberRefs.add(new ParsedClass.MemberRef(kind, owner, pool.utf8(refA[nat]), pool.utf8(refB[nat])));
                }
                case CONSTANT_NAME_AND_TYPE -> descriptors.add(pool.utf8(refB[i]));
                case CONSTANT_METHOD_TYPE -> descriptors.add(pool.utf8(refA[i]));
                default -> {
                    // константы, строки, handles (указывают на *ref, уже учтённые), indy/condy (NameAndType учтён)
                }
            }
        }

        int access = in.readUnsignedShort();
        String name = pool.className(in.readUnsignedShort());
        int superIndex = in.readUnsignedShort();
        String superName = superIndex == 0 ? null : pool.className(superIndex);
        int interfaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>(interfaceCount);
        for (int i = 0; i < interfaceCount; i++) {
            interfaces.add(pool.className(in.readUnsignedShort()));
        }
        List<ParsedClass.MemberDecl> fields = readMembers(in, pool, descriptors);
        List<ParsedClass.MemberDecl> methods = readMembers(in, pool, descriptors);
        return new ParsedClass(name, superName, List.copyOf(interfaces), access, fields, methods,
                classRefs, memberRefs, descriptors);
    }

    private static List<ParsedClass.MemberDecl> readMembers(DataInputStream in, ConstantPool pool, Set<String> descriptors)
            throws IOException, MalformedClassException {
        int count = in.readUnsignedShort();
        List<ParsedClass.MemberDecl> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int access = in.readUnsignedShort();
            String name = pool.utf8(in.readUnsignedShort());
            String descriptor = pool.utf8(in.readUnsignedShort());
            descriptors.add(descriptor);
            members.add(new ParsedClass.MemberDecl(name, descriptor, access));
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.readUnsignedShort();
                in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
            }
        }
        return List.copyOf(members);
    }

    /**
     * Ссылочные типы дескриптора поля или метода:
     * {@code ([Ljava/io/File;I)Ljava/lang/String;} → {@code java/io/File, java/lang/String}.
     */
    public static void collectDescriptorTypes(String descriptor, Set<String> out) throws MalformedClassException {
        for (int i = 0; i < descriptor.length(); i++) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    throw new MalformedClassException("Повреждённый дескриптор: " + descriptor);
                }
                out.add(descriptor.substring(i + 1, end));
                i = end;
            }
        }
    }

    /**
     * Типы из имени CONSTANT_Class: обычное внутреннее имя ({@code java/io/File})
     * или массив в дескрипторной форме ({@code [[Ljava/io/File;}, {@code [I}).
     */
    public static void collectClassRefTypes(String classRef, Set<String> out) throws MalformedClassException {
        if (classRef.startsWith("[")) {
            collectDescriptorTypes(classRef, out);
        } else {
            out.add(classRef);
        }
    }

    private record ConstantPool(int[] tags, String[] utf8, int[] refA, int[] refB) {

        String utf8(int index) throws MalformedClassException {
            expect(index, CONSTANT_UTF8);
            return utf8[index];
        }

        String className(int index) throws MalformedClassException {
            expect(index, CONSTANT_CLASS);
            return utf8(refA[index]);
        }

        int expect(int index, int tag) throws MalformedClassException {
            if (index <= 0 || index >= tags.length || tags[index] != tag) {
                throw new MalformedClassException("Ожидалась константа с тегом " + tag + " в #" + index);
            }
            return index;
        }
    }
}
