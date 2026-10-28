package com.akine.activity.application;

import com.akine.activity.application.HonorariosViews.BorradorDeHonorariosView;
import com.akine.activity.application.HonorariosViews.ClaseExcluidaView;
import com.akine.activity.application.HonorariosViews.ConfirmarLiquidacionCommand;
import com.akine.activity.application.HonorariosViews.LineaDeHonorarioView;
import com.akine.activity.application.HonorariosViews.LiquidacionDeHonorariosView;
import com.akine.activity.application.HonorariosViews.ResultadoDeConfirmacion;
import com.akine.activity.domain.ClaseInstructor;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.RepartoDeHonorario;
import com.akine.activity.domain.exception.BorradorDeHonorariosDesactualizadoException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.LiquidacionDeHonorariosNoAccesibleException;
import com.akine.activity.domain.exception.LiquidacionDeHonorariosVaciaException;
import com.akine.activity.domain.port.HonorariosRepositoryPorts.BaseDeHonorariosPort;
import com.akine.activity.domain.port.HonorariosRepositoryPorts.ClaseInstructorRepositoryPort;
import com.akine.billing.spi.LiquidacionesDeHonorarios;
import com.akine.billing.spi.LiquidacionesDeHonorarios.ClaseLiquidada;
import com.akine.billing.spi.LiquidacionesDeHonorarios.Liquidacion;
import com.akine.billing.spi.LiquidacionesDeHonorarios.LiquidacionAConfirmar;
import com.akine.offering.spi.HonorariosDeClase;
import com.akine.offering.spi.HonorariosDeClase.HonorarioDeClase;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Honorarios por clase: el borrador por periodo y colaborador, y su confirmacion como egreso
 * (AKINE-08.09b, DP-38, RF-M22-006, RF-M22-007).
 *
 * <h2>El calculo</h2>
 *
 * <pre>
 *   clases REALIZADA de la sede, inicio en el periodo (dias locales), donde el colaborador es
 *   el principal o un adicional vigente
 *     total de la clase = tarifa vigente el dia local x presentes (PRESENTE + PRESENTE_TARDE)
 *     parte             = partes iguales; el centavo sobrante, al primero (RepartoDeHonorario)
 * </pre>
 *
 * <p>Quedan afuera, con motivo: las ya liquidadas para este colaborador, las que no tienen tarifa
 * ese dia, las sin presentes y las cuya parte redondea a cero.
 *
 * <h2>Confirmar, y por que se bloquean las clases</h2>
 *
 * <p>Dos administradores que confirman a la vez la misma liquidacion calcularian la misma base y
 * los dos insertarian. Por eso confirmar toma las clases candidatas {@code FOR UPDATE} en orden de
 * id <b>antes</b> de mirar la clave de idempotencia y lo ya liquidado: el segundo espera, relee
 * en {@code READ_COMMITTED} y recibe 409 (o la misma liquidacion si traia la misma clave). El
 * unique de {@code liquidacion_honorario_clase} queda como cinturon.
 *
 * <p>Esta clase lee asistencias y no las toca, ni a ninguna sesion u obligacion (RN-M22-003).
 */
@Service
public class HonorariosDeClasesService {

	/** Mismo tope que el listado de clases: una liquidacion es mensual, no anual. */
	static final int PERIODO_MAXIMO_DIAS = 62;

	static final String YA_LIQUIDADA = "YA_LIQUIDADA";
	static final String SIN_HONORARIO = "SIN_HONORARIO";
	static final String SIN_PRESENTES = "SIN_PRESENTES";
	static final String SIN_IMPORTE = "SIN_IMPORTE";

	private final BaseDeHonorariosPort base;
	private final ClaseInstructorRepositoryPort instructores;
	private final HonorariosDeClase honorarios;
	private final OfertaDirectory ofertas;
	private final LiquidacionesDeHonorarios liquidaciones;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final SoporteEnActividad soporte;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public HonorariosDeClasesService(
			BaseDeHonorariosPort base,
			ClaseInstructorRepositoryPort instructores,
			HonorariosDeClase honorarios,
			OfertaDirectory ofertas,
			LiquidacionesDeHonorarios liquidaciones,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			SoporteEnActividad soporte) {

		this.base = base;
		this.instructores = instructores;
		this.honorarios = honorarios;
		this.ofertas = ofertas;
		this.liquidaciones = liquidaciones;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.soporte = soporte;
	}

	/** El borrador, calculado al pedirlo. No escribe nada. Exige {@code caja:operate}. */
	@Transactional(readOnly = true)
	public BorradorDeHonorariosView borrador(
			OperatingActor actor, long consultorioId, long colaboradorMembershipId,
			LocalDate desde, LocalDate hasta, Long ofertaId) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSede(organizationId, consultorioId);
		exigirCaja(actor, organizationId, consultorioId);
		exigirPeriodo(desde, hasta);

		ZoneId zona = ZonaDeSede.de(sede);
		List<Object[]> filas = base.clasesDictadas(organizationId, consultorioId, colaboradorMembershipId,
				inicio(desde, zona), inicio(hasta.plusDays(1), zona));
		return calcular(organizationId, consultorioId, zona, colaboradorMembershipId, desde, hasta, ofertaId, filas);
	}

	/**
	 * Confirma el borrador: crea el egreso {@code BORRADOR} de M22 y la liquidacion con su
	 * snapshot. Idempotente por {@code idempotencyKey}.
	 *
	 * @throws BorradorDeHonorariosDesactualizadoException el recalculo no es lo que se miro (409)
	 * @throws LiquidacionDeHonorariosVaciaException       no hay nada que liquidar (409)
	 * @throws IdempotencyKeyConflictException             misma clave, otro pedido (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeConfirmacion confirmar(
			OperatingActor actor, long consultorioId, ConfirmarLiquidacionCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSede(organizationId, consultorioId);
		exigirCaja(actor, organizationId, consultorioId);
		exigirPeriodo(command.desde(), command.hasta());

		// 1. LAS CLASES, FOR UPDATE Y EN ORDEN DE ID, antes de mirar nada mas. Ver la cabecera.
		ZoneId zona = ZonaDeSede.de(sede);
		List<Object[]> filas = base.clasesDictadasParaLiquidar(
				organizationId, consultorioId, command.colaboradorMembershipId(),
				inicio(command.desde(), zona), inicio(command.hasta().plusDays(1), zona));

		// 2. La clave, bajo el lock: dos reintentos simultaneos no crean dos liquidaciones.
		String huella = command.huella(consultorioId);
		if (command.idempotencyKey() != null) {
			Liquidacion existente = liquidaciones
					.porIdempotencyKey(organizationId, command.idempotencyKey())
					.orElse(null);
			if (existente != null) {
				if (!huella.equals(existente.requestHash())) {
					throw new IdempotencyKeyConflictException(command.idempotencyKey());
				}
				return new ResultadoDeConfirmacion(vista(existente), false);
			}
		}

		// 3. Recalcular y comparar con lo que se miro.
		BorradorDeHonorariosView borrador = calcular(
				organizationId, consultorioId, zona, command.colaboradorMembershipId(),
				command.desde(), command.hasta(), command.ofertaId(), filas);
		List<Long> actuales = borrador.clases().stream().map(LineaDeHonorarioView::claseId).toList();
		if (actuales.isEmpty()) {
			throw new LiquidacionDeHonorariosVaciaException();
		}
		// Una liquidacion es de UNA oferta: su egreso se imputa a ella (DP-39) y entra solo en la
		// rentabilidad por servicio. Un borrador que mezcla ofertas se confirma por partes.
		List<Long> ofertasDelBorrador = borrador.clases().stream()
				.map(LineaDeHonorarioView::ofertaId).distinct().toList();
		if (ofertasDelBorrador.size() > 1) {
			throw new IllegalArgumentException("El borrador mezcla las ofertas " + ofertasDelBorrador
					+ ": se confirma una liquidacion por oferta (ofertaId), porque su egreso se imputa a ella");
		}
		if (!new TreeSet<>(actuales).equals(new TreeSet<>(command.claseIds()))
				|| borrador.importeTotal().compareTo(command.importeTotal()) != 0) {
			throw new BorradorDeHonorariosDesactualizadoException(borrador.importeTotal(), actuales);
		}

		String concepto = command.concepto() == null || command.concepto().isBlank()
				? "Honorarios por clase del " + command.desde() + " al " + command.hasta()
				: command.concepto().strip();

		// 4. billing: egreso + liquidacion + detalle, en esta misma transaccion.
		Liquidacion liquidacion = liquidaciones.confirmar(new LiquidacionAConfirmar(
				organizationId, consultorioId, command.colaboradorMembershipId(), ofertasDelBorrador.get(0),
				command.desde(), command.hasta(), concepto,
				borrador.importeTotal(), borrador.moneda(),
				command.comprobanteTipo(), command.comprobanteNumero(), command.comprobanteFecha(),
				actor.accountId(),
				command.idempotencyKey(), command.idempotencyKey() == null ? null : huella,
				borrador.clases().stream().map(HonorariosDeClasesService::aSnapshot).toList()));
		return new ResultadoDeConfirmacion(vista(liquidacion), true);
	}

	/** Una liquidacion confirmada de la sede, con su snapshot. Exige {@code caja:operate}. */
	@Transactional(readOnly = true)
	public LiquidacionDeHonorariosView ver(OperatingActor actor, long consultorioId, long liquidacionId) {
		long organizationId = exigirContexto(actor);
		exigirSede(organizationId, consultorioId);
		exigirCaja(actor, organizationId, consultorioId);
		return liquidaciones.ver(organizationId, consultorioId, liquidacionId)
				.map(HonorariosDeClasesService::vista)
				.orElseThrow(() -> new LiquidacionDeHonorariosNoAccesibleException(liquidacionId));
	}

	// =================================================================================
	// El calculo
	// =================================================================================

	@SuppressWarnings("checkstyle:ParameterNumber")
	private BorradorDeHonorariosView calcular(
			long organizationId, long consultorioId, ZoneId zona, long colaborador,
			LocalDate desde, LocalDate hasta, Long ofertaId, List<Object[]> filas) {

		List<ClaseDictada> dictadas = filas.stream()
				.map(ClaseDictada::de)
				.filter(c -> ofertaId == null || c.ofertaId() == ofertaId)
				.sorted(Comparator.comparing(ClaseDictada::inicio).thenComparing(ClaseDictada::id))
				.toList();
		if (dictadas.isEmpty()) {
			return new BorradorDeHonorariosView(
					consultorioId, colaborador, desde, hasta, BigDecimal.ZERO.setScale(2), null, List.of(), List.of());
		}

		Set<Long> claseIds = new LinkedHashSet<>();
		Set<Long> ofertaIds = new LinkedHashSet<>();
		dictadas.forEach(c -> {
			claseIds.add(c.id());
			ofertaIds.add(c.ofertaId());
		});

		Map<Long, List<Long>> adicionales = new HashMap<>();
		for (ClaseInstructor i : instructores.vigentesDeLasClases(organizationId, claseIds)) {
			adicionales.computeIfAbsent(i.getClaseId(), k -> new ArrayList<>()).add(i.getMembershipId());
		}
		Map<Long, Integer> presentes = new HashMap<>();
		for (Object[] fila : base.presentesPorClase(organizationId, claseIds)) {
			presentes.put(((Number) fila[0]).longValue(), ((Number) fila[1]).intValue());
		}
		Map<Long, List<HonorarioDeClase>> tarifas = new HashMap<>();
		for (HonorarioDeClase tarifa : honorarios.activosDe(organizationId, ofertaIds)) {
			tarifas.computeIfAbsent(tarifa.ofertaId(), k -> new ArrayList<>()).add(tarifa);
		}
		Map<Long, OfertaSnapshot> nombres = ofertas.findTodas(organizationId, consultorioId, ofertaIds);
		Map<Long, Long> liquidadas = liquidaciones.clasesLiquidadas(organizationId, colaborador, claseIds);

		List<LineaDeHonorarioView> lineas = new ArrayList<>();
		List<ClaseExcluidaView> excluidas = new ArrayList<>();
		for (ClaseDictada clase : dictadas) {
			LocalDate fecha = clase.inicio().atZone(zona).toLocalDate();
			OfertaSnapshot oferta = nombres.get(clase.ofertaId());
			String ofertaNombre = oferta == null ? "Oferta " + clase.ofertaId() : oferta.nombreComercial();

			Long egresoId = liquidadas.get(clase.id());
			HonorarioDeClase tarifa = tarifas.getOrDefault(clase.ofertaId(), List.of()).stream()
					.filter(t -> t.aplicaEl(fecha))
					.findFirst()
					.orElse(null);
			int presentesDeLaClase = presentes.getOrDefault(clase.id(), 0);

			String motivo = null;
			BigDecimal importeClase = null;
			BigDecimal parte = null;
			List<Long> orden = ordenEstable(clase.principal(), adicionales.getOrDefault(clase.id(), List.of()));
			int posicion = orden.indexOf(colaborador) + 1;
			if (egresoId != null) {
				motivo = YA_LIQUIDADA;
			} else if (tarifa == null) {
				motivo = SIN_HONORARIO;
			} else if (presentesDeLaClase == 0) {
				motivo = SIN_PRESENTES;
			} else {
				importeClase = RepartoDeHonorario.totalDeLaClase(tarifa.importePorParticipante(), presentesDeLaClase);
				parte = RepartoDeHonorario.parte(importeClase, orden.size(), posicion);
				if (parte.signum() <= 0) {
					motivo = SIN_IMPORTE;
				}
			}

			if (motivo != null) {
				excluidas.add(new ClaseExcluidaView(clase.id(), clase.inicio(), fecha, clase.titulo(),
						clase.ofertaId(), ofertaNombre, motivo, egresoId));
			} else {
				lineas.add(new LineaDeHonorarioView(clase.id(), clase.inicio(), fecha, clase.titulo(),
						clase.ofertaId(), ofertaNombre, presentesDeLaClase, tarifa.id(),
						tarifa.importePorParticipante(), importeClase, orden.size(), posicion, parte,
						tarifa.moneda()));
			}
		}

		Set<String> monedas = new HashSet<>();
		lineas.forEach(l -> monedas.add(l.moneda()));
		if (monedas.size() > 1) {
			throw new IllegalArgumentException("Las clases del periodo tienen honorarios en monedas distintas "
					+ monedas + ": un egreso tiene una sola moneda, acota el periodo");
		}
		BigDecimal total = lineas.stream()
				.map(LineaDeHonorarioView::importeColaborador)
				.reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

		return new BorradorDeHonorariosView(consultorioId, colaborador, desde, hasta, total,
				monedas.isEmpty() ? null : monedas.iterator().next(), lineas, excluidas);
	}

	/** El principal primero y despues los adicionales por membership ascendente, sin repetir. */
	static List<Long> ordenEstable(Long principal, List<Long> adicionales) {
		List<Long> orden = new ArrayList<>();
		if (principal != null) {
			orden.add(principal);
		}
		adicionales.stream().sorted().filter(id -> !orden.contains(id)).forEach(orden::add);
		return orden;
	}

	private static ClaseLiquidada aSnapshot(LineaDeHonorarioView l) {
		return new ClaseLiquidada(l.claseId(), l.inicio(), l.fecha(), l.titulo(), l.ofertaId(),
				l.ofertaNombre(), l.presentes(), l.honorarioId(), l.importePorParticipante(),
				l.importeClase(), l.instructores(), l.posicion(), l.importeColaborador(), l.moneda());
	}

	private static LiquidacionDeHonorariosView vista(Liquidacion l) {
		return new LiquidacionDeHonorariosView(
				l.id(), l.consultorioId(), l.egresoId(), l.egresoEstado(), l.egresoSaldoPendiente(),
				l.colaboradorMembershipId(),
				l.colaboradorNombre(), l.ofertaId(), l.periodoDesde(), l.periodoHasta(), l.importeTotal(), l.moneda(),
				l.estado(), l.confirmadaEn(), l.confirmadaPorCuentaId(), l.liberadaEn(),
				l.clases().stream()
						.map(c -> new LineaDeHonorarioView(c.claseId(), c.claseInicio(), c.claseFecha(),
								c.claseTitulo(), c.ofertaId(), c.ofertaNombre(), c.presentes(),
								c.honorarioId(), c.importePorParticipante(), c.importeClase(),
								c.instructores(), c.posicion(), c.importeColaborador(), c.moneda()))
						.toList());
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/** Una fila de {@code clasesDictadas}: {@code [id, oferta_id, inicio_us, titulo, principal]}. */
	private record ClaseDictada(long id, long ofertaId, Instant inicio, String titulo, Long principal) {

		static ClaseDictada de(Object[] fila) {
			long micros = ((Number) fila[2]).longValue();
			return new ClaseDictada(
					((Number) fila[0]).longValue(),
					((Number) fila[1]).longValue(),
					Instant.EPOCH.plus(micros, ChronoUnit.MICROS),
					(String) fila[3],
					fila[4] == null ? null : ((Number) fila[4]).longValue());
		}
	}

	private static Instant inicio(LocalDate dia, ZoneId zona) {
		return dia.atStartOfDay(zona).toInstant();
	}

	private static void exigirPeriodo(LocalDate desde, LocalDate hasta) {
		if (desde == null || hasta == null) {
			throw new IllegalArgumentException("El periodo necesita desde y hasta");
		}
		if (desde.isAfter(hasta)) {
			throw new IllegalArgumentException("El periodo empieza despues de terminar");
		}
		if (ChronoUnit.DAYS.between(desde, hasta) >= PERIODO_MAXIMO_DIAS) {
			throw new IllegalArgumentException(
					"Un periodo de liquidacion admite hasta " + PERIODO_MAXIMO_DIAS + " dias");
		}
	}

	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("Los honorarios requieren un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private ConsultorioSnapshot exigirSede(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirCaja(OperatingActor actor, long organizationId, long consultorioId) {
		soporte.registrarSiCorresponde(permissionGuard.requirePermission(new PermissionQuery(
						actor.accountId(), PermissionCodes.CAJA_OPERATE, organizationId, consultorioId,
						null, Instant.now())),
				actor.accountId(), organizationId, consultorioId, PermissionCodes.CAJA_OPERATE);
	}
}
