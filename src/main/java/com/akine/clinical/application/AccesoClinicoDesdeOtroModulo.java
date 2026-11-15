package com.akine.clinical.application;

import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.spi.AccesoClinicoConcedido;
import com.akine.clinical.spi.AccesoClinicoDirectory;
import com.akine.clinical.spi.ActorClinico;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.audit.AuditEntry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * {@link AccesoClinicoDirectory} sobre {@link AutorizacionClinica}: la misma regla, sin copia.
 *
 * <p>Vive en {@code application} y no en {@code infrastructure} porque {@link AutorizacionClinica}
 * es privada del paquete, a proposito: es la unica puerta, y una segunda implementacion seria la
 * forma de que dos reglas de acceso clinico divergieran.
 */
@Component
class AccesoClinicoDesdeOtroModulo implements AccesoClinicoDirectory {

	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;
	private final PacienteDirectory pacientes;

	AccesoClinicoDesdeOtroModulo(
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			ClinicalSupportAccessAuditor supportAccessAuditor,
			PacienteDirectory pacientes) {
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.supportAccessAuditor = supportAccessAuditor;
		this.pacientes = pacientes;
	}

	@Override
	public AccesoClinicoConcedido exigirLectura(
			ActorClinico actor,
			long personaId,
			String justificacion,
			String operacion) {

		OperatingActor propio = new OperatingActor(
				actor.accountId(), actor.platformAdmin(), actor.organizationId(), actor.consultorioId());
		AccesoClinico acceso = AutorizacionClinica.exigirSobrePersona(
				permissionGuard, relaciones, supportAccessAuditor, pacientes, propio,
				PermissionCodes.HC_READ, personaId, justificacion, operacion);

		if (acceso.viaSupportAccess()) {
			supportAccessAuditor.record(new AuditEntry(
					actor.organizationId(),
					actor.consultorioId(),
					actor.accountId(),
					"SUPPORT_ACCESS_USED",
					AuditEvents.ENTITY_PERSONA,
					personaId,
					null,
					null,
					Map.of("operacion", operacion),
					acceso.justificacion(),
					AuditEvents.correlationId(),
					Instant.now()));
		}
		return new AccesoClinicoConcedido(
				acceso.conRelacionAsistencial(), acceso.justificacion(), acceso.viaSupportAccess());
	}
}
