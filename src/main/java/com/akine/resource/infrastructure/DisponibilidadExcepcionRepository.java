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
}
