# Subroutine City — CodeRunnerService

Изолятор исполнения Java-кода игрока. Модуль принимает исходный код строкой и выполняет его по шагам:

1. компилирует в памяти через `javax.tools.JavaCompiler`;
2. статически проверяет байткод по белому списку API;
3. загружает код в изолированный `ClassLoader`;
4. прогоняет набор тестов уровня с лимитами времени, памяти и потоков;
5. возвращает структурированный `ExecutionResult`: статусы тестов, метрики CPU и аллокаций, стек-трейсы, снимки потоков.

Java 21, без внешних runtime-зависимостей. JUnit 5 нужен только для тестов.

```bash
mvn test                                    # 52 теста, включая сквозные с реальными процессами
mvn -q compile
java -cp target/classes city.subroutine.sandbox.cli.RunnerCli powergrid-01 examples/powergrid/PowerGrid.java
java -cp target/classes city.subroutine.sandbox.cli.RunnerCli powergrid-01 examples/powergrid/PowerGridBuggy.java
java -cp target/classes city.subroutine.sandbox.cli.RunnerCli traffic-01  examples/traffic/SectorTrafficCounterRacy.java
```

## Ключевое архитектурное решение: процесс, а не поток

В Java 21 `SecurityManager` отключён (JEP 411), а `Thread.stop()` бросает `UnsupportedOperationException`.
Отсюда два следствия:

- внутри одного JVM нельзя прервать зависший `while (true)`: поток игрока будет жечь CPU вечно;
- нельзя ограничить кучу отдельному `ClassLoader`: `new long[1 << 30]` положит весь сервер.

Поэтому каждый запуск исполняется в **одноразовом JVM-процессе**. Процесс даёт жёсткие гарантии:
`-Xmx`, `-XX:+ExitOnOutOfMemoryError` и `destroyForcibly()` по таймауту. Задержку старта (~1 с на JVM и javac)
скрывает пул заранее прогретых воркеров. Каждый воркер используется ровно один раз.

```
 Движок (UE5/Unity)  ──gRPC/Protobuf──▶  Хост: DefaultCodeRunnerService
                                            │  RequestValidator (размер, пакеты)
                                            │  WorkerPool (прогретые одноразовые процессы)
                                            │  ExecutionSession (дедлайны по фазам, сборка результата)
                                            ▼
                         stdin: REQUEST ─▶ ┌──────────────── процесс-песочница (java -Xmx128m …) ───────────────┐
                                           │ WorkerMain: stdout = протокол; System.out/err/in игрока подменены    │
                                           │  1. InMemoryCompiler   javac в памяти, -proc:none                    │
                                           │  2. BytecodeVerifier   constant pool → белый список + иерархия      │
                                           │  3. SandboxClassLoader байткод из памяти, фильтр делегирования       │
                                           │  4. TargetBinding      проверка контракта уровня → MethodHandle      │
                                           │  5. TestCaseExecutor   поток на тест, таймаут, ThreadMXBean-метрики  │
                                           │     ThreadWatchdog     лимит потоков → halt                          │
                         stdout: кадры ◀── │  COMPILATION · POLICY · SUITE_STARTED · TEST_RESULT×N · FINISHED    │
                                           └──────────────────────────────────────────────────────────────────────┘
```

Результаты тестов передаются хосту **потоково**, сразу после каждого теста. Если процесс убит OOM или сторожем
потоков, уже пройденные тесты не теряются. Хост определяет, на каком тесте произошёл сбой, и помечает
оставшиеся как `SKIPPED`.

## Модель безопасности (эшелоны)

| # | Эшелон | Что закрывает |
|---|--------|---------------|
| 1 | `RequestValidator` | Размер исходников, пакет игрока, пересечение с API и наборами тестов |
| 2 | javac `-proc:none` | Исполнение чужих процессоров аннотаций на этапе компиляции |
| 3 | `BytecodeVerifier` | Любую ссылку на тип или член вне белого списка: файлы, сеть, NIO, процессы, рефлексию, `ClassLoader`, `System.exit/getenv/getProperty/setOut`, `Unsafe`, JNI, `ServiceLoader`, `ThreadGroup`, `Thread.getAllStackTraces`. Проверка идёт **до загрузки**, ни одна инструкция не исполняется |
| 4 | Проверка иерархии | Обход через подкласс: `class T extends Thread` → `T.getAllStackTraces()` проверяется по правилам всех супертипов |
| 5 | `SandboxClassLoader` | Делегирует только разрешённые типы, ресурсы недоступны |
| 6 | Процесс JVM | `-Xmx`, `ExitOnOutOfMemoryError` (игрок не может «проглотить» OOM), `MaxMetaspaceSize`, чистое окружение (`env` очищен), свой `tmpdir`, `DisableAttachMechanism` |
| 7 | Таймауты | Тест в отдельном потоке → TIMEOUT/DEADLOCK → `Runtime.halt`; хостовый дедлайн → `destroyForcibly` |
| 8 | `ThreadWatchdog` | Бомбу из потоков → `THREAD_LIMIT_EXCEEDED` → `halt` |
| 9 | ОС (продакшен) | `RunnerConfig.commandPrefix`: nsjail / bubblewrap / firejail, cgroup v2 (`pids.max`, `memory.max`, `cpu.max`), сеть в пустом namespace, read-only FS, seccomp |

Эшелоны 1–8 реализованы и покрыты тестами. Эшелон 9 обязателен для продакшена: это системная защита на случай
уязвимости в JVM или ошибки в белом списке. Модуль подготовлен к нему через `commandPrefix`. Пример:

```java
RunnerConfig.defaults().withCommandPrefix(List.of(
        "nsjail", "--quiet", "--disable_proc", "--iface_no_lo",
        "--cgroup_pids_max", "64", "--cgroup_mem_max", "268435456", "--"));
```

### Белый список (`SandboxPolicy`)

- **Разрешены пакеты целиком:** `java.lang`, `java.lang.annotation`, `java.lang.ref`, `java.math`, `java.text`,
  `java.time[.format|.temporal]`, `java.util`, `java.util.function`, `java.util.stream`, `java.util.regex`,
  `java.util.concurrent[.atomic|.locks]`, а также API-пакеты уровня.
- **Ограничены до списка членов:** `System` (время, `arraycopy`, `out/err`), `Runtime` (информация о ресурсах),
  `Class` (интроспекция без рефлексии), `PrintStream` (печать).
- **Запрещены отдельные члены:** `Thread.getAllStackTraces/stop/getContextClassLoader/...`, `Integer.getInteger`
  и аналоги (обход `System.getProperty`).
- **Разрешено только для `invokedynamic`:** лямбды, конкатенация строк, records, `switch` по шаблонам.
- Всё остальное запрещено по умолчанию.

## Контракты уровней (без «заполни пропуск»)

Игрок пишет класс целиком. Контракт задаёт только внешнюю форму:

- `EntryPoint.MethodEntry("city.player.PowerGrid", "totalLoad", List.of("int[]"), "long")`: точная сигнатура.
  Проверяются public, тип возврата и то, что метод объявлен в коде игрока, а не унаследован.
- `EntryPoint.ContractEntry("city.player.SectorTrafficCounter", "…api.TrafficCounter")`: класс реализует
  интерфейс уровня. Так проверяется ООП-дизайн через поведение (LSP/OCP).

Набор тестов (`TestSuite`) — доверенный код гейм-дизайнера. Он лежит вне пакета API, поэтому игрок
не может на него сослаться и подсмотреть ответы; это проверяет тест `levelSuiteIsNotVisibleToPlayer`.
Что доступно набору тестов через `TestContext`:

| Метод | Назначение |
|-------|------------|
| `invoke(args…)` | Вызов с упаковкой аргументов. Исключение игрока пробрасывается как есть |
| `handle()` | Точный `MethodHandle` для горячих циклов: `(long) h.invokeExact(data)` без boxing |
| `newInstance(Contract.class)` | Новый объект игрока, приведённый к интерфейсу |
| `measureAllocatedBytes(action)` | Аллокации текущего потока: ловит boxing, стримы, копии в горячих циклах |

Для проверок используются `Check.equal / throwsType / atMost / isTrue`. Сообщения на русском, `expected`/`actual`
уходят в diff-панель IDE.

## Результат

`ExecutionResult` содержит:

- `status`: `SUCCESS`, `TESTS_FAILED`, `COMPILATION_ERROR`, `POLICY_VIOLATION`, `CONTRACT_VIOLATION`, `TIMEOUT`,
  `DEADLOCK`, `MEMORY_LIMIT_EXCEEDED`, `THREAD_LIMIT_EXCEEDED`, `REJECTED`, `SANDBOX_FAILURE`;
- `diagnostics`: ошибки javac с координатами и стабильным кодом (`compiler.err.expected`), по которому клиент
  подставляет русский текст;
- `policyViolations`: правило, класс и ссылка (`java.lang.System#exit`);
- `tests[]`: для каждого теста статус, сообщение, expected/actual, `ErrorReport` (стек без кадров обвязки,
  `playerCode` на каждом кадре, цепочка причин, suppressed), метрики (`wall`, `cpu`, `allocatedBytes`, `peakHeap`,
  GC), перехваченный вывод, `ThreadSnapshot[]` при таймауте или deadlock (состояние, монитор, владелец монитора,
  стек) и `leakedThreads` (незакрытый `ExecutorService`);
- `metrics`: сводные метрики и код завершения процесса.

`ThreadSnapshot` — готовые данные для Deadlock Visualizer: граф «поток → ждёт монитор → владелец».
`ErrorReport.firstPlayerFrame()` показывает, какую строку подсветить в IDE и какой узел города «взорвать».

## Протокол хост ↔ воркер

Кадры `[u1 type][s4 length][payload]`, полезная нагрузка — ручной бинарный кодек (`WireCodec`).
Java-сериализация не используется: хост читает поток процесса, в котором исполнялся недоверенный код.
Все длины ограничены, а повреждённый кадр даёт `ProtocolException` и `SANDBOX_FAILURE`.
Сообщения JVM (например, о OOM) идут в stderr (`-XX:+DisplayVMOutputToStderr`), поэтому stdout принадлежит
только протоколу.

## Замеры: почему им можно верить

- `-XX:TieredStopAtLevel=1`: только C1, без escape analysis. Лишние аллокации не «исчезают» под оптимизатором,
  и тест на горячий цикл детерминирован. Эталонное решение даёт 0 байт на 5000 вызовов, решение со стримом
  и boxing — ~80 МБ.
- `-XX:+UseSerialGC`: один поток GC, стабильные паузы и счётчики.
- CPU и аллокации снимаются изнутри потока теста (`com.sun.management.ThreadMXBean`).
- Аллокации в потоках, созданных игроком, в `allocatedBytes` теста не входят. Их видно по `peakHeapBytes` и GC.

## Известные ограничения

- Лимит потоков проверяется опросом раз в 2 мс. Всплеск короче интервала, который сразу завершается, не
  ловится. Жёсткая граница — `pids.max` на уровне ОС (эшелон 9).
- `peakHeapBytes` — сумма пиков пулов кучи, то есть оценка сверху.
- `leakedThreads` носит информационный характер. Уровень, для которого утечка — провал, должен проверять
  это сам (например, требовать `AutoCloseable` и вызывать `close()`).

## Структура

```
src/main/java/city/subroutine/
  sandbox/api        контракты: CodeRunnerService, ExecutionRequest/Result, модели
  sandbox/compiler   InMemoryCompiler (JavaCompiler + ForwardingJavaFileManager)
  sandbox/policy     ClassFileScanner, SandboxPolicy, BytecodeVerifier
  sandbox/protocol   кадры и кодеки хост ↔ воркер
  sandbox/worker     WorkerMain, SandboxClassLoader, TestCaseExecutor, ThreadWatchdog, JvmProbe
  sandbox/host       DefaultCodeRunnerService, WorkerPool, WorkerLauncher, ExecutionSession
  sandbox/testing    API для авторов уровней: TestSuite, TestContext, Check
  sandbox/report     RussianReportFormatter (эталонная таблица ru-RU)
  levels/            LevelCatalog + уровни powergrid-01, traffic-01
examples/            эталонные, ошибочные и «злонамеренные» решения игрока
```
