package com.abatalev.demo.etcdhikari.provisor.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Чистая грамматика маркеров глобального флота групп — единственное, что покрывается юнит-тестами
 * у провизора (поллинг и партиционирование проверяются на живом стенде).
 */
class GroupFleetKeyTest {

    private static final String ROOT = "/config";

    @Test
    void parsesMarkerKey() {
        var parsed = GroupFleetKey.parse(ROOT, "/config/groups/group-1/active");
        assertInstanceOf(GroupFleetKey.Marker.class, parsed);
        assertEquals(new GroupFleetKey.Marker("group-1"), parsed);
    }

    @Test
    void markerKeyWithTrailingSlashParses() {
        var parsed = GroupFleetKey.parse(ROOT, "/config/groups/group-1/active/");
        assertInstanceOf(GroupFleetKey.Marker.class, parsed);
        assertEquals(new GroupFleetKey.Marker("group-1"), parsed);
    }

    @Test
    void missingMarkerMeansActive() {
        // Группа без маркера (ещё не помечена) — активна.
        assertTrue(GroupFleetKey.isActive(null));
    }

    @Test
    void explicitFalseMeansInactive() {
        assertFalse(GroupFleetKey.isActive("false"));
        // Регистр и пробелы вокруг значения не должны менять смысл.
        assertFalse(GroupFleetKey.isActive(" FALSE "));
    }

    @Test
    void junkValueMeansActive() {
        assertTrue(GroupFleetKey.isActive("true"));
        assertTrue(GroupFleetKey.isActive("yes"));
        assertTrue(GroupFleetKey.isActive("1"));
        assertTrue(GroupFleetKey.isActive(""));
        assertTrue(GroupFleetKey.isActive("off"));
    }

    @Test
    void rejectsNonMarkerKeys() {
        // Мусор в префиксе {root}/groups/ не даёт группы (опечатка — не маркер).
        assertInstanceOf(GroupFleetKey.Other.class, GroupFleetKey.parse(ROOT, "/config/groups/"));
        assertInstanceOf(GroupFleetKey.Other.class, GroupFleetKey.parse(ROOT, "/config/groups/group-1"));
        assertInstanceOf(GroupFleetKey.Other.class, GroupFleetKey.parse(ROOT, "/config/groups/group-1/foo"));
        assertInstanceOf(GroupFleetKey.Other.class, GroupFleetKey.parse(ROOT, "/config/groups/group-1/active/x"));
        assertInstanceOf(GroupFleetKey.Other.class, GroupFleetKey.parse(ROOT, "/config/g//active"));
        assertInstanceOf(GroupFleetKey.Other.class, GroupFleetKey.parse("/other/root", "/config/groups/a/active"));
        assertInstanceOf(GroupFleetKey.Other.class, GroupFleetKey.parse(ROOT, null));
    }

    @Test
    void markerKeyRoundTrip() {
        String key = GroupFleetKey.markerKey(ROOT, "group-1");
        assertEquals("/config/groups/group-1/active", key);
        assertInstanceOf(GroupFleetKey.Marker.class, GroupFleetKey.parse(ROOT, key));
    }

    @Test
    void groupsPrefixIsNormalized() {
        assertEquals("/config/groups/", GroupFleetKey.groupsPrefix(ROOT));
        assertEquals("/config/groups/", GroupFleetKey.groupsPrefix("/config/"));
    }
}