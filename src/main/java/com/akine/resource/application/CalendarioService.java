package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.FranjaHorarioGeneral;
import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.HorarioGeneralRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Politica de calendario de una sede y calendario nacional de feriados (M05, RF-M05-004).
 *
 * <h2>Por que un feriado nacional no cierra el centro por si solo</h2>
 *
 * <p>{@code feriado} es una tabla GLOBAL y sin dueno (ADR-0022): es un hecho del calendario, no
 * una decision operativa. La decision de cada sede —cerrar o no ese dia— vive en
 * {@code consultorio_calendario}, y es esta clase la que la lee y la escribe. Un centro de guardia
 * atiende los feriados y el modelo tiene que poder decirlo.
 *
 * <h2>La fila de politica se crea a demanda, y nunca se borra</h2>
 *
 * <p>Una sede que nunca fue editada no tiene fila: {@link #ver} devuelve los valores por defecto
 * de V23 sin escribir nada. La fila aparece en la primera EDICION —o en el primer write de
 * disponibilidad de la sede, que la crea para poder bloquearla—.
 *
 * <p><b>Esa fila es tambien la que serializa los writes de disponibilidad de la sede</b>: es
 * sobre ella que {@code DisponibilidadService} y {@code ExcepcionService} toman su
 * {@code FOR UPDATE}. Por eso esta clase no ofrece ninguna baja: borrarla no dejaria a la sede
 * "sin politica", dejaria a los writes de esa sede sin punto de serializacion, y el sintoma
 * aparecerian meses despues como dos bloques solapados que nadie sabe como entraron.
 *
 * <h2>Autorizacion</h2>
 *
 * <p>Lectura {@code colaborador:read}, edicion {@code consultorio:manage}. Los mismos dos codigos
 * que el resto de M05, sin inventar ninguno.
 */
@Service
public class CalendarioService {

	private static final Logger log = LoggerFactory.getLogger(CalendarioService.class);

	private final CalendarioSedeRepositoryPort calendarios;
	private final CalendarioSedeIniciador calendarioIniciador;
	private final FeriadoRepositoryPort feriados;
	private final ConsultorioDirectory consultorioDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final HorarioGeneralRepositoryPort horarios;

	public CalendarioService(
			CalendarioSedeRepositoryPort calendarios,
			CalendarioSedeIniciador calendarioIniciador,
			FeriadoRepositoryPort feriados,
			ConsultorioDirectory consultorioDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			HorarioGeneralRepositoryPort horarios) {

		this.calendarios = calendarios;
		this.calendarioIniciador = calendarioIniciador;
		this.feriados = feriados;
		this.consultorioDirectory = consultorioDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.horarios = horarios;
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	/**
	 * La politica de la sede y los feriados de su pais que caen en {@code [desde, hasta)}.
	 *
	 * <p><b>No crea la fila de politica.</b> Un GET que escribe convierte una consulta en una
	 * mutacion que nadie pidio y no puede ejecutarse en una transaccion de solo lectura; la sede
	 * sin fila lee sus valores por defecto y {@link CalendarioView#existePersistida()} lo dice.
	 *
	 * <p>El extremo superior de la busqueda de feriados se resta un dia porque
	 * {@code findByPaisAndFechaBetween} es INCLUSIVA en los dos extremos y esta ventana, como toda
	 * la de la etapa, es {@code [desde, hasta)}.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws com.akine.resource.domain.exception.VentanaDemasiadoAmpliaException si la ventana
	 *         supera el tope consultable (400)
	 * @throws IllegalArgumentException si la ventana esta invertida o vacia (400)
	 */
	@Transactional(readOnly = true)
	public CalendarioView ver(
			OperatingActor actor, long consultorioId, LocalDate desde, LocalDate hasta) {

		long organizationId = exigirLectura(actor, consultorioId);
		VentanaConsultable.exigirValida(desde, hasta);

		Optional<CalendarioSede> politica = calendarios.findByScope(organizationId, consultorioId);
		String pais = politica.map(CalendarioSede::getPais).orElse(CalendarioSede.PAIS_POR_DEFECTO);

		List<FeriadoView> delPeriodo =
				feriados.findByPaisAndFechaBetween(pais, desde, hasta.minusDays(1)).stream()
						.map(FeriadoView::de)
						.toList();

		List<Franja> horario = horarioVigente(organizationId, consultorioId);

		return politica
				.map(fila -> CalendarioView.de(fila, delPeriodo, horario))
				.orElseGet(() -> CalendarioView.porDefecto(consultorioId, delPeriodo, horario));
	}

	// =================================================================================
	// Edicion
	// =================================================================================

	/**
	 * Edita la politica de calendario de la sede, creando la fila a demanda con los valores por
	 * defecto si es la primera vez.
	 *
	 * <p>Semantica de PATCH: cada valor {@code null} deja el campo como estaba. Un
	 * {@code cierraPorFeriado} nulo no es {@code false} —ese es el error que convierte "no toques
	 * la politica" en "abri todos los feriados"— y por eso viaja como {@link Boolean}.
	 *
	 * <p><b>No exige {@code expectedVersion}</b>, a diferencia de la edicion de un bloque. La fila
	 * se crea a demanda: un cliente que nunca la vio no tiene ninguna version que mandar, y
	 * exigirsela le impediria su primera edicion. Lo que serializa el acceso es el
	 * {@code FOR UPDATE} sobre esta misma fila, tomado antes de leerla. La contrapartida, dicha
	 * para que no sorprenda: dos administradores que editan la politica a la vez no producen 409;
	 * gana el segundo, y la auditoria conserva los dos cambios con su autor.
	 *
	 * <p>Se audita aunque sea un solo flag: apagar {@code cierraPorFeriado} abre de golpe todos
	 * los feriados del calendario para TODOS los profesionales de la sede, hacia adelante y hacia
	 * atras. Un cambio con ese alcance tiene que tener autor y fecha.
	 *
	 * @throws ConsultorioNotAccessibleException si la sede no existe o es de otro tenant (404)
	 * @throws IllegalArgumentException si el pais viene vacio (400)
	 */
	// La misma transaccion declarada aca: la delegacion es autoinvocacion y no pasa por el proxy.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CalendarioView actualizar(
			OperatingActor actor, long consultorioId, String pais, Boolean cierraPorFeriado) {
		return actualizar(actor, consultorioId, pais, cierraPorFeriado, null);
	}

	/**
	 * Igual que {@link #actualizar(OperatingActor, long, String, Boolean)}, y ademas reemplaza el
	 * horario general de la sede cuando {@code horarioGeneral} no es {@code null} (RF-M03-003).
	 *
	 * <p>{@code null} deja el horario como estaba; una lista vacia lo borra. El reemplazo da de
	 * baja logica las franjas vigentes e inserta las nuevas bajo el mismo {@code FOR UPDATE} de
	 * la politica: dos ediciones concurrentes no mezclan franjas de una y de otra.
	 *
	 * @throws IllegalArgumentException si dos franjas del mismo dia se solapan (400)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CalendarioView actualizar(
			OperatingActor actor,
			long consultorioId,
			String pais,
			Boolean cierraPorFeriado,
			List<Franja> horarioGeneral) {

		long organizationId = exigirContextoDeLaSede(actor, consultorioId);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirGestion(actor, organizationId, consultorioId);
		// Antes del lock: un horario invalido no tiene por que esperar a nadie.
		List<Franja> nuevoHorario = horarioGeneral == null
				? null
				: FranjaHorarioGeneral.validarHorario(horarioGeneral);

		Instant ahora = Instant.now();
		CalendarioSede politica = bloquearOCrear(organizationId, consultorioId);

		Map<String, String> detalles = cambios(politica, pais, cierraPorFeriado);
		politica.actualizarPolitica(pais, cierraPorFeriado);
		CalendarioSede guardada = calendarios.save(politica);

		List<Franja> horario = nuevoHorario == null
				? horarioVigente(organizationId, consultorioId)
				: reemplazarHorario(organizationId, consultorioId, actor.accountId(),
						nuevoHorario, ahora);

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				AuditEvents.CALENDARIO_SEDE_UPDATED,
				AuditEvents.ENTITY_CALENDARIO_SEDE,
				guardada.getId(),
				null,
				null,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));

		log.info("Politica de calendario actualizada: consultorioId={} cierraPorFeriado={}",
				consultorioId, guardada.isCierraPorFeriado());

		// Sin ventana no hay feriados que devolver: ver CalendarioView.
		return CalendarioView.de(guardada, List.of(), horario);
	}

	// =================================================================================
	// Horario general en el alta de la sede (RF-M03-002)
	// =================================================================================

	/**
	 * Fija el horario general de una sede que se esta dando de alta, dentro de la transaccion
	 * del alta (A-8, CA-M03-002).
	 *
	 * <p><b>No evalua permisos ni toma el lock de la sede, y no es un descuido.</b> Lo llama
	 * {@code organization} a traves de {@code organization.spi.AltaDeSedeExtension}, despues de
	 * exigir {@code consultorio:manage} con alcance organizacion —mas fuerte que el de sede— en
	 * la misma transaccion. La sede todavia no esta commiteada: nadie mas la ve, asi que no hay
	 * con quien serializar, y crear la fila-lock en una transaccion aparte seria peor que
	 * inutil: el INSERT de {@code consultorio_calendario} esperaria por la FK a la fila de
	 * {@code consultorio} que esta misma transaccion tiene bloqueada.
	 *
	 * <p>Un horario invalido lanza y revierte el alta entera, sede incluida.
	 *
	 * @throws IllegalArgumentException si el horario no es coherente (400)
	 */
	@Transactional
	public List<Franja> fijarHorarioDeSedeNueva(
			long organizationId, long consultorioId, long accountId, List<Franja> horarioGeneral) {

		List<Franja> horario = FranjaHorarioGeneral.validarHorario(horarioGeneral);
		if (horario.isEmpty()) {
			return horario;
		}
		return reemplazarHorario(organizationId, consultorioId, accountId, horario, Instant.now());
	}

	/**
	 * Da de baja logica las franjas vigentes, inserta las nuevas y audita el antes y el despues.
	 * El llamador ya valido el horario y, si la sede existia, tomo su lock.
	 */
	private List<Franja> reemplazarHorario(
			long organizationId,
			long consultorioId,
			long accountId,
			List<Franja> nuevo,
			Instant ahora) {

		List<FranjaHorarioGeneral> vigentes = horarios.findVigentes(organizationId, consultorioId);
		List<Franja> anterior = vigentes.stream().map(FranjaHorarioGeneral::franja).toList();
		if (anterior.equals(nuevo)) {
			return anterior;
		}

		for (FranjaHorarioGeneral vigente : vigentes) {
			vigente.reemplazar(ahora);
			horarios.save(vigente);
		}
		for (Franja franja : nuevo) {
			horarios.save(new FranjaHorarioGeneral(organizationId, consultorioId, franja));
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("horarioGeneral", describir(anterior) + " -> " + describir(nuevo));
		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				accountId,
				AuditEvents.HORARIO_GENERAL_UPDATED,
				AuditEvents.ENTITY_HORARIO_GENERAL,
				consultorioId,
				null,
				null,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));

		log.info("Horario general de la sede reemplazado: consultorioId={} franjas={}",
				consultorioId, nuevo.size());
		return nuevo;
	}

	private List<Franja> horarioVigente(long organizationId, long consultorioId) {
		return horarios.findVigentes(organizationId, consultorioId).stream()
				.map(FranjaHorarioGeneral::franja)
				.toList();
	}

	/** {@code "[1 09:00-13:00, 1 14:00-18:00]"}: legible en la auditoria, sin datos sensibles. */
	private static String describir(List<Franja> horario) {
		return horario.stream()
				.map(f -> f.diaSemana() + " " + hora(f.horaDesde()) + "-" + hora(f.horaHasta()))
				.collect(Collectors.joining(", ", "[", "]"));
	}

	private static String hora(LocalTime hora) {
		return LocalTime.MAX.equals(hora) ? "24:00" : hora.toString();
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	/**
	 * Asegura la fila de politica en una transaccion aparte, toma el {@code FOR UPDATE} sobre
	 * ella y la devuelve para editarla. Ver {@link BloqueoDeSede}: es el mismo lock que serializa
	 * los writes de disponibilidad de la sede, y es a proposito que sea el mismo.
	 *
	 * <p>El alta ya no ocurre dentro de esta transaccion. Dos primeras ediciones concurrentes de
	 * la politica de la misma sede insertaban las dos y se mataban con un deadlock; ver
	 * {@link CalendarioSedeIniciador}.
	 */
	private CalendarioSede bloquearOCrear(long organizationId, long consultorioId) {
		calendarioIniciador.asegurar(organizationId, consultorioId);
		return BloqueoDeSede.tomar(calendarios, organizationId, consultorioId);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	private long exigirLectura(OperatingActor actor, long consultorioId) {
		long organizationId = AutorizacionDeSede.exigirContexto(actor, "Lectura de calendario");
		exigirSedeDelTenant(organizationId, consultorioId);

		AutorizacionDeSede.exigirPermiso(permissionGuard, actor,
				PermissionCodes.COLABORADOR_READ, organizationId, consultorioId);
		return organizationId;
	}

	/** Ver {@link AutorizacionDeSede#exigirContextoDeLaSede}. */
	private static long exigirContextoDeLaSede(OperatingActor actor, long consultorioId) {
		return AutorizacionDeSede.exigirContextoDeLaSede(
				actor, consultorioId, "Mutacion de calendario");
	}

	private void exigirGestion(OperatingActor actor, long organizationId, long consultorioId) {
		AutorizacionDeSede.exigirPermiso(permissionGuard, actor,
				PermissionCodes.CONSULTORIO_MANAGE, organizationId, consultorioId);
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		AutorizacionDeSede.exigirSedeDelTenant(consultorioDirectory, organizationId, consultorioId);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private static Map<String, String> cambios(
			CalendarioSede politica, String pais, Boolean cierraPorFeriado) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (pais != null && !pais.strip().equals(politica.getPais())) {
			detalles.put("pais", politica.getPais() + " -> " + pais.strip());
		}
		if (cierraPorFeriado != null && cierraPorFeriado != politica.isCierraPorFeriado()) {
			detalles.put("cierraPorFeriado",
					politica.isCierraPorFeriado() + " -> " + cierraPorFeriado);
		}
		return detalles;
	}
}
