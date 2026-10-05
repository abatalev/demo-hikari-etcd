package com.abatalev.demo.etcdhikari.service.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Доступ к демо-таблице проверяется без БД: подменяем {@link JdbcTemplate} и смотрим, что уходит
 * в базу. Здесь важна одна вещь — {@code ms = 0} не должен идти в базу вовсе, иначе «быстрый» замер
 * платит за поход к серверу и время ожидания соединения включает в себя чужую работу.
 */
class DemoItemsDaoTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("нулевой сон не ходит в базу")
    void zeroSleepDoesNotTouchDatabase() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new DemoItemsDao(jdbc).sleep(0L);

        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("сон удерживает соединение запросом pg_sleep на том же коннекте")
    void sleepHoldsConnection() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT pg_sleep(?)")).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        // Кэптор держит ровно то соединение, которое дал JdbcTemplate, — на этом и смысл удержания.
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(call ->
                ((ConnectionCallback<Object>) call.getArgument(0)).doInConnection(connection));

        new DemoItemsDao(jdbc).sleep(20L);

        verify(jdbc).execute(any(ConnectionCallback.class));
        verify(ps).setDouble(1, 0.02d);
        verify(ps).executeQuery();
        verify(rs).next();
        verify(ps).close();
    }

    @Test
    @DisplayName("счёт строк берётся одним запросом к демо-таблице")
    void countReadsDemoItems() throws SQLException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(String.class), eq(Integer.class))).thenReturn(2000);

        assertThat(new DemoItemsDao(jdbc).countItems()).isEqualTo(2000);

        verify(jdbc).queryForObject("SELECT count(*) FROM demo_items", Integer.class);
    }
}