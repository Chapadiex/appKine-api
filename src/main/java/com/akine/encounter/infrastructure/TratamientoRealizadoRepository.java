package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.TratamientoRealizado;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.QueryHint;
import org.springframework.data.repository.query.Param;

import com.akine.encounter.domain.port.TratamientoRepositoryPorts.PracticasAplicadas;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoAplicadoEnSede;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TratamientoRealizadoRepository
		extends JpaRepository<TratamientoRealizado, Long>, TratamientoRepositoryPort {

	/**
	 * {@inheritDoc}
	 *
	 * <p>La sesion entra en el {@code WHERE} y no se verifica despues: un id de otra sesion
	 * devuelve vacio, y el llamador contesta 404 sin tener que acordarse de descartar nada.
	 *
	 * <p><b>Sin filtro por {@code active}</b>, y es deliberado: dar de baja es idempotente y tiene
	 * que poder encontrar la fila ya dada de baja para no inventarle un 404 al segundo click.
	 */
	@Override
	@Query("""
			SELECT t FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			   AND t.id = :tratamientoId
			""")
	Optional<TratamientoRealizado> findEnSesion(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId,
			@Param("tratamientoId") long tratamientoId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>{@code ORDER BY orden} sale del indice {@code ix_tratamiento_sesion}, que lo lleva como
	 * tercera columna justamente para eso.
	 */
	@Override
	@Query("""
			SELECT t FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			   AND t.active = true
			 ORDER BY t.orden
			""")
	List<TratamientoRealizado> listarVigentes(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);

	/**
	 * {@inheritDoc}
	 *
	 * <p><b>Sin filtro por {@code active}</b>: las bajas cuentan. Ver el javadoc del puerto —el
	 * {@code orden} no se reutiliza, y reutilizarlo dejaria pasar dos vigentes con el mismo numero
	 * apenas alguien diera de baja uno y creara otro.
	 *
	 * <p>{@code COALESCE} y no {@code Optional}: una sesion sin tratamientos tiene que devolver
	 * cero para que el primero sea 1, y desempaquetar un nulo en un {@code int} seria un
	 * {@code NullPointerException} en el caso mas frecuente de todos.
	 */
	@Override
	@Query("""
			SELECT COALESCE(MAX(t.orden), 0) FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			""")
	int ultimoOrden(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);

	/** {@inheritDoc} */
	@Override
	@Query("""
			SELECT DISTINCT t.practicaId FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId = :sesionId
			   AND t.active = true
			""")
	List<Long> practicasVigentesDe(
			@Param("organizationId") long organizationId,
			@Param("sesionId") long sesionId);

	@Override
	@QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true"))
	@Query("""
			SELECT t FROM TratamientoRealizado t
			 WHERE t.organizationId = :organizationId
			   AND t.sesionId IN :sesionIds
			   AND t.active = true
			 ORDER BY t.sesionId, t.orden
			""")
	List<TratamientoRealizado> listarVigentesDeSesiones(
			@Param("organizationId") long organizationId,
			@Param("sesionIds") java.util.Collection<Long> sesionIds);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Los filtros opcionales van como bandera + valor y no como parametro nulo: PostgreSQL no
	 * infiere el tipo de un {@code NULL} suelto, y un {@code IN ()} vacio no es SQL valido.
	 */
	@Override
	default PracticasAplicadas aplicados(long organizationId, long consultorioId, LocalDate desde,
			LocalDate hastaExclusivo, ZoneId zona, Collection<Long> practicaIds, Long personaId,
			Collection<Long> profesionalMembershipIds, int limite) {

		boolean porPractica = practicaIds != null && !practicaIds.isEmpty();
		boolean recortar = profesionalMembershipIds != null;
		List<Object[]> crudas = aplicadosCrudos(organizationId, consultorioId,
				desde.atStartOfDay(zona).toInstant(), hastaExclusivo.atStartOfDay(zona).toInstant(),
				porPractica, porPractica ? practicaIds : List.of(-1L),
				personaId != null, personaId == null ? -1L : personaId,
				recortar, recortar && !profesionalMembershipIds.isEmpty()
						? profesionalMembershipIds : List.of(-1L),
				limite);
		long total = crudas.isEmpty() ? 0 : ((Number) crudas.get(0)[10]).longValue();
		List<TratamientoAplicadoEnSede> filas = crudas.stream().map(f -> {
			long micros = ((Number) f[4]).longValue();
			Instant cerrada = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
			return new TratamientoAplicadoEnSede(
					((Number) f[0]).longValue(),
					((Number) f[1]).longValue(),
					f[2] + ", " + f[3],
					LocalDate.ofInstant(cerrada, zona),
					((Number) f[5]).longValue(),
					(String) f[6],
					(String) f[7],
					(String) f[8],
					f[9] == null ? null : ((Number) f[9]).longValue());
		}).toList();
		return new PracticasAplicadas(filas, total);
	}

	/**
	 * Columnas: sesion_id, persona_id, apellido, nombre, cerrada_en en microsegundos UTC,
	 * practica_id, practica_nombre, zona, lateralidad, profesional_membership_id, total.
	 *
	 * <p>Une con {@code historia_clinica} y {@code persona} solo para el nombre y el filtro por
	 * persona: la sesion cuelga de la historia, no de la persona.
	 */
	@QueryHints(@QueryHint(name = org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, value = "true"))
	@Query(value = """
			SELECT s.id,
			       hc.persona_id,
			       p.apellido,
			       p.nombre,
			       CAST(EXTRACT(EPOCH FROM s.cerrada_en) * 1000000 AS bigint) AS cerrada_micros,
			       t.practica_id,
			       t.practica_nombre,
			       t.zona,
			       t.lateralidad,
			       t.profesional_membership_id,
			       COUNT(*) OVER () AS total
			  FROM tratamiento_realizado t
			  JOIN sesion s ON s.id = t.sesion_id AND s.organization_id = t.organization_id
			  JOIN historia_clinica hc ON hc.id = s.historia_clinica_id
			                          AND hc.organization_id = s.organization_id
			  JOIN persona p ON p.id = hc.persona_id AND p.organization_id = hc.organization_id
			 WHERE t.organization_id = :organizationId
			   AND s.consultorio_id = :consultorioId
			   AND t.active = true
			   AND s.estado = 'CERRADA'
			   AND s.deleted_at IS NULL
			   AND s.cerrada_en >= :desde
			   AND s.cerrada_en < :hasta
			   AND (:porPractica = false OR t.practica_id IN (:practicaIds))
			   AND (:porPersona = false OR hc.persona_id = :personaId)
			   AND (:recortar = false
			        OR s.profesional_membership_id IN (:memberships)
			        OR t.profesional_membership_id IN (:memberships))
			 ORDER BY s.cerrada_en DESC, s.id DESC, t.orden
			 LIMIT :limite
			""", nativeQuery = true)
	@SuppressWarnings("java:S107")
	List<Object[]> aplicadosCrudos(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("porPractica") boolean porPractica,
			@Param("practicaIds") Collection<Long> practicaIds,
			@Param("porPersona") boolean porPersona,
			@Param("personaId") long personaId,
			@Param("recortar") boolean recortar,
			@Param("memberships") Collection<Long> memberships,
			@Param("limite") int limite);
}
