package com.akine.resource.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.application.FranjaFueraDeHorario.AccionSobreFranja;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.HorarioDeSede;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Las franjas de los profesionales de una sede frente a un horario general nuevo (regla del
 * 08/11/2026: el horario del profesional queda dentro del de la sede).
 *
 * <p>Dos pasos, los dos bajo el lock de la sede que ya tomo {@link CalendarioService}:
 * {@link #afectadas} arma el aviso —que franjas quedan afuera y en que quedarian— y
 * {@link #recortar} lo aplica en la misma transaccion que reemplaza el horario.
 *
 * <p>Alcanza con el lock de la sede y no hace falta el de cada persona: recortar solo ACHICA
 * franjas dentro de su propio rango, asi que no puede crear un solapamiento nuevo ni en esta sede
 * ni en otra. Las franjas vencidas no se miran: ya no ofrecen nada. Los turnos que quedan afuera no
 * se cancelan; los informa el impacto del horario, como siempre.
 */
@Component
public class RecorteAlHorarioDeSede {

	/** Motivo de la baja logica de una franja que no tiene parte comun con el horario nuevo. */
	static final String MOTIVO_DE_BAJA = "Fuera del horario general de la sede";

	/** Horizonte de "toda la vigencia futura" para la consulta de memberships con franjas. */
	private static final int ANIOS_HACIA_ADELANTE = 100;

	private final BloqueDisponibilidadRepositoryPort bloques;
	private final DisponibilidadImpactProbe sonda;
	private final AuditTrail auditTrail;

	public RecorteAlHorarioDeSede(
			BloqueDisponibilidadRepositoryPort bloques, DisponibilidadImpactProbe sonda, AuditTrail auditTrail) {
		this.bloques = bloques;
		this.sonda = sonda;
		this.auditTrail = auditTrail;
	}

	/**
	 * Las franjas activas y no vencidas de la sede que el horario {@code nuevo} no contiene enteras,
	 * con la parte que les quedaria. Ordenadas por profesional, dia y hora. Sin horario, ninguna.
	 */
	public List<FranjaFueraDeHorario> afectadas(
			long organizationId, long consultorioId, HorarioDeSede nuevo, LocalDate hoy) {

		if (!nuevo.limita()) {
			return List.of();
		}
		List<Long> memberships = bloques.findMembershipsConBloquesVigentesEn(
				organizationId, consultorioId, hoy, hoy.plusYears(ANIOS_HACIA_ADELANTE));
		if (memberships.isEmpty()) {
			return List.of();
		}
		List<BloqueDisponibilidad> fuera = bloques.findActivosDeLasMemberships(organizationId, memberships).stream()
				.filter(bloque -> bloque.getConsultorioId() == consultorioId)
				.filter(BloqueDisponibilidad::isOperable)
				.filter(bloque -> bloque.getVigenciaHasta() == null || bloque.getVigenciaHasta().isAfter(hoy))
				.filter(bloque -> !nuevo.admite(bloque.getDiaSemana(), bloque.intervalo()))
				.toList();
		if (fuera.isEmpty()) {
			return List.of();
		}

		Set<Long> profesionales = fuera.stream().map(BloqueDisponibilidad::getMembershipId).collect(Collectors.toSet());
		DisponibilidadImpactProbe.NombresDeTurnos nombres = sonda.nombres(organizationId, Set.of(), profesionales);
		Map<Long, String> nombrePorProfesional = nombres == null ? Map.of() : nombres.profesionales();

		return fuera.stream()
				.map(bloque -> new FranjaFueraDeHorario(
						bloque.getId(),
						bloque.getMembershipId(),
						nombrePorProfesional.get(bloque.getMembershipId()),
						bloque.getDiaSemana(),
						bloque.getHoraDesde(),
						bloque.getHoraHasta(),
						bloque.getVigenciaDesde(),
						bloque.getVigenciaHasta(),
						nuevo.recortar(bloque.getDiaSemana(), bloque.intervalo()),
						null))
				.sorted(Comparator.comparing((FranjaFueraDeHorario franja) ->
								franja.profesionalNombre() == null ? "" : franja.profesionalNombre())
						.thenComparingLong(FranjaFueraDeHorario::membershipId)
						.thenComparingInt(FranjaFueraDeHorario::diaSemana)
						.thenComparing(FranjaFueraDeHorario::horaDesde))
				.toList();
	}

	/**
	 * Deja cada franja en su parte comun con el horario de la sede. La primera parte queda en el
	 * mismo bloque —conserva su id y su historia—; si la sede cierra en el medio, cada parte extra es
	 * un bloque nuevo con la misma vigencia. Sin parte comun, baja logica con
	 * {@link #MOTIVO_DE_BAJA}. Cada cambio se audita como cualquier otro cambio de bloque, con
	 * {@code origen = RECORTE_AL_HORARIO_DE_SEDE}.
	 */
	public List<FranjaFueraDeHorario> recortar(
			OperatingActor actor, long organizationId, long consultorioId,
			List<FranjaFueraDeHorario> afectadas, Instant ahora) {

		return afectadas.stream()
				.map(franja -> recortarUna(actor, organizationId, consultorioId, franja, ahora))
				.toList();
	}

	private FranjaFueraDeHorario recortarUna(
			OperatingActor actor, long organizationId, long consultorioId, FranjaFueraDeHorario franja, Instant ahora) {

		BloqueDisponibilidad bloque = bloques.findByIdScoped(franja.bloqueId(), organizationId, consultorioId)
				.orElseThrow();
		String antes = ResumenDeAgendaParaAuditoria.de(bloque);

		if (franja.resultantes().isEmpty()) {
			bloque.deactivate(ahora, MOTIVO_DE_BAJA);
			BloqueDisponibilidad guardado = bloques.save(bloque);
			auditar(actor, AuditEvents.DISPONIBILIDAD_BLOQUE_DEACTIVATED, guardado, "ACTIVO", "INACTIVO",
					MOTIVO_DE_BAJA, ResumenDeAgendaParaAuditoria.con(detalles(), antes, null), ahora);
			return franja.con(AccionSobreFranja.DADA_DE_BAJA);
		}

		IntervaloLocal primera = franja.resultantes().getFirst();
		bloque.updateDatos(null, primera.desde(), primera.hasta(), null, null, false);
		BloqueDisponibilidad guardado = bloques.save(bloque);
		auditar(actor, AuditEvents.DISPONIBILIDAD_BLOQUE_UPDATED, guardado, null, null, null,
				ResumenDeAgendaParaAuditoria.con(detalles(), antes, ResumenDeAgendaParaAuditoria.de(guardado)), ahora);

		for (IntervaloLocal parte : franja.resultantes().subList(1, franja.resultantes().size())) {
			BloqueDisponibilidad nuevo = new BloqueDisponibilidad(
					organizationId, consultorioId, bloque.getMembershipId(), bloque.getDiaSemana(),
					parte.desde(), parte.hasta(), bloque.getVigenciaDesde(), bloque.getVigenciaHasta());
			nuevo.registrarAutor(actor.accountId());
			BloqueDisponibilidad creado = bloques.save(nuevo);
			Map<String, String> detalles = detalles();
			detalles.put("partidoDeBloqueId", String.valueOf(bloque.getId()));
			auditar(actor, AuditEvents.DISPONIBILIDAD_BLOQUE_CREATED, creado, null, "ACTIVO", null,
					ResumenDeAgendaParaAuditoria.con(detalles, null, ResumenDeAgendaParaAuditoria.de(creado)), ahora);
		}
		return franja.con(AccionSobreFranja.RECORTADA);
	}

	private static Map<String, String> detalles() {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("origen", "RECORTE_AL_HORARIO_DE_SEDE");
		return detalles;
	}

	private void auditar(
			OperatingActor actor, String eventType, BloqueDisponibilidad bloque, String previousState,
			String newState, String motivo, Map<String, String> detalles, Instant ahora) {

		auditTrail.record(new AuditEntry(
				bloque.getOrganizationId(),
				bloque.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_BLOQUE_DISPONIBILIDAD,
				bloque.getId(),
				previousState,
				newState,
				AuditEvents.conActor(actor, bloque.getMembershipId(), detalles),
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
