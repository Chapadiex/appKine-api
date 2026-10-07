package com.akine.person.application;

import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PracticaDeOferta;
import com.akine.offering.spi.PracticasDeOfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.person.application.CoberturaParaOferta.Aplicable;
import com.akine.person.application.CoberturaParaOferta.Condicion;
import com.akine.person.application.CoberturaParaOferta.Motivo;
import com.akine.person.application.CoberturaParaOferta.NoAplicable;
import com.akine.person.application.CoberturaParaOferta.Practica;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.exception.OfertaNoAccesibleEnSedeException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolver la cobertura aplicable por Oferta de Servicio (B-3, RF-M08-006) y ofrecer la condicion
 * particular cuando no aplica (RF-M08-007).
 *
 * <h2>El algoritmo</h2>
 *
 * <pre>
 *   oferta de la sede del contexto (404 si no)
 *   practicas de la oferta (A-9), la principal primero y despues en orden de alta
 *   por cada cobertura FINANCIADA vigente de la persona (principal primero, B-2):
 *     la oferta no admite obra social   -&gt; no aplica, OFERTA_NO_ADMITE_OBRA_SOCIAL
 *     la oferta no declara practicas    -&gt; no aplica, OFERTA_SIN_PRACTICAS
 *     por cada practica: arancel con oferta (el especifico de la oferta manda, RF-M16-008)
 *     alguna resolvio                   -&gt; aplica, con la PRIMERA que resolvio
 *     ninguna                           -&gt; no aplica, con el motivo de la principal
 *   condicion sugerida: COBERTURA si alguna aplica, si no PARTICULAR con el precio del dia
 * </pre>
 *
 * <p><b>"No aplicar cobertura solo porque el paciente la posee"</b> (validacion de RF-M08-006) es
 * exactamente que una cobertura vigente pueda salir en {@code noAplicables}. Y RN-M08-005 —la
 * cobertura no implica que toda oferta sea facturable— es el primer corte, el de la oferta.
 *
 * <p><b>Mismo orden que el devengo (F-4) cuando la sesion cierra sin tratamientos o con la
 * principal entre los realizados</b>: la principal primero. Con otros tratamientos el devengo
 * usa los realizados; por eso viaja el detalle por practica, que explica una oferta con varias.
 *
 * <p>Solo lectura, sin auditoria: es una consulta preliminar, igual que la elegibilidad
 * administrativa. Se autoriza por pertenencia —como {@code GET .../coberturas}— y exige sede en el
 * contexto, porque el convenio es de la sede (RN-M16-001). El numero de afiliado no sale.
 */
@Service
public class CoberturaParaOfertaService {

	private final PersonaRepositoryPort personas;
	private final CoberturasAplicablesService coberturas;
	private final OfertaDirectory ofertas;
	private final PracticasDeOfertaDirectory practicasDeOferta;

	public CoberturaParaOfertaService(
			PersonaRepositoryPort personas,
			CoberturasAplicablesService coberturas,
			OfertaDirectory ofertas,
			PracticasDeOfertaDirectory practicasDeOferta) {

		this.personas = personas;
		this.coberturas = coberturas;
		this.ofertas = ofertas;
		this.practicasDeOferta = practicasDeOferta;
	}

	@Transactional(readOnly = true)
	public CoberturaParaOferta resolver(
			OperatingActor actor, long personaId, long ofertaId, LocalDate fecha) {

		long organizationId = AutorizacionDePadron.exigirContexto(
				actor, "Resolver la cobertura aplicable por oferta");
		if (actor.consultorioId() == null) {
			// Sin sede no hay convenio que resolver. Contestar "particular" seria afirmar algo falso.
			throw new AccessDeniedException(
					"La cobertura aplicable por oferta requiere un consultorio activo en el contexto: "
							+ "la oferta y el convenio son de la sede");
		}
		long consultorioId = actor.consultorioId();
		personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));
		ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNoAccesibleEnSedeException(ofertaId));

		LocalDate dia = fecha == null ? LocalDate.now() : fecha;
		Optional<PrecioDeOferta> precio =
				ofertas.precioVigenteEl(organizationId, consultorioId, ofertaId, dia);
		boolean admiteObraSocial = precio.map(PrecioDeOferta::admiteObraSocial).orElse(false);
		List<PracticaDeOferta> practicas = enOrden(
				practicasDeOferta.practicasHabilitadas(organizationId, consultorioId, ofertaId));

		List<Aplicable> aplicables = new ArrayList<>();
		List<NoAplicable> noAplicables = new ArrayList<>();
		for (CoberturaPaciente cobertura : coberturas.vigentes(organizationId, personaId, dia)) {
			if (!admiteObraSocial) {
				noAplicables.add(noAplicable(cobertura, Motivo.OFERTA_NO_ADMITE_OBRA_SOCIAL, List.of()));
				continue;
			}
			if (practicas.isEmpty()) {
				noAplicables.add(noAplicable(cobertura, Motivo.OFERTA_SIN_PRACTICAS, List.of()));
				continue;
			}
			List<Practica> detalle = practicas.stream()
					.map(p -> practica(p, coberturas.resolver(
							cobertura, organizationId, consultorioId, p.practicaId(), ofertaId, dia)))
					.toList();
			Optional<Practica> primera = detalle.stream().filter(p -> p.arancel() != null).findFirst();
			if (primera.isPresent()) {
				aplicables.add(new Aplicable(
						cobertura.getId(), cobertura.isPrincipal(),
						CoberturasAplicablesService.referencia(cobertura),
						primera.get().practicaId(), primera.get().arancel(),
						cobertura.credencialVencidaEl(dia), cobertura.getCredencialVigenciaHasta(),
						detalle));
			} else {
				noAplicables.add(noAplicable(cobertura, detalle.getFirst().motivo(), detalle));
			}
		}

		return new CoberturaParaOferta(
				personaId,
				ofertaId,
				dia,
				admiteObraSocial,
				aplicables.isEmpty() ? Condicion.PARTICULAR : Condicion.COBERTURA,
				precio.filter(PrecioDeOferta::estaTarifada).map(PrecioDeOferta::precioBase).orElse(null),
				precio.filter(PrecioDeOferta::estaTarifada).map(PrecioDeOferta::moneda).orElse(null),
				List.copyOf(aplicables),
				List.copyOf(noAplicables));
	}

	/** DP-11: la principal primero; el resto en el orden de alta que trae el {@code spi}. */
	static List<PracticaDeOferta> enOrden(List<PracticaDeOferta> declaradas) {
		return declaradas.stream()
				.sorted(Comparator.comparing((PracticaDeOferta p) -> !p.principal()))
				.toList();
	}

	private static Practica practica(PracticaDeOferta p, ResolucionDeArancel resolucion) {
		return resolucion.estaResuelta()
				? new Practica(p.practicaId(), p.principal(), resolucion.arancel(), null)
				: new Practica(p.practicaId(), p.principal(), null,
						Motivo.valueOf(resolucion.motivo().name()));
	}

	private static NoAplicable noAplicable(
			CoberturaPaciente cobertura, Motivo motivo, List<Practica> detalle) {
		return new NoAplicable(
				cobertura.getId(), cobertura.isPrincipal(),
				CoberturasAplicablesService.referencia(cobertura), motivo, detalle);
	}
}
