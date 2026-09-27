package city.subroutine.sandbox.host.debug;

import city.subroutine.sandbox.api.DebugTrace;
import city.subroutine.sandbox.api.StackFrameInfo;
import city.subroutine.sandbox.api.TraceStep;
import city.subroutine.sandbox.api.VariableValue;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Запись трассы через JDI: точка останова на каждой строке каждого класса игрока,
 * на каждом срабатывании — снимок строки, глубины, локальных переменных и верхних кадров стека.
 *
 * <p>Методы объектов игрока никогда не вызываются (никакого {@code invokeMethod}): значения строятся
 * только чтением полей, поэтому запись трассы не может исполнить код игрока или зависнуть на нём.
 * После {@link #maxSteps} шагов точки останова снимаются, и программа дорабатывает на полной скорости.
 */
public final class TraceRecorder implements Runnable {

    private static final Logger LOG = Logger.getLogger(TraceRecorder.class.getName());

    private static final int MAX_LOCALS = 24;
    private static final int MAX_STACK = 8;
    private static final int MAX_VALUE_CHARS = 120;
    private static final int MAX_ARRAY_PREVIEW = 8;
    private static final int MAX_FIELDS_PREVIEW = 4;

    private final VirtualMachine vm;
    private final String playerPackage;
    private final int maxSteps;
    private final List<TraceStep> steps = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean truncated;
    private volatile boolean finished;

    public TraceRecorder(VirtualMachine vm, String playerPackage, int maxSteps) {
        this.vm = vm;
        this.playerPackage = playerPackage;
        this.maxSteps = maxSteps;
    }

    /** Регистрирует запрос подготовки классов игрока. Вызывать до {@code vm.resume()}. */
    public void install() {
        ClassPrepareRequest request = vm.eventRequestManager().createClassPrepareRequest();
        request.addClassFilter(playerPackage + ".*");
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.enable();
    }

    @Override
    public void run() {
        try {
            while (true) {
                EventSet events = vm.eventQueue().remove();
                boolean stop = false;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent prepare) {
                        onClassPrepared(prepare.referenceType());
                    } else if (event instanceof BreakpointEvent breakpoint) {
                        onBreakpoint(breakpoint.thread(), breakpoint.location());
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        stop = true;
                    }
                }
                if (stop) {
                    return;
                }
                events.resume();
            }
        } catch (VMDisconnectedException e) {
            // процесс-песочница завершился — штатный конец записи
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Сбой записи трассы", e);
        } finally {
            finished = true;
        }
    }

    public boolean finished() {
        return finished;
    }

    public DebugTrace result(String testId) {
        List<TraceStep> copy;
        synchronized (steps) {
            copy = List.copyOf(steps);
        }
        String note = copy.isEmpty() ? "Код игрока не исполнялся в этом тесте (или остановлен до первой строки)" : null;
        return new DebugTrace(testId, copy, truncated, maxSteps, note);
    }

    private void onClassPrepared(ReferenceType type) {
        if (truncated) {
            return;
        }
        EventRequestManager requests = vm.eventRequestManager();
        try {
            for (Location location : type.allLineLocations()) {
                BreakpointRequest breakpoint = requests.createBreakpointRequest(location);
                breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                breakpoint.enable();
            }
        } catch (AbsentInformationException e) {
            // класс без отладочной информации (не должно случаться: компилируем с -g)
        }
    }

    private void onBreakpoint(ThreadReference thread, Location location) {
        if (truncated) {
            return;
        }
        try {
            int depth = thread.frameCount();
            String className = location.declaringType().name();
            String method = location.method().name();
            int line = location.lineNumber();
            synchronized (steps) {
                // Несколько байткод-позиций одной строки (заголовок for: условие и инкремент) — один шаг.
                if (!steps.isEmpty()) {
                    TraceStep last = steps.get(steps.size() - 1);
                    if (last.thread().equals(thread.name()) && last.depth() == depth && last.line() == line
                            && last.method().equals(method) && last.className().equals(className)) {
                        return;
                    }
                }
            }
            StackFrame top = thread.frame(0);
            TraceStep step = new TraceStep(steps.size(), thread.name(), depth, className, method, line,
                    locals(top), stack(thread));
            steps.add(step);
            if (steps.size() >= maxSteps) {
                truncated = true;
                vm.eventRequestManager().deleteAllBreakpoints();
            }
        } catch (IncompatibleThreadStateException | ObjectCollectedException e) {
            // поток уже не приостановлен или объект собран — шаг пропускается
        }
    }

    private List<VariableValue> locals(StackFrame frame) {
        List<VariableValue> result = new ArrayList<>();
        try {
            for (LocalVariable variable : frame.visibleVariables()) {
                if (result.size() >= MAX_LOCALS) {
                    break;
                }
                Value value = frame.getValue(variable);
                result.add(new VariableValue(variable.name(), variable.typeName(), render(value, true)));
            }
            ObjectReference self = frame.thisObject();
            if (self != null && result.size() < MAX_LOCALS) {
                result.add(0, new VariableValue("this", self.referenceType().name(), render(self, true)));
            }
        } catch (AbsentInformationException e) {
            // нет таблицы локальных переменных
        }
        return result;
    }

    private List<StackFrameInfo> stack(ThreadReference thread) throws IncompatibleThreadStateException {
        List<StackFrameInfo> frames = new ArrayList<>();
        int count = Math.min(MAX_STACK, thread.frameCount());
        for (StackFrame frame : thread.frames(0, count)) {
            Location location = frame.location();
            String className = location.declaringType().name();
            String file;
            try {
                file = location.sourceName();
            } catch (AbsentInformationException e) {
                file = null;
            }
            frames.add(new StackFrameInfo(className, location.method().name(), file, location.lineNumber(),
                    className.startsWith(playerPackage + ".")));
        }
        return frames;
    }

    private String render(Value value, boolean expand) {
        String text = renderRaw(value, expand);
        return text.length() > MAX_VALUE_CHARS ? text.substring(0, MAX_VALUE_CHARS) + "…" : text;
    }

    private String renderRaw(Value value, boolean expand) {
        if (value == null) {
            return "null";
        }
        if (value instanceof PrimitiveValue primitive) {
            return primitive.toString();
        }
        if (value instanceof StringReference string) {
            return '"' + string.value() + '"';
        }
        if (value instanceof ArrayReference array) {
            int length = array.length();
            StringBuilder out = new StringBuilder(simpleName(array.referenceType().name()).replace("[]", ""))
                    .append('[').append(length).append(']');
            if (expand && length > 0) {
                out.append(" {");
                int shown = Math.min(length, MAX_ARRAY_PREVIEW);
                List<Value> values = array.getValues(0, shown);
                for (int i = 0; i < shown; i++) {
                    out.append(i == 0 ? "" : ", ").append(renderRaw(values.get(i), false));
                }
                out.append(length > shown ? ", …}" : "}");
            }
            return out.toString();
        }
        if (value instanceof ObjectReference object) {
            String type = object.referenceType().name();
            String boxed = boxedValue(object, type);
            if (boxed != null) {
                return boxed;
            }
            StringBuilder out = new StringBuilder(simpleName(type)).append('@').append(object.uniqueID());
            if (expand && type.startsWith(playerPackage + ".")) {
                List<Field> fields = object.referenceType().allFields().stream()
                        .filter(f -> !f.isStatic()).limit(MAX_FIELDS_PREVIEW).toList();
                if (!fields.isEmpty()) {
                    out.append(" {");
                    for (int i = 0; i < fields.size(); i++) {
                        Field field = fields.get(i);
                        out.append(i == 0 ? "" : ", ").append(field.name()).append('=')
                                .append(renderRaw(object.getValue(field), false));
                    }
                    out.append('}');
                }
            }
            return out.toString();
        }
        return value.toString();
    }

    private static final Set<String> BOXES = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short",
            "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.lang.Character", "java.lang.Boolean");

    private static String boxedValue(ObjectReference object, String type) {
        if (!BOXES.contains(type)) {
            return null;
        }
        Field field = object.referenceType().fieldByName("value");
        return field == null ? null : String.valueOf(object.getValue(field));
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }
}
