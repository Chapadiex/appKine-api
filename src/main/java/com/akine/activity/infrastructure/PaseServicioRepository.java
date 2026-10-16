package com.akine.activity.infrastructure;

import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.port.ActivityRepositoryPorts.CandidatoAAvisar;
import com.akine.activity.domain.port.ActivityRepositoryPorts.CandidatoARenovar;
import com.akine.activity.domain.port.ActivityRepositoryPorts.CandidatoAVencer;
import com.akine.activity.domain.port.ActivityRepositoryPorts.EstadoDePase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pases vendidos (AKINE-08.06/08.07).
 *
 * <h2>Los UPDATE del saldo</h2>
 *
 * <p>Consumir, devolver, ajustar, anular y vencer son el unico camino por el que se mueven
 * {@code creditos_disponibles} y {@code estado}, y todos <b>suben {@code version}</b> (la leccion de #33 y #39: un UPDATE nativo que no la mueve deja
 * pasar una escritura vieja). La condicion vive en el {@code WHERE} y la evalua InnoDB con lectura
 * actual bajo el lock exclusivo de la fila: dos consumos del ultimo credito no pueden pasar los dos.
 *
 * <p><b>El orden de las asignaciones importa.</b> MySQL evalua el {@code SET} de izquierda a
 * derecha con los valores ya asignados, asi que {@code estado} se calcula ANTES de tocar el saldo.
 *
 * <p>{@code clearAutomatically = false} a proposito: limpiar el contexto desacoplaria la asistencia
 * y la inscripcion que el llamador tiene cargadas a mitad de transaccion (el defecto de #57). La
 * entidad nunca se vuelve a guardar despues de un consumo y el saldo se relee con
 * {@link #saldoActual}.
 */
public interface PaseServicioRepository
		extends JpaRepository<PaseServicio, Long>, PaseServicioRepositoryPort {

	@Override
	@Query("""
			SELECT p FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.consultorioId = :consultorioId
			   AND p.id = :paseId
			""")
	Optional<PaseServicio> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("paseId") long paseId);

	@Override
	@Query("""
			SELECT p FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.idempotencyKey = :idempotencyKey
			""")
	Optional<PaseServicio> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);

	@Override
	@Query("""
			SELECT p FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.personaId = :personaId
			 ORDER BY p.vigenciaHasta DESC, p.id DESC
			""")
	List<PaseServicio> findDePersona(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId);

	@Override
	@Query(value = """
			SELECT id FROM pase_servicio
			 WHERE organization_id = :organizationId
			   AND persona_id = :personaId
			   AND snapshot_oferta_id = :ofertaId
			   AND estado = 'ACTIVO'
			   AND creditos_disponibles > 0
			   AND vigencia_desde <= :fecha
			   AND vigencia_hasta >= :fecha
			 ORDER BY vigencia_hasta ASC, id ASC
			 FOR UPDATE
			""", nativeQuery = true)
	List<Long> lockElegibles(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId,
			@Param("ofertaId") long ofertaId,
			@Param("fecha") LocalDate fecha);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET estado = CASE WHEN creditos_disponibles = 1 THEN 'AGOTADO' ELSE estado END,
			       creditos_disponibles = creditos_disponibles - 1,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND estado = 'ACTIVO'
			   AND creditos_disponibles >= 1
			   AND vigencia_desde <= :fecha
			   AND vigencia_hasta >= :fecha
			""", nativeQuery = true)
	int consumirUno(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("fecha") LocalDate fecha);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET estado = 'ACTIVO',
			       creditos_disponibles = creditos_disponibles + 1,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND estado IN ('ACTIVO', 'AGOTADO', 'VENCIDO')
			   AND creditos_disponibles < creditos_iniciales
			""", nativeQuery = true)
	int devolverUno(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	@Query(value = """
			SELECT creditos_disponibles FROM pase_servicio
			 WHERE id = :paseId AND organization_id = :organizationId
			""", nativeQuery = true)
	int saldoActual(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	@Query(value = """
			SELECT * FROM pase_servicio
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			 FOR UPDATE
			""", nativeQuery = true)
	Optional<PaseServicio> lockByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("paseId") long paseId);

	/**
	 * El ajuste manual. El rango del saldo final y la version leida van en el {@code WHERE}: el
	 * servicio ya los valido con el pase bloqueado, y esto es el cinturon que no depende de que el
	 * servicio no cambie. {@code estado} se calcula antes que el saldo (orden del {@code SET}).
	 */
	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET estado = CASE WHEN creditos_disponibles + :delta = 0 THEN 'AGOTADO' ELSE 'ACTIVO' END,
			       creditos_disponibles = creditos_disponibles + :delta,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND version = :versionLeida
			   AND estado IN ('ACTIVO', 'AGOTADO')
			   AND creditos_disponibles + :delta >= 0
			   AND creditos_disponibles + :delta <= creditos_iniciales
			""", nativeQuery = true)
	int ajustar(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("delta") int delta,
			@Param("versionLeida") long versionLeida);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET estado = 'ANULADO',
			       creditos_disponibles = 0,
			       anulado_en = :ahora,
			       anulado_por_cuenta_id = :actorCuentaId,
			       motivo_anulacion = :motivo,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND version = :versionLeida
			   AND estado IN ('ACTIVO', 'AGOTADO', 'VENCIDO')
			""", nativeQuery = true)
	int anular(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("versionLeida") long versionLeida,
			@Param("actorCuentaId") long actorCuentaId,
			@Param("motivo") String motivo,
			@Param("ahora") Instant ahora);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET estado = 'VENCIDO',
			       creditos_disponibles = 0,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND estado = 'ACTIVO'
			   AND vigencia_hasta < :hoy
			""", nativeQuery = true)
	int vencer(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("hoy") LocalDate hoy);

	@Override
	default EstadoDePase estadoActual(long organizationId, long paseId) {
		Object[] fila = filaDeEstado(organizationId, paseId).get(0);
		return new EstadoDePase(
				((Number) fila[0]).intValue(), (String) fila[1], ((Number) fila[2]).longValue());
	}

	@Query(value = """
			SELECT creditos_disponibles, estado, version FROM pase_servicio
			 WHERE id = :paseId AND organization_id = :organizationId
			""", nativeQuery = true)
	List<Object[]> filaDeEstado(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	default List<CandidatoAVencer> candidatosAVencer(LocalDate limite, int maximo) {
		return filasAVencer(limite, PageRequest.of(0, maximo)).stream()
				.map(fila -> new CandidatoAVencer(
						(Long) fila[0], (Long) fila[1], (Long) fila[2], (LocalDate) fila[3]))
				.toList();
	}

	@Query("""
			SELECT p.id, p.organizationId, p.consultorioId, p.vigenciaHasta FROM PaseServicio p
			 WHERE p.estado = com.akine.activity.domain.EstadoPase.ACTIVO
			   AND p.vigenciaHasta < :limite
			 ORDER BY p.vigenciaHasta ASC, p.id ASC
			""")
	List<Object[]> filasAVencer(@Param("limite") LocalDate limite, Pageable pagina);

	@Override
	@Query("""
			SELECT COUNT(p) > 0 FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.personaId = :personaId
			   AND p.productoId = :productoId
			   AND p.estado <> com.akine.activity.domain.EstadoPase.ANULADO
			   AND p.vigenciaDesde <= :hasta
			   AND p.vigenciaHasta >= :desde
			""")
	boolean existeAbonoSuperpuesto(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId,
			@Param("productoId") long productoId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta);

	// =================================================================================
	// Renovacion de abonos (AKINE-08.08). Los tres UPDATE suben version, como los del saldo.
	// =================================================================================

	@Override
	default int configurarRenovacion(
			long organizationId, long paseId, boolean activa, long actorCuentaId, long versionLeida) {
		return actualizarRenovacion(organizationId, paseId, activa ? 1 : 0, actorCuentaId, versionLeida);
	}

	/** Prenderla fija quien la autorizo; apagarla conserva el ultimo que la autorizo (historia). */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET renovacion_autorizada_por_cuenta_id = CASE WHEN :activa = 1 THEN :actorCuentaId
			                                                  ELSE renovacion_autorizada_por_cuenta_id END,
			       renovacion_automatica = :activa,
			       renovacion_omitida_motivo = NULL,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND version = :versionLeida
			   AND tipo = 'ABONO'
			   AND estado <> 'ANULADO'
			""", nativeQuery = true)
	int actualizarRenovacion(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("activa") int activa,
			@Param("actorCuentaId") long actorCuentaId,
			@Param("versionLeida") long versionLeida);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET renovacion_automatica = 0,
			       renovacion_omitida_motivo = NULL,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND renovacion_automatica = 1
			""", nativeQuery = true)
	int marcarRenovado(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET renovacion_automatica = 0,
			       renovacion_omitida_motivo = :motivo,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND renovacion_automatica = 1
			""", nativeQuery = true)
	int omitirRenovacion(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("motivo") String motivo);

	@Override
	@Query("""
			SELECT p.id FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.renuevaPaseId = :paseId
			""")
	Optional<Long> renovacionDe(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId);

	@Override
	default Map<Long, Long> renovacionesDe(long organizationId, Collection<Long> paseIds) {
		if (paseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Long> renovaciones = new HashMap<>();
		for (Object[] fila : filasDeRenovacion(organizationId, paseIds)) {
			renovaciones.put((Long) fila[0], (Long) fila[1]);
		}
		return renovaciones;
	}

	/** Usa {@code uk_pase_servicio_renovacion (organization_id, renueva_pase_id)}: sin indice nuevo. */
	@Query("""
			SELECT p.renuevaPaseId, p.id FROM PaseServicio p
			 WHERE p.organizationId = :organizationId
			   AND p.renuevaPaseId IN :paseIds
			""")
	List<Object[]> filasDeRenovacion(
			@Param("organizationId") long organizationId,
			@Param("paseIds") Collection<Long> paseIds);

	@Override
	default List<CandidatoARenovar> candidatosARenovar(LocalDate limite, int maximo) {
		return filasARenovar(limite, PageRequest.of(0, maximo)).stream()
				.map(fila -> new CandidatoARenovar(
						(Long) fila[0], (Long) fila[1], (Long) fila[2], (Long) fila[3], (LocalDate) fila[4]))
				.toList();
	}

	@Query("""
			SELECT p.id, p.organizationId, p.consultorioId, p.productoId, p.vigenciaHasta FROM PaseServicio p
			 WHERE p.renovacionAutomatica = true
			   AND p.vigenciaHasta <= :limite
			   AND p.tipo = com.akine.activity.domain.TipoProducto.ABONO
			   AND p.estado <> com.akine.activity.domain.EstadoPase.ANULADO
			 ORDER BY p.vigenciaHasta ASC, p.id ASC
			""")
	List<Object[]> filasARenovar(@Param("limite") LocalDate limite, Pageable pagina);

	// =================================================================================
	// Avisos (RF-M26-008, AKINE-08.08). La marca es la idempotencia: el UPDATE la pone una sola vez.
	// =================================================================================

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET aviso_vencimiento_en = :ahora,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND aviso_vencimiento_en IS NULL
			   AND estado = 'ACTIVO'
			""", nativeQuery = true)
	int marcarAvisoDeVencimiento(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("ahora") Instant ahora);

	@Override
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pase_servicio
			   SET aviso_saldo_bajo_en = :ahora,
			       version = version + 1
			 WHERE id = :paseId
			   AND organization_id = :organizationId
			   AND aviso_saldo_bajo_en IS NULL
			   AND estado = 'ACTIVO'
			   AND creditos_disponibles BETWEEN 1 AND :umbral
			   AND creditos_disponibles < creditos_iniciales
			""", nativeQuery = true)
	int marcarAvisoDeSaldoBajo(
			@Param("organizationId") long organizationId,
			@Param("paseId") long paseId,
			@Param("umbral") int umbral,
			@Param("ahora") Instant ahora);

	@Override
	default List<CandidatoAAvisar> candidatosAvisoDeVencimiento(LocalDate desde, LocalDate hasta, int maximo) {
		return aCandidatos(filasAvisoDeVencimiento(desde, hasta, PageRequest.of(0, maximo)));
	}

	@Override
	default List<CandidatoAAvisar> candidatosAvisoDeSaldoBajo(int umbral, int maximo) {
		return aCandidatos(filasAvisoDeSaldoBajo(umbral, PageRequest.of(0, maximo)));
	}

	private static List<CandidatoAAvisar> aCandidatos(List<Object[]> filas) {
		return filas.stream()
				.map(fila -> new CandidatoAAvisar(
						(Long) fila[0], (Long) fila[1], (Long) fila[2], (LocalDate) fila[3]))
				.toList();
	}

	@Query("""
			SELECT p.id, p.organizationId, p.consultorioId, p.vigenciaHasta FROM PaseServicio p
			 WHERE p.estado = com.akine.activity.domain.EstadoPase.ACTIVO
			   AND p.vigenciaHasta BETWEEN :desde AND :hasta
			   AND p.creditosDisponibles > 0
			   AND p.avisoVencimientoEn IS NULL
			 ORDER BY p.vigenciaHasta ASC, p.id ASC
			""")
	List<Object[]> filasAvisoDeVencimiento(
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta,
			Pageable pagina);

	@Query("""
			SELECT p.id, p.organizationId, p.consultorioId, p.vigenciaHasta FROM PaseServicio p
			 WHERE p.estado = com.akine.activity.domain.EstadoPase.ACTIVO
			   AND p.creditosDisponibles BETWEEN 1 AND :umbral
			   AND p.creditosDisponibles < p.creditosIniciales
			   AND p.avisoSaldoBajoEn IS NULL
			 ORDER BY p.id ASC
			""")
	List<Object[]> filasAvisoDeSaldoBajo(@Param("umbral") int umbral, Pageable pagina);
}
