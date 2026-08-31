package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.TipoAntecedente;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link AntecedenteClinico}.
 *
 * <p>La consulta usa el indice {@code ix_hc_antecedente_historia}, que empieza por
 * {@code organization_id}: el tenant acota antes que nada. Los dos filtros opcionales van como
 * {@code :param IS NULL OR ...} para que una sola consulta sirva a los cuatro casos, en vez de
 * cuatro metodos que despues divergen.
 *
 * <p>El orden es por {@code registradoEn} descendente con desempate por id: dos antecedentes
 * cargados en la misma transaccion comparten instante hasta el microsegundo, y sin el desempate el
 * orden entre ellos seria el que quiera MySQL — o sea, distinto en cada corrida.
 */
public interface AntecedenteClinicoRepository
		extends JpaRepository<AntecedenteClinico, Long>, AntecedenteClinicoRepositoryPort {

	@Override
	Optional<AntecedenteClinico> findByIdAndOrganizationId(Long id, Long organizationId);

	@Override
	@Query("""
			SELECT a FROM AntecedenteClinico a
			 WHERE a.organizationId = :organizationId
			   AND a.historiaClinicaId = :historiaClinicaId
			   AND (:tipo IS NULL OR a.tipo = :tipo)
			   AND (:soloVigentes = false OR a.active = true)
			 ORDER BY a.registradoEn DESC, a.id DESC
			""")
	List<AntecedenteClinico> buscarDeHistoria(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("tipo") TipoAntecedente tipo,
			@Param("soloVigentes") boolean soloVigentes);
}
