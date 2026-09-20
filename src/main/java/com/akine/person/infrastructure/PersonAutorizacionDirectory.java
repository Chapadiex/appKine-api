package com.akine.person.infrastructure;

import com.akine.person.application.AutorizacionElegibleView;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.spi.AutorizacionDirectory;
import com.akine.person.spi.AutorizacionSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Publica la lectura de autorizaciones hacia otros modulos (RF-M11-007).
 *
 * <p>Mismo patron que {@code PersonPacienteDirectory}: traduce la entidad a un snapshot y nada
 * mas. <b>La entidad nunca cruza el borde</b> —AGENT.md seccion 4 regla 6— y el snapshot deja
 * afuera el convenio congelado y el adjunto, que son detalles que {@code clinical} no sabria
 * interpretar.
 *
 * <p>Reusa {@code AutorizacionElegibleView} para calcular el {@code motivoNoElegible}: el mismo
 * desenlace no puede llamarse distinto segun por que puerto se lo mire, y tener dos tablas de
 * motivos es la forma mas rapida de que diverjan.
 *
 * <p>Filtra las dadas de baja aca y no en la consulta: la baja es informacion que el llamador no
 * necesita distinguir de la inexistencia, y colapsar los cuatro casos en {@code Optional.empty()}
 * es lo que impide usar este puerto como oraculo de ids.
 */
@Component
public class PersonAutorizacionDirectory implements AutorizacionDirectory {

	private final AutorizacionRepositoryPort autorizaciones;

	public PersonAutorizacionDirectory(AutorizacionRepositoryPort autorizaciones) {
		this.autorizaciones = autorizaciones;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AutorizacionSnapshot> find(
			long organizationId, long personaId, long autorizacionId, LocalDate fecha) {

		LocalDate dia = fecha == null ? LocalDate.now() : fecha;

		return autorizaciones
				.findByIdAndOrganizationIdAndPersonaId(autorizacionId, organizationId, personaId)
				.filter(Autorizacion::isActive)
				.map(autorizacion -> snapshot(autorizacion, dia));
	}

	private static AutorizacionSnapshot snapshot(Autorizacion autorizacion, LocalDate dia) {
		AutorizacionElegibleView veredicto = AutorizacionElegibleView.de(autorizacion, dia);
		return new AutorizacionSnapshot(
				autorizacion.getId(),
				autorizacion.getOrganizationId(),
				autorizacion.getPersonaId(),
				autorizacion.getCoberturaId(),
				autorizacion.getPracticaId(),
				autorizacion.getNumero(),
				autorizacion.getCantidadAutorizada(),
				autorizacion.getCantidadConsumida(),
				autorizacion.saldo(),
				autorizacion.getVigenciaDesde(),
				autorizacion.getVigenciaHasta(),
				dia,
				veredicto.habilita(),
				veredicto.motivoNoElegible());
	}
}
