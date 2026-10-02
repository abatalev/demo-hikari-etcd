package com.abatalev.demo.etcdhikari.provisor.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectionGrowthTest {

    private static final int N = 100;

    /** Узел без публикации: инстанс держит не больше своего потолка (худший случай). */
    private static ConnectionGrowth.NodeState node(String key, int ceiling, Integer target) {
        return new ConnectionGrowth.NodeState(key, ceiling, target, null, false);
    }

    /** Узел с подтверждённой публикацией долга. */
    private static ConnectionGrowth.NodeState node(String key, int ceiling, Integer target, int debt) {
        return new ConnectionGrowth.NodeState(key, ceiling, target, debt, true);
    }

    private static ConnectionGrowth.Result plan(int budget, ConnectionGrowth.NodeState... nodes) {
        return ConnectionGrowth.plan(budget, List.of(nodes));
    }

    /** Команды плана по узлам. */
    private static Map<String, Integer> commands(ConnectionGrowth.Result result) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ConnectionGrowth.Decision d : result.decisions()) {
            map.put(d.nodeKey(), d.command());
        }
        return map;
    }

    /**
     * Сколько соединений флот будет держать сразу после применения команд.
     *
     * <p>Растущий инстанс держит не больше новой команды — его потолок не понижался. Сжимаемый
     * инстанс держит прежний потолок до тех пор, пока не вернёт соединения сам: это и есть
     * неосвобождённое сжатие следующего прохода.
     */
    private static int heldAfterPass(List<ConnectionGrowth.NodeState> nodes,
            ConnectionGrowth.Result result) {
        Map<String, Integer> commands = commands(result);
        int held = 0;
        for (ConnectionGrowth.NodeState node : nodes) {
            Integer command = commands.get(node.nodeKey());
            if (command != null && command > node.ceiling()) {
                // Растущий инстанс добирает приказанное сразу одним изменением (eager fill).
                held += command;
            } else {
                // Сжимаемый или стоящий на месте держит прежнее, пока освобождение не отчитается.
                // Доли нет — конфигурация снята, инстанс тоже держит своё до отчёта.
                held += node.ceiling() + ConnectionGrowth.debt(node);
            }
        }
        return held;
    }

    @Test
    @DisplayName("устойчивое состояние: роста нет, доли уже равны потолкам")
    void steadyStateHasNoGrowth() {
        ConnectionGrowth.Result result = plan(N, node("a", 25, 25, 0), node("b", 25, 25, 0),
                node("c", 25, 25, 0), node("d", 25, 25, 0));

        assertEquals(0, result.growable());
        assertEquals(100, result.sumCeilings());
        assertEquals(0, result.sumDebt());
        assertEquals(Map.of("a", 25, "b", 25, "c", 25, "d", 25), commands(result));
    }

    @Test
    @DisplayName("rolling 4×25 → 5×20: сначала сжатие, новый узел без доли до освобождения")
    void rollingFourToFive() {
        // 5-й узел только появился: потолка нет, доли ещё не выдавали.
        ConnectionGrowth.Result result = plan(N,
                node("a", 25, 20, 0), node("b", 25, 20, 0), node("c", 25, 20, 0),
                node("d", 25, 20, 0), node("e", 0, 20));

        // Ни одного роста: флот занимает все 100, а четверо сжимаются — место не освободилось.
        assertEquals(0, result.growable());
        assertEquals(100, result.sumHeld());
        assertEquals(Map.of("a", 20, "b", 20, "c", 20, "d", 20, "e", 0), commands(result));
    }

    @Test
    @DisplayName("освобождение сжатия открывает место ровно на освобождённое")
    void releasedShrinkUnlocksExactlyTheReleasedAmount() {
        // Четверо сжались до 20 и отчитались: каждый держит 25, то есть по 5 сверх потолка.
        ConnectionGrowth.Result result = plan(N,
                node("a", 20, 20, 5), node("b", 20, 20, 5), node("c", 20, 20, 5),
                node("d", 20, 20, 5), node("e", 0, 20));

        assertEquals(80, result.sumCeilings());
        assertEquals(20, result.sumDebt());
        assertEquals(100, result.sumHeld());
        // Сжимаемое место не считается свободным, хотя потолки его уже не занимают.
        assertEquals(0, result.growable());
        assertEquals(Map.of("a", 20, "b", 20, "c", 20, "d", 20, "e", 0), commands(result));
    }

    @Test
    @DisplayName("частичное освобождение открывает ровно освобождённую часть")
    void partialReleaseUnlocksPart() {
        // Двое освободили всё, двое держат ещё по 5: свободно 10 из 20.
        ConnectionGrowth.Result result = plan(N,
                node("a", 20, 20, 0), node("b", 20, 20, 0), node("c", 20, 20, 5),
                node("d", 20, 20, 5), node("e", 0, 20));

        assertEquals(10, result.sumDebt());
        assertEquals(10, result.growable());
        assertEquals(10, commands(result).get("e"));
    }

    @Test
    @DisplayName("после полного освобождения новый узел получает свою долю")
    void newNodeGetsItsShareAfterFullRelease() {
        ConnectionGrowth.Result result = plan(N,
                node("a", 20, 20, 0), node("b", 20, 20, 0), node("c", 20, 20, 0),
                node("d", 20, 20, 0), node("e", 0, 20));

        assertEquals(0, result.sumDebt());
        assertEquals(80, result.sumHeld());
        assertEquals(20, result.growable());
        Map<String, Integer> commands = commands(result);
        assertEquals(20, commands.get("e"));
        // Потолки выросли на 20, сумма ровно N — бюджет не пробит.
        assertEquals(100, commands.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("сумма команд и фактически занятое после прохода не превышают N")
    void commandsAndHeldNeverExceedBudget() {
        // a уже сжат 90 → 50 и держит своё, b не дотягивает, c теряет конфигурацию.
        // Перебираем всю шкалу долга — от «сжимает вовсе» до «освободил всё». Долг возможен
        // только у сжатого: до сжатия флот держит 100, и лишние 10 взяться неоткуда.
        for (int debt = 0; debt <= 40; debt += 4) {
            List<ConnectionGrowth.NodeState> nodes = List.of(node("a", 50, 50, debt),
                    node("b", 10, 50, 0), node("c", 0, null, 0));
            ConnectionGrowth.Result result = plan(N, nodes.toArray(ConnectionGrowth.NodeState[]::new));
            int sum = commands(result).values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(sum <= N, "сумма потолков " + sum + " превысила N=" + N + " при долге " + debt);
            int held = heldAfterPass(nodes, result);
            assertEquals(N, held, "занято после прохода при долге " + debt);
        }
    }

    /** Сколько флот держит по снимку: потолки плюс долги. */
    private static int heldSum(int budget, List<ConnectionGrowth.NodeState> nodes) {
        int held = 0;
        for (ConnectionGrowth.NodeState node : nodes) {
            held += node.ceiling() + ConnectionGrowth.debt(node);
        }
        return held;
    }

    @Test
    @DisplayName("на случайных снимках флот никогда не выходит за N")
    void randomSnapshotsNeverExceedBudget() {
        // Детерминированный генератор: падение теста воспроизводимо.
        Random rnd = new Random(20260930L);
        for (int attempt = 0; attempt < 20_000; attempt++) {
            int budget = 1 + rnd.nextInt(400);
            int count = 1 + rnd.nextInt(9);
            int[] targets = new int[count];
            // Цели по распределению всегда в сумме дают N: столько же должен раздать провижёр.
            int sum = 0;
            for (int i = 0; i < count; i++) {
                targets[i] = rnd.nextInt(budget + 1);
                sum += targets[i];
            }
            if (sum == 0) {
                targets[0] = 1;
                sum = 1;
            }
            // Доводим сумму целей до N долями по крайнему узлу: сумма долей = N.
            targets[count - 1] += budget - sum;
            if (targets[count - 1] < 0) {
                continue;
            }

            // Потолки — результат предыдущего прохода, поэтому их сумма не превышает N. Иначе
            // снимок недостижим и проверял бы неправило против невозможного состояния.
            int[] ceilings = new int[count];
            int pool = budget;
            for (int i = 0; i < count - 1; i++) {
                ceilings[i] = rnd.nextInt(Math.min(targets[i] + 1, pool + 1));
                pool -= ceilings[i];
            }
            ceilings[count - 1] = pool;

            List<ConnectionGrowth.NodeState> nodes = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                Integer reported = rnd.nextInt(4) == 0 ? null : rnd.nextInt(ceilings[i] + 1);
                boolean acknowledged = reported != null && rnd.nextBoolean();
                // Часть узлов теряет конфигурацию — потолок у них не выдаётся.
                Integer target = rnd.nextInt(8) == 0 ? null : targets[i];
                nodes.add(new ConnectionGrowth.NodeState("n" + i, ceilings[i], target, reported,
                        acknowledged));
            }
            // Снимок берётся после предыдущего прохода, а тот держит флот в пределах N. Если
            // «потолок + долг» уже выше N, состояние недостижимо: долг убывает только вместе с
            // потолком (сжатый инстанс отчитывается о меньшем, чем держал), поэтому ужимаем
            // потолки, пока флот не уложится в бюджет.
            while (heldSum(budget, nodes) > budget) {
                int worst = -1;
                int worstHeld = 0;
                for (int i = 0; i < nodes.size(); i++) {
                    ConnectionGrowth.NodeState node = nodes.get(i);
                    if (node.ceiling() <= 0) {
                        continue;
                    }
                    int nodeHeld = node.ceiling() + ConnectionGrowth.debt(node);
                    if (nodeHeld > worstHeld) {
                        worstHeld = nodeHeld;
                        worst = i;
                    }
                }
                if (worst < 0) {
                    break;
                }
                ConnectionGrowth.NodeState node = nodes.get(worst);
                nodes.set(worst, new ConnectionGrowth.NodeState(node.nodeKey(), node.ceiling() - 1,
                        node.target(), node.reported(), node.reportAcknowledged()));
            }
            ConnectionGrowth.Result result = ConnectionGrowth.plan(budget, nodes);
            String state = nodes + " N=" + budget;
            int sumCommands = commands(result).values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(sumCommands <= budget,
                    "сумма потолков " + sumCommands + " превысила N=" + budget + "; " + state);
            int held = heldAfterPass(nodes, result);
            assertTrue(held <= budget,
                    "занято после прохода " + held + " превысило N=" + budget + "; " + state);
            for (ConnectionGrowth.Decision decision : result.decisions()) {
                assertTrue(decision.command() >= 0, "отрицательная команда " + decision.command());
            }
        }
    }

    @Test
    @DisplayName("подтверждённый долг ноль там, где публикации нет, а верхняя оценка полна")
    void confirmedDebtNeedsReport() {
        // «b» без публикации: для ограничения роста он держит весь потолок (худший случай),
        // но подтверждённого сжатия за ним нет — держать сверх потолка молча нельзя.
        ConnectionGrowth.Result result = plan(N,
                node("a", 25, 25, 10),
                node("b", 25, 25));

        assertEquals(10, result.sumConfirmedDebt());
        // Верхняя оценка по-прежнему полна: 25 + (10 + 25).
        assertEquals(35, result.sumDebt());
        assertEquals(85, result.sumHeld());
        // Публикация без подтверждения (относится к прежнему потолку) — тоже не подтверждение.
        ConnectionGrowth.Result stale = plan(N,
                new ConnectionGrowth.NodeState("a", 25, 25, 30, false));
        assertEquals(0, stale.sumConfirmedDebt());
    }

    @Test
    @DisplayName("тесный бюджет N=50, второй инстанс лишний: место не выдаётся, пока первый держит")
    void tightBudgetExcessNodeDoesNotUnlockImmediately() {
        // N=50, m=30: обслуживается только один, второй — избыток (конфигурация снимается).
        ConnectionGrowth.Result result = plan(50, node("a", 25, 50, 0), node("b", 25, null, 0));

        // Флот занимает все 50, второй ещё ничего не отпустил — расти нельзя.
        assertEquals(0, result.growable());
        assertEquals(50, result.sumHeld());
        assertEquals(List.of("b"), result.losesConfig());
        assertEquals(Map.of("a", 25), commands(result));
    }

    @Test
    @DisplayName("тесный бюджет N=50: место открывается по мере освобождения лишнего")
    void tightBudgetUnlocksAsExcessReleases() {
        // Второй снят с конфигурации, но держит 25 и об этом отчитался: потолка у него уже нет.
        ConnectionGrowth.Result result = plan(50, node("a", 25, 50, 0), node("b", 0, null, 25));

        assertEquals(25, result.sumDebt());
        assertEquals(50, result.sumHeld());
        assertEquals(0, result.growable());
        assertEquals(25, commands(result).get("a"));

        // Держал 10: освободилось 15, столько и можно отдать растущему.
        ConnectionGrowth.Result partial = plan(50, node("a", 25, 50, 0), node("b", 0, null, 10));
        assertEquals(15, partial.growable());
        assertEquals(40, commands(partial).get("a"));

        // Освободил всё: оставшийся бюджет целиком достаётся единственному обслуживаемому.
        ConnectionGrowth.Result released = plan(50, node("a", 25, 50, 0), node("b", 0, null, 0));
        assertEquals(25, released.growable());
        assertEquals(50, commands(released).get("a"));
    }

    @Test
    @DisplayName("без публикации берётся худший случай: инстанс держит всё, что мог")
    void missingReportIsWorstCase() {
        assertEquals(25, ConnectionGrowth.debt(node("a", 25, 20)));
        assertEquals(0, ConnectionGrowth.debt(node("a", 0, 20)));
    }

    @Test
    @DisplayName("публикация, относящаяся к прежнему потолку, игнорируется")
    void staleReportIgnored() {
        ConnectionGrowth.NodeState stale = new ConnectionGrowth.NodeState("a", 20, 20, 25, false);
        assertEquals(20, ConnectionGrowth.debt(stale));
    }

    @Test
    @DisplayName("подтверждённая публикация неотрицательна даже при мусоре в etcd")
    void negativeDebtClampedToZero() {
        assertEquals(0, ConnectionGrowth.debt(node("a", 20, 20, -7)));
    }

    @Test
    @DisplayName("непризнанная публикация флота не даёт места, пока инстансы не отчитаются")
    void fleetWithoutReportsGrowsNothing() {
        // Свежий флот: публикаций нет ни у кого. Рост заблокирован — и это правильно: денег
        // освобождения ещё никто не подтвердил.
        ConnectionGrowth.Result result = plan(N, node("a", 25, 20), node("b", 25, 20),
                node("c", 25, 20), node("d", 25, 20), node("e", 0, 20));

        assertEquals(0, result.growable());
        assertEquals(100, result.sumDebt());
        assertEquals(0, commands(result).get("e"));
    }

    @Test
    @DisplayName("увеличение бюджета даёт рост сразу: сжимать нечего")
    void budgetIncreaseGrowsImmediately() {
        ConnectionGrowth.Result result = plan(200, node("a", 25, 50, 0), node("b", 25, 50, 0));

        assertEquals(150, result.growable());
        assertEquals(Map.of("a", 50, "b", 50), commands(result));
    }

    @Test
    @DisplayName("уменьшение бюджета сжимает всех, роста нет")
    void budgetDecreaseShrinksOnly() {
        ConnectionGrowth.Result result = plan(50, node("a", 25, 25, 0), node("b", 25, 25, 0));

        assertEquals(0, result.growable());
        assertEquals(Map.of("a", 25, "b", 25), commands(result));
    }

    @Test
    @DisplayName("смешанный переход: растущий узел ждёт освобождения сжимающегося")
    void mixedGrowAndShrinkWaits() {
        // a уменьшается 50 → 30, b должен вырасти 20 → 40. Пока a держит 50, места нет.
        ConnectionGrowth.Result result = plan(70, node("a", 50, 30), node("b", 20, 40, 0));

        assertEquals(0, result.growable());
        assertEquals(Map.of("a", 30, "b", 20), commands(result));

        // a отчитался, что держит 40 при потолке 30: освободилось 10 — столько и достаётся b.
        ConnectionGrowth.Result afterReport = plan(70, node("a", 30, 30, 10), node("b", 20, 40, 0));
        assertEquals(10, afterReport.sumDebt());
        assertEquals(10, afterReport.growable());
        assertEquals(30, commands(afterReport).get("b"));

        // Дожал до потолка: освободились все 20, b получает свою долю целиком.
        ConnectionGrowth.Result released = plan(70, node("a", 30, 30, 0), node("b", 20, 40, 0));
        assertEquals(20, released.growable());
        assertEquals(40, commands(released).get("b"));
    }

    @Test
    @DisplayName("деактивация группы: активные получают место только после освобождения неактивных")
    void groupDeactivationUnlocksAfterInactiveRelease() {
        // N=100, R=1: было 8×~13, стало 4 активных по 24 и 4 неактивных по 1.
        ConnectionGrowth.Result result = plan(N, node("a1", 12, 24, 0), node("a2", 13, 24, 0),
                node("a3", 12, 24, 0), node("a4", 13, 24, 0), node("c1", 12, 1), node("c2", 13, 1),
                node("c3", 12, 1), node("c4", 13, 1));

        assertEquals(0, result.growable());
        assertEquals(Map.of("a1", 12, "a2", 13, "a3", 12, "a4", 13, "c1", 1, "c2", 1, "c3", 1,
                "c4", 1), commands(result));

        // Неактивные до конца отпустили своё: освободилось 46, ровно столько нужно активным.
        ConnectionGrowth.Result released = plan(N, node("a1", 12, 24, 0), node("a2", 13, 24, 0),
                node("a3", 12, 24, 0), node("a4", 13, 24, 0), node("c1", 1, 1, 0), node("c2", 1, 1, 0),
                node("c3", 1, 1, 0), node("c4", 1, 1, 0));
        assertEquals(46, released.growable());
        Map<String, Integer> commands = commands(released);
        assertEquals(24, commands.get("a1"));
        assertEquals(24, commands.get("a2"));
        assertEquals(100, commands.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("нулевая команда не пишется: ноль сервис отклоняет, узел без доли не обслуживается")
    void zeroCommandIsNotServable() {
        ConnectionGrowth.Result result = plan(N, node("a", 25, 20), node("b", 25, 20),
                node("c", 25, 20), node("d", 25, 20), node("e", 0, 20));

        assertEquals(0, commands(result).get("e"));
        assertFalse(result.decisions().get(4).servable());
        assertTrue(result.decisions().get(0).servable());
    }

    @Test
    @DisplayName("признаки роста и сжатия соответствуют направлению")
    void decisionFlags() {
        ConnectionGrowth.Result result = plan(N, node("a", 50, 30), node("b", 10, 40, 0),
                node("c", 0, 30));
        List<ConnectionGrowth.Decision> decisions = result.decisions();
        // a сжался; b и c хотели бы расти, но место занято сжатием a.
        assertTrue(decisions.get(0).shrinks());
        assertFalse(decisions.get(0).grows());
        assertFalse(decisions.get(1).grows());
        assertFalse(decisions.get(2).servable());
    }

    @Test
    @DisplayName("свободное место общее на сервис: два растущих узла не получают его по полной")
    void freeSpaceIsHandedOutOnce() {
        // N=100, было 25×4. Двое уже сжаты до 1 и отчитываются: держат 13 и 14. Свободно 23.
        // Растущих двое. Если бы каждый получил все 23, флот занял бы 77 + 46 = 123.
        List<ConnectionGrowth.NodeState> nodes = List.of(node("a", 25, 49, 0), node("b", 25, 49, 0),
                node("c", 1, 1, 12), node("d", 1, 1, 13));
        ConnectionGrowth.Result result = plan(N, nodes.toArray(ConnectionGrowth.NodeState[]::new));

        assertEquals(23, result.growable());
        // Всё место достаётся первому по сортировке ключа, второй не растёт вовсе.
        assertEquals(Map.of("a", 48, "b", 25, "c", 1, "d", 1), commands(result));
        // Сжимающие держат своё до отчёта — с ними сумма ровно N.
        assertEquals(100, heldAfterPass(nodes, result));
    }

    @Test
    @DisplayName("одна реплика провизёра и другая дают одинаковый план из одного снимка")
    void planIsDeterministic() {
        ConnectionGrowth.NodeState[] nodes = {
                node("a", 50, 30, 10), node("b", 20, 40), node("c", 0, 30), node("d", 0, null, 15),
        };
        assertEquals(commands(plan(100, nodes)), commands(plan(100, nodes)));
    }
}
