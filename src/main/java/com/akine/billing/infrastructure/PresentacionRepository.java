package com.akine.billing.infrastructure;

import com.akine.billing.domain.Presentacion;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lotes reclamados a financiadores. Las dos escrituras del saldo son <b>UPDATE condicionales
 * nativos</b>.
 *
 * <p>Nativos y no JPQL a proposito: la condicion del {@code WHERE} es lo que decide la correctitud
 * de la etapa y tiene que estar a la vista de quien lee. Hacerlas por JPA exigiria ademas cargar la
 * entidad antes, que es exactamente la lectura previa que se quiere evitar — ahi es donde se cuela
 * la ventana entre el debito y el pago concurrentes.
 *
 * <p>Corolario que hay que tener presente: <b>estas sentencias no incrementan la
 * {@code @Version}</b>. La proteccion la da la condicion, que es mas fuerte que un token optimista
 * porque no depende de que el cliente lo haya leido antes.
 *
 * <p>Las dos llevan {@code clearAutomatically} y {@code flushAutomatically}, y no es decoracion: un
 * UPDATE nativo <b>no actualiza la copia que la sesion de JPA tiene en memoria</b>. Sin limpiar el
 * contexto, releer la presentacion despues de escribirla devuelve el saldo y el estado viejos — y
 * justamente esa relectura es la que decide si el rechazo fue "el estado cambio" o "no alcanza el
 * saldo". Es la misma trampa que {@code JornadaCajaRepository} documenta.
 */
public interface PresentacionRepository
		extends JpaRepository<Presentacion, Long>, PresentacionRepositoryPort {

	@Override
	@Query("""
			SELECT p FROM Presentacion p
			 WHERE p.organizationId = :organizationId
			   AND p.consultorioId = :consultorioId
			   AND p.id = :presentacionId
			   AND p.deletedAt IS NULL
			""")
	Optional<Presentacion> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("presentacionId") long presentacionId);

	/**
	 * Nativo por la paginacion con {@code LIMIT/OFFSET} y por los filtros opcionales, que en JPQL
	 * obligarian a varias consultas o a un criteria que nadie va a leer. Mismo criterio que
	 * {@code JornadaCajaRepository.findHistorico}.
	 */
	@Override
	@Query(value = """
			SELECT * FROM presentacion
			 WHERE organization_id = :organizationId
			   AND consultorio_id = :consultorioId
			   AND deleted_at IS NULL
			   AND (:estado IS NULL OR estado = :estado)
			   AND (:financiadorId IS NULL OR financiador_id = :financiadorId)
			   AND (:desde IS NULL OR periodo_hasta >= :desde)
			   AND (:hasta IS NULL OR periodo_desde <= :hasta)
			 ORDER BY periodo_hasta DESC, id DESC
			 LIMIT :limite OFFSET :desplazamiento
			""", nativeQuery = true)
	List<Presentacion> buscar(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("estado") String estado,
			@Param("financiadorId") Long financiadorId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta,
			@Param("limite") int limite,
			@Param("desplazamiento") int desplazamiento);

	/**
	 * <p>Sin filtrar por sede a proposito: el unique de V56 es
	 * {@code (organization_id, financiador_id, factura_numero)} y acotar la consulta a una sede
	 * haria que el servicio respondiera "libre" para un numero que la base va a rechazar.
	 */
	@Override
	@Query("""
			SELECT COUNT(p) > 0 FROM Presentacion p
			 WHERE p.organizationId = :organizationId
			   AND p.financiadorId = :financiadorId
			   AND p.facturaNumero = :facturaNumero
			""")
	boolean existeFactura(
			@Param("organizationId") long organizationId,
			@Param("financiadorId") long financiadorId,
			@Param("facturaNumero") String facturaNumero);

	/** RF-M21-007. Ver el puerto: cero filas es la respuesta, no un error tecnico. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE presentacion
			   SET total_cobrado = total_cobrado + :importe,
			       saldo         = saldo - :importe
			 WHERE id = :presentacionId
			   AND organization_id = :organizationId
			   AND estado IN ('PRESENTADA', 'FACTURADA')
			   AND saldo >= :importe
			""", nativeQuery = true)
	@Override
	int registrarCobro(
			@Param("organizationId") long organizationId,
			@Param("presentacionId") long presentacionId,
			@Param("importe") BigDecimal importe);

	/** RF-M21-006. Misma forma y mismas condiciones, sobre otra columna. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE presentacion
			   SET total_debitado = total_debitado + :importe,
			       saldo          = saldo - :importe
			 WHERE id = :presentacionId
			   AND organization_id = :organizationId
			   AND estado IN ('PRESENTADA', 'FACTURADA')
			   AND saldo >= :importe
			""", nativeQuery = true)
	@Override
	int registrarDebito(
			@Param("organizationId") long organizationId,
			@Param("presentacionId") long presentacionId,
			@Param("importe") BigDecimal importe);

	// =================================================================================
	// M23 — agregaciones de reporte (AKINE-07.06)
	// =================================================================================

	/**
	 * Presentado, facturado, debitado y pendiente por financiador (RF-M23-005).
	 *
	 * <h3>Hoy devuelve lista vacia en cualquier despliegue real</h3>
	 *
	 * <p>Y no es un defecto de esta consulta. Una presentacion solo puede contener items
	 * provenientes de obligaciones con {@code responsable = FINANCIADOR}, y <b>no existe ninguna:
	 * nada las produce</b>. {@code ObligacionDevengador} devenga una sola obligacion a nombre del
	 * paciente y el enchufe que V36 reservo y V56 completo nunca se conecto. Es la decision
	 * pendiente del usuario que AKINE-07.04 ya habia declarado, y la cadena entera se apaga con
	 * ella.
	 *
	 * <p>El reporte lo dice con una advertencia en vez de mostrar un cero mudo: un tablero que en
	 * produccion muestra ceros sin explicar por que es peor que uno ausente.
	 *
	 * <h3>El recorte del periodo es por SOLAPAMIENTO</h3>
	 *
	 * <p>{@code periodo_desde <= :hasta AND periodo_hasta >= :desde}: una presentacion del 15/8 al
	 * 15/9 <b>tiene que aparecer</b> en el reporte de septiembre. Recortar por contencion la
	 * dejaria afuera de los dos meses que toca, y el centro no veria el lote mas grande que armo.
	 *
	 * <h3>Los estados</h3>
	 *
	 * <p>Excluye {@code BORRADOR} —no existe para el financiador: no tiene numero y nadie la vio—
	 * y {@code ANULADA}. {@code facturado} suma solo lo que llego a tener factura.
	 */
	@Query(value = """
			SELECT p.financiador_id                                                AS financiador,
			       SUM(p.total_presentado)                                         AS presentado,
			       SUM(CASE WHEN p.estado IN ('FACTURADA', 'CONCILIADA')
			                THEN p.total_presentado ELSE 0 END)                     AS facturado,
			       SUM(p.total_debitado)                                            AS debitado,
			       SUM(p.total_cobrado)                                             AS cobrado,
			       SUM(p.saldo)                                                     AS pendiente
			  FROM presentacion p
			 WHERE p.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND p.estado IN ('PRESENTADA', 'FACTURADA', 'CONCILIADA')
			   AND p.deleted_at IS NULL
			   AND p.periodo_desde <= :hasta
			   AND p.periodo_hasta >= :desde
			 GROUP BY p.financiador_id
			 ORDER BY presentado DESC, financiador
			 LIMIT :limite
			""", nativeQuery = true)
	List<Object[]> resumirPorFinanciadorEnElReporte(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta,
			@Param("limite") int limite);
}
