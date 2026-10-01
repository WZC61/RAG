package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChunkInfo> findByUserIdAndFileMd5AndChunkIndex(String userId, String fileMd5, int chunkIndex);

    List<ChunkInfo> findByUserIdAndFileMd5OrderByChunkIndexAsc(String userId, String fileMd5);

    boolean existsByUserIdAndFileMd5AndChunkIndex(String userId, String fileMd5, int chunkIndex);

    @Transactional
    @Modifying
    @Query("delete from ChunkInfo c where c.userId = :userId and c.fileMd5 = :fileMd5")
    int deleteByUserIdAndFileMd5(@Param("userId") String userId, @Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying
    @Query("delete from ChunkInfo c where c.userId = :userId and c.fileMd5 = :fileMd5 and c.chunkIndex = :chunkIndex")
    int deleteByUserIdAndFileMd5AndChunkIndex(@Param("userId") String userId, @Param("fileMd5") String fileMd5, @Param("chunkIndex") int chunkIndex);

    @Query("select c.chunkIndex from ChunkInfo c where c.userId = :userId and c.fileMd5 = :fileMd5 order by c.chunkIndex asc")
    List<Integer> findChunkIndexesByUserIdAndFileMd5(@Param("userId") String userId, @Param("fileMd5") String fileMd5);
}
