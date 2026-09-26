package city.subroutine.levels;

import city.subroutine.levels.powergrid.PowerGridSuite;
import city.subroutine.levels.traffic.TrafficCounterSuite;
import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.SandboxLimits;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Реестр уровней. В продакшене — загрузка из контент-пакетов; здесь — два эталонных уровня. */
public final class LevelCatalog {

    public static final String PLAYER_PACKAGE = "city.player";

    private static final Map<String, LevelDefinition> LEVELS = Map.of(
            "powergrid-01", new LevelDefinition(
                    "powergrid-01",
                    "Энергосеть: суммарная нагрузка",
                    new EntryPoint.MethodEntry(PLAYER_PACKAGE + ".PowerGrid", "totalLoad", List.of("int[]"), "long"),
                    PowerGridSuite.class.getName(),
                    PLAYER_PACKAGE,
                    List.of(),
                    SandboxLimits.defaults()),
            "traffic-01", new LevelDefinition(
                    "traffic-01",
                    "Транспорт: потокобезопасный счётчик",
                    new EntryPoint.ContractEntry(PLAYER_PACKAGE + ".SectorTrafficCounter",
                            "city.subroutine.levels.traffic.api.TrafficCounter"),
                    TrafficCounterSuite.class.getName(),
                    PLAYER_PACKAGE,
                    List.of("city.subroutine.levels.traffic.api"),
                    SandboxLimits.defaults().withPerTestTimeout(java.time.Duration.ofSeconds(5))));

    private LevelCatalog() {
    }

    public static Optional<LevelDefinition> find(String id) {
        return Optional.ofNullable(LEVELS.get(id));
    }

    public static List<String> ids() {
        return LEVELS.keySet().stream().sorted().toList();
    }
}
