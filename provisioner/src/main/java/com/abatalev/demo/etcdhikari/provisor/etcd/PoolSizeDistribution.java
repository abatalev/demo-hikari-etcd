package com.abatalev.demo.etcdhikari.provisor.etcd;

import java.util.ArrayList;
import java.util.List;

/**
 * Чистое распределение сервисного бюджета соединений между живыми инстансами <em>активных</em>
 * групп.
 *
 * <p>Бюджет N (ключ {@code {root}/services/{service}/activeMaxConnections}) делится равномерно
 * между переданными инстансами, сумма долей равна N. Остаток от деления достаётся первым инстансам
 * в переданном порядке, поэтому доли не «дрожат» на ±1 при пересчёте с одним и тем же составом.
 *
 * <p>Минимум m (ключ {@code .../activeMinConnections}) — ограничение снизу: обслуживаются только
 * те инстансы, которым достаётся не меньше m. Формула: {@code serve = min(n, N div m)}; если
 * {@code serve == n}, равномерное распределение само по себе даёт каждому не меньше
 * {@code N div n ≥ m}, и отдельной ветки кода для «нормального режима» не требуется.
 *
 * <p>Чистая функция: ни клиента, ни потоков, ни часов — только числа и список ключей. Всё, что
 * касается etcd (чтение бюджета, партиционирование активных/неактивных, резерв R, запись долей,
 * очистка избытка), лежит в воркере поверх неё.
 */
public final class PoolSizeDistribution {

    /** Доля одного обслуживаемого инстанса. */
    public record Share(String nodeKey, int size) {
    }

    /**
     * Результат распределения.
     *
     * @param shares доли обслуживаемых инстансов в порядке переданных ключей, не длиннее
     *     {@code shares.size()}, остальные — избыток
     * @param excess число живых инстансов, которым доля не досталась (обслуживать их нельзя)
     * @param refusal причина отказа, либо {@code null}; при отказе распределение не применяется
     *     вовсе — провизор ничего не меняет, чтобы опечатка в etcd не обнулила живой сервис
     */
    public record Result(List<Share> shares, int excess, String refusal) {

        /** Распределение отклонено (неразбираемое значение бюджета или минимума). */
        public boolean refused() {
            return refusal != null;
        }

        /** Сумма долей (с учётом усечения до верхней границы может быть меньше бюджета). */
        public int total() {
            int sum = 0;
            for (Share share : shares) {
                sum += share.size();
            }
            return sum;
        }
    }

    private PoolSizeDistribution() {
    }

    /**
     * Делит бюджет между инстансами.
     *
     * @param budget бюджет соединений сервиса N
     * @param minPerInstance минимальная доля m
     * @param maxShare верхняя граница доли (зеркалит границу сервисной валидации размера пула)
     * @param sortedNodeKeys полные ключи живых узлов в детерминированном порядке
     * @return доли, избыток и причина отказа, если распределение невозможно
     */
    public static Result distribute(int budget, int minPerInstance, int maxShare,
            List<String> sortedNodeKeys) {
        int n = sortedNodeKeys.size();
        if (n == 0) {
            // Живых узлов нет: распределять нечего, отказа тоже нет (сервис без инстансов).
            return new Result(List.of(), 0, null);
        }
        if (budget < 1) {
            return refuse(n, "бюджет соединений " + budget + " должен быть не меньше 1");
        }
        if (minPerInstance < 1) {
            return refuse(n, "минимальная доля " + minPerInstance + " должна быть не меньше 1");
        }
        if (minPerInstance > maxShare) {
            return refuse(n, "минимальная доля " + minPerInstance + " выше верхней границы доли "
                    + maxShare);
        }
        if (budget < minPerInstance) {
            return refuse(n, "бюджет " + budget + " меньше минимальной доли " + minPerInstance);
        }

        // Обслуживаемых не больше, чем allows, и не больше числа живых узлов.
        int serve = Math.min(n, budget / minPerInstance);
        int base = budget / serve;
        int remainder = budget % serve;

        List<Share> shares = new ArrayList<>(serve);
        for (int i = 0; i < serve; i++) {
            int size = i < remainder ? base + 1 : base;
            // Доля выше верхней границы усекается: сервис отклонил бы весь конфиг инстанса.
            shares.add(new Share(sortedNodeKeys.get(i), Math.min(size, maxShare)));
        }
        return new Result(List.copyOf(shares), n - serve, null);
    }

    /** Отказ: распределение не применяется, доли и избыток пустые. */
    private static Result refuse(int n, String reason) {
        return new Result(List.of(), n, reason);
    }
}
