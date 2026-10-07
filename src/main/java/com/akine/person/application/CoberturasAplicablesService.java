package com.akine.person.application;

import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.TipoCobertura;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.spi.CoberturaAplicable;
import com.akine.person.spi.CoberturaNoAplicable;
import com.akine.person.spi.ReferenciaCongelada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Que coberturas de una persona aplican a una practica en una fecha.
 *
 * <p>Solo lectura: sin escrituras ni auditoria. El numero de afiliado no sale de aca. Una persona
 * inexistente o de otra organizacion no tiene coberturas en esta organizacion y da listas vacias.
 * La vigencia de la credencial es solo una alerta y no excluye.
 *
 * <p>B-3: con {@code ofertaId}, el arancel especifico de la oferta (RF-M16-008) manda sobre el
 * general de la practica. Sin oferta, solo el general.
 */
@Service
public class CoberturasAplicablesService {

	private final CoberturaPacienteRepositoryPort coberturas;
	private final ArancelDirectory aranceles;

	public CoberturasAplicablesService(
			CoberturaPacienteRepositoryPort coberturas, ArancelDirectory aranceles) {
		this.coberturas = coberturas;
		this.aranceles = aranceles;
	}

	@Transactional(readOnly = true)
	public List<CoberturaAplicable> aplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			LocalDate fecha) {
		return aplicables(organizationId, consultorioId, personaId, practicaId, null, fecha);
	}

	@Transactional(readOnly = true)
	public List<CoberturaAplicable> aplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			Long ofertaId, LocalDate fecha) {

		return vigentes(organizationId, personaId, fecha).stream()
				.map(c -> {
					ResolucionDeArancel resolucion =
							resolver(c, organizationId, consultorioId, practicaId, ofertaId, fecha);
					return resolucion.estaResuelta() ? aplicable(c, resolucion, fecha) : null;
				})
				.filter(Objects::nonNull)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<CoberturaNoAplicable> noAplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			LocalDate fecha) {
		return noAplicables(organizationId, consultorioId, personaId, practicaId, null, fecha);
	}

	@Transactional(readOnly = true)
	public List<CoberturaNoAplicable> noAplicables(
			long organizationId, long consultorioId, long personaId, long practicaId,
			Long ofertaId, LocalDate fecha) {

		return vigentes(organizationId, personaId, fecha).stream()
				.map(c -> {
					ResolucionDeArancel resolucion =
							resolver(c, organizationId, consultorioId, practicaId, ofertaId, fecha);
					return resolucion.estaResuelta()
							? null
							: new CoberturaNoAplicable(
									c.getId(), c.isPrincipal(), referencia(c),
									resolucion.motivo());
				})
				.filter(Objects::nonNull)
				.toList();
	}

	/** Financiadas, activas y vigentes en la fecha; principal primero y el resto por id. */
	public List<CoberturaPaciente> vigentes(long organizationId, long personaId, LocalDate fecha) {
		return coberturas.activasDe(organizationId, personaId).stream()
				.filter(c -> c.getTipo() == TipoCobertura.FINANCIADA)
				.filter(c -> c.vigenteEl(fecha))
				.sorted(Comparator
						.comparing((CoberturaPaciente c) -> !c.isPrincipal())
						.thenComparing(CoberturaPaciente::getId))
				.toList();
	}

	public ResolucionDeArancel resolver(
			CoberturaPaciente c, long organizationId, long consultorioId, long practicaId,
			Long ofertaId, LocalDate fecha) {

		return aranceles.resolver(
				organizationId, consultorioId, c.getFinanciadorId(), c.getPlanId(), practicaId,
				ofertaId, fecha);
	}

	static CoberturaAplicable aplicable(
			CoberturaPaciente c, ResolucionDeArancel resolucion, LocalDate fecha) {
		return new CoberturaAplicable(
				c.getId(), c.isPrincipal(), referencia(c), resolucion,
				c.credencialVencidaEl(fecha), c.getCredencialVigenciaHasta());
	}

	static ReferenciaCongelada referencia(CoberturaPaciente c) {
		return new ReferenciaCongelada(
				c.getFinanciadorId(), c.getFinanciadorNombre(), c.getPlanId(), c.getPlanNombre());
	}
}
