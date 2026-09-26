package city.subroutine.sandbox.worker;

import city.subroutine.sandbox.testing.AssertionFailure;
import city.subroutine.sandbox.testing.TestContext;
import city.subroutine.sandbox.testing.ThrowingRunnable;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/** Контекст одного теста. Используется только из потока этого теста. */
final class DefaultTestContext implements TestContext {

    private final TargetBinding binding;
    private final JvmProbe probe;
    private Object receiver;

    DefaultTestContext(TargetBinding binding, JvmProbe probe) {
        this.binding = binding;
        this.probe = probe;
    }

    @Override
    public Object invoke(Object... args) throws Throwable {
        return handle().invokeWithArguments(args == null ? new Object[] {null} : Arrays.copyOf(args, args.length));
    }

    @Override
    public MethodHandle handle() throws Throwable {
        if (!binding.hasMethod()) {
            throw new IllegalStateException("Контракт уровня — интерфейс; используйте newInstance()");
        }
        if (binding.isStaticMethod()) {
            return binding.method();
        }
        if (receiver == null) {
            receiver = binding.newInstance();
        }
        return binding.method().bindTo(receiver);
    }

    @Override
    public <T> T newInstance(Class<T> contract) throws Throwable {
        if (!contract.isAssignableFrom(binding.playerClass())) {
            throw new AssertionFailure("Класс " + binding.playerClass().getName() + " не реализует " + contract.getName());
        }
        return contract.cast(binding.newInstance());
    }

    @Override
    public long measureAllocatedBytes(ThrowingRunnable action) throws Throwable {
        long before = probe.currentThreadAllocatedBytes();
        action.run();
        return probe.currentThreadAllocatedBytes() - before;
    }

    @Override
    public Class<?> playerClass() {
        return binding.playerClass();
    }
}
