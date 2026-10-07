package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentFigure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface DocumentFigureRepository extends JpaRepository<DocumentFigure, Long> {
    List<DocumentFigure> findByFileMd5(String fileMd5);

    Optional<DocumentFigure> findByFileMd5AndProcessingGenerationAndPageNumberAndFigureIndex(
            String fileMd5, Long processingGeneration, Integer pageNumber, Integer figureIndex);

    List<DocumentFigure> findByFileMd5AndProcessingGenerationOrderByPageNumberAscFigureIndexAsc(
            String fileMd5, Long processingGeneration);

    @Transactional(transactionManager = "transactionManager")
    @Modifying
    @Query("delete from DocumentFigure f where f.fileMd5 = :md5")
    void deleteByFileMd5(@Param("md5") String fileMd5);
}
