package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionEvaluator;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditAgendaFilter;
import com.akine.platform.spi.audit.AuditEventSummary;
import com.akine.platform.spi.audit.AuditQuery;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.scheduling.domain.PermissionCodes;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.ProfesionalNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Historial de cambios de la agenda de UN profesional: horario habitual, excepciones y bloqueos, y sus
 * turnos (AKINE-agenda-historial).
 *
 * <h2>Una sola fuente</h2>
 *
 * <p>Se lee de la auditoria transversal ({@code audit_event}) por {@code platform.spi.audit.AuditQuery},
 * filtrando por las columnas generadas de V134. No hay tabla nueva ni copia: todo cambio de agenda ya
 * se auditaba, con actor, rol, motivo y antes/despues.
 *
 * <h2>Quien lee que</h2>
 *
 * <p>Exige {@code agenda:excepciones}: quien administra la agenda de alguien ve sus cambios, con el mismo
 * alcance. {@code ORG_ADMIN} toda la organizacion; {@code CONSULTORIO_ADMIN} y {@code ADMINISTRATIVO}
 * sus sedes; {@code PROFESIONAL} ({@code ACTIVIDAD_PROPIA}) solo su propia agenda, en todas sus sedes.
 * Un grant de {@code agenda:excepciones} amplia al profesional a su sede, igual que para operar. Sin
 * ninguna sede alcanzable: 403. Otra organizacion o un profesional que no es de ella: 404.
 *
 * <p>El paciente de un turno viaja solo a quien tiene {@code turno:read} en la sede del evento (PHI
 * minima); el resto del cambio no es dato clinico.
 */
@Service
public class HistorialDeAgendaService {

	private static final DateTimeFormatter TURNO = DateTimeFormatter.ofPattern("dd/MM HH:mm");
	private static final String[] DIAS = {"Lun", "Mar", "Mie", "Jue", "Vie", "Sab", "Dom"};
	private static final String SEPARADOR = " · ";

	private final ConsultorioMembershipDirectory memberships;
	private final ConsultorioDirectory consultorios;
	private final PermissionEvaluator permisos;
	private final AuditQuery auditoria;
	private final AccountIdentityDirectory identidades;
	private final NombresDeAgenda nombres;
	private final PacienteDirectory pacientes;
	private final TurnoRepositoryPort turnos;

	public HistorialDeAgendaService(
			ConsultorioMembershipDirectory memberships,
			ConsultorioDirectory consultorios,
			PermissionEvaluator permisos,
			AuditQuery auditoria,
			AccountIdentityDirectory identidades,
			NombresDeAgenda nombres,
			PacienteDirectory pacientes,
			TurnoRepositoryPort turnos) {
		this.memberships = memberships;
		this.consultorios = consultorios;
		this.permisos = permisos;
		this.auditoria = auditoria;
		this.identidades = identidades;
		this.nombres = nombres;
		this.pacientes = pacientes;
		this.turnos = turnos;
	}

	/**
	 * @throws AccessDeniedException             sin contexto, o sin el permiso sobre ninguna sede (403)
	 * @throws ProfesionalNotAccessibleException otra organizacion, o el profesional no es de esta (404)
	 * @throws ConsultorioNoAccesibleException   la sede del filtro no es de la organizacion (404)
	 */
	@Transactional(readOnly = true)
	public Page<CambioDeAgendaView> listar(
			OperatingActor actor, long organizationId, FiltroDeHistorialDeAgenda filtro, Pageable pagina) {

		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("El historial de agenda requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId) {
			throw new ProfesionalNotAccessibleException(filtro.profesionalId());
		}
		ConsultorioMembershipSnapshot profesional = memberships.find(organizationId, filtro.profesionalId())
				.orElseThrow(() -> new ProfesionalNotAccessibleException(filtro.profesionalId()));
		if (filtro.consultorioId() != null && consultorios.find(organizationId, filtro.consultorioId()).isEmpty()) {
			throw new ConsultorioNoAccesibleException(filtro.consultorioId());
		}

		Alcance alcance = alcance(actor, organizationId, profesional.accountId(), filtro.consultorioId());

		Set<Long> deLaPersona = new LinkedHashSet<>();
		deLaPersona.add(profesional.membershipId());
		memberships.findTodasDeLaCuenta(organizationId, profesional.accountId())
				.forEach(m -> deLaPersona.add(m.membershipId()));

		Page<AuditEventSummary> hechos = auditoria.deAgenda(new AuditAgendaFilter(organizationId, deLaPersona,
				TipoDeCambioDeAgenda.eventTypes(filtro.tipos()), alcance.sedes(), filtro.desde(), filtro.hasta(),
				filtro.texto()), pagina);
		return componer(organizationId, hechos, alcance);
	}

	// =================================================================================
	// Alcance del lector
	// =================================================================================

	/**
	 * @param sedes       las sedes cuyo historial se lee, o {@code null} para toda la organizacion
	 * @param sedesConPhi sedes donde el lector tiene {@code turno:read}, o {@code null} para todas
	 */
	record Alcance(Set<Long> sedes, Set<Long> sedesConPhi) {

		boolean veePaciente(Long consultorioId) {
			return sedesConPhi == null || (consultorioId != null && sedesConPhi.contains(consultorioId));
		}
	}

	Alcance alcance(OperatingActor actor, long organizationId, long cuentaDelProfesional, Long consultorioPedido) {
		Instant ahora = Instant.now();
		boolean esSuAgenda = cuentaDelProfesional == actor.accountId();

		Set<Long> sedes;
		PermissionDecision deLaOrganizacion = evaluar(actor, PermissionCodes.AGENDA_EXCEPCIONES, organizationId, null, ahora);
		if (deLaOrganizacion.granted() && (!deLaOrganizacion.limitadaAActividadPropia() || esSuAgenda)) {
			sedes = consultorioPedido == null ? null : Set.of(consultorioPedido);
		} else {
			sedes = new LinkedHashSet<>();
			boolean soloPropia = deLaOrganizacion.limitadaAActividadPropia();
			for (Long sede : candidatas(actor, organizationId, consultorioPedido)) {
				PermissionDecision decision = evaluar(actor, PermissionCodes.AGENDA_EXCEPCIONES, organizationId, sede, ahora);
				soloPropia |= decision.limitadaAActividadPropia();
				if (decision.granted() && (!decision.limitadaAActividadPropia() || esSuAgenda)) {
					sedes.add(sede);
				}
			}
			if (sedes.isEmpty()) {
				if (soloPropia && !esSuAgenda) {
					throw new AgendaAjenaException();
				}
				throw new AccessDeniedException("Sin permiso para ver el historial de agenda en esta sede");
			}
		}

		Set<Long> sedesConPhi;
		if (evaluar(actor, PermissionCodes.TURNO_READ, organizationId, null, ahora).granted()) {
			sedesConPhi = null;
		} else {
			sedesConPhi = new HashSet<>();
			for (Long sede : sedes == null ? candidatas(actor, organizationId, consultorioPedido) : sedes) {
				if (evaluar(actor, PermissionCodes.TURNO_READ, organizationId, sede, ahora).granted()) {
					sedesConPhi.add(sede);
				}
			}
		}
		return new Alcance(sedes, sedesConPhi);
	}

	/** Las sedes donde el lector tiene un vinculo vigente: las unicas que un alcance de sede puede cubrir. */
	private Collection<Long> candidatas(OperatingActor actor, long organizationId, Long consultorioPedido) {
		if (consultorioPedido != null) {
			return List.of(consultorioPedido);
		}
		return memberships.findByAccount(organizationId, actor.accountId()).stream()
				.map(ConsultorioMembershipSnapshot::consultorioId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
	}

	private PermissionDecision evaluar(OperatingActor actor, String permiso, long organizationId, Long sede, Instant at) {
		return permisos.evaluate(new PermissionQuery(actor.accountId(), permiso, organizationId, sede, null, at));
	}

	// =================================================================================
	// Armado de la vista: nombres en lote, una consulta por fuente
	// =================================================================================

	private Page<CambioDeAgendaView> componer(long organizationId, Page<AuditEventSummary> hechos, Alcance alcance) {
		List<AuditEventSummary> filas = hechos.getContent();
		if (filas.isEmpty()) {
			return hechos.map(fila -> null);
		}

		// Los turnos nuevos de los traslados, para decir a donde fueron.
		Set<Long> turnosNuevos = new HashSet<>();
		filas.forEach(f -> idDe(f, "turnoNuevoId").ifPresent(turnosNuevos::add));
		Map<Long, Turno> nuevos = new HashMap<>();
		if (!turnosNuevos.isEmpty()) {
			turnos.findTodosPorId(organizationId, turnosNuevos).forEach(t -> nuevos.put(t.getId(), t));
		}

		Set<Long> profesionales = new HashSet<>();
		Set<Long> espacios = new HashSet<>();
		Set<Long> sedes = new HashSet<>();
		Set<Long> cuentas = new HashSet<>();
		Set<Long> personas = new HashSet<>();
		for (AuditEventSummary fila : filas) {
			idDe(fila, "profesionalMembershipId").ifPresent(profesionales::add);
			idDe(fila, "membershipId").ifPresent(profesionales::add);
			idDe(fila, "profesionalAnteriorId").ifPresent(profesionales::add);
			idDe(fila, "espacioId").ifPresent(espacios::add);
			idDe(fila, "espacioAnteriorId").ifPresent(espacios::add);
			idDe(fila, "consultorioDestinoId").ifPresent(sedes::add);
			if (fila.consultorioId() != null) {
				sedes.add(fila.consultorioId());
			}
			if (fila.actorAccountId() != null) {
				cuentas.add(fila.actorAccountId());
			}
			if (alcance.veePaciente(fila.consultorioId())) {
				idDe(fila, "personaId").ifPresent(personas::add);
			}
		}
		for (Turno nuevo : nuevos.values()) {
			if (nuevo.getProfesionalMembershipId() != null) {
				profesionales.add(nuevo.getProfesionalMembershipId());
			}
			if (nuevo.getEspacioId() != null) {
				espacios.add(nuevo.getEspacioId());
			}
			sedes.add(nuevo.getConsultorioId());
		}

		Nombres resueltos = new Nombres(
				nombres.profesionales(organizationId, profesionales),
				nombres.espacios(organizationId, espacios, Instant.now()),
				sedesDe(organizationId, sedes),
				identidades.identidadesDe(cuentas),
				personas.isEmpty() ? Map.of() : pacientes.findAll(organizationId, personas),
				nuevos);
		return hechos.map(fila -> vista(fila, resueltos, alcance));
	}

	private Map<Long, ConsultorioSnapshot> sedesDe(long organizationId, Collection<Long> ids) {
		Map<Long, ConsultorioSnapshot> porId = new HashMap<>();
		ids.forEach(id -> consultorios.find(organizationId, id).ifPresent(sede -> porId.put(id, sede)));
		return porId;
	}

	private record Nombres(
			Map<Long, String> profesionales,
			Map<Long, String> espacios,
			Map<Long, ConsultorioSnapshot> sedes,
			Map<Long, AccountIdentity> cuentas,
			Map<Long, PacienteSnapshot> pacientes,
			Map<Long, Turno> turnosNuevos) {

		String profesional(Long id) {
			return id == null ? null : profesionales.getOrDefault(id, "Profesional #" + id);
		}

		String sede(Long id) {
			ConsultorioSnapshot sede = id == null ? null : sedes.get(id);
			return sede == null ? (id == null ? null : "Sede #" + id) : sede.name();
		}

		ZoneId zona(Long sedeId) {
			ConsultorioSnapshot sede = sedeId == null ? null : sedes.get(sedeId);
			return sede == null || sede.timezone() == null ? ZoneId.of("UTC") : ZoneId.of(sede.timezone());
		}
	}

	private CambioDeAgendaView vista(AuditEventSummary fila, Nombres n, Alcance alcance) {
		TipoDeCambioDeAgenda tipo = TipoDeCambioDeAgenda.deEvento(fila.eventType()).orElseThrow();
		Map<String, String> d = fila.details();
		Long profesional = idDe(fila, "profesionalMembershipId").or(() -> idDe(fila, "membershipId")).orElse(null);
		Long profesionalAnterior = idDe(fila, "profesionalAnteriorId").orElse(null);
		boolean esTurno = tipo.entidad() == TipoDeCambioDeAgenda.Entidad.TURNO;
		Instant inicio = esTurno ? instante(d.get("inicio")) : null;

		String antes;
		String despues;
		switch (tipo) {
			case TURNO_RESERVA, TURNO_SOBRETURNO -> {
				antes = null;
				despues = turno(inicio, fila.consultorioId(), profesional, idDe(fila, "espacioId").orElse(null), n);
			}
			case TURNO_REPROGRAMACION -> {
				antes = turno(instante(d.get("inicioAnterior")), fila.consultorioId(), profesionalAnterior,
						idDe(fila, "espacioAnteriorId").orElse(null), n);
				despues = turno(inicio, fila.consultorioId(), profesional, idDe(fila, "espacioId").orElse(null), n);
			}
			case TURNO_TRASLADO -> {
				antes = turno(inicio, fila.consultorioId(), profesional, idDe(fila, "espacioId").orElse(null), n);
				Turno nuevo = idDe(fila, "turnoNuevoId").map(n.turnosNuevos()::get).orElse(null);
				despues = nuevo != null
						? turno(nuevo.getInicio(), nuevo.getConsultorioId(), nuevo.getProfesionalMembershipId(),
								nuevo.getEspacioId(), n)
						: n.sede(idDe(fila, "consultorioDestinoId").orElse(null));
			}
			case TURNO_OBSERVACIONES -> {
				antes = vacioANull(d.get("observacionesAntes"));
				despues = vacioANull(d.get("observacionesDespues"));
			}
			case TURNO_CONFIRMACION, TURNO_CANCELACION, TURNO_AUSENCIA -> {
				antes = estado(fila.previousState());
				despues = estado(fila.newState());
			}
			default -> {
				antes = d.containsKey("antes") ? d.get("antes") : lado(d, true);
				despues = d.containsKey("despues") ? d.get("despues") : lado(d, false);
			}
		}

		Long personaId = esTurno && alcance.veePaciente(fila.consultorioId()) ? idDe(fila, "personaId").orElse(null) : null;
		PacienteSnapshot paciente = personaId == null ? null : n.pacientes().get(personaId);
		AccountIdentity actor = fila.actorAccountId() == null ? null : n.cuentas().get(fila.actorAccountId());
		return new CambioDeAgendaView(
				fila.id(),
				fila.occurredAt(),
				tipo,
				tipo.entidad().name(),
				fila.entityId(),
				fila.consultorioId(),
				n.sede(fila.consultorioId()),
				fila.actorAccountId(),
				actor == null ? null : actor.nombreCompleto(),
				d.get("rol"),
				fila.reason(),
				antes,
				despues,
				profesional,
				n.profesional(profesional),
				esTurno ? fila.entityId() : null,
				inicio,
				personaId,
				paciente == null ? null : paciente.nombreCompleto(),
				tipo == TipoDeCambioDeAgenda.TURNO_REPROGRAMACION && profesionalAnterior != null
						&& !profesionalAnterior.equals(profesional));
	}

	/** "Lun 10/11 10:00 · Sede Centro · Gomez, Laura · Box 2", en la zona de la sede. */
	private static String turno(Instant inicio, Long sede, Long profesional, Long espacio, Nombres n) {
		List<String> partes = new ArrayList<>();
		if (inicio != null) {
			var local = inicio.atZone(n.zona(sede));
			partes.add(DIAS[local.getDayOfWeek().getValue() - 1] + " " + TURNO.format(local));
		}
		if (sede != null) {
			partes.add(n.sede(sede));
		}
		if (profesional != null) {
			partes.add(n.profesional(profesional));
		}
		if (espacio != null) {
			partes.add(n.espacios().getOrDefault(espacio, "Box #" + espacio));
		}
		return partes.isEmpty() ? null : String.join(SEPARADOR, partes);
	}

	/**
	 * Para filas viejas sin {@code antes}/{@code despues}: los campos que la edicion anoto como
	 * "a -> b", partidos por lado. Las claves tecnicas no se muestran.
	 */
	static String lado(Map<String, String> detalles, boolean antes) {
		Set<String> tecnicas = Set.of("membershipId", "rol", "alcance", "tipo", "turnosAfectados");
		List<String> partes = new ArrayList<>();
		detalles.forEach((clave, valor) -> {
			if (tecnicas.contains(clave) || valor == null) {
				return;
			}
			int flecha = valor.indexOf(" -> ");
			if (flecha >= 0 && !clave.equals("horario") && !clave.equals("fechas")) {
				partes.add(clave + " " + (antes ? valor.substring(0, flecha) : valor.substring(flecha + 4)));
			} else if (!antes) {
				partes.add(clave + " " + valor);
			}
		});
		return partes.isEmpty() ? null : String.join(SEPARADOR, partes);
	}

	private static java.util.Optional<Long> idDe(AuditEventSummary fila, String clave) {
		String valor = fila.details().get(clave);
		if (valor == null || valor.isBlank() || !valor.chars().allMatch(Character::isDigit) || valor.length() > 18) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(Long.parseLong(valor));
	}

	private static Instant instante(String valor) {
		try {
			return valor == null || valor.isBlank() || "null".equals(valor) ? null : Instant.parse(valor);
		} catch (RuntimeException ilegible) {
			return null;
		}
	}

	private static String estado(String estado) {
		return estado == null ? null : capital(estado.toLowerCase(Locale.ROOT).replace('_', ' '));
	}

	private static String vacioANull(String texto) {
		return texto == null || texto.isBlank() ? null : texto;
	}

	private static String capital(String texto) {
		return texto.isEmpty() ? texto : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
	}
}
