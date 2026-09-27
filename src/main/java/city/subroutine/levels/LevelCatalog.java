package city.subroutine.levels;

import city.subroutine.levels.energy.EnergySuite;
import city.subroutine.levels.powergrid.PowerGridSuite;
import city.subroutine.levels.telemetry.TelemetrySuite;
import city.subroutine.levels.traffic.TrafficCounterSuite;
import city.subroutine.levels.warehouse.WarehouseSuite;
import city.subroutine.levels.water.WaterSuite;
import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.SandboxLimits;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Кампания «Subroutine City». В продакшене — контент-пакеты; здесь — шесть уровней, покрывающих программу курса. */
public final class LevelCatalog {

    public static final String PLAYER_PACKAGE = "city.player";

    private static final String CH1 = "Глава 1. Граничные случаи";
    private static final String CH2 = "Глава 2. Исключения и ресурсы";
    private static final String CH3 = "Глава 3. Контракты и ООП";
    private static final String CH4 = "Глава 4. Память";
    private static final String CH5 = "Глава 5. Конкурентность";

    private static final List<LevelDefinition> ALL = List.of(
            powerGrid(), water(), warehouse(), telemetry(), traffic(), energy());

    private static final Map<String, LevelDefinition> BY_ID =
            ALL.stream().collect(Collectors.toUnmodifiableMap(LevelDefinition::id, Function.identity()));

    private LevelCatalog() {
    }

    public static Optional<LevelDefinition> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Уровни в порядке кампании. */
    public static List<LevelDefinition> all() {
        return ALL.stream().sorted(Comparator.comparingInt(l -> l.info().order())).toList();
    }

    public static List<String> ids() {
        return all().stream().map(LevelDefinition::id).toList();
    }

    private static String starter(String className) {
        return """
                package city.player;

                // Напишите класс %s целиком: контракт — на вкладке «Контракт».
                // Пакет city.player обязателен.
                """.formatted(className);
    }

    // ------------------------------------------------------------------------------------------------

    private static LevelDefinition powerGrid() {
        return new LevelDefinition(
                "powergrid-01",
                "Энергосеть: суммарная нагрузка",
                new EntryPoint.MethodEntry(PLAYER_PACKAGE + ".PowerGrid", "totalLoad", List.of("int[]"), "long"),
                PowerGridSuite.class.getName(),
                PLAYER_PACKAGE,
                List.of(),
                SandboxLimits.defaults(),
                new LevelInfo(CH1, 1, 1, "power",
                        "Посчитайте нагрузку сети и не дайте счётчику переполниться.",
                        """
                                Диспетчер энергосети каждые 10 мс запрашивает суммарную нагрузку всех секторов города. \
                                В часы пик отдельные секторы выдают нагрузку до Integer.MAX_VALUE кВт, а датчики \
                                отключённых районов присылают null. Прошлая версия диспетчера сложила нагрузку в int — \
                                и полгорода ушло в блэкаут.""",
                        List.of(
                                "Метод public static long totalLoad(int[] sectorLoads) в классе city.player.PowerGrid.",
                                "null → IllegalArgumentException.",
                                "Отрицательная нагрузка любого сектора → IllegalArgumentException.",
                                "Сумма не переполняется при значениях до Integer.MAX_VALUE.",
                                "Входной массив не изменяется.",
                                "Метод вызывается в горячем цикле: никаких лишних объектов (boxing, стримы, копии)."),
                        List.of("Переполнение int и выбор типа", "Граничные случаи и null",
                                "Аллокации в горячих циклах"),
                        """
                                package city.player;

                                public final class PowerGrid {
                                    /**
                                     * @param sectorLoads нагрузка по секторам, кВт
                                     * @return суммарная нагрузка
                                     * @throws IllegalArgumentException если sectorLoads == null
                                     *         или есть отрицательная нагрузка
                                     */
                                    public static long totalLoad(int[] sectorLoads)
                                }
                                """,
                        starter("PowerGrid")));
    }

    private static LevelDefinition water() {
        return new LevelDefinition(
                "water-01",
                "Водозабор: закрыть все задвижки",
                new EntryPoint.ContractEntry(PLAYER_PACKAGE + ".WaterStation",
                        "city.subroutine.levels.water.api.FlowMeter"),
                WaterSuite.class.getName(),
                PLAYER_PACKAGE,
                List.of("city.subroutine.levels.water.api"),
                SandboxLimits.defaults(),
                new LevelInfo(CH2, 2, 2, "water",
                        "Замерьте расход и не оставьте ни одной задвижки открытой.",
                        """
                                Водозабор замеряет расход, открывая задвижки по очереди. Старые задвижки клинит: \
                                при открытии, при замере и даже при закрытии. Каждая забытая открытой задвижка — \
                                затопленный квартал, а потерянная ошибка — авария, о которой диспетчер не узнает.""",
                        List.of(
                                "Класс city.player.WaterStation implements FlowMeter с public-конструктором без параметров.",
                                "Каждая успешно открытая задвижка закрывается ровно один раз, даже при ошибке.",
                                "Первая ошибка останавливает замер и выходит наружу тем же объектом, без обёрток.",
                                "Ошибка закрытия не теряется: suppressed к основной или сама, если основной нет.",
                                "board == null или valveIds == null → IllegalArgumentException."),
                        List.of("try-with-resources", "Проброс исключений без потерь", "Suppressed-исключения"),
                        """
                                package city.subroutine.levels.water.api;

                                public class ValveJammedException extends Exception { … }

                                public interface Valve extends AutoCloseable {
                                    String id();
                                    int flowRate() throws ValveJammedException;
                                    @Override void close() throws ValveJammedException;
                                }

                                public interface ValveBoard {
                                    Valve open(String valveId) throws ValveJammedException;
                                }

                                public interface FlowMeter {
                                    long measureTotal(ValveBoard board, List<String> valveIds)
                                            throws ValveJammedException;
                                }
                                """,
                        starter("WaterStation")));
    }

    private static LevelDefinition warehouse() {
        return new LevelDefinition(
                "warehouse-01",
                "Склад: принцип подстановки Лисков",
                new EntryPoint.ContractEntry(PLAYER_PACKAGE + ".ColdStorage",
                        "city.subroutine.levels.warehouse.api.Storage"),
                WarehouseSuite.class.getName(),
                PLAYER_PACKAGE,
                List.of("city.subroutine.levels.warehouse.api"),
                SandboxLimits.defaults(),
                new LevelInfo(CH3, 3, 2, "warehouse",
                        "Постройте холодильный склад, который не сломает диспетчера.",
                        """
                                Диспетчер порта распределяет контейнеры по складам через общий интерфейс Storage и \
                                не знает, какой склад перед ним. Новый холодильный склад на 8 мест принимает только \
                                контейнеры не теплее −18 °C. Прошлый подрядчик бросал исключение на тёплый контейнер — \
                                и диспетчер падал посреди разгрузки.""",
                        List.of(
                                "Класс city.player.ColdStorage implements Storage, вместимость 8.",
                                "Принимает только контейнеры с temperature ≤ −18.0.",
                                "Неподходящий контейнер или полный склад → offer() возвращает false, без исключений.",
                                "poll() — FIFO; пустой склад → null.",
                                "offer(null) → NullPointerException."),
                        List.of("Принцип подстановки Лисков", "Предусловия и постусловия контракта",
                                "Проверка реализации против эталонной модели"),
                        """
                                package city.subroutine.levels.warehouse.api;

                                public record Container(String id, double temperature) { }

                                /**
                                 * capacity() > 0 и не меняется.
                                 * offer(null) → NPE; не может принять → false без изменений; иначе true.
                                 * poll() — FIFO; пустой склад → null.
                                 * size() всегда в [0, capacity()].
                                 */
                                public interface Storage {
                                    int capacity();
                                    boolean offer(Container container);
                                    Container poll();
                                    int size();
                                }
                                """,
                        starter("ColdStorage")));
    }

    private static LevelDefinition telemetry() {
        return new LevelDefinition(
                "telemetry-01",
                "Телеметрия: кэш без утечек",
                new EntryPoint.ContractEntry(PLAYER_PACKAGE + ".SensorCache",
                        "city.subroutine.levels.telemetry.api.TelemetryCache"),
                TelemetrySuite.class.getName(),
                PLAYER_PACKAGE,
                List.of("city.subroutine.levels.telemetry.api"),
                SandboxLimits.defaults().withHeapMegabytes(96).withPerTestTimeout(Duration.ofSeconds(8))
                        .withTotalTimeout(Duration.ofSeconds(40)),
                new LevelInfo(CH4, 4, 3, "telemetry",
                        "Кэш показаний датчиков, который переживёт шторм.",
                        """
                                Центр телеметрии хранит последние показания датчиков для панели мэра. Во время \
                                магнитной бури в сеть одновременно выходят миллионы датчиков. Кэш, который помнит всё, \
                                съедает память центра за минуты. Памяти у центра — 96 МБ.""",
                        List.of(
                                "Класс city.player.SensorCache implements TelemetryCache.",
                                "Не более 1000 датчиков; при переполнении вытесняется давно не использованный (LRU).",
                                "record() и latest() считаются обращением.",
                                "sensorId == null → IllegalArgumentException.",
                                "Выдерживает поток показаний 2 000 000 разных датчиков в 96 МБ."),
                        List.of("Утечки памяти в коллекциях", "LRU-вытеснение", "Ограниченные структуры данных"),
                        """
                                package city.subroutine.levels.telemetry.api;

                                public interface TelemetryCache {
                                    int CAPACITY = 1_000;
                                    void record(String sensorId, double value);
                                    OptionalDouble latest(String sensorId);
                                    int size();
                                }
                                """,
                        starter("SensorCache")));
    }

    private static LevelDefinition traffic() {
        return new LevelDefinition(
                "traffic-01",
                "Транспорт: потокобезопасный счётчик",
                new EntryPoint.ContractEntry(PLAYER_PACKAGE + ".SectorTrafficCounter",
                        "city.subroutine.levels.traffic.api.TrafficCounter"),
                TrafficCounterSuite.class.getName(),
                PLAYER_PACKAGE,
                List.of("city.subroutine.levels.traffic.api"),
                SandboxLimits.defaults().withPerTestTimeout(Duration.ofSeconds(5)),
                new LevelInfo(CH5, 5, 3, "traffic",
                        "Считайте машины сразу со всех датчиков и не теряйте ни одной.",
                        """
                                Восемь датчиков на перекрёстках одновременно сообщают о каждом проезде. \
                                Старый счётчик терял больше половины событий, и светофоры переключались вслепую.""",
                        List.of(
                                "Класс city.player.SectorTrafficCounter implements TrafficCounter.",
                                "Сектор вне [0, 64) → IllegalArgumentException.",
                                "Ни одно событие не теряется при одновременных вызовах из многих потоков."),
                        List.of("Гонки данных", "Атомарные операции", "java.util.concurrent.atomic"),
                        """
                                package city.subroutine.levels.traffic.api;

                                public interface TrafficCounter {
                                    int SECTORS = 64;
                                    void register(int sector);
                                    long ofSector(int sector);
                                    long total();
                                }
                                """,
                        starter("SectorTrafficCounter")));
    }

    private static LevelDefinition energy() {
        return new LevelDefinition(
                "energy-01",
                "Энергобанк: переводы без deadlock",
                new EntryPoint.ContractEntry(PLAYER_PACKAGE + ".EnergyBank",
                        "city.subroutine.levels.energy.api.EnergyGrid"),
                EnergySuite.class.getName(),
                PLAYER_PACKAGE,
                List.of("city.subroutine.levels.energy.api"),
                SandboxLimits.defaults().withPerTestTimeout(Duration.ofSeconds(4)),
                new LevelInfo(CH5, 6, 4, "energy",
                        "Перебрасывайте энергию между аккумуляторами, не заморозив город.",
                        """
                                Энергобанк перераспределяет заряд между аккумуляторами районов по запросам десятков \
                                подстанций одновременно — в том числе встречным: A→B и B→A в одну и ту же миллисекунду. \
                                Аккумуляторы не потокобезопасны. Прошлая прошивка захватывала замки в разном порядке, \
                                и однажды вечером все подстанции замерли навсегда.""",
                        List.of(
                                "Класс city.player.EnergyBank implements EnergyGrid.",
                                "amount ≤ 0, from == to или null → IllegalArgumentException.",
                                "Перевод атомарен; недостаточно заряда → false без изменений.",
                                "Энергия сохраняется при любом числе одновременных переводов.",
                                "Встречные переводы не приводят к взаимной блокировке."),
                        List.of("Взаимная блокировка (deadlock)", "Порядок захвата замков", "Атомарность операций"),
                        """
                                package city.subroutine.levels.energy.api;

                                /** Не потокобезопасен. Монитор аккумулятора можно использовать как его замок. */
                                public final class Battery {
                                    public Battery(int id, long charge)
                                    public int id()          // уникальный номер
                                    public long charge()
                                    public void withdraw(long amount)  // IllegalStateException, если мало заряда
                                    public void deposit(long amount)
                                }

                                public interface EnergyGrid {
                                    boolean transfer(Battery from, Battery to, long amount);
                                }
                                """,
                        starter("EnergyBank")));
    }
}
