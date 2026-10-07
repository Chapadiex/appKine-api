package com.akine.person.infrastructure;

import com.akine.person.domain.AutorizacionAlerta;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionAlertaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Alertas sobre autorizaciones (DP-13, AKINE C-4). Ver el javadoc del puerto.
 */
public interface AutorizacionAlertaRepository
		extends JpaRepository<AutorizacionAlerta, Long>, AutorizacionAlertaRepositoryPort {

	/**
	 * Una alerta por consumo, sin choque.
	 *
	 * <p>{@code ON DUPLICATE KEY UPDATE movimiento_id = movimiento_id} es un no-op deliberado: la
	 * primera anulacion que la genero queda como origen, y una segunda anulacion de la misma sesion
	 * —F-4 va a devengar dos obligaciones por sesion— no la pisa ni lanza. Es la forma de que dos
	 * anulaciones concurrentes no terminen en un {@code DataIntegrityViolationException} dentro de
	 * la transaccion de {@code billing}, que no se puede atrapar sin dejarla marcada para rollback.
	 */
	@Override
	@Modifying
	@Query(value = """
			INSERT INTO autorizacion_alerta (
			        organization_id, autorizacion_id, persona_id, movimiento_id, tipo,
			        sesion_id, obligacion_id, motivo_origen, generada_en, generada_por, created_at)
			VALUES (:organizationId, :autorizacionId, :personaId, :movimientoId, :tipo,
			        :sesionId, :obligacionId, :motivoOrigen, :generadaEn, :generadaPor, :generadaEn)
			ON DUPLICATE KEY UPDATE movimiento_id = movimiento_id
			""", nativeQuery = true)
	@SuppressWarnings("java:S107")
	void registrarSiFalta(
			@Param("organizationId") long organizationId,
			@Param("autorizacionId") long autorizacionId,
			@Param("personaId") long personaId,
			@Param("movimientoId") long movimientoId,
			@Param("tipo") String tipo,
			@Param("sesionId") long sesionId,
			@Param("obligacionId") long obligacionId,
			@Param("motivoOrigen") String motivoOrigen,
			@Param("generadaEn") Instant generadaEn,
			@Param("generadaPor") Long generadaPor);

	@Override
	@Modifying
	@Query(value = """
			UPDATE autorizacion_alerta
			   SET resuelta_en = :resueltaEn,
			       resuelta_por = :resueltaPor,
			       resolucion = :resolucion
			 WHERE organization_id = :organizationId
			   AND movimiento_id = :movimientoId
			   AND resuelta_en IS NULL
			""", nativeQuery = true)
	int resolverDelMovimiento(
			@Param("organizationId") long organizationId,
			@Param("movimientoId") long movimientoId,
			@Param("resolucion") String resolucion,
			@Param("resueltaEn") Instant resueltaEn,
			@Param("resueltaPor") Long resueltaPor);

	@Override
	@Query("""
			SELECT a FROM AutorizacionAlerta a
			 WHERE a.organizationId = :organizationId
			   AND a.autorizacionId = :autorizacionId
			 ORDER BY a.generadaEn ASC, a.id ASC
			""")
	List<AutorizacionAlerta> listarDeAutorizacion(
			@Param("organizationId") long organizationId,
			@Param("autorizacionId") long autorizacionId);
}
