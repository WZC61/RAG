package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentFigure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentFigureRepository extends JpaRepository<DocumentFigure, Long> {
    List<DocumentFigure> findByFileMd5(String fileMd5);

    @Transactional(transactionManager = "transactionManager")
    @Modifying
    @Query("delete from DocumentFigure f where f.fileMd5 = :md5")
    void deleteByFileMd5(@Param("md5") String fileMd5);
}
