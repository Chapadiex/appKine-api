package com.akine.person.infrastructure;

import com.akine.person.domain.AutorizacionMovimiento;
import com.akine.person.domain.TipoMovimientoAutorizacion;
import com.akine.person.domain.TipoOrigenMovimiento;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionMovimientoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia del ledger de movimientos de saldo (M17, AKINE-04.05).
 *
 * <p><b>Append-only.</b> El puerto que implementa no declara {@code update} ni {@code delete}, y
 * esta interfaz no agrega ninguno: un ledger que se puede editar no es un ledger. Mismo diseño que
 * {@code PlanEventoRepository} y que el historial de turnos de 05.03.
 *
 * <p><b>Toda consulta lleva {@code organizationId}</b>, sin excepcion y verificado
 * individualmente, como el encabezado de {@code PersonRepositoryPorts} obliga. Este ledger dice
 * cuantas sesiones se le prestaron a un paciente y cuando: una consulta sin la columna de tenant
 * lo entrega entero.
 */
public interface AutorizacionMovimientoRepository
		extends JpaRepository<AutorizacionMovimiento, Long>, AutorizacionMovimientoRepositoryPort {

	/**
	 * {@inheritDoc}
	 *
	 * <p>Los cinco predicados son exactamente {@code uk_autorizacion_movimiento_origen}. Si algun
	 * dia difieren, la idempotencia deja de funcionar en silencio y el reintento del cierre pasa a
	 * chocar contra el unique: cualquier cambio en el unique tiene que llegar hasta aca.
	 */
	@Override
	@Query("""
			SELECT m FROM AutorizacionMovimiento m
			 WHERE m.organizationId = :organizationId
			   AND m.autorizacionId = :autorizacionId
			   AND m.tipo = :tipo
			   AND m.tipoOrigen = :tipoOrigen
			   AND m.referenciaOrigen = :referenciaOrigen
			""")
	Optional<AutorizacionMovimiento> buscarPorOrigen(
			@Param("organizationId") Long organizationId,
			@Param("autorizacionId") Long autorizacionId,
			@Param("tipo") TipoMovimientoAutorizacion tipo,
			@Param("tipoOrigen") TipoOrigenMovimiento tipoOrigen,
			@Param("referenciaOrigen") Long referenciaOrigen);

	@Override
	@Query("""
			SELECT m FROM AutorizacionMovimiento m
			 WHERE m.organizationId = :organizationId
			   AND m.autorizacionId = :autorizacionId
			   AND m.id = :movimientoId
			""")
	Optional<AutorizacionMovimiento> buscarDeLaAutorizacion(
			@Param("organizationId") Long organizationId,
			@Param("autorizacionId") Long autorizacionId,
			@Param("movimientoId") Long movimientoId);

	@Override
	@Query("""
			SELECT m FROM AutorizacionMovimiento m
			 WHERE m.organizationId = :organizationId
			   AND m.autorizacionId = :autorizacionId
			 ORDER BY m.ocurrioEn ASC, m.id ASC
			""")
	List<AutorizacionMovimiento> listarDeAutorizacion(
			@Param("organizationId") Long organizationId,
			@Param("autorizacionId") Long autorizacionId);

	/**
	 * Lo que un hecho de origen dejo en el ledger, en todas las autorizaciones (AKINE C-4).
	 *
	 * <p>La resuelve {@code ix_movimiento_origen} de {@code V76}.
	 */
	@Override
	@Query("""
			SELECT m FROM AutorizacionMovimiento m
			 WHERE m.organizationId = :organizationId
			   AND m.tipoOrigen = :tipoOrigen
			   AND m.referenciaOrigen = :referenciaOrigen
			 ORDER BY m.ocurrioEn ASC, m.id ASC
			""")
	List<AutorizacionMovimiento> listarDeOrigen(
			@Param("organizationId") Long organizationId,
			@Param("tipoOrigen") TipoOrigenMovimiento tipoOrigen,
			@Param("referenciaOrigen") Long referenciaOrigen);
}
