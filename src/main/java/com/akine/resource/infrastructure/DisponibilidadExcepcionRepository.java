package com.akine.resource.infrastructure;

import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los cierres y aperturas puntuales de disponibilidad. Toda consulta filtra por
 * {@code organizationId} y {@code consultorioId}: un id de otro tenant o de otra sede no debe
 * resolver nunca (ADR-0004).
 */
public interface DisponibilidadExcepcionRepository
		extends JpaRepository<DisponibilidadExcepcion, Long>, DisponibilidadExcepcionRepositoryPort {

	/**
	 * Excepciones ACTIVAS cuya ventana {@code [fechaDesde, fechaHasta)} se solapa con
	 * {@code [desde, hasta)}, de esa membership PUNTUAL o de alcance SEDE ENTERA.
	 *
	 * <p><b>{@code membershipId IS NULL} entra en el resultado a proposito.</b> Es el mismo
	 * significado que ya tiene {@code consultorio_id} nulo en {@code membership} (V10) y en
	 * {@code colaborador_invitacion} (V21): SCOPE, no un hueco. Si esta consulta filtrara solo
	 * por {@code membershipId = :membershipId}, un cierre de sede completo (feriado, corte de
	 * luz, cierre administrativo) dejaria de aplicarsele a cada profesional en silencio, y
	 * ningun test que solo mire un profesional a la vez lo va a detectar.
	 *
	 * <p>Es JPQL y no nativa: no lleva bloqueo, es una lectura pura cubierta por
	 * {@code ix_disponibilidad_excepcion_sede_fechas} y
	 * {@code ix_disponibilidad_excepcion_sede_profesional_fecha}.
	 */
	@Query("""
			SELECT e FROM DisponibilidadExcepcion e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND (e.membershipId = :membershipId OR e.membershipId IS NULL)
			   AND e.active = true
			   AND e.fechaDesde < :hasta
			   AND e.fechaHasta > :desde
			 ORDER BY e.fechaDesde ASC
			""")
	@Override
	List<DisponibilidadExcepcion> findQueCubren(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId,
			@Param("membershipId") Long membershipId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta);

	/**
	 * Excepciones ACTIVAS de alcance SEDE ENTERA que se solapan con {@code [desde, hasta)}.
	 *
	 * <p>Es el complemento exacto de {@link #findQueCubren}, no un caso particular suyo: alli
	 * {@code membershipId} es obligatorio y las de sede se SUMAN a las del profesional; aca solo
	 * entran las de sede. Lo pide el listado de excepciones sin {@code membershipId}, que es la
	 * pantalla de calendario de la sede. La cubre {@code ix_disponibilidad_excepcion_sede_fechas}.
	 */
	@Query("""
			SELECT e FROM DisponibilidadExcepcion e
			 WHERE e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			   AND e.membershipId IS NULL
			   AND e.active = true
			   AND e.fechaDesde < :hasta
			   AND e.fechaHasta > :desde
			 ORDER BY e.fechaDesde ASC
			""")
	@Override
	List<DisponibilidadExcepcion> findDeSedeQueCubren(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta);

	/**
	 * Una excepcion por id, acotada al tenant y a la sede, activa o no. Sin acotar por
	 * membership: una excepcion de sede entera no tiene una unica duena, y la ruta ya fija la
	 * sede.
	 */
	@Query("""
			SELECT e FROM DisponibilidadExcepcion e
			 WHERE e.id = :id
			   AND e.organizationId = :organizationId
			   AND e.consultorioId = :consultorioId
			""")
	@Override
	Optional<DisponibilidadExcepcion> findByIdScoped(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);

	/**
	 * Excepciones ACTIVAS de esa membership PUNTUAL (nunca de alcance sede) que todavia no
	 * terminaron, ordenadas por {@code fechaDesde} ascendente.
	 *
	 * <p>Alimenta {@code ResourceDesvinculacionProbe} (RN-M05-004) y por eso, a proposito, es lo
	 * opuesto de {@link #findQueCubren}: aca {@code membershipId IS NULL} NO entra. Una excepcion
	 * de sede entera no es "de" este profesional y no queda huerfana porque el se desvincule —
	 * la sede sigue existiendo — asi que contarla en el impacto de ESTA persona seria over-conteo.
	 * Tampoco acota por {@code consultorioId}: la pantalla de desvinculacion pregunta por la
	 * membership sola, igual que {@code countVigentesDe} en
	 * {@code BloqueDisponibilidadRepository}.
	 */
	@Query("""
			SELECT e FROM DisponibilidadExcepcion e
			 WHERE e.organizationId = :organizationId
			   AND e.membershipId = :membershipId
			   AND e.active = true
			   AND e.fechaHasta > :fecha
			 ORDER BY e.fechaDesde ASC
			""")
	List<DisponibilidadExcepcion> findFuturasDeLaMembership(
			@Param("organizationId") Long organizationId,
			@Param("membershipId") Long membershipId,
			@Param("fecha") LocalDate fecha);
}
