package city.subroutine.levels;

import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.testing.TestSuite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Страховка от расхождения описания уровня (то, что видит игрок) и настоящего контракта. */
class LevelCatalogTest {

    @Test
    void levelsAreOrderedAndUnique() {
        List<LevelDefinition> levels = LevelCatalog.all();
        assertEquals(6, levels.size());
        Set<Integer> orders = new HashSet<>();
        for (LevelDefinition level : levels) {
            assertTrue(orders.add(level.info().order()), "Повтор порядка: " + level.id());
        }
    }

    @Test
    void contractTextMentionsEveryContractMethod() throws Exception {
        for (LevelDefinition level : LevelCatalog.all()) {
            String text = level.info().contractText();
            switch (level.entryPoint()) {
                case EntryPoint.MethodEntry m -> assertTrue(text.contains(m.methodName() + "("),
                        level.id() + ": в контракте нет метода " + m.methodName());
                case EntryPoint.ContractEntry c -> {
                    Class<?> contract = Class.forName(c.contractInterface());
                    assertTrue(text.contains("interface " + contract.getSimpleName()),
                            level.id() + ": в контракте нет интерфейса " + contract.getSimpleName());
                    for (Method method : contract.getDeclaredMethods()) {
                        if (!Modifier.isStatic(method.getModifiers())) {
                            assertTrue(text.contains(method.getName() + "("),
                                    level.id() + ": в контракте нет метода " + method.getName());
                        }
                    }
                }
            }
        }
    }

    @Test
    void suitesInstantiateAndStarterCodeIsNotASolution() throws Exception {
        for (LevelDefinition level : LevelCatalog.all()) {
            TestSuite suite = (TestSuite) Class.forName(level.testSuiteClass()).getDeclaredConstructor().newInstance();
            assertFalse(suite.cases().isEmpty(), level.id());
            String starter = level.info().starterCode();
            assertTrue(starter.startsWith("package " + level.playerPackage() + ";"), level.id());
            assertFalse(starter.contains("class "), level.id() + ": стартовый код не должен содержать класс — "
                    + "уровни без «заполни пропуск»");
        }
    }
}
