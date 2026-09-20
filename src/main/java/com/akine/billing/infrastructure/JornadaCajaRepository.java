package com.akine.billing.infrastructure;

import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Jornadas de caja. Las tres escrituras son <b>UPDATE condicionales nativos</b>.
 *
 * <p>Nativos y no JPQL a proposito: la condicion del {@code WHERE} es lo que decide la correctitud
 * de la etapa entera y tiene que estar a la vista de quien lee. Hacerlas por JPA exigiria ademas
 * cargar la entidad antes, que es exactamente la lectura previa que se quiere evitar — ahi es donde
 * se cuela la ventana entre dos operadores concurrentes.
 *
 * <p>Corolario que hay que tener presente: <b>estas sentencias no incrementan ninguna
 * {@code @Version}</b>, y por eso {@code jornada_caja} no tiene esa columna. Ver el javadoc de
 * {@link JornadaCaja}.
 *
 * <p>Las tres llevan {@code clearAutomatically} y {@code flushAutomatically}, y no es decoracion:
 * <b>un UPDATE nativo no actualiza la copia que la sesion de JPA tiene en memoria</b>. Sin limpiar
 * el contexto, releer la jornada despues de escribirla devuelve el saldo y el estado viejos, y
 * justamente esa relectura es la que decide si el rechazo fue "ya estaba cerrada" o "el saldo
 * cambio". Es la misma trampa que {@code CobroRepository} evita derivando el estado de la
 * obligacion con SQL en vez de cargando la entidad.
 */
public interface JornadaCajaRepository
		extends JpaRepository<JornadaCaja, Long>, JornadaCajaRepositoryPort {

	@Override
	@Query("""
			SELECT j FROM JornadaCaja j
			 WHERE j.organizationId = :organizationId
			   AND j.consultorioId = :consultorioId
			   AND j.id = :jornadaId
			""")
	Optional<JornadaCaja> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("jornadaId") long jornadaId);

	/** A lo sumo una: lo garantiza {@code uk_jornada_caja_abierta} sobre la columna generada. */
	@Override
	@Query("""
			SELECT j FROM JornadaCaja j
			 WHERE j.organizationId = :organizationId
			   AND j.consultorioId = :consultorioId
			   AND j.estado = com.akine.billing.domain.EstadoJornada.ABIERTA
			""")
	Optional<JornadaCaja> findAbierta(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);

	/**
	 * RF-M20-007. Nativo por la paginacion con {@code LIMIT/OFFSET} y por los filtros opcionales,
	 * que en JPQL obligarian a tres consultas o a un criteria que nadie va a leer.
	 */
	@Override
	@Query(value = """
			SELECT * FROM jornada_caja
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			   AND (:estado IS NULL OR estado = :estado)
			   AND (:desde IS NULL OR fecha_negocio >= :desde)
			   AND (:hasta IS NULL OR fecha_negocio <= :hasta)
			 ORDER BY fecha_negocio DESC, id DESC
			 LIMIT :limite OFFSET :desplazamiento
			""", nativeQuery = true)
	List<JornadaCaja> findHistorico(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("estado") String estado,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta,
			@Param("limite") int limite,
			@Param("desplazamiento") int desplazamiento);

	/** Cero filas = la jornada se cerro entre medio. Ver el puerto. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE jornada_caja
			   SET saldo_arqueo = saldo_arqueo + :importe
			 WHERE id = :jornadaId
			   AND organization_id = :organizationId
			   AND estado = 'ABIERTA'
			""", nativeQuery = true)
	@Override
	int sumarAlSaldo(
			@Param("organizationId") long organizationId,
			@Param("jornadaId") long jornadaId,
			@Param("importe") BigDecimal importe);

	/**
	 * La condicion {@code saldo_arqueo >= :importe} es todo el diseno del egreso.
	 *
	 * <p>Un cajon no puede tener menos de cero pesos. Cero filas tiene dos causas —cerro, o no
	 * alcanza— y el servicio las distingue releyendo; es seguro porque un UPDATE de cero filas no
	 * marca la transaccion para rollback.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE jornada_caja
			   SET saldo_arqueo = saldo_arqueo - :importe
			 WHERE id = :jornadaId
			   AND organization_id = :organizationId
			   AND estado = 'ABIERTA'
			   AND saldo_arqueo >= :importe
			""", nativeQuery = true)
	@Override
	int restarDelSaldo(
			@Param("organizationId") long organizationId,
			@Param("jornadaId") long jornadaId,
			@Param("importe") BigDecimal importe);

	/**
	 * El cierre entero en una sentencia.
	 *
	 * <p>{@code diferencia} la calcula el motor contra su propio {@code saldo_arqueo}: el cliente
	 * manda lo que <b>conto</b>, nunca lo que cree que deberia haber.
	 *
	 * <p>{@code saldo_arqueo = :saldoTeoricoEsperado} es el control optimista, y lo que impide
	 * registrar un faltante que nunca existio cuando entra un cobro en efectivo mientras el
	 * operador cuenta.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE jornada_caja
			   SET estado = 'CERRADA',
			       saldo_teorico_cierre = saldo_arqueo,
			       saldo_declarado = :saldoDeclarado,
			       diferencia = :saldoDeclarado - saldo_arqueo,
			       motivo_diferencia = :motivoDiferencia,
			       cerrada_en = :cerradaEn,
			       cerrada_por_cuenta_id = :cerradaPorCuentaId
			 WHERE id = :jornadaId
			   AND organization_id = :organizationId
			   AND estado = 'ABIERTA'
			   AND saldo_arqueo = :saldoTeoricoEsperado
			""", nativeQuery = true)
	@Override
	int cerrar(
			@Param("organizationId") long organizationId,
			@Param("jornadaId") long jornadaId,
			@Param("saldoTeoricoEsperado") BigDecimal saldoTeoricoEsperado,
			@Param("saldoDeclarado") BigDecimal saldoDeclarado,
			@Param("motivoDiferencia") String motivoDiferencia,
			@Param("cerradaEn") Instant cerradaEn,
			@Param("cerradaPorCuentaId") long cerradaPorCuentaId);

	// =================================================================================
	// M23 — agregaciones de reporte (AKINE-07.06)
	// =================================================================================

	/**
	 * Suma de las diferencias de arqueo del periodo (RF-M23-004).
	 *
	 * <p>Positiva sobra, negativa falta. Es el unico indicador economico del reporte que puede ser
	 * <b>negativo</b>, y tiene que poder serlo: reportar el valor absoluto haria que un mes con mil
	 * de sobra y mil de faltante se leyera igual que uno sin ninguna diferencia, que es justo el
	 * mes que hay que mirar.
	 *
	 * <p>Solo jornadas {@code CERRADA}: una abierta todavia no arqueo y su {@code diferencia} es
	 * nula por el check de V54.
	 *
	 * <p>Corta por {@code fecha_negocio}, que ya es la fecha local de la sede.
	 */
	@Query("""
			SELECT SUM(j.diferencia) FROM JornadaCaja j
			 WHERE j.organizationId = :organizationId
			   AND j.consultorioId = :consultorioId
			   AND j.estado = com.akine.billing.domain.EstadoJornada.CERRADA
			   AND j.fechaNegocio >= :desde
			   AND j.fechaNegocio <= :hasta
			""")
	BigDecimal sumarDiferenciasEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta);
}
