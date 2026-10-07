package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL ACL predicate, isolated H2; never loads development credentials. */
class FigurePermissionIntegrationTest {
    LocalContainerEntityManagerFactoryBean factory; TransactionTemplate tx; FileUploadRepository files;
    @BeforeEach void setup() {
        factory=new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:figure-acl;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        factory.setManagedTypes(PersistenceManagedTypes.of(FileUpload.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop")); factory.afterPropertiesSet();
        tx=new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        files=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject())).getRepository(FileUploadRepository.class);
    }
    @AfterEach void close() { if(factory!=null)factory.destroy(); }
    void relation(String user,String org,boolean published,int status) {
        tx.executeWithoutResult(t->{var file=new FileUpload();file.setFileMd5("abc");file.setUserId(user);
            file.setFileName("paper.pdf");file.setOrgTag(org);file.setPublic(published);file.setStatus(status);files.saveAndFlush(file);});
    }
    @Test void ownerOrganizationPublicAndRevocationUseCurrentRelations() {
        relation("A","TEAM",false,1);
        assertTrue(files.existsAuthorizedCompletedContent("abc","A",List.of()));
        assertTrue(files.existsAuthorizedCompletedContent("abc","B",List.of("TEAM")));
        assertFalse(files.existsAuthorizedCompletedContent("abc","B",List.of("OTHER")));
        tx.executeWithoutResult(t->{var f=files.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("abc","A").orElseThrow(); f.setPublic(true);files.saveAndFlush(f);});
        assertTrue(files.existsAuthorizedCompletedContent("abc","B",List.of()));
        tx.executeWithoutResult(t->files.deleteAllInBatch());
        assertFalse(files.existsAuthorizedCompletedContent("abc","A",List.of("TEAM")));
    }
    @Test void privateOrganizationTagsAndIncompleteUploadsCannotGrantAccess() {
        relation("A","PRIVATE_A",false,1); relation("B","TEAM",true,0);
        assertFalse(files.existsAuthorizedCompletedContent("abc","C",List.of("PRIVATE_A","TEAM")));
        assertTrue(files.existsAuthorizedCompletedContent("abc","A",List.of()));
    }
}
