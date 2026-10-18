package com.akine.billing.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * La deuda de una clase asistida en una oferta {@code POR_CLASE} (U-39, RF-M18-008, RF-M19-009).
 * La consume {@code activity}, que registra la asistencia: misma direccion y misma razon que
 * {@link DevengoDeVentas} —la asistencia y su deuda van en la misma transaccion, cosa que un
 * observador posterior al commit no puede dar (CA-M18-008-04)—.
 *
 * <h2>Devengar no es cobrar (DP-06)</h2>
 *
 * <p>La implementacion crea <b>a lo sumo una obligacion viva por asistencia</b> y nada mas: ni
 * cobro ni caja. Se paga con {@code POST /cobros} como cualquier deuda (RF-M19-009).
 *
 * <h2>Anular no es borrar, y con cobros no se anula</h2>
 *
 * <p>Las mismas reglas que el resto de {@code billing} ({@code Obligacion#anular},
 * {@link DevengoDeVentas#anularDeudaDeVenta}): la anulacion deja la fila {@code ANULADA} con
 * motivo, y una deuda con cobros imputados <b>no se anula</b> —lo que corresponde es reintegrar el
 * cobro (RF-M19-010), que es un acto de caja con su propio registro—. A diferencia de la venta, el
 * llamador <b>no</b> rechaza: la asistencia es un hecho y su correccion no depende de la plata
 * (CA-M19-009-06, RF-M19-009 "no cambiar asistencia al revertir/cancelar cobro").
 */
public interface DevengoDeClases {

	/**
	 * Devenga la deuda de una asistencia, <b>en la transaccion del llamador</b>.
	 *
	 * <p>Idempotente por asistencia: si ya tiene una deuda viva, la devuelve sin escribir
	 * ({@link DeudaDeClase.Efecto#YA_DEVENGADA}).
	 *
	 * @param buscarPrevia {@code false} cuando la asistencia se inserto en esta misma transaccion:
	 *                     ninguna deuda puede apuntarle y se ahorra la consulta (el unique de V107
	 *                     sigue siendo el cinturon)
	 */
	DeudaDeClase devengar(ClaseAsistida asistida, boolean buscarPrevia);

	/**
	 * Anula la deuda viva de esa asistencia, <b>en la transaccion del llamador</b>, con
	 * {@code FOR UPDATE}. Sin deuda viva: {@link DeudaDeClase.Efecto#SIN_DEUDA}. Con cobros: no
	 * escribe y devuelve {@link DeudaDeClase.Efecto#CON_COBROS}.
	 */
	DeudaDeClase anular(long organizationId, long asistenciaId, long actorCuentaId, String motivo, Instant ahora);

	/**
	 * Anula las deudas vivas de las asistencias de una clase que se cancela (RF-M28-006 paso 7,
	 * RN-M28-009). Una por una con la misma regla de {@link #anular}: las que tienen cobros quedan
	 * vivas y se informan. Idempotente: las ya anuladas no aparecen.
	 */
	List<DeudaDeClase> anularPorCancelacion(
			long organizationId, long claseId, long actorCuentaId, String motivo, Instant ahora);

	/**
	 * Una asistencia que devenga, con el precio ya resuelto por el llamador: el vigente de la
	 * oferta el dia de la clase en la sede, que aca se congela.
	 */
	record ClaseAsistida(
			long organizationId,
			long consultorioId,
			long asistenciaId,
			long claseId,
			long personaId,
			long ofertaId,
			String nombre,
			BigDecimal importe,
			String moneda,
			long actorCuentaId,
			Instant ocurridaEn) {
	}

	/** Lo que paso con la deuda de una asistencia. Los ids e importes son null sin deuda. */
	record DeudaDeClase(Efecto efecto, Long obligacionId, BigDecimal importe, BigDecimal saldo,
						String moneda, String estado) {

		public enum Efecto {
			/** Se devengo ahora. */
			DEVENGADA,
			/** Ya tenia su deuda viva: no se escribio nada. */
			YA_DEVENGADA,
			/** Se anulo ahora. */
			ANULADA,
			/** Tiene cobros imputados: no se escribio nada y la deuda sigue viva. */
			CON_COBROS,
			/** No hay deuda viva que anular. */
			SIN_DEUDA
		}

		public static DeudaDeClase sinDeuda() {
			return new DeudaDeClase(Efecto.SIN_DEUDA, null, null, null, null, null);
		}
	}
}
