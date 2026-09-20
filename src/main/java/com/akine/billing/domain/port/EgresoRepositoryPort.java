package com.akine.billing.domain.port;

import com.akine.billing.domain.Egreso;

import java.math.BigDecimal;
import java.time.LocalDate;
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

	Optional<Egreso> findByIdInScope(long organizationId, long consultorioId, long egresoId);

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
	 * @param desde / hasta      recorte por {@code registrado_en}; {@code null} no filtra
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	List<Egreso> buscar(
			long organizationId, long consultorioId, String estado, String categoria,
			String beneficiarioClave, LocalDate desde, LocalDate hasta,
			int limite, int desplazamiento);

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
	 */
	int descontarSaldo(long organizationId, long egresoId, BigDecimal importe);

	/**
	 * Devuelve al saldo lo que un pago anulado habia descontado.
	 *
	 * <p>Sin condicion de importe: devolver nunca puede pasarse: el {@code CHECK}
	 * {@code saldo_pendiente <= importe_total} lo respalda por si un camino futuro se equivoca.
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
