package com.abatalev.demo.etcdhikari.service.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Доступ к демо-таблице: два запроса, которыми нагрузочная точка имитирует работу.
 *
 * <p>Здесь только SQL и ничего больше: ни измерений, ни разбора запроса. Всё, что выше, держит
 * соединение занятым намеренно — на этом и построен замер влияния размера пула.
 */
@Component
public class DemoItemsDao {

    private final JdbcTemplate jdbc;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "JdbcTemplate — Spring-бин, разделяется контейнером по дизайну")
    public DemoItemsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** `count(*)` отражает размер таблицы, а не нагрузку; ради него стоит держать отдельный флаг. */
    public int countItems() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM demo_items", Integer.class);
        // count(*) не бывает null, но queryForObject этого не обещает — распаковывать нельзя.
        return n == null ? 0 : n;
    }

    /** pg_sleep держит коннект занятым — так нагрузка реально упирается в размер пула. */
    public void sleep(long ms) {
        if (ms == 0) {
            return;
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_sleep(?)")) {
                ps.setDouble(1, ms / 1000.0);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
            return null;
        });
    }
}