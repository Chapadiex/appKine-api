package com.akine.activity.infrastructure;

import com.akine.activity.domain.PaseServicio;
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
import java.util.List;
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
}
