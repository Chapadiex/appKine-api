package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.TipoAntecedente;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>Nativa y con {@code LIMIT :limite} por lo mismo que las otras dos fuentes del timeline:
	 * la pagina se recorta en la base. El instante del hecho es {@code registrado_en} —un
	 * antecedente no declara cuando ocurrio, declara cuando se registro— y el desempate por
	 * {@code id DESC} mantiene el orden estable entre paginas.
	 */
	@Override
	@Query(value = """
			SELECT * FROM historia_clinica_antecedente a
			 WHERE a.organization_id = :organizationId
			   AND a.historia_clinica_id = :historiaClinicaId
			   AND a.active = 1
			   AND a.registrado_en <= :hasta
			 ORDER BY a.registrado_en DESC, a.id DESC
			 LIMIT :limite
			""", nativeQuery = true)
	List<AntecedenteClinico> buscarParaTimeline(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("hasta") Instant hasta,
			@Param("limite") int limite);
}
