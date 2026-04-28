package com.ratones.sifenwrapper.repository;

import com.ratones.sifenwrapper.entity.ElectronicDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ElectronicDocumentRepository extends JpaRepository<ElectronicDocument, Long> {

    Optional<ElectronicDocument> findByCdc(String cdc);

    List<ElectronicDocument> findByNroLote(String nroLote);

    List<ElectronicDocument> findByCompanyIdAndEstado(Long companyId, String estado);

    @Query("SELECT DISTINCT e.companyId FROM ElectronicDocument e WHERE e.estado = :estado")
    List<Long> findDistinctCompanyIdsByEstado(@Param("estado") String estado);

    @Query("SELECT e FROM ElectronicDocument e WHERE e.companyId = :companyId " +
           "AND e.estado = :estado AND e.tipoDocumento = :tipoDocumento " +
           "ORDER BY e.createdAt ASC")
    List<ElectronicDocument> findByCompanyIdAndEstadoAndTipoDocumento(
            @Param("companyId") Long companyId,
            @Param("estado") String estado,
            @Param("tipoDocumento") Short tipoDocumento);

    @Query("SELECT DISTINCT e.tipoDocumento FROM ElectronicDocument e " +
           "WHERE e.companyId = :companyId AND e.estado = :estado")
    List<Short> findDistinctTipoDocumentoByCompanyIdAndEstado(
            @Param("companyId") Long companyId,
            @Param("estado") String estado);

    @Query("SELECT DISTINCT e.nroLote FROM ElectronicDocument e " +
           "WHERE e.estado = 'ENVIADO' AND e.sentAt < :cutoff AND e.nroLote IS NOT NULL")
    List<String> findDistinctNroLoteForPolling(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT e FROM ElectronicDocument e " +
           "WHERE e.estado = 'ENVIADO' AND e.sentAt < :cutoff AND e.nroLote IS NOT NULL " +
           "ORDER BY e.sentAt ASC")
    List<ElectronicDocument> findSentDocumentsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT COUNT(e) FROM ElectronicDocument e WHERE e.companyId = :companyId AND e.estado = :estado")
    long countByCompanyIdAndEstado(@Param("companyId") Long companyId, @Param("estado") String estado);

       Page<ElectronicDocument> findAllByOrderByCreatedAtDesc(Pageable pageable);

       Page<ElectronicDocument> findAllByCompanyIdOrderByCreatedAtDesc(Long companyId, Pageable pageable);

       Page<ElectronicDocument> findAllByEstadoOrderByCreatedAtDesc(String estado, Pageable pageable);

       Page<ElectronicDocument> findAllByCompanyIdAndEstadoOrderByCreatedAtDesc(Long companyId, String estado, Pageable pageable);
}
