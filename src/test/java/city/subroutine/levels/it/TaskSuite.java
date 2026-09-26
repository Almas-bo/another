package city.subroutine.levels.it;

import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.List;

/** Тестовый уровень: контракт {@code city.player.Task implements Runnable}, два прогона run(). */
public final class TaskSuite implements TestSuite {

    @Override
    public List<TestCase> cases() {
        return List.of(
                TestCase.of("first-run", "Первый запуск задачи", ctx -> ctx.newInstance(Runnable.class).run()),
                TestCase.of("second-run", "Второй запуск задачи", ctx -> ctx.newInstance(Runnable.class).run()));
    }
}
