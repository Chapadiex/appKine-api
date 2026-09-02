package com.akine.billing.infrastructure;

import com.akine.billing.domain.EstadoObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.HitoDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import com.akine.person.spi.ResumenDePersonaContributor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que {@code billing} aporta al Paciente 360: la situacion economica (AKINE-03.02, RF-M07-004).
 *
 * <h2>Deuda, no cuenta corriente</h2>
 *
 * <p>Esto es un resumen: cuanto debe y cuantas deudas abiertas tiene. <b>No es la cuenta
 * corriente</b>, que ya existe en {@code /api/v1/consultorios/&#123;id&#125;/personas/&#123;id&#125;/obligaciones}
 * y trae cada obligacion con su snapshot y su saldo. Duplicar aca esa lectura convertiria a la
 * ficha del mostrador en una segunda pantalla de cobranza que se desincroniza el dia que una de
 * las dos cambie.
 *
 * <h2>{@code BigDecimal} y {@code add}, nunca {@code double}</h2>
 *
 * <p>Es la regla que dejo AKINE-07.01 y AGENT.md seccion 5. Un total que se muestra en pantalla es
 * el que el operador le dice al paciente: no hay tal cosa como plata "solo para mostrar".
 *
 * <h2>Alcance: la organizacion, no la sede</h2>
 *
 * <p>{@code findDeLaPersona} filtra por organizacion. Es deliberado y es la misma decision que
 * tomo el contribuyente de turnos: la deuda de un paciente es una sola aunque se haya generado en
 * dos sedes del mismo centro, y mostrar solo la de la sede en la que el operador esta parado
 * produciria el peor error posible de esta pantalla —decirle a alguien que no debe nada—.
 */
@Component
public class EconomiaEnElResumenDePersona implements ResumenDePersonaContributor {

	private static final String SECCION = "economia";

	/** Moneda de referencia cuando la persona no tiene ninguna obligacion todavia. */
	private static final String MONEDA_POR_DEFECTO = "ARS";

	private final ObligacionRepositoryPort obligaciones;

	public EconomiaEnElResumenDePersona(ObligacionRepositoryPort obligaciones) {
		this.obligaciones = obligaciones;
	}

	@Override
	public String seccion() {
		return SECCION;
	}

	/**
	 * {@code cobro:register}, que es el mismo permiso con el que {@code ObligacionService} deja
	 * leer la cuenta corriente. No se inventa un {@code obligacion:read} que la matriz no tiene:
	 * una etapa no amplia la matriz.
	 */
	@Override
	public String permisoRequerido() {
		return PermissionCodes.COBRO_REGISTER;
	}

	@Override
	public AporteDeResumen aportar(ConsultaDeResumen consulta) {
		List<Obligacion> deLaPersona =
				obligaciones.findDeLaPersona(consulta.organizationId(), consulta.personaId());

		BigDecimal deuda = BigDecimal.ZERO;
		long abiertas = 0;
		String moneda = MONEDA_POR_DEFECTO;
		List<HitoDeResumen> hitos = new ArrayList<>();

		for (Obligacion obligacion : deLaPersona) {
			if (obligacion.getEstado() != EstadoObligacion.ANULADA) {
				// La anulada no suma ni cuenta: su saldo ya no se debe. Sigue siendo un hito, que
				// es como el historico se mantiene consultable sin contaminar el total.
				deuda = deuda.add(obligacion.getSaldo());
				moneda = obligacion.getMoneda();
				if (obligacion.getSaldo().signum() > 0) {
					abiertas++;
				}
			}
			if (hitos.size() < consulta.limiteHitos()) {
				hitos.add(new HitoDeResumen(
						SECCION,
						"OBLIGACION",
						obligacion.getDevengadaEn(),
						obligacion.getSnapshotNombre(),
						obligacion.getEstado().name(),
						obligacion.getId()));
			}
		}

		return new AporteDeResumen(
				SECCION,
				List.of(
						IndicadorDeResumen.dinero("deuda-total", "Deuda", deuda, moneda),
						IndicadorDeResumen.contando(
								"obligaciones-abiertas", "Deudas abiertas", abiertas)),
				hitos);
	}
}
