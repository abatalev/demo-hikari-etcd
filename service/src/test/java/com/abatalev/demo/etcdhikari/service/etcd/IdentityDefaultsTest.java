package com.abatalev.demo.etcdhikari.service.etcd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * Цепочка дефолтов идентичности из {@code application.yml}:
 * {@code instance = ${ETCD_INSTANCE:${POD_NAME:${HOSTNAME:}}}},
 * {@code group = ${ETCD_GROUP:${POD_NAMESPACE:}}}, {@code pool-name = ${POOL_NAME:${pool.etcd.instance:}}}.
 *
 * <p>Цепочка живёт в YAML, поэтому здесь она проверяется той же механикой, которой её прочитает
 * Spring: YAML складывается в источники свойств вместе с тестовым «окружением», а плейсхолдеры
 * разрешает тот же {@code PropertySourcesPropertyResolver}, что и среда Spring. Отдельный источник
 * окружения без системного — намеренно: каждая переменная задана явно (или не задана вовсе), и
 * поведение теста не зависит от имени хоста, на котором он выполняется.
 */
class IdentityDefaultsTest {

    private static final String INSTANCE = "pool.etcd.instance";
    private static final String GROUP = "pool.etcd.group";
    private static final String POOL_NAME = "pool.db.pool-name";

    private static PropertySourcesPropertyResolver resolver(Map<String, Object> env) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        if (!env.isEmpty()) {
            sources.addFirst(new MapPropertySource("test-env", new LinkedHashMap<>(env)));
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> ps : loader.load("application.yml", new ClassPathResource("application.yml"))) {
            sources.addLast(ps);
        }
        return new PropertySourcesPropertyResolver(sources);
    }

    @Test
    @DisplayName("явное имя инстанса и группы имеет приоритет; POOL_NAME перекрывает имя инстанса")
    void explicitIdentityWins() throws IOException {
        PropertySourcesPropertyResolver r = resolver(Map.of(
                "ETCD_INSTANCE", "service-a-group-1-1",
                "POD_NAME", "pod-1",
                "HOSTNAME", "hex123",
                "ETCD_GROUP", "group-1",
                "POD_NAMESPACE", "ns-1",
                "POOL_NAME", "custom-pool"));
        assertThat(r.getProperty(INSTANCE)).isEqualTo("service-a-group-1-1");
        assertThat(r.getProperty(GROUP)).isEqualTo("group-1");
        assertThat(r.getProperty(POOL_NAME)).isEqualTo("custom-pool");
    }

    @Test
    @DisplayName("без явного имени берётся имя пода, группа — из namespace, POOL_NAME следует за инстансом")
    void podNameUsedWhenNoExplicit() throws IOException {
        PropertySourcesPropertyResolver r = resolver(Map.of(
                "POD_NAME", "pod-1",
                "HOSTNAME", "hex123",
                "POD_NAMESPACE", "ns-1"));
        assertThat(r.getProperty(INSTANCE)).isEqualTo("pod-1");
        assertThat(r.getProperty(GROUP)).isEqualTo("ns-1");
        // POOL_NAME не задан — имя сессии пула обязано совпасть с именем инстанса (узла регистрации)
        assertThat(r.getProperty(POOL_NAME)).isEqualTo("pod-1");
    }

    @Test
    @DisplayName("ни явного, ни пода — остаётся имя хоста; группа пуста без namespace")
    void hostnameUsedAsLastResort() throws IOException {
        PropertySourcesPropertyResolver r = resolver(Map.of("HOSTNAME", "hex123abc"));
        assertThat(r.getProperty(INSTANCE)).isEqualTo("hex123abc");
        assertThat(r.getProperty(POOL_NAME)).isEqualTo("hex123abc");
        assertThat(r.getProperty(GROUP)).isEmpty();
    }

    @Test
    @DisplayName("пустая цепочка даёт пустой сегмент, и валидация пути называет отсутствующий сегмент")
    void emptyChainFailsPathValidation() throws IOException {
        PropertySourcesPropertyResolver r = resolver(Map.of());
        // Ни один источник имени не дал значения: сегмент пуст. Валидация путей (EtcdKeyPath)
        // обязана назвать проблемный сегмент — иначе пустой instance молча построил бы путь, в
        // который провизор ничего не положил бы и инстанс вечно стоял бы без конфигурации.
        assertThat(r.getProperty(INSTANCE)).isEmpty();

        assertThatThrownBy(() -> EtcdKeyPath.build("/config", "service-a", "group-1",
                r.getProperty(INSTANCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance");

        assertThatThrownBy(() -> EtcdKeyPath.build("/config", "service-a",
                r.getProperty(GROUP), "instance-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }
}