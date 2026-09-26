package city.subroutine.sandbox.testing;

/** Тело теста. Любое исключение, кроме {@link AssertionFailure}, классифицируется как ошибка кода игрока. */
@FunctionalInterface
public interface TestBody {

    void run(TestContext context) throws Throwable;
}
