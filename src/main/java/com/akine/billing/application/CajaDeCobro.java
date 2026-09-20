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
	 * Un movimiento de ingreso por cada medio del cobro.
	 *
	 * @throws CajaNoAbiertaException el cobro incluye efectivo y la sede no tiene caja abierta (409)
	 */
	public void registrarIngresos(
			long organizationId, ConsultorioSnapshot sede, long cobroId, String moneda,
			List<CobroMedio> medios, Instant cuando, long actorCuentaId) {

		Map<MedioDePago, BigDecimal> porMedio = agrupar(medios);

		JornadaCaja jornada = jornadas.findAbierta(organizationId, sede.id()).orElse(null);
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
