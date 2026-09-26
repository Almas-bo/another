package city.subroutine.sandbox.protocol;

/** Типы кадров. Направление: H→W — хост в воркер, W→H — воркер в хост. */
public enum FrameType {
    /** W→H: воркер запущен, javac прогрет, ждёт запрос. */
    HELLO(1),
    /** H→W: ExecutionRequest. Отправляется ровно один раз — воркер одноразовый. */
    REQUEST(2),
    /** W→H: результат компиляции. */
    COMPILATION(3),
    /** W→H: результат проверки байткода. */
    POLICY(4),
    /** W→H: список тестов набора (id + название), в порядке исполнения. */
    SUITE_STARTED(5),
    /** W→H: результат одного теста (потоково, сразу по завершении теста). */
    TEST_RESULT(6),
    /** W→H: финальный вердикт; после него воркер завершает процесс. */
    FINISHED(7);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FrameType fromCode(int code) throws ProtocolException {
        for (FrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ProtocolException("Неизвестный тип кадра: " + code);
    }
}
