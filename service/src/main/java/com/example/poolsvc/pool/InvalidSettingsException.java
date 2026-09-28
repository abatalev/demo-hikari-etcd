package com.example.poolsvc.pool;

/** Конфиг из etcd не прошёл валидацию: применяем последний рабочий и логируем причину. */
public class InvalidSettingsException extends RuntimeException {

    public InvalidSettingsException(String message) {
        super(message);
    }

    public InvalidSettingsException(String message, Throwable cause) {
        super(message, cause);
    }
}
