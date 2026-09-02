package com.akine.person.infrastructure;

import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de la metadata de adjuntos administrativos (M25).
 *
 * <p><b>Ninguna consulta resuelve por id pelado</b> y todas empiezan por {@code organizationId},
 * como el resto del modulo: un {@code findById} sobre esta tabla seria la forma mas corta de leer
 * el documento de identidad de un paciente de otro centro.
 *
 * <p>El listado y su conteo son nativos por el mismo motivo que los del padron: el filtro de tres
 * estados —vigentes, de baja, todos— se expresa con un {@code int} y no con un {@code Boolean}
 * nullable, y JPQL obliga a escribir eso con comparaciones que el planificador lee peor. El
 * {@code LIMIT ... OFFSET} tambien pagina <b>en la base</b> y no en memoria, misma decision que
 * tomo 03.01 y por la misma razon: una persona puede acumular decenas de documentos, pero el
 * criterio de no traerse lo que no se muestra no cambia con el volumen.
 */
public interface AdjuntoRepository
		extends JpaRepository<AdjuntoAdministrativo, Long>, AdjuntoRepositoryPort {

	@Override
	@Query("""
			SELECT a FROM AdjuntoAdministrativo a
			 WHERE a.organizationId = :organizationId
			   AND a.personaId = :personaId
			   AND a.id = :adjuntoId
			""")
	Optional<AdjuntoAdministrativo> buscarDeLaPersona(
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId,
			@Param("adjuntoId") Long adjuntoId);

	@Override
	@Query("""
			SELECT a FROM AdjuntoAdministrativo a
			 WHERE a.organizationId = :organizationId
			   AND a.personaId = :personaId
			   AND a.checksumSha256 = :checksumSha256
			   AND a.active = true
			""")
	Optional<AdjuntoAdministrativo> buscarVigentePorChecksum(
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId,
			@Param("checksumSha256") String checksumSha256);

	@Override
	@Query(value = """
			SELECT * FROM adjunto_administrativo a
			 WHERE a.organization_id = :organizationId
			   AND a.persona_id = :personaId
			   AND (:categoria IS NULL OR a.categoria = :categoria)
			   AND (:activoFiltro = -1 OR a.active = :activoFiltro)
			 ORDER BY a.subido_en DESC, a.id DESC
			 LIMIT :limite OFFSET :offset
			""", nativeQuery = true)
	List<AdjuntoAdministrativo> listar(
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId,
			@Param("categoria") String categoria,
			@Param("activoFiltro") int activoFiltro,
			@Param("offset") int offset,
			@Param("limite") int limite);

	@Override
	@Query(value = """
			SELECT COUNT(*) FROM adjunto_administrativo a
			 WHERE a.organization_id = :organizationId
			   AND a.persona_id = :personaId
			   AND (:categoria IS NULL OR a.categoria = :categoria)
			   AND (:activoFiltro = -1 OR a.active = :activoFiltro)
			""", nativeQuery = true)
	long contar(
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId,
			@Param("categoria") String categoria,
			@Param("activoFiltro") int activoFiltro);

	@Override
	@Query(value = """
			SELECT a.categoria, COUNT(*) FROM adjunto_administrativo a
			 WHERE a.organization_id = :organizationId
			   AND a.persona_id = :personaId
			   AND a.active = 1
			 GROUP BY a.categoria
			 ORDER BY a.categoria
			""", nativeQuery = true)
	List<Object[]> contarVigentesPorCategoria(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);
}
