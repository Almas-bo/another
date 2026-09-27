package city.subroutine.levels.water;

import city.subroutine.levels.water.api.FlowMeter;
import city.subroutine.levels.water.api.Valve;
import city.subroutine.levels.water.api.ValveBoard;
import city.subroutine.levels.water.api.ValveJammedException;
import city.subroutine.sandbox.testing.Check;
import city.subroutine.sandbox.testing.TestCase;
import city.subroutine.sandbox.testing.TestSuite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Уровень «Водозабор: закрыть все задвижки» — try-with-resources, проброс исключений, suppressed. */
public final class WaterSuite implements TestSuite {

    @Override
    public List<TestCase> cases() {
        return List.of(
                TestCase.of("sum-flow", "Суммарный расход трёх задвижек", ctx -> {
                    FlowMeter meter = ctx.newInstance(FlowMeter.class);
                    Board board = new Board().valve("A", 10).valve("B", 20).valve("C", 30);
                    Check.equal(60L, meter.measureTotal(board, List.of("A", "B", "C")), "Суммарный расход");
                    board.checkAllClosedOnce();
                }),

                TestCase.of("empty-list", "Пустой список задвижек", ctx -> {
                    FlowMeter meter = ctx.newInstance(FlowMeter.class);
                    Board board = new Board();
                    Check.equal(0L, meter.measureTotal(board, List.of()), "Расход без задвижек");
                    Check.equal(0, board.opened.size(), "Открыто задвижек");
                }),

                TestCase.of("null-arguments", "null вместо пульта или списка — IllegalArgumentException", ctx -> {
                    FlowMeter meter = ctx.newInstance(FlowMeter.class);
                    Check.throwsType(IllegalArgumentException.class, () -> meter.measureTotal(null, List.of("A")),
                            "measureTotal(null, …)");
                    Check.throwsType(IllegalArgumentException.class, () -> meter.measureTotal(new Board(), null),
                            "measureTotal(…, null)");
                }),

                TestCase.of("jam-on-flow", "Задвижку B заклинило при замере", ctx -> {
                    FlowMeter meter = ctx.newInstance(FlowMeter.class);
                    Board board = new Board().valve("A", 10).jamOnFlow("B").valve("C", 30);
                    ValveJammedException thrown = Check.throwsType(ValveJammedException.class,
                            () -> meter.measureTotal(board, List.of("A", "B", "C")), "Замер с заклинившей B");
                    Check.isTrue(thrown == board.flowError("B"),
                            "Наружу должно выйти то же исключение, что бросила задвижка B, без обёрток. Получено: " + thrown);
                    board.checkAllClosedOnce();
                    Check.isTrue(!board.attempted.contains("C"), "После ошибки на B задвижку C открывать нельзя");
                }),

                TestCase.of("jam-on-close", "Задвижку B заклинило при закрытии", ctx -> {
                    FlowMeter meter = ctx.newInstance(FlowMeter.class);
                    Board board = new Board().valve("A", 10).jamOnClose("B", 20).valve("C", 30);
                    ValveJammedException thrown = Check.throwsType(ValveJammedException.class,
                            () -> meter.measureTotal(board, List.of("A", "B", "C")), "Замер с B, заклинившей при закрытии");
                    Check.isTrue(thrown == board.closeError("B"),
                            "Ошибка закрытия B должна выйти наружу. Получено: " + thrown);
                    board.checkAllClosedOnce();
                }),

                TestCase.of("suppressed-kept", "Две ошибки сразу: основная + suppressed", ctx -> {
                    FlowMeter meter = ctx.newInstance(FlowMeter.class);
                    Board board = new Board().valve("A", 10).jamOnFlowAndClose("B");
                    ValveJammedException thrown = Check.throwsType(ValveJammedException.class,
                            () -> meter.measureTotal(board, List.of("A", "B")), "Замер с двойной ошибкой B");
                    Check.isTrue(thrown == board.flowError("B"),
                            "Основной должна остаться ошибка замера, а не ошибка закрытия. Получено: " + thrown);
                    Check.isTrue(Arrays.asList(thrown.getSuppressed()).contains(board.closeError("B")),
                            "Ошибка закрытия потеряна: её нужно добавить как suppressed (try-with-resources делает это сам)");
                    board.checkAllClosedOnce();
                }),

                TestCase.of("jam-on-open", "Задвижку C не удалось открыть", ctx -> {
                    FlowMeter meter = ctx.newInstance(FlowMeter.class);
                    Board board = new Board().valve("A", 10).valve("B", 20).jamOnOpen("C");
                    ValveJammedException thrown = Check.throwsType(ValveJammedException.class,
                            () -> meter.measureTotal(board, List.of("A", "B", "C")), "Замер с неоткрывающейся C");
                    Check.isTrue(thrown == board.openError("C"), "Наружу должна выйти ошибка открытия C. Получено: " + thrown);
                    board.checkAllClosedOnce();
                }));
    }

    /** Пульт-имитатор: считает открытия и закрытия каждой задвижки. */
    private static final class Board implements ValveBoard {

        private final Map<String, Spec> specs = new HashMap<>();
        final List<MockValve> opened = new ArrayList<>();
        final List<String> attempted = new ArrayList<>();

        private static final class Spec {
            int flow;
            ValveJammedException openError;
            ValveJammedException flowError;
            ValveJammedException closeError;
        }

        Board valve(String id, int flow) {
            spec(id).flow = flow;
            return this;
        }

        Board jamOnFlow(String id) {
            spec(id).flowError = new ValveJammedException("Задвижка " + id + ": заклинило при замере");
            return this;
        }

        Board jamOnClose(String id, int flow) {
            spec(id).flow = flow;
            spec(id).closeError = new ValveJammedException("Задвижка " + id + ": заклинило при закрытии");
            return this;
        }

        Board jamOnFlowAndClose(String id) {
            jamOnFlow(id);
            spec(id).closeError = new ValveJammedException("Задвижка " + id + ": заклинило при закрытии");
            return this;
        }

        Board jamOnOpen(String id) {
            spec(id).openError = new ValveJammedException("Задвижка " + id + ": не открывается");
            return this;
        }

        ValveJammedException flowError(String id) {
            return specs.get(id).flowError;
        }

        ValveJammedException closeError(String id) {
            return specs.get(id).closeError;
        }

        ValveJammedException openError(String id) {
            return specs.get(id).openError;
        }

        private Spec spec(String id) {
            return specs.computeIfAbsent(id, k -> new Spec());
        }

        @Override
        public Valve open(String valveId) throws ValveJammedException {
            attempted.add(valveId);
            Spec spec = specs.get(valveId);
            if (spec == null) {
                throw new IllegalStateException("Неизвестная задвижка " + valveId);
            }
            if (spec.openError != null) {
                throw spec.openError;
            }
            MockValve valve = new MockValve(valveId, spec);
            opened.add(valve);
            return valve;
        }

        void checkAllClosedOnce() {
            for (MockValve valve : opened) {
                Check.isTrue(valve.closeCount != 0, "Задвижка " + valve.id + " осталась открытой — утечка ресурса");
                Check.isTrue(valve.closeCount == 1,
                        "Задвижка " + valve.id + " закрыта " + valve.closeCount + " раз(а), а нужно ровно один");
            }
        }

        private static final class MockValve implements Valve {

            private final String id;
            private final Spec spec;
            int closeCount;

            MockValve(String id, Spec spec) {
                this.id = id;
                this.spec = spec;
            }

            @Override
            public String id() {
                return id;
            }

            @Override
            public int flowRate() throws ValveJammedException {
                if (closeCount > 0) {
                    throw new IllegalStateException("Замер на уже закрытой задвижке " + id);
                }
                if (spec.flowError != null) {
                    throw spec.flowError;
                }
                return spec.flow;
            }

            @Override
            public void close() throws ValveJammedException {
                closeCount++;
                if (spec.closeError != null && closeCount == 1) {
                    throw spec.closeError;
                }
            }
        }
    }
}
