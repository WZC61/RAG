package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileContent;
import com.yizhaoqi.smartpai.model.FileUpload;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SharedAclMigrationSchemaTest {
    @Test void backfillUsesTheActualFileUploadColumn() throws Exception {
        String column = FileUpload.class.getDeclaredField("fileMd5").getAnnotation(Column.class).name();
        String sql = Files.readString(Path.of("docs/databases/shared_content_acl_migration.sql"));
        assertTrue(sql.contains("FROM (SELECT DISTINCT " + column + " FROM file_upload) f"));
        assertTrue(sql.contains("f." + column + ", 'ACL_CHANGED'"));
        assertFalse(sql.contains("f.md5"));
        assertTrue(sql.contains("WHERE NOT EXISTS"), "A repeated migration must not duplicate the backfill event");
    }

    @Test void deletionMarkerIsAnExplicitSchemaField() throws Exception {
        String column = FileContent.class.getDeclaredField("deletedAt").getAnnotation(Column.class).name();
        String ddl = Files.readString(Path.of("docs/databases/ddl.sql"));
        String migration = Files.readString(Path.of("docs/databases/shared_content_acl_migration.sql"));
        assertTrue(ddl.contains(column + " DATETIME DEFAULT NULL"));
        assertTrue(migration.contains("ADD COLUMN " + column + " DATETIME DEFAULT NULL"));
        assertTrue(migration.contains("column_name = '" + column + "'"), "Existing developer columns are reused");
    }
}
