package com.akine.billing.application;

import com.akine.billing.domain.CobroMedio;
import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asienta en la caja el dinero que un cobro hizo entrar, <b>en la misma transaccion del cobro</b>.
 *
 * <h2>Por que es automatico y no lo carga una persona</h2>
 *
 * <p>La alternativa —que alguien registre el movimiento despues— tiene una falla que no se puede
 * cerrar: nada obliga a que lo haga, nada verifica que el importe coincida, y la caja y los cobros
 * divergen <b>sin que falle nada</b>. El unico mecanismo que los reconciliaria es un reporte que
 * nadie corre.
 *
 * <p>Es el mismo razonamiento con el que 07.01 devenga la deuda dentro de la transaccion del cierre
 * clinico, con la misma contrapartida asumida: <b>si el asiento falla, el cobro no se confirma.</b>
 * Aca el costo es menor, porque no hay borde de modulo que cruzar — {@code Cobro} y
 * {@code MovimientoCaja} son del mismo modulo y de la misma transaccion.
 *
 * <h2>Cobro y movimiento no son uno a uno</h2>
 *
 * <p>Un cobro con efectivo y tarjeta produce <b>dos</b> movimientos, y solo el primero afecta el
 * arqueo. Los medios repetidos se agregan en uno solo: al cajon no le importa que el operador haya
 * cargado dos lineas de efectivo, y una fila por linea haria que el unique que garantiza la
 * idempotencia del reintento tuviera que llevar un numero de orden que no significa nada.
 *
 * <h2>El efectivo exige caja abierta; el resto no</h2>
 *
 * <p>Si entra efectivo y la sede no tiene jornada abierta, <b>el cobro se rechaza</b>: la plata
 * entra al cajon igual, y si el sistema no sabe a que jornada pertenece, el arqueo de ese dia no
 * cuadra contra nada. Con tarjeta o transferencia no hay nada que exigir, porque esa plata nunca
 * toco el cajon; su movimiento se asienta igual —para que la operatoria del dia este completa— y no
 * participa de ningun arqueo.
 */
@Component
public class CajaDeCobro {

	private final JornadaCajaRepositoryPort jornadas;
	private final MovimientoCajaService movimientos;

	public CajaDeCobro(JornadaCajaRepositoryPort jornadas, MovimientoCajaService movimientos) {
		this.jornadas = jornadas;
		this.movimientos = movimientos;
	}

	/**
	 * La jornada abierta de la sede, leida <b>antes</b> de que el cobro tome el numerador.
	 *
	 * <p>Es una lectura consistente y no necesita el lock del comprobante: el cobro corre en
	 * {@code REPEATABLE READ} y su foto ya quedo fijada por la primera lectura de la transaccion
	 * —la de idempotencia—, asi que leerla antes o despues del {@code UPDATE} del numerador devuelve
	 * lo mismo. Leerla despues era una sentencia mas dentro de la seccion que serializa los cobros
	 * de la sede (docs/carga.md §10). Lo que decide si la jornada sigue abierta es la suma
	 * condicional de {@link MovimientoCajaService#asentar}, que no cambio de lugar.
	 *
	 * <p><b>No rechaza nada.</b> El efectivo sin jornada se sigue rechazando en
	 * {@link #registrarIngresos(Destino, long, String, List, Instant, long)}, en el mismo punto que
	 * antes: rechazarlo aca haria que un cobro con dos errores contestara el otro.
	 */
	public Destino destino(long organizationId, ConsultorioSnapshot sede) {
		return new Destino(organizationId, sede,
				jornadas.findAbierta(organizationId, sede.id()).orElse(null));
	}

	/** Lo que {@link #destino} leyo: la sede y su jornada abierta, o {@code null} si no hay. */
	public record Destino(long organizationId, ConsultorioSnapshot sede, JornadaCaja jornada) {
	}

	/**
	 * Un movimiento de ingreso por cada medio del cobro.
	 *
	 * @throws CajaNoAbiertaException el cobro incluye efectivo y la sede no tiene caja abierta (409)
	 */
	public void registrarIngresos(
			long organizationId, ConsultorioSnapshot sede, long cobroId, String moneda,
			List<CobroMedio> medios, Instant cuando, long actorCuentaId) {

		registrarIngresos(destino(organizationId, sede), cobroId, moneda, medios, cuando, actorCuentaId);
	}

	/**
	 * Lo mismo, con la jornada que {@link #destino} ya leyo.
	 *
	 * @throws CajaNoAbiertaException el cobro incluye efectivo y la sede no tiene caja abierta (409)
	 */
	public void registrarIngresos(
			Destino destino, long cobroId, String moneda,
			List<CobroMedio> medios, Instant cuando, long actorCuentaId) {

		long organizationId = destino.organizationId();
		ConsultorioSnapshot sede = destino.sede();
		JornadaCaja jornada = destino.jornada();
		Map<MedioDePago, BigDecimal> porMedio = agrupar(medios);

		if (jornada == null && porMedio.containsKey(MedioDePago.EFECTIVO)) {
			throw new CajaNoAbiertaException(sede.id());
		}

		// La fecha del hecho es la de la jornada cuando hay una: un turno de caja abierto anoche y
		// cerrado hoy a la madrugada es UN arqueo, y partirlo por medianoche haria que la suma del
		// ledger de la jornada no diera su propio saldo. Sin jornada, la del dia de la sede.
		LocalDate fechaNegocio = jornada != null
				? jornada.getFechaNegocio()
				: CajaAcceso.fechaDeNegocio(sede, cuando);

		porMedio.forEach((medio, importe) -> movimientos.asentar(
				organizationId, sede.id(), jornada, fechaNegocio,
				TipoMovimiento.INGRESO, medio, importe, moneda,
				"Cobro " + cobroId, null,
				OrigenMovimiento.COBRO, cobroId, null,
				cuando, actorCuentaId, null, null));
	}

	/**
	 * La salida de plata de un reintegro de saldo a favor (F-3).
	 *
	 * <p>Las mismas reglas que el ingreso, del otro lado: solo el efectivo mueve el arqueo, el
	 * efectivo exige jornada abierta, y <b>el cajon no queda en negativo</b> —un reintegro que no
	 * entra en el cajon se rechaza—. Todo por {@link MovimientoCajaService#asentar}, para que no haya
	 * un segundo camino por el que salga plata (ver {@code CajaDeEgreso}).
	 *
	 * @throws CajaNoAbiertaException se reintegra en efectivo y la sede no tiene caja abierta (409)
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public void registrarReintegro(
			long organizationId, ConsultorioSnapshot sede, long reintegroId, long cobroId,
			MedioDePago medio, BigDecimal importe, String moneda, Instant cuando, long actorCuentaId) {

		JornadaCaja jornada = jornadas.findAbierta(organizationId, sede.id()).orElse(null);
		LocalDate fechaNegocio = jornada != null
				? jornada.getFechaNegocio()
				: CajaAcceso.fechaDeNegocio(sede, cuando);

		movimientos.asentar(
				organizationId, sede.id(), jornada, fechaNegocio,
				TipoMovimiento.EGRESO, medio, importe, moneda,
				"Reintegro del cobro " + cobroId, null,
				OrigenMovimiento.REINTEGRO, reintegroId, null,
				cuando, actorCuentaId, null, null);
	}

	/**
	 * Un medio repetido es un solo movimiento. Ver el javadoc de la clase.
	 *
	 * <p>{@link LinkedHashMap} y no {@code HashMap}: el orden de asiento queda igual al orden en que
	 * el operador cargo los medios, y un ledger cuyo orden depende del hash de un enum es
	 * gratuitamente dificil de leer en una investigacion.
	 */
	private static Map<MedioDePago, BigDecimal> agrupar(List<CobroMedio> medios) {
		Map<MedioDePago, BigDecimal> porMedio = new LinkedHashMap<>();
		for (CobroMedio medio : medios) {
			porMedio.merge(medio.getMedio(), medio.getImporte(), BigDecimal::add);
		}
		return porMedio;
	}
}
