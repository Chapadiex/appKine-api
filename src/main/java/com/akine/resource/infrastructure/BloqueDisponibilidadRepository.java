package com.akine.resource.infrastructure;

import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a los bloques recurrentes de atencion de un profesional en una sede. Toda consulta
 * filtra por {@code organizationId}, {@code consultorioId} y {@code membershipId}: un id de
 * otro tenant, de otra sede o de otro profesional no debe resolver nunca (ADR-0004,
 * RN-M05-001).
 */
public interface BloqueDisponibilidadRepository
		extends JpaRepository<BloqueDisponibilidad, Long>, BloqueDisponibilidadRepositoryPort {

	/**
	 * Bloques ACTIVOS cuya ventana de vigencia {@code [vigenciaDesde, vigenciaHasta)} se solapa
	 * con {@code [desde, hasta)}.
	 *
	 * <p>Es JPQL y no nativa porque no lleva ninguna clausula de bloqueo: es una lectura pura,
	 * la que arma la disponibilidad efectiva de una ventana concreta (diseno §4). La cubre
	 * {@code ix_profesional_disponibilidad_sede_profesional_dia}.
	 */
	@Query("""
			SELECT b FROM BloqueDisponibilidad b
			 WHERE b.organizationId = :organizationId
			   AND b.consultorioId = :consultorioId
			   AND b.membershipId = :membershipId
			   AND b.active = true
			   AND b.vigenciaDesde < :hasta
			   AND (b.vigenciaHasta IS NULL OR b.vigenciaHasta > :desde)
			 ORDER BY b.diaSemana ASC, b.horaDesde ASC
			""")
	@Override
	List<BloqueDisponibilidad> findVigentesEn(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId,
			@Param("membershipId") Long membershipId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta);

	/**
	 * Todos los bloques ACTIVOS de esa membership en esa sede, sin filtro de fecha.
	 *
	 * <p>Se ejecuta SIEMPRE despues de {@code CalendarioSedeRepository#lockByScope}, dentro de
	 * la misma transaccion que valida el solapamiento del alta o la edicion (RF-M05-005). No
	 * filtra por vigencia a proposito: dos bloques de vigencia futura pueden solaparse entre si
	 * sin que ninguno este vigente todavia, y ese solapamiento se rechaza igual.
	 */
	@Query("""
			SELECT b FROM BloqueDisponibilidad b
			 WHERE b.organizationId = :organizationId
			   AND b.consultorioId = :consultorioId
			   AND b.membershipId = :membershipId
			   AND b.active = true
			 ORDER BY b.diaSemana ASC, b.horaDesde ASC
			""")
	@Override
	List<BloqueDisponibilidad> findActivosDe(
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId,
			@Param("membershipId") Long membershipId);

	/** Un bloque por id, acotado al tenant y a la sede, activo o no. */
	@Query("""
			SELECT b FROM BloqueDisponibilidad b
			 WHERE b.id = :id
			   AND b.organizationId = :organizationId
			   AND b.consultorioId = :consultorioId
			""")
	@Override
	Optional<BloqueDisponibilidad> findByIdScoped(
			@Param("id") Long id,
			@Param("organizationId") Long organizationId,
			@Param("consultorioId") Long consultorioId);
}
