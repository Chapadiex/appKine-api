package com.akine.billing.domain.port;

import com.akine.billing.domain.Egreso;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia del compromiso.
 *
 * <p><b>No declara {@code delete}, y esa ausencia es el diseno</b> (RN-M22-002: anular no significa
 * borrar). Un egreso anulado conserva su fila con motivo, actor y fecha.
 *
 * <p>Las dos escrituras del saldo son {@code UPDATE} <b>condicionales</b>, no metodos de la
 * entidad, por la misma razon que en 07.02, 04.05 y 07.03: la condicion del {@code WHERE} es lo que
 * decide la correctitud y evaluarla en Java obliga a leer antes de escribir, que es donde se cuela
 * la ventana entre dos operadores concurrentes.
 */
public interface EgresoRepositoryPort {

	Egreso save(Egreso egreso);

	/**
	 * Empuja la edicion pendiente para que {@code @Version} avance <b>antes</b> de armar la
	 * respuesta: sin esto la correccion del borrador devolveria la version leida y la siguiente
	 * correccion de la misma pantalla moriria en un 409 que no culpa a nadie.
	 */
	void flush();

	Optional<Egreso> findByIdInScope(long organizationId, long consultorioId, long egresoId);

	/**
	 * El egreso, <b>con lock de fila</b> ({@code SELECT ... FOR UPDATE}), para la anulacion.
	 *
	 * <p>La anulacion decide mirando el saldo —no se anula un compromiso con pagos vigentes— y
	 * despues lo escribe por JPA. Leido sin lock, un pago que commiteaba entre la lectura y el flush
	 * no se veia: el egreso quedaba {@code ANULADO} con un pago {@code CONFIRMADO} y la plata fuera
	 * del cajon sin nada que lo marcara (E2E de web #94, 9 de 12 intentos). Con el lock las dos
	 * operaciones se serializan: si el pago llego antes, la anulacion lo ve y responde
	 * {@code egreso-con-pagos}; si llego despues, su {@code UPDATE} ve {@code ANULADO} y responde
	 * {@code egreso-no-pagable}.
	 *
	 * <p>Sin deadlock posible con el pago: el pago toma la fila del egreso con su primer
	 * {@code UPDATE}, y la anulacion no tiene ningun otro lock tomado antes que este.
	 */
	Optional<Egreso> findByIdInScopeParaEscribir(long organizationId, long consultorioId, long egresoId);

	Optional<Egreso> findByIdempotencyKey(long organizationId, String idempotencyKey);

	/**
	 * Un egreso vigente con ese comprobante del mismo beneficiario, si lo hay.
	 *
	 * <p>El caso borde "factura externa duplicada". Lo respalda el unique; esto existe para poder
	 * responder un 409 legible <b>antes</b> de que reviente la constraint — que ademas dejaria la
	 * transaccion marcada para rollback.
	 */
	Optional<Egreso> findVigentePorComprobante(
			long organizationId, String beneficiarioClave,
			String comprobanteTipo, String comprobanteNumero);

	/**
	 * La bandeja, filtrada (RF-M22-004): fecha, categoria, beneficiario y estado.
	 *
	 * @param estado             {@code null} no filtra
	 * @param categoria          {@code null} no filtra
	 * @param beneficiarioClave  {@code null} no filtra
	 * @param desde / hasta      recorte por {@code registrado_en} en el rango semiabierto
	 *                           {@code [desde, hasta)}: el dia de la sede ya convertido a
	 *                           instantes ({@code CajaAcceso.inicioDelDia}); {@code null} no filtra
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	List<Egreso> buscar(
			long organizationId, long consultorioId, String estado, String categoria,
			String beneficiarioClave, Instant desde, Instant hasta,
			int limite, int desplazamiento);

	/**
	 * Totales de los egresos registrados en {@code [desde, hasta)}, por moneda, categoria y estado
	 * (DP-45). Mismo recorte por fecha que {@link #buscar}.
	 *
	 * @return filas {@code [moneda, categoria, estado, cantidad, importe, saldo]}
	 */
	List<Object[]> resumirPorCategoriaYEstado(
			long organizationId, long consultorioId, Instant desde, Instant hasta);

	/**
	 * Descuenta del saldo pendiente, si el egreso lo admite <b>y alcanza</b>.
	 *
	 * <p>{@code ... WHERE estado = 'CONFIRMADO' AND saldo_pendiente >= :importe}. Un egreso no se
	 * paga dos veces y no se puede pagar de mas.
	 *
	 * @return filas afectadas. <b>Cero tiene dos causas</b> —el estado ya no lo admite, o no alcanza
	 *         el saldo— y el llamador las distingue releyendo. Es seguro: un {@code UPDATE} de cero
	 *         filas no marca la transaccion para rollback, a diferencia de un {@code flush} fallido
	 *         por constraint
	 *
	 * <p><b>Avanza {@code version}.</b> Antes no lo hacia, y una escritura por JPA que habia leido el
	 * egreso antes del pago pasaba su {@code WHERE version = N} intacta y pisaba el saldo. Es la regla
	 * que {@code CobroRepositoryPort.descontarSaldo} ya aplica a la obligacion.
	 */
	int descontarSaldo(long organizationId, long egresoId, BigDecimal importe);

	/**
	 * Devuelve al saldo lo que un pago anulado habia descontado.
	 *
	 * <p>Sin condicion de importe: devolver nunca puede pasarse: el {@code CHECK}
	 * {@code saldo_pendiente <= importe_total} lo respalda por si un camino futuro se equivoca.
	 *
	 * <p>Avanza {@code version}, por lo mismo que {@link #descontarSaldo}.
	 *
	 * @return filas afectadas
	 */
	int devolverSaldo(long organizationId, long egresoId, BigDecimal importe);

	/**
	 * Deriva el estado del saldo, en una sentencia.
	 *
	 * <p>Con SQL y no leyendo la entidad para cambiarle el estado en Java: <b>releerla despues del
	 * UPDATE de arriba traeria la version que la sesion de JPA tiene cacheada</b>, que ya no refleja
	 * el saldo real. Es una trampa que este repositorio ya pago antes.
	 */
	void actualizarEstadoPorSaldo(long organizationId, long egresoId);
}
