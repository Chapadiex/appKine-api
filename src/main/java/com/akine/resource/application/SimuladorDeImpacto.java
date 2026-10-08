package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.resource.application.ImpactoDeDisponibilidad.TurnoAfectado;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.DiaCalculado;
import com.akine.resource.domain.DisponibilidadEfectivaCalculator;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.Feriado;
import com.akine.resource.domain.FranjaEfectiva;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.FeriadoRepositoryPort;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import com.akine.resource.spi.DisponibilidadImpactProbe.TurnoPendiente;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Que turnos pendientes deja afuera un cambio de disponibilidad, <b>sin aplicarlo</b> (A-11,
 * RN-M05-004).
 *
 * <h2>Como lo calcula</h2>
 *
 * <ol>
 *   <li>Le pide a {@link DisponibilidadImpactProbe} los turnos pendientes de la ventana —de un
 *       profesional o, para una excepcion de sede, de todos—. Sin turnos, no lee una regla.</li>
 *   <li>Por cada profesional con turnos, lee sus bloques y excepciones de la ventana, arma la
 *       version POSTERIOR aplicando el cambio <b>en memoria</b> sobre copias, y corre
 *       {@link DisponibilidadEfectivaCalculator} dos veces: antes y despues.</li>
 *   <li>Un turno queda afectado si la disponibilidad de antes lo cubria entero y la de despues
 *       no.</li>
 * </ol>
 *
 * <p>Usa el mismo calculador que la disponibilidad efectiva y el motor de slots, asi que la
 * respuesta es coherente con lo que la agenda va a ofrecer despues del cambio. Lo que NO mira es el
 * vinculo del profesional: un turno de alguien desvinculado ya esta en conflicto por la
 * desvinculacion —lo informa otra sonda— y no por este cambio.
 *
 * <h2>Nunca muta una entidad gestionada</h2>
 *
 * <p>Los bloques y excepciones que lee el repositorio son entidades de la sesion JPA, y la edicion
 * real las va a mutar despues en la misma transaccion. El cambio se simula sobre <b>copias
 * transitorias</b>; tocar la entidad leida dejaria un UPDATE pendiente en una consulta que promete
 * no modificar nada.
 */
@Component
public class SimuladorDeImpacto {

	/**
	 * Hasta cuantos dias hacia adelante se mira. No sale de ningun RF: es el horizonte con el que
	 * un centro planifica, y la respuesta publica hasta donde llego ({@code evaluadoHasta}).
	 */
	static final int DIAS_DE_HORIZONTE = 90;

	/** Cuantos turnos viajan en la lista. La cuenta es siempre la completa. */
	static final int LIMITE_DE_LISTA = 50;

	private static final DisponibilidadEfectivaCalculator CALCULADOR =
			new DisponibilidadEfectivaCalculator();

	private final BloqueDisponibilidadRepositoryPort bloques;
	private final DisponibilidadExcepcionRepositoryPort excepciones;
	private final FeriadoRepositoryPort feriados;
	private final CalendarioSedeRepositoryPort calendarios;
	private final DisponibilidadImpactProbe sonda;

	public SimuladorDeImpacto(
			BloqueDisponibilidadRepositoryPort bloques,
			DisponibilidadExcepcionRepositoryPort excepciones,
			FeriadoRepositoryPort feriados,
			CalendarioSedeRepositoryPort calendarios,
			DisponibilidadImpactProbe sonda) {

		this.bloques = bloques;
		this.excepciones = excepciones;
		this.feriados = feriados;
		this.calendarios = calendarios;
		this.sonda = sonda;
	}

	// =================================================================================
	// Los cuatro cambios
	// =================================================================================

	/**
	 * Editar un bloque. La ventana llega al fin de vigencia MAS LEJANO entre el anterior y el
	 * nuevo: los turnos que una edicion deja huerfanos son justamente los posteriores al nuevo fin.
	 *
	 * @param actual el bloque como esta hoy; no se modifica
	 * @throws IllegalArgumentException si la edicion propuesta es incoherente (400)
	 */
	public ImpactoDeDisponibilidad deEdicion(
			ConsultorioSnapshot sede, BloqueDisponibilidad actual, BloqueEdicionCommand cambio,
			Instant ahora) {

		BloqueDisponibilidad editado = copia(actual);
		editado.updateDatos(
				cambio.diaSemana(), cambio.horaDesde(), cambio.horaHasta(),
				cambio.vigenciaDesde(), cambio.vigenciaHasta(), cambio.limpiarVigenciaHasta());

		LocalDate hoy = LocalDate.ofInstant(ahora, zonaDe(sede));
		LocalDate fin = actual.getVigenciaHasta() == null || editado.getVigenciaHasta() == null
				? null
				: max(actual.getVigenciaHasta(), editado.getVigenciaHasta());
		return simular(sede, actual.getMembershipId(), hoy, fin, ahora,
				reemplazandoBloque(actual.getId(), editado), UnaryOperator.identity());
	}

	/** Dar de baja un bloque: lo que cubria hasta su fin de vigencia queda afuera. */
	public ImpactoDeDisponibilidad deBaja(
			ConsultorioSnapshot sede, BloqueDisponibilidad actual, Instant ahora) {

		LocalDate hoy = LocalDate.ofInstant(ahora, zonaDe(sede));
		return simular(sede, actual.getMembershipId(), hoy, actual.getVigenciaHasta(), ahora,
				reemplazandoBloque(actual.getId(), null), UnaryOperator.identity());
	}

	/**
	 * Cargar una excepcion. Un CIERRE recorta; una APERTURA solo agrega y responde cero sin que
	 * haga falta un caso aparte: lo cubierto despues contiene a lo cubierto antes.
	 *
	 * @param nueva la excepcion propuesta, TRANSITORIA; si es de sede su membership es nula
	 */
	public ImpactoDeDisponibilidad deAltaDeExcepcion(
			ConsultorioSnapshot sede, DisponibilidadExcepcion nueva, Instant ahora) {

		LocalDate hoy = LocalDate.ofInstant(ahora, zonaDe(sede));
		return simular(sede, nueva.getMembershipId(), max(hoy, nueva.getFechaDesde()),
				nueva.getFechaHasta(), ahora,
				UnaryOperator.identity(), agregando(nueva));
	}

	/** Dar de baja una excepcion. Quitar una APERTURA recorta; quitar un CIERRE solo agrega. */
	public ImpactoDeDisponibilidad deBajaDeExcepcion(
			ConsultorioSnapshot sede, DisponibilidadExcepcion actual, Instant ahora) {

		LocalDate hoy = LocalDate.ofInstant(ahora, zonaDe(sede));
		return simular(sede, actual.getMembershipId(), max(hoy, actual.getFechaDesde()),
				actual.getFechaHasta(), ahora,
				UnaryOperator.identity(), quitando(actual.getId()));
	}

	// =================================================================================
	// El calculo
	// =================================================================================

	/**
	 * @param membershipId profesional, o {@code null} para todos los de la sede
	 * @param desde        primera fecha local
	 * @param finPedido    fecha local EXCLUSIVA; {@code null} es "sin fin" y cae al horizonte
	 */
	private ImpactoDeDisponibilidad simular(
			ConsultorioSnapshot sede,
			Long membershipId,
			LocalDate desde,
			LocalDate finPedido,
			Instant ahora,
			UnaryOperator<List<BloqueDisponibilidad>> cambioDeBloques,
			UnaryOperator<List<DisponibilidadExcepcion>> cambioDeExcepciones) {

		ZoneId zona = zonaDe(sede);
		LocalDate horizonte = LocalDate.ofInstant(ahora, zona).plusDays(DIAS_DE_HORIZONTE);
		LocalDate hasta = finPedido == null || finPedido.isAfter(horizonte) ? horizonte : finPedido;
		Instant hastaInstante = hasta.atStartOfDay(zona).toInstant();
		if (!desde.isBefore(hasta) || !hastaInstante.isAfter(ahora)) {
			// Nada futuro que evaluar: un bloque ya vencido o una excepcion pasada.
			return ImpactoDeDisponibilidad.ninguno(null);
		}

		long organizationId = sede.organizationId();
		long consultorioId = sede.id();
		List<TurnoPendiente> pendientes =
				sonda.pendientesEn(organizationId, consultorioId, membershipId, ahora, hastaInstante);
		if (pendientes.isEmpty()) {
			return ImpactoDeDisponibilidad.ninguno(hasta);
		}

		Set<LocalDate> feriadosQueCierran = feriadosQueCierran(organizationId, consultorioId, desde, hasta);

		Map<Long, List<TurnoPendiente>> porProfesional = pendientes.stream()
				.collect(Collectors.groupingBy(TurnoPendiente::membershipId, LinkedHashMap::new,
						Collectors.toList()));

		List<TurnoAfectado> afectados = new ArrayList<>();
		for (Map.Entry<Long, List<TurnoPendiente>> entrada : porProfesional.entrySet()) {
			long profesional = entrada.getKey();
			List<BloqueDisponibilidad> bloquesAntes = bloques.findVigentesEn(
					organizationId, consultorioId, profesional, desde, hasta);
			List<DisponibilidadExcepcion> excepcionesAntes = excepciones.findQueCubren(
					organizationId, consultorioId, profesional, desde, hasta);

			Map<LocalDate, DiaCalculado> antes = CALCULADOR.calcular(
					profesional, desde, hasta, bloquesAntes, excepcionesAntes, feriadosQueCierran);
			Map<LocalDate, DiaCalculado> despues = CALCULADOR.calcular(
					profesional, desde, hasta,
					cambioDeBloques.apply(bloquesAntes),
					cambioDeExcepciones.apply(excepcionesAntes),
					feriadosQueCierran);

			for (TurnoPendiente turno : entrada.getValue()) {
				if (cubre(antes, turno, zona) && !cubre(despues, turno, zona)) {
					afectados.add(new TurnoAfectado(
							turno.turnoId(), turno.membershipId(), turno.inicio(), turno.fin()));
				}
			}
		}

		if (afectados.isEmpty()) {
			return ImpactoDeDisponibilidad.ninguno(hasta);
		}
		afectados.sort(Comparator.comparing(TurnoAfectado::inicio)
				.thenComparingLong(TurnoAfectado::turnoId));
		return new ImpactoDeDisponibilidad(
				afectados.size(),
				afectados.get(0).inicio(),
				afectados.subList(0, Math.min(LIMITE_DE_LISTA, afectados.size())),
				hasta);
	}

	/**
	 * {@code true} si alguna franja del dia del turno lo contiene ENTERO. Las franjas contiguas se
	 * unen antes de mirar: un turno de 11:30 a 12:30 entre un bloque de 9 a 12 y una apertura de 12
	 * a 14 esta cubierto, y sin la union contaria como "no cubierto antes" y nunca se informaria.
	 */
	private static boolean cubre(Map<LocalDate, DiaCalculado> dias, TurnoPendiente turno, ZoneId zona) {
		LocalDate fecha = LocalDate.ofInstant(turno.inicio(), zona);
		DiaCalculado dia = dias.get(fecha);
		if (dia == null || dia.estaVacio()) {
			return false;
		}
		List<Instant[]> tramos = dia.franjas().stream()
				.map(FranjaEfectiva::intervalo)
				.map(intervalo -> new Instant[] {
						aInstante(fecha, intervalo.desde(), zona),
						aInstante(fecha, intervalo.hasta(), zona)})
				.sorted(Comparator.comparing(tramo -> tramo[0]))
				.toList();

		Instant inicioUnido = null;
		Instant finUnido = null;
		for (Instant[] tramo : tramos) {
			if (finUnido != null && !tramo[0].isAfter(finUnido)) {
				finUnido = tramo[1].isAfter(finUnido) ? tramo[1] : finUnido;
				continue;
			}
			if (contiene(inicioUnido, finUnido, turno)) {
				return true;
			}
			inicioUnido = tramo[0];
			finUnido = tramo[1];
		}
		return contiene(inicioUnido, finUnido, turno);
	}

	private static boolean contiene(Instant desde, Instant hasta, TurnoPendiente turno) {
		return desde != null && !turno.inicio().isBefore(desde) && !turno.fin().isAfter(hasta);
	}

	/** Misma conversion que {@code DisponibilidadEfectivaService}: las 24:00 son el dia siguiente. */
	private static Instant aInstante(LocalDate fecha, LocalTime hora, ZoneId zona) {
		if (IntervaloLocal.FIN_DE_DIA.equals(hora)) {
			return ZonedDateTime.of(fecha.plusDays(1), LocalTime.MIDNIGHT, zona).toInstant();
		}
		return ZonedDateTime.of(fecha, hora, zona).toInstant();
	}

	private Set<LocalDate> feriadosQueCierran(
			long organizationId, long consultorioId, LocalDate desde, LocalDate hasta) {

		CalendarioSede politica = calendarios.findByScope(organizationId, consultorioId)
				.orElseGet(() -> new CalendarioSede(organizationId, consultorioId));
		if (!politica.isCierraPorFeriado()) {
			return Set.of();
		}
		// findByPaisAndFechaBetween es inclusiva en los dos extremos: de ahi el minusDays.
		return feriados.findByPaisAndFechaBetween(politica.getPais(), desde, hasta.minusDays(1))
				.stream()
				.map(Feriado::getFecha)
				.collect(Collectors.toSet());
	}

	// =================================================================================
	// Los cambios, sobre copias
	// =================================================================================

	/** Saca el bloque {@code id} de la lista y, si hay reemplazo, pone la copia editada. */
	private static UnaryOperator<List<BloqueDisponibilidad>> reemplazandoBloque(
			Long id, BloqueDisponibilidad reemplazo) {

		return lista -> {
			List<BloqueDisponibilidad> resultado = new ArrayList<>(lista.stream()
					.filter(bloque -> !Objects.equals(bloque.getId(), id))
					.toList());
			if (reemplazo != null) {
				resultado.add(reemplazo);
			}
			return resultado;
		};
	}

	private static UnaryOperator<List<DisponibilidadExcepcion>> agregando(DisponibilidadExcepcion nueva) {
		return lista -> {
			List<DisponibilidadExcepcion> resultado = new ArrayList<>(lista);
			resultado.add(nueva);
			return resultado;
		};
	}

	private static UnaryOperator<List<DisponibilidadExcepcion>> quitando(Long id) {
		return lista -> lista.stream()
				.filter(excepcion -> !Objects.equals(excepcion.getId(), id))
				.toList();
	}

	private static BloqueDisponibilidad copia(BloqueDisponibilidad bloque) {
		return new BloqueDisponibilidad(
				bloque.getOrganizationId(),
				bloque.getConsultorioId(),
				bloque.getMembershipId(),
				bloque.getDiaSemana(),
				bloque.getHoraDesde(),
				bloque.getHoraHasta(),
				bloque.getVigenciaDesde(),
				bloque.getVigenciaHasta());
	}

	private static LocalDate max(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	private static ZoneId zonaDe(ConsultorioSnapshot sede) {
		return ZonaSede.de(sede);
	}
}
