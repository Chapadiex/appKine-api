package com.akine.person.infrastructure;

import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Autorizaciones de un paciente (M17, AKINE-03.06).
 *
 * <p>{@link #aprobadasDe} es la consulta que importa: es la que se hace <b>bajo el lock</b> para
 * validar el solapamiento, y la que resuelve la elegibilidad. Filtra por {@code APROBADA} en la
 * base porque una PENDIENTE no participa de ninguna de las dos preguntas —todavia no autoriza
 * ninguna cantidad—, y traerlas para descartarlas en memoria seria leer bajo el lock mas filas de
 * las que la regla mira.
 *
 * <p>Deliberadamente <b>no</b> filtra por fecha: quien decide el solapamiento es
 * {@code Autorizacion#seSolapaCon}, y expresar la comparacion de intervalos en JPQL la partiria
 * entre el repositorio y el dominio, que es como se termina con dos definiciones de la misma
 * regla que divergen.
 */
public interface AutorizacionRepository
		extends JpaRepository<Autorizacion, Long>, AutorizacionRepositoryPort {

	@Override
	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.id = :id
			   AND a.organizationId = :organizationId
			   AND a.personaId = :personaId
			""")
	Optional<Autorizacion> findByIdAndOrganizationIdAndPersonaId(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("personaId") Long personaId);

	@Override
	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.organizationId = :organizationId
			   AND a.personaId = :personaId
			 ORDER BY a.vigenciaDesde DESC, a.id DESC
			""")
	List<Autorizacion> historial(
			@Param("organizationId") Long organizationId, @Param("personaId") Long personaId);

	@Override
	default List<Autorizacion> aprobadasDe(
			Long organizationId, Long coberturaId, Long practicaId) {

		return buscarPorEstado(
				organizationId, coberturaId, practicaId, EstadoAutorizacion.APROBADA);
	}

	@Query("""
			SELECT a FROM Autorizacion a
			 WHERE a.organizationId = :organizationId
			   AND a.coberturaId = :coberturaId
			   AND a.practicaId = :practicaId
			   AND a.active = true
			   AND a.estado = :estado
			 ORDER BY a.vigenciaDesde DESC, a.id DESC
			""")
	List<Autorizacion> buscarPorEstado(
			@Param("organizationId") Long organizationId,
			@Param("coberturaId") Long coberturaId,
			@Param("practicaId") Long practicaId,
			@Param("estado") EstadoAutorizacion estado);
}
