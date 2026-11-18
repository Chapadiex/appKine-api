package com.akine.clinical.application;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.OrigenDato;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.TipoAntecedente;
import com.akine.clinical.domain.exception.AdjuntoClinicoNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.PacienteDirectory;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Datos que declara el paciente en su historia (DP-47), por persona.
 *
 * <p>Quien declara tiene {@code hc:declarar} y no {@code hc:read}: todo lo de aca autoriza con
 * {@code hc:declarar} y devuelve solo lo DECLARADO. Lo que cargo un profesional no aparece ni se
 * alcanza por id (404).
 */
@Service
public class DatosDeclaradosService {

	private final HistoriaClinicaRepositoryPort historias;
	private final HistoriaClinicaService historiaService;
	private final AntecedenteClinicoService antecedentes;
	private final AdjuntoClinicoService adjuntos;
	private final PacienteDirectory pacientes;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;
	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccion;
	private final FichaDeIngresoService fichas;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public DatosDeclaradosService(
			HistoriaClinicaRepositoryPort historias,
			HistoriaClinicaService historiaService,
			AntecedenteClinicoService antecedentes,
			AdjuntoClinicoService adjuntos,
			PacienteDirectory pacientes,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			ClinicalSupportAccessAuditor supportAccessAuditor,
			AuditTrail auditTrail,
			PlatformTransactionManager transactionManager,
			FichaDeIngresoService fichas) {

		this.fichas = fichas;

		this.historias = historias;
		this.historiaService = historiaService;
		this.antecedentes = antecedentes;
		this.adjuntos = adjuntos;
		this.pacientes = pacientes;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.supportAccessAuditor = supportAccessAuditor;
		this.auditTrail = auditTrail;
		this.transaccion = new TransactionTemplate(transactionManager);
	}

	/** Lo declarado vigente de la persona. Sin historia: listas vacias e id null, no 404. */
	@Transactional
	public DatosDeclaradosView ver(OperatingActor actor, long personaId, String justificacion) {
		AccesoClinico acceso = autorizar(actor, personaId, justificacion, "Ver datos declarados");
		long organizationId = actor.contextOrganizationId();
		Optional<HistoriaClinica> historia = historias.buscarVigentePorPersona(organizationId, personaId);
		if (historia.isEmpty()) {
			return new DatosDeclaradosView(null, List.of(), List.of(), null);
		}
		long historiaId = historia.get().getId();
		auditTrail.record(new AuditEntry(
				organizationId,
				actor.consultorioId(),
				actor.accountId(),
				AuditEvents.HISTORIA_CLINICA_ACCESSED,
				AuditEvents.ENTITY_HISTORIA_CLINICA,
				historiaId,
				null,
				null,
				Map.of("personaId", String.valueOf(personaId), "alcance", "DECLARADOS",
						"viaDeAcceso", acceso.via()),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				Instant.now()));
		return new DatosDeclaradosView(historiaId,
				antecedentes.declaradosDeHistoria(organizationId, historiaId),
				adjuntos.declaradosDeHistoria(organizationId, historiaId),
				fichas.deHistoria(organizationId, historiaId).orElse(null));
	}

	public AntecedenteView declararAntecedente(
			OperatingActor actor, long personaId, TipoAntecedente tipo, String descripcion,
			DetalleDeAntecedente detalle, String justificacion) {
		return antecedentes.declarar(actor, personaId, tipo, descripcion, detalle, justificacion);
	}

	/** La ficha de ingreso, declarada: queda sin revisar. */
	public FichaDeIngresoView declararFicha(
			OperatingActor actor, long personaId, FichaDeIngresoService.DatosDeFicha datos, Long version,
			String justificacion) {
		return fichas.declarar(actor, personaId, datos, version, justificacion);
	}

	/**
	 * Sin transaccion de afuera: la subida la exige ({@code AdjuntoClinicoService#subir}). Primero
	 * autoriza y abre la historia en su propia transaccion; si la subida despues falla, queda una
	 * historia abierta y vacia, que es lo mismo que abrirla a mano.
	 */
	public AdjuntoClinicoService.AdjuntoClinicoAlta declararAdjunto(
			OperatingActor actor, long personaId, AdjuntoClinicoAltaCommand command, String justificacion) {
		Long historiaId = transaccion.execute(estado -> {
			autorizar(actor, personaId, justificacion, "Declarar adjunto clinico");
			return historiaService.asegurar(actor.contextOrganizationId(), personaId, actor.accountId()).getId();
		});
		return adjuntos.subir(actor, historiaId, command, justificacion, OrigenDato.DECLARADO);
	}

	/** El archivo de un adjunto declarado, revisado o no. Uno del profesional es 404. */
	public ContenidoDeAdjuntoClinico contenido(
			OperatingActor actor, long personaId, long adjuntoId, String justificacion) {
		long historiaId = historiaDe(actor, personaId, adjuntoId, justificacion, "Abrir adjunto declarado");
		return adjuntos.contenido(actor, historiaId, adjuntoId, justificacion, OrigenDato.DECLARADO);
	}

	/** Baja de un declarado sin revisar. {@code tipo} es "antecedentes" o "adjuntos". */
	public void darDeBaja(
			OperatingActor actor, long personaId, TipoDatoDeclarado tipo, long id, String motivo, String justificacion) {
		if (tipo == TipoDatoDeclarado.ANTECEDENTES) {
			antecedentes.darDeBajaDeclarado(actor, personaId, id, motivo, justificacion);
			return;
		}
		long historiaId = historiaDe(actor, personaId, id, justificacion, "Dar de baja un adjunto declarado");
		adjuntos.darDeBaja(actor, historiaId, id, motivo, justificacion, OrigenDato.DECLARADO);
	}

	private long historiaDe(
			OperatingActor actor, long personaId, long adjuntoId, String justificacion, String operacion) {
		return transaccion.execute(estado -> {
			autorizar(actor, personaId, justificacion, operacion);
			return historias.buscarVigentePorPersona(actor.contextOrganizationId(), personaId)
					.map(HistoriaClinica::getId)
					.orElseThrow(() -> new AdjuntoClinicoNotAccessibleException(adjuntoId));
		});
	}

	private AccesoClinico autorizar(OperatingActor actor, long personaId, String justificacion, String operacion) {
		return AutorizacionClinica.exigirSobrePersona(permissionGuard, relaciones, supportAccessAuditor,
				pacientes, actor, PermissionCodes.HC_DECLARAR, personaId, justificacion, operacion);
	}

	/** Los dos recursos que se declaran, como aparecen en la ruta de la baja. */
	public enum TipoDatoDeclarado {
		ANTECEDENTES,
		ADJUNTOS
	}

	/**
	 * Lo declarado de una persona. {@code historiaClinicaId} null si todavia no tiene historia. La
	 * ficha de ingreso viaja entera, la haya guardado quien declara o un profesional: es un solo
	 * formulario y quien lo completa tiene que ver lo que ya dice para no pisarlo a ciegas.
	 */
	public record DatosDeclaradosView(
			Long historiaClinicaId, List<AntecedenteView> antecedentes, List<AdjuntoClinicoView> adjuntos,
			FichaDeIngresoView ficha) {
	}
}
