package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.ConteoDeSesionesPorOferta;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SesionRepository extends JpaRepository<Sesion, Long>, SesionRepositoryPort {

	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :sesionId
			""")
	Optional<Sesion> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("sesionId") long sesionId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Es la MISMA consulta que la de arriba y lo unico que la distingue es el
	 * {@code OPTIMISTIC_FORCE_INCREMENT}. El nombre lleva {@code WithLock} para documentarlo:
	 * Spring Data ignora el texto entre {@code find} y {@code By}, asi que no cambia nada de la
	 * consulta. Mismo patron que {@code CasoClinicoRepository} y {@code EntradaClinicaRepository}.
	 */
	@Override
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :sesionId
			""")
	Optional<Sesion> findWithLockByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("sesionId") long sesionId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>{@code flushAutomatically} para que cualquier cambio pendiente de la sesion salga antes
	 * y no pise esta version; no se limpia el contexto ({@code clearAutomatically}) porque el
	 * llamador sigue usando la entidad ya leida y despues escribe en otras tablas.
	 */
	@Override
	@Modifying(flushAutomatically = true)
	@Query("""
			UPDATE Sesion s
			   SET s.version = s.version + 1
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :sesionId
			   AND s.version = :versionEsperada
			""")
	int avanzarVersion(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("sesionId") long sesionId,
			@Param("versionEsperada") long versionEsperada);

	/**
	 * <p>Sin filtro por sede a proposito: un turno pertenece a UNA sede, asi que agregarlo no
	 * acota nada y abriria la puerta a que un llamador pase la sede equivocada y reciba
	 * {@code empty} en vez de la sesion que existe — lo que haria que el segundo inicio creara una
	 * segunda sesion y chocara contra el unique.
	 */
	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.turnoId = :turnoId
			   AND s.deletedAt IS NULL
			""")
	Optional<Sesion> findVivaPorTurno(
			@Param("organizationId") long organizationId,
			@Param("turnoId") long turnoId);

	/**
	 * <p>{@code ORDER BY iniciadaEn DESC} con {@code LIMIT 1} via {@code Optional}: Spring Data lo
	 * traduce a un {@code LIMIT}, y el indice {@code ix_sesion_comparacion} de V34 lo sostiene. Sin
	 * ese indice esta consulta recorre toda la historia del paciente en cada apertura de sesion.
	 */
	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.historiaClinicaId = :historiaClinicaId
			   AND s.iniciadaEn < :antesDe
			   AND s.evaluadaEn IS NOT NULL
			   AND s.deletedAt IS NULL
			 ORDER BY s.iniciadaEn DESC
			 LIMIT 1
			""")
	Optional<Sesion> findPreviaEvaluada(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId,
			@Param("antesDe") Instant antesDe);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Nativa y con {@code LIMIT :limite} porque la pagina del timeline se recorta <b>en la
	 * base</b>: un paciente cronico con 900 sesiones no puede traerlas todas para descartar 890 en
	 * memoria. Calza con {@code ix_sesion_cerradas}
	 * {@code (organization_id, historia_clinica_id, cerrada_en)}.
	 *
	 * <p>Se filtra por {@code estado} <b>y</b> por {@code cerrada_en IS NOT NULL} aunque el CHECK
	 * de V35 los ate: el {@code ORDER BY} sobre una columna nullable ordenaria las abiertas al
	 * final en vez de dejarlas afuera, y un dato que el timeline no puede datar no es un evento.
	 *
	 * <p>El desempate por {@code id DESC} mantiene el orden estable entre paginas, igual que en las
	 * tres fuentes de {@code clinical}.
	 */
	@Override
	@Query(value = """
			SELECT * FROM sesion s
			 WHERE s.organization_id = :organizationId
			   AND s.historia_clinica_id = :historiaClinicaId
			   AND s.estado = 'CERRADA'
			   AND s.cerrada_en IS NOT NULL
			   AND s.cerrada_en <= :hasta
			   AND s.deleted_at IS NULL
			   AND (:casoId IS NULL OR s.caso_id = :casoId)
			 ORDER BY s.cerrada_en DESC, s.id DESC
			 LIMIT :limite
			""", nativeQuery = true)
	List<Sesion> buscarCerradasParaTimeline(
			@Param("organizationId") long organizationId,
			@Param("historiaClinicaId") long historiaClinicaId,
			@Param("hasta") Instant hasta,
			@Param("limite") int limite,
			@Param("casoId") Long casoId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>JPQL con expresion de constructor y no nativa, al reves que la consulta del timeline. La
	 * diferencia es lo que devuelve cada una: aquella trae filas enteras y se recorta con
	 * {@code LIMIT}, esto trae una agregacion de tres columnas que Hibernate valida contra el
	 * modelo al arrancar la aplicacion. Con una nativa, un alias mal escrito aparece recien en
	 * runtime — y esta etapa no puede correr tests de integracion.
	 *
	 * <p>Calza con {@code ix_sesion_caso} {@code (organization_id, caso_id, cerrada_en)}. Se filtra
	 * por {@code estado} y por {@code deletedAt} y <b>no</b> por historia: el caso ya acota a un
	 * paciente, y agregar la historia obligaria al llamador a resolverla para preguntar algo que no
	 * la necesita.
	 */
	@Override
	@Query("""
			SELECT new com.akine.encounter.domain.ConteoDeSesionesPorOferta(
			           s.ofertaId,
			           SUM(CASE WHEN s.asistencia = com.akine.encounter.domain.Asistencia.PRESENTE
			                    THEN 1L ELSE 0L END),
			           SUM(CASE WHEN s.asistencia = com.akine.encounter.domain.Asistencia.AUSENTE
			                    THEN 1L ELSE 0L END))
			  FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.casoId = :casoId
			   AND s.estado = com.akine.encounter.domain.EstadoSesion.CERRADA
			   AND s.deletedAt IS NULL
			 GROUP BY s.ofertaId
			""")
	List<ConteoDeSesionesPorOferta> contarCerradasPorOferta(
			@Param("organizationId") long organizationId,
			@Param("casoId") long casoId);

	// =================================================================================
	// M23 — agregaciones de reporte (AKINE-07.06)
	// =================================================================================
	//
	// Las cinco se calculan al leer. No hay ninguna tabla de resumen de sesiones, por la
	// misma razon por la que no hay tabla de timeline clinico (04.02): una tabla de
	// resumen es una segunda copia de la verdad.
	//
	// Todas cortan por `cerrada_en` salvo la ultima, y todas excluyen la baja logica. El
	// indice que las sostiene es `ix_sesion_sede_cierre`, creado por V59: `sesion` tenia
	// indices por historia, por profesional y por caso, y ninguno por sede.

	/** Atenciones realmente realizadas en la sede durante el periodo (RF-M23-003). */
	@Query("""
			SELECT COUNT(s) FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.cerradaEn >= :desde
			   AND s.cerradaEn < :hasta
			   AND s.deletedAt IS NULL
			""")
	long contarCerradasEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Las que pertenecen a un Caso Clinico.
	 *
	 * <p>RN-M23-004 pide que las sesiones de casos distintos no se mezclen en conteos
	 * contextuales. Contar aparte las que tienen caso y las que no es la forma minima de
	 * cumplirlo: la diferencia entre este numero y el total es exactamente la atencion
	 * suelta, que hoy existe porque el gate de RF-M10-007 esta deliberadamente apagado.
	 */
	@Query("""
			SELECT COUNT(s) FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.casoId IS NOT NULL
			   AND s.cerradaEn >= :desde
			   AND s.cerradaEn < :hasta
			   AND s.deletedAt IS NULL
			""")
	long contarCerradasConCasoEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Presentes o ausentes.
	 *
	 * <p>La asistencia viaja como texto y se compara contra el nombre del enum, en vez de recibir
	 * el tipo: el puerto no puede exponer {@code encounter.domain.Asistencia} a un llamador de
	 * otra capa sin convertirlo en parte del contrato del modulo.
	 */
	@Query("""
			SELECT COUNT(s) FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.cerradaEn >= :desde
			   AND s.cerradaEn < :hasta
			   AND s.deletedAt IS NULL
			   AND CAST(s.asistencia AS string) = :asistencia
			""")
	long contarCerradasPorAsistenciaEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("asistencia") String asistencia);

	/**
	 * Las abiertas y todavia sin cerrar.
	 *
	 * <p><b>Corta por {@code iniciada_en} y no por {@code cerrada_en}</b>, que es nula justamente
	 * en estas: cortar por el cierre las dejaria siempre en cero y el indicador diria que no hay
	 * sesiones colgadas cuando las hay.
	 */
	@Query("""
			SELECT COUNT(s) FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.estado = com.akine.encounter.domain.EstadoSesion.BORRADOR
			   AND s.iniciadaEn >= :desde
			   AND s.iniciadaEn < :hasta
			   AND s.deletedAt IS NULL
			""")
	long contarEnBorradorEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * El detalle, agrupado por Caso.
	 *
	 * <p><b>Por caso y nunca por persona.</b> Una fila por paciente convertiria el reporte en un
	 * listado de quien se atendio cuantas veces, que es contenido clinico que esta seccion no
	 * puede entregar: quien lo necesita entra por M10 con su permiso y su justificacion, y ese
	 * acceso queda registrado como lo que es.
	 *
	 * <p>Nativa por el {@code LIMIT}, igual que en {@code TurnoRepository}.
	 */
	@Query(value = """
			SELECT s.caso_id AS caso, COUNT(*) AS cantidad
			  FROM sesion s
			 WHERE s.organization_id = :organizationId
			   AND s.consultorio_id = :consultorioId
			   AND s.cerrada_en >= :desde
			   AND s.cerrada_en < :hasta
			   AND s.deleted_at IS NULL
			 GROUP BY s.caso_id
			 ORDER BY cantidad DESC, caso
			 LIMIT :limite
			""", nativeQuery = true)
	List<Object[]> contarCerradasPorCasoEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("limite") int limite);
}
