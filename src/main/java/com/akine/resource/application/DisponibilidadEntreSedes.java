package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.problem.ConflictoDeAgenda;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.ActividadReservadaEnConflictoException;
import com.akine.resource.domain.exception.BloqueoConTurnosAfectadosException;
import com.akine.resource.domain.exception.DisponibilidadSuperpuestaException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.spi.ActividadDePersonaProbe;
import com.akine.resource.spi.BloqueoDeAgendaDeProfesional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lo que una excepcion de agenda de un profesional tiene que respetar en las OTRAS sedes de la misma
 * persona (disponibilidad central, contrato 0.158.0).
 *
 * <h2>Apertura</h2>
 *
 * <ol>
 *   <li><b>Actividad reservada en otra sede</b> —un turno o una clase, como principal o adicional— en la
 *       franja de la apertura: <b>siempre 409 {@code recurso-ocupado}</b>. La pregunta es la de la logica
 *       central ({@link ActividadDePersonaProbe}, que contesta {@code scheduling.spi.OcupacionDePersona}).</li>
 *   <li><b>Disponibilidad en otra sede</b> —su horario habitual o una apertura alla, ya recortados por
 *       cierres, feriados y el horario de la sede (la disponibilidad EFECTIVA)—: 409
 *       {@code bloque-solapado} con cada franja, salvo que se pida la sustitucion. Una franja ya cerrada
 *       alla no cuenta, porque la efectiva ya no la tiene.</li>
 *   <li><b>Sustitucion</b>: en la misma transaccion se crea un CIERRE (motivo {@code BLOQUEO}) en la otra
 *       sede por cada franja superpuesta, auditado con su origen. Exige {@code agenda:excepciones} sobre
 *       ese profesional en esa sede (403 si no) y, por el punto 1, que alla no haya nada reservado.</li>
 * </ol>
 *
 * <h2>Cierre</h2>
 *
 * <p>Las clases de esta sede que quedan sin instructor (principal o adicional) se informan junto con
 * los turnos afectados y piden la misma confirmacion ({@code confirmaTurnosAfectados}).
 *
 * <p><b>Todo corre bajo los locks</b>: el de {@code consultorio_calendario} de cada sede que se escribe
 * (por id ascendente) y despues el de la persona ({@code agenda_profesional}). Es el mismo orden sede →
 * persona de los turnos y las clases, asi que una reserva concurrente en la otra sede espera o es
 * esperada, y la segunda ve a la primera en {@code READ_COMMITTED}.
 */
@Component
public class DisponibilidadEntreSedes {

	private final DisponibilidadEfectivaService efectiva;
	private final ObjectProvider<ActividadDePersonaProbe> actividad;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;
	private final DisponibilidadExcepcionRepositoryPort excepciones;
	private final BloqueoDeAgendaDeProfesional agendas;
	private final AuditTrail auditTrail;
	private final BloqueDisponibilidadRepositoryPort bloques;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public DisponibilidadEntreSedes(
			DisponibilidadEfectivaService efectiva,
			ObjectProvider<ActividadDePersonaProbe> actividad,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard,
			DisponibilidadExcepcionRepositoryPort excepciones,
			BloqueoDeAgendaDeProfesional agendas,
			AuditTrail auditTrail,
			BloqueDisponibilidadRepositoryPort bloques) {
		this.efectiva = efectiva;
		this.actividad = actividad;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
		this.excepciones = excepciones;
		this.agendas = agendas;
		this.auditTrail = auditTrail;
		this.bloques = bloques;
	}

	/** La fila de la persona, ANTES de la transaccion de negocio (FilaLockPrevia). */
	void asegurarPersona(long organizationId, long membershipId) {
		agendas.asegurar(organizationId, List.of(membershipId));
	}

	/** Lock de la persona, dentro de la transaccion y DESPUES del de la sede. Devuelve sus memberships. */
	Set<Long> bloquearPersona(long organizationId, long membershipId) {
		return agendas.bloquear(organizationId, membershipId);
	}

	/** Las otras sedes donde atiende la misma persona (sin lock): las que una sustitucion escribe. */
	Set<Long> otrasSedesDeLaPersona(long organizationId, long consultorioId, long membershipId) {
		Set<Long> sedes = new TreeSet<>();
		for (Long otra : agendas.deLaMismaPersona(organizationId, membershipId)) {
			memberships.find(organizationId, otra)
					.map(ConsultorioMembershipSnapshot::consultorioId)
					.filter(sede -> sede != null && sede != consultorioId)
					.ifPresent(sedes::add);
		}
		return sedes;
	}

	// =================================================================================
	// Apertura
	// =================================================================================

	/**
	 * Valida una apertura de profesional contra las otras sedes de la persona y, si se pidio, sustituye.
	 * Bajo los locks. Devuelve los cierres creados por la sustitucion (vacio si no hubo).
	 *
	 * @param apertura ya guardada si se va a sustituir (los cierres la nombran), o la propuesta
	 */
	List<DisponibilidadExcepcion> validarApertura(
			OperatingActor actor, ConsultorioSnapshot sede, DisponibilidadExcepcion apertura, Set<Long> mismaPersona,
			boolean sustituir, Instant ahora) {

		long organizationId = sede.organizationId();
		long membershipId = apertura.getMembershipId();
		List<Instant[]> tramos = tramos(apertura, ZoneId.of(ZonaSede.de(sede).getId()));

		// 1. Lo reservado en otra sede no se sustituye nunca.
		ActividadDePersonaProbe probe = actividad.getIfAvailable();
		if (probe != null) {
			for (Instant[] tramo : tramos) {
				Optional<ConflictoDeAgenda> reservado = probe
						.actividadReservada(organizationId, sede.id(), membershipId, tramo[0], tramo[1]).stream()
						.filter(conflicto -> !conflicto.mismaSede())
						.findFirst();
				if (reservado.isPresent()) {
					throw new ActividadReservadaEnConflictoException(reservado.get());
				}
			}
		}

		// 2. La disponibilidad efectiva de la misma persona en las otras sedes.
		List<Superposicion> superposiciones = superposiciones(
				organizationId, sede.id(), membershipId, mismaPersona, apertura.getFechaDesde(),
				apertura.getFechaHasta(), tramos);
		if (superposiciones.isEmpty()) {
			return List.of();
		}
		if (!sustituir) {
			throw new DisponibilidadSuperpuestaException(superposiciones.stream().map(Superposicion::comoConflicto).toList());
		}

		// 3. Sustitucion explicita: un cierre en la otra sede por cada franja, con permiso alla.
		List<DisponibilidadExcepcion> creados = new ArrayList<>();
		for (Superposicion superposicion : superposiciones) {
			exigirPermisoEnLaOtraSede(actor, organizationId, superposicion);
			creados.add(cerrar(actor, sede, apertura, superposicion, ahora));
		}
		return creados;
	}

	private void exigirPermisoEnLaOtraSede(OperatingActor actor, long organizationId, Superposicion superposicion) {
		try {
			AutorizacionDeSede.exigirPermisoSobreProfesional(permissionGuard, memberships, actor,
					PermissionCodes.AGENDA_EXCEPCIONES, organizationId, superposicion.sede().id(),
					superposicion.membershipId());
		} catch (AccessDeniedException sinPermiso) {
			throw new AccessDeniedException("Para sustituir la disponibilidad hay que poder cerrar la agenda del "
					+ "profesional en la sede " + superposicion.sede().name()
					+ ", y con este perfil no se puede. Pidaselo a quien gestiona esa sede.", sinPermiso);
		}
	}

	private DisponibilidadExcepcion cerrar(
			OperatingActor actor, ConsultorioSnapshot sedeDeLaApertura, DisponibilidadExcepcion apertura,
			Superposicion superposicion, Instant ahora) {

		ZoneId zona = ZoneId.of(ZonaSede.de(superposicion.sede()).getId());
		ZonedDateTime desde = superposicion.desde().atZone(zona);
		ZonedDateTime hasta = superposicion.hasta().atZone(zona);
		LocalDate fecha = desde.toLocalDate();
		LocalTime horaHasta = hasta.toLocalDate().isAfter(fecha) ? LocalTime.MAX : hasta.toLocalTime();
		DisponibilidadExcepcion cierre = new DisponibilidadExcepcion(
				superposicion.sede().organizationId(), superposicion.sede().id(), superposicion.membershipId(),
				TipoExcepcion.CIERRE, MotivoExcepcion.BLOQUEO, fecha, fecha.plusDays(1), desde.toLocalTime(), horaHasta,
				null, recortar(motivoDeSustitucion(sedeDeLaApertura)));
		cierre.registrarAutor(actor.accountId());
		DisponibilidadExcepcion guardado = excepciones.save(cierre);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("alcance", "PROFESIONAL");
		detalles.put("membershipId", String.valueOf(superposicion.membershipId()));
		detalles.put("tipo", TipoExcepcion.CIERRE.name());
		detalles.put("motivo", MotivoExcepcion.BLOQUEO.name());
		detalles.put("origen", "SUSTITUCION_POR_APERTURA");
		detalles.put("aperturaConsultorioId", String.valueOf(sedeDeLaApertura.id()));
		if (apertura.getId() != null) {
			detalles.put("aperturaId", String.valueOf(apertura.getId()));
		}
		detalles.put("fechas", fecha + " -> " + fecha.plusDays(1));
		detalles.put("horario", desde.toLocalTime() + " -> " + horaHasta);
		// El historial lo lee como cualquier cierre ("Cierre por bloqueo, 09/11/2026, 10:00–12:00") y el
		// origen va en castellano en el motivo; las claves de arriba quedan para trazabilidad.
		ResumenDeAgendaParaAuditoria.con(detalles, null, ResumenDeAgendaParaAuditoria.de(guardado));
		auditTrail.record(new AuditEntry(
				guardado.getOrganizationId(), guardado.getConsultorioId(), actor.accountId(),
				AuditEvents.DISPONIBILIDAD_EXCEPCION_CREATED, AuditEvents.ENTITY_EXCEPCION_DISPONIBILIDAD,
				guardado.getId(), null, "ACTIVO",
				AuditEvents.conActor(actor, guardado.getMembershipId(), detalles),
				motivoDeSustitucion(sedeDeLaApertura), AuditEvents.correlationId(), ahora));
		return guardado;
	}

	/** "Sustituida por una apertura en la sede Norte": la nota del cierre y el motivo de su auditoria. */
	private static String motivoDeSustitucion(ConsultorioSnapshot sedeDeLaApertura) {
		return "Sustituida por una apertura en la sede " + sedeDeLaApertura.name();
	}

	private static String recortar(String texto) {
		return texto.length() <= 280 ? texto : texto.substring(0, 280);
	}

	/** Una franja de la apertura que la misma persona ya ofrece en otra sede. */
	record Superposicion(long membershipId, ConsultorioSnapshot sede, Instant desde, Instant hasta) {

		ConflictoDeAgenda comoConflicto() {
			return new ConflictoDeAgenda(ConflictoDeAgenda.Tipo.DISPONIBILIDAD, null, sede.id(), sede.name(), false,
					desde, hasta, ZonaSede.de(sede).getId(), null, null);
		}
	}

	private List<Superposicion> superposiciones(
			long organizationId, long consultorioId, long membershipId, Set<Long> mismaPersona,
			LocalDate fechaDesde, LocalDate fechaHasta, List<Instant[]> tramos) {

		List<Superposicion> superposiciones = new ArrayList<>();
		for (Long otra : mismaPersona) {
			if (otra == membershipId) {
				continue;
			}
			Optional<ConsultorioSnapshot> otraSede = memberships.find(organizationId, otra)
					.map(ConsultorioMembershipSnapshot::consultorioId)
					.filter(sede -> sede != null && sede != consultorioId)
					.flatMap(sede -> consultorios.find(organizationId, sede));
			if (otraSede.isEmpty()) {
				continue;
			}
			// Un dia de margen a cada lado: la zona de la otra sede puede correr la fecha.
			efectiva.sinAutorizar(organizationId, otraSede.get(), otra, fechaDesde.minusDays(1), fechaHasta.plusDays(1))
					.dias().stream()
					.flatMap(dia -> dia.franjas().stream())
					.forEach(franja -> {
						for (Instant[] tramo : tramos) {
							Instant desde = franja.desde().isAfter(tramo[0]) ? franja.desde() : tramo[0];
							Instant hasta = franja.hasta().isBefore(tramo[1]) ? franja.hasta() : tramo[1];
							if (desde.isBefore(hasta)) {
								superposiciones.add(new Superposicion(otra, otraSede.get(), desde, hasta));
							}
						}
					});
		}
		return superposiciones;
	}

	// =================================================================================
	// Cierre
	// =================================================================================

	/**
	 * Las clases de esta sede que el cierre deja sin instructor: las del profesional del cierre (principal
	 * o adicional), o todas si es de sede entera. Vacio sin la sonda.
	 */
	List<BloqueoConTurnosAfectadosException.Clase> clasesAfectadas(
			ConsultorioSnapshot sede, DisponibilidadExcepcion cierre, Instant ahora) {

		ActividadDePersonaProbe probe = actividad.getIfAvailable();
		if (probe == null) {
			return List.of();
		}
		List<BloqueoConTurnosAfectadosException.Clase> clases = new ArrayList<>();
		for (Instant[] tramo : tramos(cierre, ZoneId.of(ZonaSede.de(sede).getId()))) {
			if (!tramo[1].isAfter(ahora)) {
				continue;
			}
			probe.clasesDeLaSede(sede.organizationId(), sede.id(), cierre.getMembershipId(), tramo[0], tramo[1])
					.forEach(clase -> clases.add(new BloqueoConTurnosAfectadosException.Clase(
							clase.claseId(), clase.instructorMembershipId(), clase.inicio(), clase.fin())));
		}
		return clases.stream().distinct().toList();
	}

	// =================================================================================
	// Liberacion: baja o achique de un CIERRE
	// =================================================================================

	/**
	 * Las memberships a las que un CIERRE recorta en su sede: la suya si es de un profesional; si es de la
	 * sede entera, las que tienen horario habitual o una apertura propia ahi en esas fechas. Sin lock:
	 * {@link #bloquearPersonas} la vuelve a calcular bajo el de la sede.
	 */
	Set<Long> recortadasPorElCierre(DisponibilidadExcepcion cierre) {
		if (cierre.getTipo() != TipoExcepcion.CIERRE) {
			return Set.of();
		}
		if (!cierre.esDeSede()) {
			return Set.of(cierre.getMembershipId());
		}
		Set<Long> ids = new TreeSet<>(bloques.findMembershipsConBloquesVigentesEn(cierre.getOrganizationId(),
				cierre.getConsultorioId(), cierre.getFechaDesde(), cierre.getFechaHasta()));
		ids.addAll(excepciones.findMembershipsConAperturasEn(cierre.getOrganizationId(), cierre.getConsultorioId(),
				cierre.getFechaDesde(), cierre.getFechaHasta()));
		return ids;
	}

	/** Las filas de esas personas, ANTES de la transaccion de negocio (FilaLockPrevia). */
	void asegurarPersonas(long organizationId, Set<Long> membershipIds) {
		if (!membershipIds.isEmpty()) {
			agendas.asegurar(organizationId, membershipIds);
		}
	}

	/**
	 * Locks de las personas en orden de cuenta, DESPUES del de la sede (mismo orden sede → persona que el
	 * alta). Devuelve, por membership, todas las de la misma persona en la organizacion.
	 */
	Map<Long, Set<Long>> bloquearPersonas(long organizationId, Set<Long> membershipIds) {
		Map<Long, Set<Long>> personas = new LinkedHashMap<>();
		if (membershipIds.isEmpty()) {
			return personas;
		}
		agendas.bloquearEnOrden(organizationId, membershipIds);
		membershipIds.forEach(id -> personas.put(id, agendas.deLaMismaPersona(organizationId, id)));
		return personas;
	}

	/**
	 * Dar de baja o achicar un CIERRE devuelve disponibilidad: la franja que vuelve a ofrecerse en esta sede
	 * no puede pisar la disponibilidad efectiva (habitual o apertura) de la misma persona en otra sede. Se
	 * llama bajo los locks y DESPUES de escribir el cambio (con flush): la efectiva de esta sede ya es la
	 * nueva, y lo que se mira es lo que de la franja del cierre ANTERIOR quedo ofrecido. Solo el futuro: un
	 * cierre pasado se puede ordenar sin mirar nada.
	 *
	 * @param anterior el cierre como estaba antes de la baja o la edicion
	 * @throws DisponibilidadSuperpuestaException 409 {@code bloque-solapado}, con la sede real y la franja
	 */
	void validarLiberacion(
			ConsultorioSnapshot sede, DisponibilidadExcepcion anterior, Map<Long, Set<Long>> personas, Instant ahora) {

		ZoneId zona = ZoneId.of(ZonaSede.de(sede).getId());
		List<Instant[]> liberados = tramos(anterior, zona).stream()
				.filter(tramo -> tramo[1].isAfter(ahora))
				.map(tramo -> new Instant[] {tramo[0].isAfter(ahora) ? tramo[0] : ahora, tramo[1]})
				.toList();
		if (liberados.isEmpty()) {
			return;
		}
		LocalDate hoy = ahora.atZone(zona).toLocalDate();
		LocalDate desde = anterior.getFechaDesde().isBefore(hoy) ? hoy : anterior.getFechaDesde();
		for (Map.Entry<Long, Set<Long>> persona : personas.entrySet()) {
			List<Instant[]> ofrecidos = new ArrayList<>();
			efectiva.sinAutorizar(sede.organizationId(), sede, persona.getKey(), desde.minusDays(1),
							anterior.getFechaHasta().plusDays(1))
					.dias().stream()
					.flatMap(dia -> dia.franjas().stream())
					.forEach(franja -> {
						for (Instant[] tramo : liberados) {
							Instant inicio = franja.desde().isAfter(tramo[0]) ? franja.desde() : tramo[0];
							Instant fin = franja.hasta().isBefore(tramo[1]) ? franja.hasta() : tramo[1];
							if (inicio.isBefore(fin)) {
								ofrecidos.add(new Instant[] {inicio, fin});
							}
						}
					});
			if (ofrecidos.isEmpty()) {
				continue;
			}
			List<Superposicion> superposiciones = superposiciones(sede.organizationId(), sede.id(), persona.getKey(),
					persona.getValue(), desde, anterior.getFechaHasta(), ofrecidos);
			if (!superposiciones.isEmpty()) {
				throw new DisponibilidadSuperpuestaException(
						superposiciones.stream().map(Superposicion::comoConflicto).toList(),
						DisponibilidadSuperpuestaException.AL_LIBERAR);
			}
		}
	}

	// =================================================================================
	// Tramos
	// =================================================================================

	/** Un tramo por dia de la excepcion, en instantes de la sede. Sin hora: el dia entero. */
	static List<Instant[]> tramos(DisponibilidadExcepcion excepcion, ZoneId zona) {
		List<Instant[]> tramos = new ArrayList<>();
		for (LocalDate dia = excepcion.getFechaDesde(); dia.isBefore(excepcion.getFechaHasta()); dia = dia.plusDays(1)) {
			Instant desde = excepcion.getHoraDesde() == null
					? dia.atStartOfDay(zona).toInstant()
					: dia.atTime(excepcion.getHoraDesde()).atZone(zona).toInstant();
			Instant hasta = excepcion.getHoraHasta() == null || excepcion.getHoraHasta().equals(LocalTime.MAX)
					|| excepcion.getHoraHasta().equals(LocalTime.MIDNIGHT)
					? dia.plusDays(1).atStartOfDay(zona).toInstant()
					: dia.atTime(excepcion.getHoraHasta()).atZone(zona).toInstant();
			tramos.add(new Instant[] {desde, hasta});
		}
		return tramos;
	}
}
