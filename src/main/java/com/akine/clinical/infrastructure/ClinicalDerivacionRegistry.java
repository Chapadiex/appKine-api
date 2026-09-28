package com.akine.clinical.infrastructure;

import com.akine.clinical.application.DerivacionClinicaService;
import com.akine.clinical.spi.ActorDeDerivacion;
import com.akine.clinical.spi.DerivacionClinicaRegistry;
import com.akine.clinical.spi.DerivacionSnapshot;
import com.akine.clinical.spi.EstadoClinicoDeParticipacion;
import com.akine.clinical.spi.OrigenDeParticipacion;
import com.akine.clinical.spi.RegistroDeDerivacion;
import com.akine.clinical.spi.ReversionDeDerivacion;
import org.springframework.stereotype.Component;

/**
 * El adaptador que expone la derivacion clinica al modulo duenio de la participacion.
 *
 * <p>Delega y no decide, igual que {@code ClinicalHistoriaClinicaDirectory} y
 * {@code ClinicalCasoDirectory}. La autorizacion, el acceso justificado y la auditoria viven en
 * {@link DerivacionClinicaService}, donde vive la politica de DP-03; ponerlos aca los sacaria del
 * unico lugar donde se pueden mantener coherentes.
 *
 * <p><b>Sin {@code @Transactional}</b>: lo son los metodos a los que delega. Envolverlos en otra
 * transaccion solo alargaria la del llamador sin ganar una invariante.
 */
@Component
public class ClinicalDerivacionRegistry implements DerivacionClinicaRegistry {

	private final DerivacionClinicaService derivaciones;

	public ClinicalDerivacionRegistry(DerivacionClinicaService derivaciones) {
		this.derivaciones = derivaciones;
	}

	@Override
	public EstadoClinicoDeParticipacion consultar(
			ActorDeDerivacion actor, OrigenDeParticipacion origen, long participacionId,
			long personaId) {
		return derivaciones.consultar(actor, origen, participacionId, personaId);
	}

	@Override
	public DerivacionSnapshot registrar(RegistroDeDerivacion registro) {
		return derivaciones.registrar(registro);
	}

	@Override
	public DerivacionSnapshot revertir(ReversionDeDerivacion reversion) {
		return derivaciones.revertir(reversion);
	}
}
