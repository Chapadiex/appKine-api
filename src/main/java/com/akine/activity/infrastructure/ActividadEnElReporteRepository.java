package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseProgramada;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Las lecturas de {@code activity} para los reportes de M23 (AKINE-09.01): RF-M23-007 y
 * RF-M23-010.
 *
 * <h2>Por que un repositorio aparte</h2>
 *
 * <p>Ninguna de estas consultas es del dominio: no las usa ningun caso de uso de M28/M29 y no
 * sostienen ningun invariante. Juntarlas aca deja a la vista, en un solo archivo, <b>todo</b> lo
 * que el reporte lee de las cinco tablas del modulo, que es lo que hay que revisar cuando una de
 * ellas cambia. Son de solo lectura y agregan en el motor: ninguna devuelve filas por persona.
 *
 * <h2>Las tres reglas que comparten</h2>
 *
 * <ul>
 *   <li><b>Tenant y sede siempre</b>, y la sede sale de la columna propia de la tabla, no de un
 *       join: es la consulta "sin join" que V60 y V61 dejaron posible a proposito.</li>
 *   <li><b>Los instantes se comparan contra la ventana ya proyectada</b> a la zona de la sede
 *       ({@code [desde, hasta)}, exclusiva arriba). Las fechas de negocio
 *       ({@code vigencia_desde}/{@code vigencia_hasta}) se comparan contra fechas locales.</li>
 *   <li><b>Agrupan por el texto del estado</b>, no por un enum: un estado nuevo tiene que seguir
 *       contando en vez de romper el reporte. Mismo criterio que {@code TurnosEnElReporte}.</li>
 * </ul>
 *
 * <p>No lee ninguna tabla de otro modulo (AGENT.md §4, regla 1): la capacidad de la oferta y del
 * espacio, que entran en la ocupacion, se piden por {@code offering.spi} y {@code resource.spi}.
 */
public interface ActividadEnElReporteRepository extends Repository<ClaseProgramada, Long> {

	/**
	 * Las clases del periodo, una fila por clase, <b>incluidas las canceladas</b> (RF-M23-007).
	 *
	 * <p>Cancelar una clase sella {@code deleted_at} (V58, {@code ck_clase_cancelacion_completa}),
	 * y es el unico camino que lo hace: filtrar por {@code deleted_at IS NULL} dejaria el indicador
	 * de canceladas siempre en cero, que es el defecto 16 que #86 corrigio en los turnos.
	 *
	 * <p>Sin {@code LIMIT}: los totales se calculan sobre todas, y el tope de filas de detalle lo
	 * aplica el contribuyente. Con la ventana maxima de 366 dias son unas pocas miles de filas de
	 * nueve columnas; agrupar en el motor no sirve porque la capacidad efectiva se resuelve por
	 * {@code spi} clase por clase.
	 *
	 * <p>{@code inicio_epoch} sale de {@code TIMESTAMPDIFF} contra la epoca y no de
	 * {@code UNIX_TIMESTAMP}: este ultimo interpreta el {@code DATETIME} con la zona de la sesion,
	 * y la columna guarda UTC.
	 *
	 * <p>Columnas: id, oferta_id, espacio_id, capacidad, cupo_ocupado, estado, inicio_epoch (segundos
	 * UTC), dia local, hora local, minuto local, titulo. La hora viaja en dos numeros y no
	 * formateada: un {@code '%H:%i'} dentro de la consulta es un {@code :i} que el parser de
	 * parametros nombrados puede tomar por propio.
	 */
	@Query(value = """
			SELECT c.id,
			       c.oferta_id,
			       c.espacio_id,
			       c.capacidad,
			       c.cupo_ocupado,
			       c.estado,
			       TIMESTAMPDIFF(SECOND, '1970-01-01', c.inicio) AS inicio_epoch,
			       DATE(IFNULL(CONVERT_TZ(c.inicio, '+00:00', :zona), c.inicio)) AS dia,
			       HOUR(IFNULL(CONVERT_TZ(c.inicio, '+00:00', :zona), c.inicio)) AS hora,
			       MINUTE(IFNULL(CONVERT_TZ(c.inicio, '+00:00', :zona), c.inicio)) AS minuto,
			       c.titulo
			  FROM clase_programada c
			 WHERE c.organization_id = :organizationId
			   AND c.consultorio_id = :consultorioId
			   AND c.inicio >= :desde
			   AND c.inicio < :hasta
			   AND (:recortar = FALSE OR c.profesional_membership_id IN (:memberships))
			 ORDER BY c.inicio, c.id
			""", nativeQuery = true)
	List<Object[]> clasesDelPeriodo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("zona") String zona,
			@Param("recortar") boolean recortar,
			@Param("memberships") Collection<Long> memberships);

	/**
	 * Inscripciones de las clases del periodo, por estado (RF-M23-007, RF-M28-002 a RF-M28-004).
	 *
	 * <p>Cuentan <b>todas</b>, tambien las canceladas (que sellan {@code deleted_at}): la
	 * cancelacion conserva la fila justamente para que se pueda contar (RF-M28-003). La tercera
	 * columna cuenta las que salieron de la lista de espera hacia un lugar ({@code promovida_en}),
	 * que conservan la marca aunque su estado ya sea otro.
	 *
	 * <p>El periodo es el de la <b>clase</b> ({@code clase_programada.inicio}), no el de la
	 * inscripcion: un reporte de octubre trae a los inscriptos de las clases de octubre, aunque se
	 * hayan anotado en septiembre.
	 *
	 * <p>Columnas: estado, cantidad, promovidas.
	 */
	@Query(value = """
			SELECT i.estado,
			       COUNT(*) AS cantidad,
			       SUM(CASE WHEN i.promovida_en IS NOT NULL THEN 1 ELSE 0 END) AS promovidas
			  FROM inscripcion_clase i
			  JOIN clase_programada c
			    ON c.id = i.clase_id
			   AND c.organization_id = i.organization_id
			 WHERE i.organization_id = :organizationId
			   AND i.consultorio_id = :consultorioId
			   AND c.inicio >= :desde
			   AND c.inicio < :hasta
			   AND (:recortar = FALSE OR c.profesional_membership_id IN (:memberships))
			 GROUP BY i.estado
			 ORDER BY i.estado
			""", nativeQuery = true)
	List<Object[]> inscripcionesPorEstado(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("recortar") boolean recortar,
			@Param("memberships") Collection<Long> memberships);

	/**
	 * Asistencias registradas en las clases del periodo, por resultado (RF-M23-007, RF-M28-007).
	 *
	 * <p>Una asistencia <b>no es una sesion clinica</b> (RN-M28-007, RN-M23-005): esta consulta no
	 * mira {@code sesion} y la seccion de sesiones no mira esta tabla. El resultado es el vigente,
	 * ya corregido; la tercera columna dice cuantas se corrigieron al menos una vez.
	 *
	 * <p>Columnas: resultado, cantidad, corregidas.
	 */
	@Query(value = """
			SELECT a.resultado,
			       COUNT(*) AS cantidad,
			       SUM(CASE WHEN a.correcciones > 0 THEN 1 ELSE 0 END) AS corregidas
			  FROM asistencia_actividad a
			  JOIN clase_programada c
			    ON c.id = a.clase_id
			   AND c.organization_id = a.organization_id
			 WHERE a.organization_id = :organizationId
			   AND a.consultorio_id = :consultorioId
			   AND c.inicio >= :desde
			   AND c.inicio < :hasta
			   AND (:recortar = FALSE OR c.profesional_membership_id IN (:memberships))
			 GROUP BY a.resultado
			 ORDER BY a.resultado
			""", nativeQuery = true)
	List<Object[]> asistenciasPorResultado(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta,
			@Param("recortar") boolean recortar,
			@Param("memberships") Collection<Long> memberships);

	// =================================================================================
	// Pases y abonos (RF-M23-010)
	// =================================================================================

	/**
	 * Pases y abonos vendidos en el periodo, por tipo (RF-M29-002, RF-M29-007).
	 *
	 * <p>Por {@code vendido_en}, y <b>incluidos los que despues se anularon</b>: una venta anulada
	 * fue una venta, y su anulacion se cuenta aparte en {@link #anuladosPorTipo}. La tercera
	 * columna cuenta los periodos de abono que vendio el job de renovacion ({@code renueva_pase_id}
	 * no nulo, V103).
	 *
	 * <p>Columnas: tipo, vendidos, renovados.
	 */
	@Query(value = """
			SELECT p.tipo,
			       COUNT(*) AS vendidos,
			       SUM(CASE WHEN p.renueva_pase_id IS NOT NULL THEN 1 ELSE 0 END) AS renovados
			  FROM pase_servicio p
			 WHERE p.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND p.vendido_en >= :desde
			   AND p.vendido_en < :hasta
			 GROUP BY p.tipo
			""", nativeQuery = true)
	List<Object[]> vendidosPorTipo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Pases y abonos anulados en el periodo, por {@code anulado_en} y por tipo.
	 *
	 * <p>Columnas: tipo, anulados.
	 */
	@Query(value = """
			SELECT p.tipo, COUNT(*) AS anulados
			  FROM pase_servicio p
			 WHERE p.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND p.anulado_en >= :desde
			   AND p.anulado_en < :hasta
			 GROUP BY p.tipo
			""", nativeQuery = true)
	List<Object[]> anuladosPorTipo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Pases y abonos cuya vigencia termino dentro del periodo y antes de hoy, por tipo (RF-M29-006).
	 *
	 * <p><b>Un pack vencido y un abono vencido no son lo mismo</b>, y la consulta lo dice:
	 *
	 * <ul>
	 *   <li>Un <b>pack</b> vence cuando el job lo pasa a {@code VENCIDO} con saldo sin usar. Uno que
	 *       termino {@code AGOTADO} se uso entero: no vencio nada.</li>
	 *   <li>Un <b>abono</b> cubre un periodo (RN-M29-007): vence cuando el periodo termina, haya
	 *       usado o no todos sus creditos. Solo el anulado queda afuera, porque nunca cubrio.</li>
	 * </ul>
	 *
	 * <p>{@code vigencia_hasta} es inclusiva (V64): un pase que vence hoy todavia vale hoy, por eso
	 * {@code < :hoy} y no {@code <=}. {@code hoy} es el de la sede, nunca el del servidor.
	 *
	 * <p>Columnas: tipo, vencidos.
	 */
	@Query(value = """
			SELECT p.tipo, COUNT(*) AS vencidos
			  FROM pase_servicio p
			 WHERE p.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND p.vigencia_hasta >= :desde
			   AND p.vigencia_hasta <= :hasta
			   AND p.vigencia_hasta < :hoy
			   AND ((p.tipo = 'PACK' AND p.estado = 'VENCIDO')
			     OR (p.tipo = 'ABONO' AND p.estado <> 'ANULADO'))
			 GROUP BY p.tipo
			""", nativeQuery = true)
	List<Object[]> vencidosPorTipo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") LocalDate desde,
			@Param("hasta") LocalDate hasta,
			@Param("hoy") LocalDate hoy);

	/**
	 * Lo vigente <b>hoy</b> en la sede, por tipo: cantidad de pases y suma de sus saldos
	 * (CA-M23-010-06: "total de creditos disponibles coincide con suma de saldos vigentes").
	 *
	 * <p>Vigente es {@code ACTIVO} y con {@code hoy} dentro de {@code [vigencia_desde,
	 * vigencia_hasta]}: un abono vendido para el mes que viene todavia no es saldo usable, y un
	 * {@code ACTIVO} con la vigencia ya terminada es uno que el job de vencimiento todavia no
	 * alcanzo. Contarlo seria mostrar como disponible un credito que nadie puede usar.
	 *
	 * <p><b>No depende del periodo pedido</b>: es una foto, y el estado de un pase no se puede
	 * reconstruir hacia atras. El indicador lo declara con la fecha de corte.
	 *
	 * <p>Columnas: tipo, vigentes, creditos_disponibles.
	 */
	@Query(value = """
			SELECT p.tipo,
			       COUNT(*) AS vigentes,
			       COALESCE(SUM(p.creditos_disponibles), 0) AS disponibles
			  FROM pase_servicio p
			 WHERE p.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND p.estado = 'ACTIVO'
			   AND p.vigencia_desde <= :hoy
			   AND p.vigencia_hasta >= :hoy
			 GROUP BY p.tipo
			""", nativeQuery = true)
	List<Object[]> vigentesPorTipo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("hoy") LocalDate hoy);

	/**
	 * Movimientos del ledger de creditos en el periodo, por producto y tipo de movimiento
	 * (RF-M29-009, RN-M29-003/004).
	 *
	 * <p>La sede es la <b>de la venta</b> ({@code pase_servicio.consultorio_id}): {@code
	 * movimiento_pase} no lleva sede, y un consumo hecho en otra sede de la organizacion se imputa
	 * al pase, que se vendio aca. El indicador lo declara.
	 *
	 * <p>{@code cantidad} tiene signo (V64): la suma de un tipo es el neto de ese tipo. El nombre
	 * del producto es el <b>congelado en la venta</b> ({@code snapshot_nombre}): un producto
	 * renombrado sigue apareciendo con el nombre con que se vendio (§8.4 de M23).
	 *
	 * <p>Columnas: producto, tipo de pase, tipo de movimiento, movimientos, creditos.
	 */
	@Query(value = """
			SELECT p.snapshot_nombre,
			       p.tipo,
			       m.tipo,
			       COUNT(*) AS movimientos,
			       SUM(m.cantidad) AS creditos
			  FROM movimiento_pase m
			  JOIN pase_servicio p
			    ON p.id = m.pase_id
			   AND p.organization_id = m.organization_id
			 WHERE m.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND m.ocurrido_en >= :desde
			   AND m.ocurrido_en < :hasta
			 GROUP BY p.snapshot_nombre, p.tipo, m.tipo
			 ORDER BY p.snapshot_nombre, p.tipo, m.tipo
			""", nativeQuery = true)
	List<Object[]> movimientosPorProductoYTipo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);

	/**
	 * Cuantos pases de la sede tienen un saldo que <b>no</b> coincide con la suma de su ledger
	 * (RNF-M29-001; RF-M23-010: "saldos deben reconciliar con movimientos").
	 *
	 * <p>El invariante es {@code creditos_disponibles == SUM(cantidad)}: la {@code COMPRA} aporta
	 * los iniciales (ver {@code MovimientoPase}). Tiene que dar cero siempre; si no, el reporte lo
	 * dice en voz alta en vez de mostrar un total que no cierra.
	 */
	@Query(value = """
			SELECT COUNT(*)
			  FROM pase_servicio p
			 WHERE p.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND p.creditos_disponibles <> (
			           SELECT COALESCE(SUM(m.cantidad), 0)
			             FROM movimiento_pase m
			            WHERE m.organization_id = p.organization_id
			              AND m.pase_id = p.id)
			""", nativeQuery = true)
	long pasesConSaldoDivergente(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);

	// =================================================================================
	// Continuidad del circuito (RF-M23-008) e ingresos por servicio (RF-M23-009), 09.01b
	// =================================================================================

	/**
	 * Las clases de la sede a las que esas personas <b>asistieron</b> ({@code ASISTIO}: presente o
	 * presente tarde, con la correccion ya aplicada) y que empezaron despues de {@code desde}.
	 *
	 * <p>La asistencia es lo que prueba que la persona recibio el servicio. Una inscripcion sin
	 * asistencia o la compra de un pase no lo prueban: "pasar a un servicio" se mide por lo
	 * prestado, igual que la sesion cuenta cuando se cierra y no cuando se reserva el turno.
	 *
	 * <p>Llega por {@code ix_inscripcion_persona} (V60). Columnas: persona_id, oferta_id, inicio en
	 * microsegundos UTC.
	 */
	@Query(value = """
			SELECT i.persona_id,
			       c.oferta_id,
			       TIMESTAMPDIFF(MICROSECOND, '1970-01-01', c.inicio) AS inicio_micros
			  FROM inscripcion_clase i
			  JOIN clase_programada c
			    ON c.id = i.clase_id
			   AND c.organization_id = i.organization_id
			 WHERE i.organization_id = :organizationId
			   AND i.persona_id IN (:personas)
			   AND i.consultorio_id = :consultorioId
			   AND i.estado = 'ASISTIO'
			   AND c.inicio > :desde
			""", nativeQuery = true)
	List<Object[]> asistenciasDespuesDe(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("personas") Collection<Long> personas,
			@Param("desde") Instant desde);

	/**
	 * Los movimientos de credito del periodo por oferta del pase, tipo de producto y tipo de
	 * movimiento. Sede de la venta, como el reporte de pases.
	 *
	 * <p>Columnas: snapshot_oferta_id, tipo de producto, tipo de movimiento, creditos (con signo).
	 */
	@Query(value = """
			SELECT p.snapshot_oferta_id,
			       p.tipo,
			       m.tipo,
			       SUM(m.cantidad) AS creditos
			  FROM movimiento_pase m
			  JOIN pase_servicio p
			    ON p.id = m.pase_id
			   AND p.organization_id = m.organization_id
			 WHERE m.organization_id = :organizationId
			   AND p.consultorio_id = :consultorioId
			   AND m.ocurrido_en >= :desde
			   AND m.ocurrido_en < :hasta
			 GROUP BY p.snapshot_oferta_id, p.tipo, m.tipo
			""", nativeQuery = true)
	List<Object[]> creditosPorOfertaYTipo(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("desde") Instant desde,
			@Param("hasta") Instant hasta);
}
