package com.akine.scheduling.application;

import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.scheduling.domain.ModalidadRecepcion;
import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe;
import com.akine.scheduling.spi.PrepagoDeTurnoProbe.PrepagoDeTurno;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calcula el estado del prepago de la recepcion de un turno (AKINE E-6, DP-06 / ADR-0013).
 *
 * <pre>
 *   hay anticipo vigente del turno                 -&gt; REGISTRADO (aunque la oferta no lo exija)
 *   la oferta no exige prepago                     -&gt; NO_EXIGIDO
 *   la recepcion esta anulada o cerrada            -&gt; NO_EXIGIDO
 *   la recepcion se resolvio con COBERTURA         -&gt; NO_EXIGIDO
 *   en cualquier otro caso                         -&gt; PENDIENTE, con el precio particular sugerido
 * </pre>
 *
 * <p><b>Con cobertura no se exige.</b> El flujo historico que ADR-0013 reconoce es cobrarle al
 * <b>particular</b> antes de pasarlo a la sala; con cobertura paga el financiador meses despues y
 * lo del paciente es el coseguro, que depende del arancel del convenio y que la recepcion no
 * cotiza. Registrar un anticipo de coseguro igual se puede —se imputa al coseguro que devengue el
 * cierre—; lo que no hay es alerta. Decision a revisar en el diseno.
 *
 * <p><b>Nada de esto bloquea.</b> El resultado se muestra; ninguna transicion lo consulta para
 * decidir si procede.
 */
@Component
public class PrepagoDeRecepcion {

	private final OfertaDirectory ofertas;
	private final PrepagoDeTurnoProbe prepagos;

	public PrepagoDeRecepcion(OfertaDirectory ofertas, PrepagoDeTurnoProbe prepagos) {
		this.ofertas = ofertas;
		this.prepagos = prepagos;
	}

	/** El prepago de la recepcion de un turno. */
	public PrepagoView de(long organizationId, long consultorioId, Turno turno, Recepcion recepcion) {
		Map<Long, PrepagoDeTurno> registrados =
				prepagos.prepagosDe(organizationId, List.of(turno.getId()));
		return calcular(
				ofertas.precioDe(organizationId, consultorioId, turno.getOfertaId()),
				recepcion, registrados.get(turno.getId()));
	}

	/**
	 * El prepago de cada recepcion de un lote de turnos, por id de turno. Una consulta a billing
	 * para todo el lote y una por oferta distinta, no una por turno.
	 */
	public Map<Long, PrepagoView> de(
			long organizationId, long consultorioId, List<Turno> turnos,
			Map<Long, Recepcion> recepcionDe) {

		List<Turno> conRecepcion = turnos.stream()
				.filter(turno -> recepcionDe.containsKey(turno.getId()))
				.toList();
		if (conRecepcion.isEmpty()) {
			return Map.of();
		}
		Map<Long, PrepagoDeTurno> registrados = prepagos.prepagosDe(
				organizationId, conRecepcion.stream().map(Turno::getId).toList());
		Map<Long, Optional<PrecioDeOferta>> precios = new HashMap<>();
		Map<Long, PrepagoView> resultado = new HashMap<>();
		for (Turno turno : conRecepcion) {
			Optional<PrecioDeOferta> precio = precios.computeIfAbsent(turno.getOfertaId(),
					ofertaId -> ofertas.precioDe(organizationId, consultorioId, ofertaId));
			resultado.put(turno.getId(),
					calcular(precio, recepcionDe.get(turno.getId()), registrados.get(turno.getId())));
		}
		return resultado;
	}

	static PrepagoView calcular(
			Optional<PrecioDeOferta> precio, Recepcion recepcion, PrepagoDeTurno registrado) {

		if (registrado != null) {
			return new PrepagoView(PrepagoView.REGISTRADO, null, registrado.moneda(),
					registrado.cobroId(), registrado.importe(), registrado.saldoAFavor());
		}
		boolean exige = precio.map(PrecioDeOferta::exigePrepago).orElse(false);
		if (!exige || !recepcion.estaAbierta()
				|| recepcion.getModalidad() == ModalidadRecepcion.COBERTURA) {
			return new PrepagoView(PrepagoView.NO_EXIGIDO, null, null, null, null, null);
		}
		return new PrepagoView(PrepagoView.PENDIENTE,
				precio.get().estaTarifada() ? precio.get().precioBase() : null,
				precio.get().moneda(), null, null, null);
	}
}
