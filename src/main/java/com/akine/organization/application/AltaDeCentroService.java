package com.akine.organization.application;

import com.akine.organization.domain.Plan;
import com.akine.organization.spi.AltaDeCentro;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Lado {@code organization} del alta de un centro desde la plataforma: organizacion,
 * suscripcion (en prueba o activa), primera sede y contratacion, en la transaccion de quien
 * llama. Si vino de un pedido de la landing, lo marca aprobado.
 */
@Service
public class AltaDeCentroService implements AltaDeCentro {

	private final OrganizationService organizationService;
	private final PlanCatalogService planCatalogService;
	private final ContratacionService contratacionService;
	private final SolicitudDePruebaService solicitudes;

	public AltaDeCentroService(
			OrganizationService organizationService,
			PlanCatalogService planCatalogService,
			ContratacionService contratacionService,
			SolicitudDePruebaService solicitudes) {
		this.organizationService = organizationService;
		this.planCatalogService = planCatalogService;
		this.contratacionService = contratacionService;
		this.solicitudes = solicitudes;
	}

	@Override
	@Transactional(readOnly = true, propagation = Propagation.REQUIRED)
	public void validar(Comando comando) {
		planCatalogService.requireOfrecido(comando.planCode());
		if (comando.solicitudId() != null) {
			solicitudes.exigirPendiente(comando.solicitudId());
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public CentroProvisto provisionar(Comando comando) {
		Plan plan = planCatalogService.requireOfrecido(comando.planCode());
		Instant pruebaHasta = comando.prueba()
				? Instant.now().plus(Duration.ofDays(DIAS_DE_PRUEBA))
				: null;
		String sede = comando.nombrePrimeraSede() == null || comando.nombrePrimeraSede().isBlank()
				? comando.nombreCentro()
				: comando.nombrePrimeraSede();
		ProvisionedTenant tenant = organizationService.provisionTenant(
				comando.nombreCentro(), null, comando.zonaHoraria(), plan, sede,
				comando.actorAccountId(), pruebaHasta);
		long organizationId = tenant.organization().getId();
		contratacionService.asignar(organizationId, comando.servicios(), comando.adicionales());
		if (comando.solicitudId() != null) {
			solicitudes.aprobar(comando.solicitudId(), organizationId, comando.actorAccountId());
		}
		return new CentroProvisto(organizationId, tenant.consultorio().getId());
	}
}
