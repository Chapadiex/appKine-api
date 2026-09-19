package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.AdjuntoClinico;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AdjuntoClinicoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de la metadata de adjuntos clinicos (M09 / M25).
 *
 * <p><b>Ninguna consulta resuelve por id pelado</b> y todas acotan por {@code organizationId} y
 * por {@code historiaClinicaId}. Lo segundo no sobra: un adjunto resuelto solo por tenant e id
 * dejaria que un pedido autorizado sobre la historia A entregue el estudio de la historia B del
 * mismo centro. La autorizacion se evaluo contra una historia concreta y la consulta tiene que
 * respetar esa misma frontera.
 *
 * <p>El listado y su conteo son nativos por el mismo motivo que los de {@code AdjuntoRepository}:
 * el filtro de tres estados —vigentes, de baja, todos— se expresa con un {@code int} y JPQL
 * obliga a escribir eso con comparaciones que el planificador lee peor. El {@code LIMIT ...
 * OFFSET} tambien pagina <b>en la base</b>: un paciente cronico acumula cientos de estudios y no
 * hay razon para traerse los que no se muestran.
 *
 * <p>El orden es {@code subido_en DESC} con desempate por {@code id DESC}, mismo criterio que
 * {@link EntradaClinicaRepository}: dos cargas de la misma transaccion comparten instante hasta
 * el microsegundo, y sin el desempate el orden entre ellas seria distinto en cada corrida y
 * distinto entre paginas.
 */
public interface AdjuntoClinicoRepository
		extends JpaRepository<AdjuntoClinico, Long>, AdjuntoClinicoRepositoryPort {

	@Override
	@Query("""
			SELECT a FROM AdjuntoClinico a
			 WHERE a.organizationId = :organizationId
			   AND a.historiaClinicaId = :historiaClinicaId
			   AND a.id = :adjuntoId
			""")
	Optional<AdjuntoClinico> buscarDeLaHistoria(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("adjuntoId") Long adjuntoId);

	@Override
	@Query("""
			SELECT a FROM AdjuntoClinico a
			 WHERE a.organizationId = :organizationId
			   AND a.historiaClinicaId = :historiaClinicaId
			   AND a.checksumSha256 = :checksumSha256
			   AND a.active = true
			""")
	Optional<AdjuntoClinico> buscarVigentePorChecksum(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("checksumSha256") String checksumSha256);

	@Override
	@Query(value = """
			SELECT * FROM adjunto_clinico a
			 WHERE a.organization_id = :organizationId
			   AND a.historia_clinica_id = :historiaClinicaId
			   AND (:categoria IS NULL OR a.categoria = :categoria)
			   AND (:entradaId IS NULL OR a.entrada_clinica_id = :entradaId)
			   AND (:activoFiltro = -1 OR a.active = :activoFiltro)
			 ORDER BY a.subido_en DESC, a.id DESC
			 LIMIT :limite OFFSET :offset
			""", nativeQuery = true)
	List<AdjuntoClinico> listar(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("categoria") String categoria,
			@Param("entradaId") Long entradaId,
			@Param("activoFiltro") int activoFiltro,
			@Param("offset") int offset,
			@Param("limite") int limite);

	@Override
	@Query(value = """
			SELECT COUNT(*) FROM adjunto_clinico a
			 WHERE a.organization_id = :organizationId
			   AND a.historia_clinica_id = :historiaClinicaId
			   AND (:categoria IS NULL OR a.categoria = :categoria)
			   AND (:entradaId IS NULL OR a.entrada_clinica_id = :entradaId)
			   AND (:activoFiltro = -1 OR a.active = :activoFiltro)
			""", nativeQuery = true)
	long contar(
			@Param("organizationId") Long organizationId,
			@Param("historiaClinicaId") Long historiaClinicaId,
			@Param("categoria") String categoria,
			@Param("entradaId") Long entradaId,
			@Param("activoFiltro") int activoFiltro);
}
