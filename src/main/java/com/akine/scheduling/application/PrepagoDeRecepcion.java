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
	 * El prepago de CADA turno de un lote, por id de turno, haya llegado el paciente o no (E-6 para
	 * los que tienen recepcion, E-8 para los que todavia no). Una consulta a billing para todo el
	 * lote y una por oferta distinta, no una por turno.
	 *
	 * <p>Para un turno con recepcion vigente el resultado es exactamente el de
	 * {@link #calcular(Optional, Recepcion, PrepagoDeTurno)}: la agenda del dia y
	 * {@code Recepcion.prepago} nunca dicen cosas distintas del mismo turno.
	 */
	public Map<Long, PrepagoView> de(
			long organizationId, long consultorioId, List<Turno> turnos,
			Map<Long, Recepcion> recepcionDe) {

		if (turnos.isEmpty()) {
			return Map.of();
		}
		Map<Long, PrepagoDeTurno> registrados = prepagos.prepagosDe(
				organizationId, turnos.stream().map(Turno::getId).toList());
		Map<Long, Optional<PrecioDeOferta>> precios = new HashMap<>();
		Map<Long, PrepagoView> resultado = new HashMap<>();
		for (Turno turno : turnos) {
			Optional<PrecioDeOferta> precio = precios.computeIfAbsent(turno.getOfertaId(),
					ofertaId -> ofertas.precioDe(organizationId, consultorioId, ofertaId));
			Recepcion recepcion = recepcionDe.get(turno.getId());
			PrepagoDeTurno registrado = registrados.get(turno.getId());
			resultado.put(turno.getId(), recepcion != null
					? calcular(precio, recepcion, registrado)
					: antesDeLaLlegada(precio, turno, registrado));
		}
		return resultado;
	}

	static PrepagoView calcular(
			Optional<PrecioDeOferta> precio, Recepcion recepcion, PrepagoDeTurno registrado) {

		return resolver(precio, registrado,
				!recepcion.estaAbierta() || recepcion.getModalidad() == ModalidadRecepcion.COBERTURA);
	}

	/**
	 * El prepago de un turno SIN recepcion vigente (AKINE E-8): lo que la agenda del dia muestra
	 * antes del check-in, para que el mostrador sepa que tiene que cobrar antes de que la persona
	 * llegue a la ventanilla.
	 *
	 * <p>Antes de la llegada todavia no se sabe si se atiende con cobertura —eso lo decide la
	 * validacion—, asi que {@code PENDIENTE} se lee "si se atiende como particular". Un turno
	 * cancelado o ausente ya no espera a nadie: {@code NO_EXIGIDO}, salvo que tenga un anticipo
	 * vigente, que sigue {@code REGISTRADO} para que se vea que hay plata para reintegrar.
	 */
	static PrepagoView antesDeLaLlegada(
			Optional<PrecioDeOferta> precio, Turno turno, PrepagoDeTurno registrado) {

		return resolver(precio, registrado, turno.getEstado().esTerminal());
	}

	private static PrepagoView resolver(
			Optional<PrecioDeOferta> precio, PrepagoDeTurno registrado, boolean noSeExigeAca) {

		if (registrado != null) {
			return new PrepagoView(PrepagoView.REGISTRADO, null, registrado.moneda(),
					registrado.cobroId(), registrado.importe(), registrado.saldoAFavor());
		}
		boolean exige = precio.map(PrecioDeOferta::exigePrepago).orElse(false);
		if (!exige || noSeExigeAca) {
			return new PrepagoView(PrepagoView.NO_EXIGIDO, null, null, null, null, null);
		}
		return new PrepagoView(PrepagoView.PENDIENTE,
				precio.get().estaTarifada() ? precio.get().precioBase() : null,
				precio.get().moneda(), null, null, null);
	}
}
