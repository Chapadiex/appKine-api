package com.akine.clinical.application;

import com.akine.clinical.domain.ActividadFisica;
import com.akine.clinical.domain.FichaDeIngreso;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.Ocupacion;
import com.akine.clinical.domain.OrigenDato;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.FichaDeIngresoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.OcupacionRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ficha de ingreso de la historia: ocupacion, peso, altura y actividad fisica.
 *
 * <p>Dos caminos, como el resto de DP-47: quien declara ({@code hc:declarar}, por persona, abre la
 * historia si falta) deja la ficha sin revisar; el profesional ({@code hc:write}) la deja revisada
 * por el. Cada guardado reemplaza la ficha entera y exige la version leida: la pantalla guarda
 * campo por campo y dos personas editando a la vez se enteran con un 409.
 */
@Service
public class FichaDeIngresoService {

	/** Contenido de la ficha que manda la pantalla. */
	public record DatosDeFicha(
			Long ocupacionId, BigDecimal pesoKg, BigDecimal alturaCm, ActividadFisica actividad, String detalle) {
	}

	private final HistoriaClinicaRepositoryPort historias;
	private final HistoriaClinicaService historiaService;
	private final FichaDeIngresoRepositoryPort fichas;
	private final OcupacionRepositoryPort ocupaciones;
	private final PacienteDirectory pacientes;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;
	private final AuditTrail auditTrail;
	private final NombresDeAutores nombres;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public FichaDeIngresoService(
			HistoriaClinicaRepositoryPort historias,
			HistoriaClinicaService historiaService,
			FichaDeIngresoRepositoryPort fichas,
			OcupacionRepositoryPort ocupaciones,
			PacienteDirectory pacientes,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			ClinicalSupportAccessAuditor supportAccessAuditor,
			AuditTrail auditTrail,
			NombresDeAutores nombres) {

		this.historias = historias;
		this.historiaService = historiaService;
		this.fichas = fichas;
		this.ocupaciones = ocupaciones;
		this.pacientes = pacientes;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.supportAccessAuditor = supportAccessAuditor;
		this.auditTrail = auditTrail;
		this.nombres = nombres;
	}

	/** La ficha la declara quien atiende el mostrador: queda sin revisar. Abre la historia si falta. */
	@Transactional
	public FichaDeIngresoView declarar(
			OperatingActor actor, long personaId, DatosDeFicha datos, Long version, String justificacion) {

		AccesoClinico acceso = autorizar(actor, personaId, PermissionCodes.HC_DECLARAR, justificacion,
				"Declarar ficha de ingreso");
		HistoriaClinica historia = historiaService.asegurar(
				actor.contextOrganizationId(), personaId, actor.accountId());
		return guardar(actor, acceso, historia, datos, version, OrigenDato.DECLARADO, AuditEvents.FICHA_DECLARADA);
	}

	/** La ficha la carga o corrige el profesional: queda revisada por el. */
	@Transactional
	public FichaDeIngresoView registrar(
			OperatingActor actor, long personaId, DatosDeFicha datos, Long version, String justificacion) {

		AccesoClinico acceso = autorizar(actor, personaId, PermissionCodes.HC_WRITE, justificacion,
				"Registrar ficha de ingreso");
		HistoriaClinica historia = historiaVigente(actor, personaId);
		return guardar(actor, acceso, historia, datos, version, OrigenDato.PROFESIONAL,
				AuditEvents.FICHA_REGISTRADA);
	}

	/** Marca revisada la ficha. Idempotente: una ya revisada se devuelve como esta, sin evento. */
	@Transactional
	public FichaDeIngresoView revisar(OperatingActor actor, long personaId, String justificacion) {
		AccesoClinico acceso = autorizar(actor, personaId, PermissionCodes.HC_WRITE, justificacion,
				"Revisar ficha de ingreso");
		HistoriaClinica historia = historiaVigente(actor, personaId);
		FichaDeIngreso ficha = fichas.buscarPorHistoria(historia.getOrganizationId(), historia.getId())
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));
		Instant ahora = Instant.now();
		if (ficha.revisar(actor.accountId(), ahora)) {
			ficha = fichas.saveAndFlush(ficha);
			auditar(AuditEvents.FICHA_REVISADA, ficha, actor, acceso, "SIN_REVISAR", "REVISADA", ahora);
			registrarSoporte(acceso, actor, ficha.getOrganizationId(), AuditEvents.ENTITY_FICHA_DE_INGRESO,
					ficha.getId(), "Revisar ficha de ingreso", ahora);
		}
		return vista(ficha);
	}

	/**
	 * La ficha de la historia del profesional (hc:read), o vacio si todavia no hay. Es una lectura
	 * clinica y se audita como acceso a la historia (regla 9), como el resto de las lecturas.
	 */
	@Transactional
	public Optional<FichaDeIngresoView> ver(OperatingActor actor, long personaId, String justificacion) {
		AccesoClinico acceso = autorizar(actor, personaId, PermissionCodes.HC_READ, justificacion,
				"Ver ficha de ingreso");
		Optional<HistoriaClinica> historia = historias.buscarVigentePorPersona(actor.contextOrganizationId(), personaId);
		if (historia.isEmpty()) {
			return Optional.empty();
		}
		long organizationId = historia.get().getOrganizationId();
		long historiaId = historia.get().getId();
		Instant ahora = Instant.now();
		auditTrail.record(new AuditEntry(organizationId, actor.consultorioId(), actor.accountId(),
				AuditEvents.HISTORIA_CLINICA_ACCESSED, AuditEvents.ENTITY_HISTORIA_CLINICA, historiaId, null, null,
				Map.of("personaId", String.valueOf(personaId), "alcance", "FICHA", "viaDeAcceso", acceso.via()),
				acceso.justificacion(), AuditEvents.correlationId(), ahora));
		registrarSoporte(acceso, actor, organizationId, AuditEvents.ENTITY_HISTORIA_CLINICA, historiaId,
				"Ver ficha de ingreso", ahora);
		return deHistoria(organizationId, historiaId);
	}

	/** La ficha de una historia, sin autorizar: lo hace quien llama. */
	Optional<FichaDeIngresoView> deHistoria(long organizationId, long historiaClinicaId) {
		return fichas.buscarPorHistoria(organizationId, historiaClinicaId).map(this::vista);
	}

	private FichaDeIngresoView guardar(
			OperatingActor actor,
			AccesoClinico acceso,
			HistoriaClinica historia,
			DatosDeFicha datos,
			Long version,
			OrigenDato origen,
			String evento) {

		long organizationId = historia.getOrganizationId();
		if (datos.ocupacionId() != null
				&& ocupaciones.buscarVisible(organizationId, datos.ocupacionId()).isEmpty()) {
			throw new IllegalArgumentException("La ocupacion elegida no existe");
		}
		Optional<FichaDeIngreso> existente = fichas.buscarPorHistoria(organizationId, historia.getId());
		if (existente.isPresent() ? !Objects.equals(version, existente.get().getVersion()) : version != null) {
			throw new OptimisticLockingFailureException(
					"La ficha de ingreso cambio desde que se leyo: volve a cargarla");
		}
		FichaDeIngreso ficha = existente.orElseGet(() -> new FichaDeIngreso(organizationId, historia.getId()));
		String previo = existente.map(f -> f.estaDeclaradaSinRevisar() ? "SIN_REVISAR" : "REVISADA").orElse(null);

		Instant ahora = Instant.now();
		ficha.actualizar(datos.ocupacionId(), datos.pesoKg(), datos.alturaCm(), datos.actividad(),
				datos.detalle(), origen, actor.accountId(), ahora);
		try {
			ficha = fichas.saveAndFlush(ficha);
		} catch (DataIntegrityViolationException choque) {
			// Dos primeras altas a la vez: el unique por historia deja pasar una. La otra se entera
			// como cualquier edicion concurrente, no con un 500.
			throw new OptimisticLockingFailureException(
					"Otra persona guardo la ficha de ingreso al mismo tiempo: volve a cargarla", choque);
		}
		auditar(evento, ficha, actor, acceso, previo, ficha.estaDeclaradaSinRevisar() ? "SIN_REVISAR" : "REVISADA",
				ahora);
		registrarSoporte(acceso, actor, organizationId, AuditEvents.ENTITY_FICHA_DE_INGRESO, ficha.getId(),
				"Guardar ficha de ingreso", ahora);
		return vista(ficha);
	}

	private FichaDeIngresoView vista(FichaDeIngreso ficha) {
		String ocupacion = ficha.getOcupacionId() == null ? null
				: ocupaciones.buscarVisible(ficha.getOrganizationId(), ficha.getOcupacionId())
						.map(Ocupacion::getNombre).orElse(null);
		return FichaDeIngresoView.de(ficha, ocupacion, nombres.nombres(ficha.getCargadoPor(), ficha.getRevisadoPor()));
	}

	private HistoriaClinica historiaVigente(OperatingActor actor, long personaId) {
		return historias.buscarVigentePorPersona(actor.contextOrganizationId(), personaId)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));
	}

	private AccesoClinico autorizar(
			OperatingActor actor, long personaId, String permiso, String justificacion, String operacion) {
		return AutorizacionClinica.exigirSobrePersona(permissionGuard, relaciones, supportAccessAuditor,
				pacientes, actor, permiso, personaId, justificacion, operacion);
	}

	/** Con acceso de soporte de plataforma, el uso queda registrado como en el resto de clinical. */
	private void registrarSoporte(
			AccesoClinico acceso, OperatingActor actor, long organizationId, String entidad, long entidadId,
			String operacion, Instant ahora) {
		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(organizationId, actor.consultorioId(), actor.accountId(),
				"SUPPORT_ACCESS_USED", entidad, entidadId, null, null, Map.of("operacion", operacion),
				acceso.justificacion(), AuditEvents.correlationId(), ahora));
	}

	/** Sin peso, altura ni ocupacion: es contenido clinico y la auditoria se lee sin permiso clinico. */
	private void auditar(
			String evento, FichaDeIngreso ficha, OperatingActor actor, AccesoClinico acceso,
			String previo, String nuevo, Instant ahora) {

		auditTrail.record(new AuditEntry(
				ficha.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				evento,
				AuditEvents.ENTITY_FICHA_DE_INGRESO,
				ficha.getId(),
				previo,
				nuevo,
				Map.of("historiaClinicaId", String.valueOf(ficha.getHistoriaClinicaId()),
						"origen", ficha.getOrigen().name(), "viaDeAcceso", acceso.via()),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}
}
