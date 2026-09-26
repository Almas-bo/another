package city.subroutine.sandbox.policy;

import java.util.List;
import java.util.Optional;

/** Источник информации о доверенных (JDK и API уровня) классах. Имена внутренние. */
public interface TypeHierarchy {

    /**
     * @return прямые супертипы (суперкласс первым, затем интерфейсы) или пусто, если тип неизвестен
     */
    Optional<List<String>> directSupertypes(String internalName);

    /**
     * Объявляет ли тип сам (не наследует) поле, метод или конструктор ({@code <init>}) с таким именем.
     * При невозможности определить — {@code true} (консервативно: правило будет применено).
     */
    boolean declaresMember(String internalName, String memberName);
}
