package com.yizhaoqi.smartpai.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class EsDocumentIdsTest {
    @Test
    void sameBusinessIdentityProducesExactlyTheSameId() {
        assertEquals("TEXT:abc:7:3", EsDocumentIds.text("abc", 7, 3));
        assertEquals(EsDocumentIds.text("abc", 7, 3), EsDocumentIds.text("abc", 7, 3));
    }

    @Test
    void chunkGenerationAndFileEachDistinguishIds() {
        String id = EsDocumentIds.text("abc", 7, 3);
        assertNotEquals(id, EsDocumentIds.text("abc", 7, 4));
        assertNotEquals(id, EsDocumentIds.text("abc", 8, 3));
        assertNotEquals(id, EsDocumentIds.text("def", 7, 3));
    }

    @Test
    void longGenerationIsNotNarrowedToInt() {
        assertEquals("TEXT:abc:2147483648:3", EsDocumentIds.text("abc", 2147483648L, 3));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void contentGenerationCannotBeInventedOrMissing(long generation) {
        assertThrows(IllegalArgumentException.class, () -> EsDocumentIds.text("abc", generation, 3));
    }

    @Test
    void legacyNamespaceIsStableUserScopedAndSeparateFromContentTasks() {
        String id = EsDocumentIds.legacyText("abc", "user-a", 3);
        assertEquals("LEGACY_TEXT:abc:user-a:3", id);
        assertEquals(id, EsDocumentIds.legacyText("abc", "user-a", 3));
        assertNotEquals(id, EsDocumentIds.legacyText("abc", "user-b", 3));
        assertNotEquals(id, EsDocumentIds.text("abc", 1, 3));
    }

    @Test
    void figureIdIsStableAndDistinguishesFileGenerationPageAndFigure() {
        String id = EsDocumentIds.figure("abc", 7, 4, 2);
        assertEquals("FIGURE:abc:7:4:2", id);
        assertEquals(id, EsDocumentIds.figure("abc", 7, 4, 2));
        assertNotEquals(id, EsDocumentIds.figure("def", 7, 4, 2));
        assertNotEquals(id, EsDocumentIds.figure("abc", 8, 4, 2));
        assertNotEquals(id, EsDocumentIds.figure("abc", 7, 5, 2));
        assertNotEquals(id, EsDocumentIds.figure("abc", 7, 4, 3));
        assertNotEquals(id, EsDocumentIds.text("abc", 7, 2));
        assertEquals("FIGURE:abc:2147483648:4:2", EsDocumentIds.figure("abc", 2147483648L, 4, 2));
    }

    @Test
    void invalidFigureIdentityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> EsDocumentIds.figure("abc", 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> EsDocumentIds.figure("abc", 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> EsDocumentIds.figure("abc", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> EsDocumentIds.figure("", 1, 1, 1));
    }
}
