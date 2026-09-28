package com.example.poolsvc.web;

import com.example.poolsvc.etcd.EtcdPoolConfigSource;
import com.example.poolsvc.pool.HikariSettings;
import com.example.poolsvc.pool.ManagedPool;

/** Ответ /api/pool: что сейчас в пуле, какая конфигурация применилась и откуда. */
public record PoolStatusResponse(
        ManagedPool.Runtime pool,
        HikariSettings config,
        PostgresSessions postgres,
        EtcdPoolConfigSource.EtcdStatus etcd) {

    /** Сколько сессий postgres держит именно наш пул (application_name = pool-service). */
    public record PostgresSessions(Integer sessions, Integer active, Integer idle, String error) {

        public static PostgresSessions unavailable(String reason) {
            return new PostgresSessions(null, null, null, reason);
        }
    }
}
