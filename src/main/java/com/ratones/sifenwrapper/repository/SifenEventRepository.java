package com.ratones.sifenwrapper.repository;

import com.ratones.sifenwrapper.entity.SifenEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SifenEventRepository extends JpaRepository<SifenEvent, Long>, JpaSpecificationExecutor<SifenEvent> {

    @Query(value = "SELECT nextval('sifen_event_id_seq')", nativeQuery = true)
    Long nextEventoId();

    List<SifenEvent> findByCompanyIdAndCdcOrderByCreatedAtDesc(Long companyId, String cdc);

    Optional<SifenEvent> findFirstByCompanyIdAndCdcOrderByCreatedAtDesc(Long companyId, String cdc);

    Optional<SifenEvent> findByIdAndCompanyId(Long id, Long companyId);

    boolean existsByCompanyIdAndCdcAndTipoEventoAndEstado(
            Long companyId, String cdc, Short tipoEvento, String estado);

    Optional<SifenEvent> findFirstByCompanyIdAndCdcAndTipoEventoAndEstadoInOrderByCreatedAtDesc(
            Long companyId, String cdc, Short tipoEvento, Collection<String> estados);

    /** Guard de duplicados para inutilización (tipo 2): no tiene cdc, se identifica por el rango. */
    Optional<SifenEvent> findFirstByCompanyIdAndTipoEventoAndTimbradoAndEstablecimientoAndPuntoExpedicionAndNumeroDesdeAndNumeroHastaAndEstadoInOrderByCreatedAtDesc(
            Long companyId, Short tipoEvento, Integer timbrado, String establecimiento, String puntoExpedicion,
            String numeroDesde, String numeroHasta, Collection<String> estados);
}
