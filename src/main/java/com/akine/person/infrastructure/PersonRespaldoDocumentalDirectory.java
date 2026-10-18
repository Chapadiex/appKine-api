package com.akine.person.infrastructure;

import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import com.akine.person.spi.RespaldoDocumentalDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Adaptador de {@link RespaldoDocumentalDirectory} sobre los puertos de M17 (AKINE-07.04b).
 *
 * <p>La orden se evalua con la misma regla que {@code ElegibilidadAdministrativaService}
 * —{@code OrdenMedica#vigenteEl} y {@code sirveParaCobertura}—: un mismo requisito no puede
 * cumplirse distinto en la recepcion y en la presentacion. La autorizacion se aparta a proposito en
 * una cosa, el saldo: ver el javadoc del puerto.
 *
 * <p>Las tres lecturas van por {@code organization_id} y {@code persona_id}: una cobertura o una
 * orden de otro tenant o de otra persona no respalda nada.
 */
@Component
public class PersonRespaldoDocumentalDirectory implements RespaldoDocumentalDirectory {

	private final OrdenMedicaRepositoryPort ordenes;
	private final AutorizacionRepositoryPort autorizaciones;
	private final CoberturaPacienteRepositoryPort coberturas;

	public PersonRespaldoDocumentalDirectory(
			OrdenMedicaRepositoryPort ordenes,
			AutorizacionRepositoryPort autorizaciones,
			CoberturaPacienteRepositoryPort coberturas) {
		this.ordenes = ordenes;
		this.autorizaciones = autorizaciones;
		this.coberturas = coberturas;
	}

	@Override
	@Transactional(readOnly = true)
	public RespaldoDocumental respaldo(
			long organizationId, long personaId, long coberturaId, Long practicaId,
			LocalDate fecha) {

		boolean numeroAfiliado = coberturas
				.findByIdAndOrganizationIdAndPersonaId(coberturaId, organizationId, personaId)
				.map(cobertura -> cobertura.getNumeroAfiliado() != null
						&& !cobertura.getNumeroAfiliado().isBlank())
				.orElse(false);

		boolean orden = ordenes.activasDe(organizationId, personaId).stream()
				.anyMatch(o -> o.vigenteEl(fecha) && o.sirveParaCobertura(coberturaId));

		boolean autorizacion = autorizaciones.aprobadasDePersona(organizationId, personaId).stream()
				.anyMatch(a -> ampara(a, coberturaId, practicaId, fecha));

		return new RespaldoDocumental(orden, autorizacion, numeroAfiliado);
	}

	/** Activa, APROBADA, de esa cobertura y practica, y vigente ese dia. Sin mirar el saldo. */
	private static boolean ampara(
			Autorizacion autorizacion, long coberturaId, Long practicaId, LocalDate fecha) {
		return autorizacion.isActive()
				&& autorizacion.getEstado().habilita()
				&& Objects.equals(autorizacion.getCoberturaId(), coberturaId)
				&& (practicaId == null || Objects.equals(autorizacion.getPracticaId(), practicaId))
				&& autorizacion.vigencia().cubre(fecha);
	}
}
