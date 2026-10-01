package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Only an in-memory JPA database is created; no application or external services start. */
class ChunkInfoRepositoryTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private static TransactionTemplate transaction;
    private static ChunkInfoRepository repository;
    private static EntityManager entityManager;

    @BeforeAll
    static void createDatabase() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:chunk-isolation;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setManagedTypes(PersistenceManagedTypes.of(ChunkInfo.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        transaction = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        repository = new JpaRepositoryFactory(entityManager).getRepository(ChunkInfoRepository.class);
    }

    @AfterAll
    static void closeDatabase() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void clearRows() {
        transaction.executeWithoutResult(status -> repository.deleteAllInBatch());
    }

    @Test
    void sameMd5AndIndexCanBelongToDifferentUsersButNotRepeatForOneUser() {
        transaction.executeWithoutResult(status -> repository.saveAllAndFlush(List.of(chunk("1", 0), chunk("2", 0))));
        assertEquals(2L, repository.count());

        RuntimeException duplicate = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(
                status -> repository.saveAndFlush(chunk("1", 0))));
        Throwable cause = duplicate;
        while (!(cause instanceof ConstraintViolationException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertInstanceOf(ConstraintViolationException.class, cause);
        assertEquals(2L, repository.count());
    }

    @Test
    void queriesAndExistenceAreLimitedToOwnerAndSortedByIndex() {
        transaction.executeWithoutResult(status -> repository.saveAllAndFlush(
                List.of(chunk("1", 2), chunk("2", 1), chunk("1", 0))));

        assertEquals(List.of(0, 2), repository.findChunkIndexesByUserIdAndFileMd5("1", "md5"));
        assertEquals(List.of("1", "1"), repository.findByUserIdAndFileMd5OrderByChunkIndexAsc("1", "md5")
                .stream().map(ChunkInfo::getUserId).toList());
        assertTrue(repository.existsByUserIdAndFileMd5AndChunkIndex("2", "md5", 1));
        assertFalse(repository.existsByUserIdAndFileMd5AndChunkIndex("1", "md5", 1));
        assertTrue(repository.findChunkIndexesByUserIdAndFileMd5("1", "other-md5").isEmpty());
    }

    @Test
    void singleChunkAndMergeCleanupLeaveOtherUsersAndFilesUntouched() {
        ChunkInfo otherFile = chunk("1", 0);
        otherFile.setFileMd5("other-md5");
        transaction.executeWithoutResult(status -> repository.saveAllAndFlush(
                List.of(chunk("1", 0), chunk("1", 1), chunk("2", 0), otherFile)));

        transaction.executeWithoutResult(status -> {
            assertEquals(1, repository.deleteByUserIdAndFileMd5AndChunkIndex("1", "md5", 0));
            entityManager.clear();
            assertEquals(List.of(1), repository.findChunkIndexesByUserIdAndFileMd5("1", "md5"));
            assertTrue(repository.existsByUserIdAndFileMd5AndChunkIndex("2", "md5", 0));
            assertEquals(1, repository.deleteByUserIdAndFileMd5("1", "md5"));
            entityManager.clear();
        });

        assertTrue(repository.findChunkIndexesByUserIdAndFileMd5("1", "md5").isEmpty());
        assertEquals(List.of(0), repository.findChunkIndexesByUserIdAndFileMd5("2", "md5"));
        assertEquals(List.of(0), repository.findChunkIndexesByUserIdAndFileMd5("1", "other-md5"));
    }

    private ChunkInfo chunk(String userId, int index) {
        ChunkInfo chunk = new ChunkInfo();
        chunk.setUserId(userId);
        chunk.setFileMd5("md5");
        chunk.setChunkIndex(index);
        chunk.setChunkMd5("chunk-md5");
        chunk.setStoragePath("chunks/" + userId + "/md5/" + index);
        return chunk;
    }
}
