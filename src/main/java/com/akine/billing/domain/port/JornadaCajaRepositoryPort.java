package com.akine.billing.domain.port;

import com.akine.billing.domain.JornadaCaja;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de jornadas de caja, con las tres escrituras condicionales que sostienen la etapa.
 *
 * <p><b>Ninguna de las tres toma un lock.</b> Las tres expresan su regla como una condicion del
 * {@code WHERE}, y cero filas afectadas es la respuesta —no un error tecnico—. Es el patron que
 * 07.02 uso para imputar un cobro y 04.05 para consumir una autorizacion, y la razon es siempre la
 * misma: una condicion no necesita leer antes de escribir, que es donde se cuela la ventana entre
 * dos operadores concurrentes, y <b>no puede deadlockear</b>.
 *
 * <p>Ninguna operacion borra ni reabre: una jornada se cierra y se queda cerrada (RN-M20-003).
 */
public interface JornadaCajaRepositoryPort {

	JornadaCaja save(JornadaCaja jornada);

	Optional<JornadaCaja> findByIdInScope(long organizationId, long consultorioId, long jornadaId);

	/** La jornada abierta de la sede, si la hay. A lo sumo una: lo garantiza un unique. */
	Optional<JornadaCaja> findAbierta(long organizationId, long consultorioId);

	/**
	 * Los cierres historicos de la sede, del mas reciente al mas viejo (RF-M20-007).
	 *
	 * @param estado {@code null} trae abiertas y cerradas
	 */
	List<JornadaCaja> findHistorico(
			long organizationId, long consultorioId, String estado,
			LocalDate desde, LocalDate hasta, int limite, int desplazamiento);

	/**
	 * Suma al saldo arqueable, si la jornada sigue abierta.
	 *
	 * @return filas afectadas. <b>Cero significa que la jornada se cerro</b> entre medio
	 */
	int sumarAlSaldo(long organizationId, long jornadaId, BigDecimal importe);

	/**
	 * Resta del saldo arqueable, si la jornada sigue abierta <b>y alcanza la plata</b>.
	 *
	 * <p>{@code ... WHERE estado = 'ABIERTA' AND saldo_arqueo >= :importe}. Un egreso no puede dejar
	 * la caja en negativo, y no por una regla de negocio sino porque un cajon no puede tener menos
	 * de cero pesos.
	 *
	 * @return filas afectadas. <b>Cero tiene dos causas</b> —la jornada cerro, o no alcanza— y el
	 *         llamador las distingue releyendo la jornada. Es seguro: un UPDATE de cero filas no
	 *         marca la transaccion para rollback, a diferencia de un flush fallido por constraint
	 */
	int restarDelSaldo(long organizationId, long jornadaId, BigDecimal importe);

	/**
	 * Cierra la jornada en una sola sentencia, congelando el teorico y calculando la diferencia.
	 *
	 * <p><b>Las dos condiciones del WHERE son el diseno del cierre:</b>
	 *
	 * <ul>
	 *   <li>{@code estado = 'ABIERTA'} resuelve el cierre concurrente — el segundo afecta cero
	 *       filas.</li>
	 *   <li>{@code saldo_arqueo = :saldoTeoricoEsperado} resuelve el cobro en efectivo que entra
	 *       <b>entre que el operador cuenta y confirma</b>. Sin ella el cierre registraria un
	 *       faltante que nunca existio y obligaria a justificar por escrito un desvio inventado.</li>
	 * </ul>
	 *
	 * <p>{@code diferencia} la calcula el motor como {@code declarado - saldo_arqueo}: nunca la
	 * manda el cliente.
	 *
	 * @return filas afectadas. Cero = ya cerrada o el saldo cambio; el llamador relee para saber cual
	 */
	int cerrar(
			long organizationId, long jornadaId, BigDecimal saldoTeoricoEsperado,
			BigDecimal saldoDeclarado, String motivoDiferencia,
			Instant cerradaEn, long cerradaPorCuentaId);
}
