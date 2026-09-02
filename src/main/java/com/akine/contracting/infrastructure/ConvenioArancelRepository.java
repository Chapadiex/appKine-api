package com.akine.contracting.infrastructure;

import com.akine.contracting.domain.ConvenioArancel;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioArancelRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@code convenio_arancel} (M16).
 *
 * <p>Mismas dos invariantes que {@code ConvenioRepository}: toda consulta lleva el tenant y el
 * alcance —aca el convenio—, y ninguna toma el lock.
 */
public interface ConvenioArancelRepository
		extends JpaRepository<ConvenioArancel, Long>, ConvenioArancelRepositoryPort {

	@Override
	@Query("""
			SELECT a FROM ConvenioArancel a
			 WHERE a.id = :id
			   AND a.organizationId = :organizationId
			   AND a.convenioId = :convenioId
			""")
	Optional<ConvenioArancel> findByIdAndScope(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("convenioId") Long convenioId);

	@Override
	@Query("""
			SELECT a FROM ConvenioArancel a
			 WHERE a.organizationId = :organizationId
			   AND a.convenioId = :convenioId
			 ORDER BY a.vigenciaDesde DESC, a.id DESC
			""")
	List<ConvenioArancel> findAllByConvenio(
			@Param("organizationId") Long organizationId,
			@Param("convenioId") Long convenioId);

	/** Ver {@code ConvenioRepository#findActivosPorAlcance}: mismo orden y mismo motivo. */
	@Override
	@Query("""
			SELECT a FROM ConvenioArancel a
			 WHERE a.organizationId = :organizationId
			   AND a.convenioId = :convenioId
			   AND a.practicaId = :practicaId
			   AND a.active = true
			 ORDER BY a.vigenciaDesde DESC, a.id DESC
			""")
	List<ConvenioArancel> findActivosPorPractica(
			@Param("organizationId") Long organizationId,
			@Param("convenioId") Long convenioId,
			@Param("practicaId") Long practicaId);

	@Override
	@Query("""
			SELECT COUNT(a) FROM ConvenioArancel a
			 WHERE a.organizationId = :organizationId
			   AND a.convenioId = :convenioId
			   AND a.active = true
			""")
	long countActivosDeConvenio(
			@Param("organizationId") Long organizationId,
			@Param("convenioId") Long convenioId);
}
