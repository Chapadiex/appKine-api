package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.EstadoCaso;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoProfesionalRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * La lectura limitada de la Historia Clinica (matriz §32, "Ver Historia Clinica" del
 * {@code ADMINISTRATIVO}: "Limitado"; {@code docs/seguridad/matriz-permisos-minima.md} §4).
 *
 * <h2>Por que {@code paciente:read} y no {@code hc:read}</h2>
 *
 * <p>Lo que devuelve son datos administrativos —que la historia existe, cuando se abrio, que
 * casos hay, en que estado y quien los trata— y <b>nunca contenido clinico</b>. Es la misma
 * clase de dato que el padron, asi que se autoriza con el permiso del padron (DP-22: lo tiene
 * todo el personal y no el rol {@code PACIENTE}). Con {@code hc:read} quedaria afuera
 * justamente el rol para el que existe, y G-5 ya prohibe otorgarle {@code hc:read} al
 * {@code ADMINISTRATIVO} por grant.
 *
 * <p><b>No exige justificacion ni relacion asistencial, y no se audita como acceso clinico</b>
 * (DP-03 audita el acceso al contenido). Tampoco hay {@code SUPPORT_ACCESS_USED}: ningun rol con
 * alcance de soporte tiene {@code paciente:read} (mismo razonamiento que
 * {@code AutorizacionDePadron#exigirLecturaDelPadron}).
 *
 * <h2>404 y 200 vacio no son lo mismo</h2>
 *
 * <p>Una persona de otra organizacion, o sin perfil de paciente vigente, es 404. Un paciente
 * propio sin historia es 200 con {@code tieneHistoriaClinica = false}: saberlo es parte de lo
 * que la recepcion necesita, y no revela nada fuera del tenant.
 */
@Service
public class HistoriaClinicaAdministrativaService {

	private static final Logger log =
			LoggerFactory.getLogger(HistoriaClinicaAdministrativaService.class);

	private final PermissionGuard permissionGuard;
	private final PacienteDirectory pacientes;
	private final HistoriaClinicaRepositoryPort historias;
	private final CasoClinicoRepositoryPort casos;
	private final CasoProfesionalRepositoryPort equipos;

	public HistoriaClinicaAdministrativaService(
			PermissionGuard permissionGuard,
			PacienteDirectory pacientes,
			HistoriaClinicaRepositoryPort historias,
			CasoClinicoRepositoryPort casos,
			CasoProfesionalRepositoryPort equipos) {

		this.permissionGuard = permissionGuard;
		this.pacientes = pacientes;
		this.historias = historias;
		this.casos = casos;
		this.equipos = equipos;
	}

	@Transactional(readOnly = true)
	public DatosAdministrativosDeHistoriaView datosAdministrativos(
			OperatingActor actor, long personaId) {

		if (actor.contextOrganizationId() == null) {
			log.info("Datos administrativos de la HC sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		long organizationId = actor.contextOrganizationId();
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.PACIENTE_READ,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));

		// El directorio devuelve a cualquier persona del tenant, sea o no paciente: lo que decide
		// el 404 es el perfil vigente. Sin esto, una persona con el perfil dado de baja exponia su
		// historia y sus casos, y una que nunca fue paciente respondia 200 vacio.
		boolean esPacienteVigente = pacientes.find(organizationId, personaId)
				.filter(paciente -> paciente.activa() && paciente.esPacienteVigente())
				.isPresent();
		if (!esPacienteVigente) {
			throw new HistoriaClinicaNotAccessibleException(personaId);
		}

		Optional<HistoriaClinica> historia =
				historias.buscarVigentePorPersona(organizationId, personaId);
		if (historia.isEmpty()) {
			return new DatosAdministrativosDeHistoriaView(personaId, false, null, null, List.of());
		}

		List<DatosAdministrativosDeHistoriaView.Caso> casosDeLaHistoria = casos
				.buscarDeHistoria(organizationId, historia.get().getId(), false).stream()
				.sorted(Comparator.comparing(
						(CasoClinico caso) -> caso.getEstado() != EstadoCaso.ACTIVO))
				.map(caso -> aVista(organizationId, caso))
				.toList();

		return new DatosAdministrativosDeHistoriaView(personaId, true, historia.get().getId(),
				historia.get().getAbiertaEn(), casosDeLaHistoria);
	}

	private DatosAdministrativosDeHistoriaView.Caso aVista(long organizationId, CasoClinico caso) {
		return new DatosAdministrativosDeHistoriaView.Caso(
				caso.getId(),
				caso.getNumeroCaso(),
				caso.getEstado().name(),
				caso.getOfertaId(),
				caso.getOfertaConsultorioId(),
				caso.getAbiertoEn(),
				caso.getCerradoEn(),
				equipos.buscarDeCaso(organizationId, caso.getId(), true).stream()
						.map(CasoProfesionalView::de)
						.toList());
	}
}
