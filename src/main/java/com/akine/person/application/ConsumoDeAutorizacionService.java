package com.akine.person.application;

import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.AutorizacionAlerta;
import com.akine.person.domain.AutorizacionEvento;
import com.akine.person.domain.TipoEventoAutorizacion;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionEventoRepositoryPort;
import com.akine.person.domain.AutorizacionMovimiento;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.ResolucionAlertaAutorizacion;
import com.akine.person.domain.TipoAlertaAutorizacion;
import com.akine.person.domain.TipoMovimientoAutorizacion;
import com.akine.person.domain.TipoOrigenMovimiento;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionSinSaldoException;
import com.akine.person.domain.exception.MovimientoNotAccessibleException;
import com.akine.person.domain.exception.MovimientoYaRevertidoException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.ReversionSinMotivoException;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionAlertaRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionMovimientoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.person.spi.ConsumoARevisar;
import com.akine.person.spi.ConsumoPorSesion;
import com.akine.person.spi.ResultadoDeConsumo;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Mueve el saldo de las autorizaciones: consumo, reversion y las consultas que los explican (M17,
 * RF-M17-003/004/005, AKINE-04.05).
 *
 * <h2>1. El saldo nunca negativo lo decide la BASE, no un {@code if}</h2>
 *
 * <pre>
 *   UPDATE autorizacion
 *      SET cantidad_consumida = cantidad_consumida + :n
 *    WHERE id = :id AND organization_id = :org
 *      AND (cantidad_autorizada IS NULL
 *           OR cantidad_autorizada - cantidad_consumida &gt;= :n)
 * </pre>
 *
 * <p><b>Cero filas afectadas significa "no hay saldo"</b>, y lo decide el motor. No hace falta
 * leer antes —que es donde se cuela la ventana entre lectura y escritura— y <b>no se toma ningun
 * lock</b>, asi que no hay deadlock posible. Es exactamente lo que 07.02 hizo para imputar un
 * cobro, y resuelve el caso borde que el plan nombra: <b>ultima unidad concurrente</b>. Dos
 * sesiones peleando por la ultima unidad: una gana, la otra ve cero filas.
 *
 * <h3>Por que estas transacciones NO van en {@code READ_COMMITTED}</h3>
 *
 * <p>La regla del repositorio es que "las mutaciones que serializan van en
 * {@code READ_COMMITTED}", y esta <b>no serializa</b>: no toma ninguna fila-lock. Bajo
 * {@code REPEATABLE READ} el problema que esa regla resuelve —InnoDB fija la foto en la primera
 * lectura consistente, que ocurre antes del lock, y las dos transacciones pasan— <b>no puede
 * ocurrir aca</b>, porque la decision no sale de ninguna lectura previa sino del {@code WHERE} del
 * propio {@code UPDATE}, y un {@code UPDATE} en InnoDB lee siempre la version <b>actual</b> de la
 * fila, no la de la foto. Cambiar el nivel no agregaria ninguna garantia y sumaria una diferencia
 * de comportamiento respecto del resto del modulo, que es peor que no tocar nada.
 *
 * <p><b>Y no hay {@code OPTIMISTIC_FORCE_INCREMENT}</b>, que es la otra tentacion. Esa herramienta
 * existe para cuando una escritura <b>no toca ninguna columna del padre</b> —02.07 la necesito
 * porque escribia solo en tablas hijas—. Aca el {@code UPDATE} toca {@code cantidad_consumida},
 * que es una columna de la autorizacion: forzar la version encima chocaria con el propio
 * {@code UPDATE} nativo, que ni siquiera pasa por la sesion de JPA.
 *
 * <h2>2. La idempotencia es un unique, no un chequeo</h2>
 *
 * <p>{@code uk_autorizacion_movimiento_origen} sobre
 * {@code (organization_id, autorizacion_id, tipo, tipo_origen, referencia_origen)}. El reintento
 * del mismo cierre encuentra su fila y se responde con ella, no con un 409 ni con un segundo
 * descuento.
 *
 * <p><b>Se consulta ANTES de insertar y no se atrapa el choque despues.</b> La diferencia importa
 * y es la trampa que este repositorio ya pago: atrapar una {@code DataIntegrityViolationException}
 * despues de un flush fallido no des-marca la transaccion —Hibernate ya la puso {@code
 * rollbackOnly}— y Spring lanza {@code UnexpectedRollbackException} al commitear. Resolver el
 * choque desde afuera obligaria a insertar en {@code REQUIRES_NEW}, y eso <b>separaria el
 * movimiento del {@code UPDATE} del saldo en dos transacciones distintas</b>, que es precisamente
 * lo que esta etapa no puede permitirse: el ledger y la columna se escriben juntos o no se escribe
 * ninguno.
 *
 * <p>El pre-chequeo deja una ventana teorica —dos consumos <b>simultaneos</b> del mismo
 * {@code sesionId}—, y esa ventana esta cerrada mas arriba: {@code SesionService.cerrar} sale
 * temprano si la sesion ya esta cerrada y, ante dos cierres realmente concurrentes, el
 * {@code @Version} de {@code Sesion} mata al segundo antes de que llegue a notificar a nadie. Si
 * aun asi ocurriera, el unique hace cumplir la regla y lo que se pierde es el cierre, no la
 * coherencia del saldo.
 *
 * <h2>3. El ledger es la fuente de verdad; la columna es el saldo materializado</h2>
 *
 * <p>Las dos escrituras van en la MISMA transaccion. Si alguna vez divergieran nada lo detectaria
 * automaticamente —no hay nadie recalculando—, y por eso {@link #saldo} devuelve las dos cuentas
 * con un {@code coherente} al lado: es la unica mitigacion que se pudo poner sin Docker.
 *
 * <h2>4. Esta clase NO consume al reservar un turno (DP-05)</h2>
 *
 * <p>El unico origen que produce {@code CONSUMO} es {@code SESION}, y llega por
 * {@code person.spi.ConsumoDeAutorizaciones}. Ninguna transicion administrativa prueba que una
 * prestacion ocurrio: un turno que despues se cancela habria comido una unidad que el paciente
 * nunca uso.
 */
@Service
public class ConsumoDeAutorizacionService {

	private static final Logger log = LoggerFactory.getLogger(ConsumoDeAutorizacionService.class);

	private final AutorizacionRepositoryPort autorizaciones;
	private final AutorizacionMovimientoRepositoryPort movimientos;
	private final PersonaRepositoryPort personas;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final CoberturaPacienteRepositoryPort coberturas;
	private final AutorizacionAlertaRepositoryPort alertas;
	private final AutorizacionEventoRepositoryPort eventos;

	@SuppressWarnings("java:S107")
	public ConsumoDeAutorizacionService(
			AutorizacionRepositoryPort autorizaciones,
			AutorizacionMovimientoRepositoryPort movimientos,
			PersonaRepositoryPort personas,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			CoberturaPacienteRepositoryPort coberturas,
			AutorizacionAlertaRepositoryPort alertas,
			AutorizacionEventoRepositoryPort eventos) {

		this.autorizaciones = autorizaciones;
		this.movimientos = movimientos;
		this.personas = personas;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.coberturas = coberturas;
		this.alertas = alertas;
		this.eventos = eventos;
	}

	// =================================================================================
	// El consumo. Lo dispara el cierre de sesion, nunca una persona.
	// =================================================================================

	/**
	 * Descuenta las unidades de la sesion cerrada, si hay una autorizacion de donde.
	 *
	 * <p><b>No lanza por falta de saldo ni por falta de autorizacion.</b> Devuelve el desenlace.
	 * El motivo completo esta en {@code ResultadoDeConsumo} y en el observador que lo llama.
	 *
	 * <p><b>Sin permiso evaluado, y es correcto:</b> esto no lo dispara una persona sino un hecho
	 * clinico ya ocurrido. Exigir {@code paciente:manage} haria que un profesional sin permiso
	 * administrativo cerrara sesiones que no descuentan, que es peor que no descontar ninguna —
	 * porque la diferencia quedaria escondida y dependeria de quien atendio. Quien ya paso el
	 * control de acceso es el cierre de la sesion.
	 *
	 * <p>La transaccion es la del cierre, por {@code Propagation.REQUIRED}: el movimiento y el
	 * saldo tienen que caer o sobrevivir junto con la sesion que los produjo.
	 */
	@Transactional
	public List<ResultadoDeConsumo> consumirPorSesion(ConsumoPorSesion hecho) {
		long organizationId = hecho.organizationId();
		LocalDate fecha = hecho.fecha() == null ? LocalDate.now() : hecho.fecha();
		int cantidad = hecho.cantidad() <= 0 ? 1 : hecho.cantidad();

		// IDEMPOTENCIA POR SESION (AKINE C-4). Lo que esta sesion ya dejo en el ledger, en
		// CUALQUIER autorizacion. El unique de V50 ya impide consumir dos veces la MISMA
		// autorizacion, pero no alcanza: la que el primer disparo dejo agotada ya no habilita, y
		// sin esto el re-disparo elegiria OTRA autorizacion de la misma practica y descontaria de
		// nuevo. Ver docs/diseno/AKINE-C-4-consumo.md §2.3.
		Map<Long, AutorizacionMovimiento> yaConsumidas = new TreeMap<>();
		movimientos.listarDeOrigen(organizationId, TipoOrigenMovimiento.SESION, hecho.sesionId())
				.stream()
				.filter(movimiento -> movimiento.getTipo() == TipoMovimientoAutorizacion.CONSUMO)
				.forEach(movimiento ->
						yaConsumidas.putIfAbsent(movimiento.getAutorizacionId(), movimiento));

		List<ResultadoDeConsumo> resultados = new ArrayList<>();
		Set<Long> practicasYaCubiertas = new HashSet<>();
		yaConsumidas.forEach((autorizacionId, previo) -> {
			Optional<Autorizacion> consumida =
					autorizaciones.findByIdAndOrganizationId(autorizacionId, organizationId);
			consumida.map(Autorizacion::getPracticaId).ifPresent(practicasYaCubiertas::add);
			resultados.add(ResultadoDeConsumo.yaConsumida(
					autorizacionId, previo.getId(), consumida.map(Autorizacion::saldo).orElse(null)));
		});

		boolean sinPracticas = hecho.practicasRealizadas().isEmpty();
		Set<Long> practicasPendientes = new HashSet<>(hecho.practicasRealizadas());
		practicasPendientes.removeAll(practicasYaCubiertas);
		if (!yaConsumidas.isEmpty() && (sinPracticas || practicasPendientes.isEmpty())) {
			// Sin practicas la sesion consume una sola unidad (04.05), y ya la consumio. Con
			// practicas, todas quedaron cubiertas por lo que ya esta en el ledger.
			return resultados;
		}

		List<Autorizacion> habilitadas = autorizaciones
				.aprobadasDePersona(organizationId, hecho.personaId()).stream()
				.filter(autorizacion -> !yaConsumidas.containsKey(autorizacion.getId()))
				.filter(autorizacion -> autorizacion.habilitaEl(fecha))
				.toList();

		if (habilitadas.isEmpty()) {
			// El caso MAS frecuente, no una anomalia: un paciente particular, o uno cuya obra
			// social no exige autorizacion previa, cierra todas sus sesiones asi.
			log.debug("Cierre sin autorizacion elegible: sesionId={} personaId={}",
					hecho.sesionId(), hecho.personaId());
			return conDesenlace(resultados, ResultadoDeConsumo.sinAutorizacionElegible());
		}

		// AKINE C-4. La cobertura bajo la que se otorgo tiene que estar vigente el dia de la
		// atencion (RF-M17-004, "vigencia de referencias"): dada de baja, vencida o todavia no
		// empezada, la autorizacion no se gasta. Mismo criterio que la cobertura aplicable de B-2.
		Set<Long> coberturasVigentes = coberturas
				.activasDe(organizationId, hecho.personaId()).stream()
				.filter(cobertura -> cobertura.vigenteEl(fecha))
				.map(CoberturaPaciente::getId)
				.collect(Collectors.toSet());
		List<Autorizacion> conCobertura = habilitadas.stream()
				.filter(autorizacion -> coberturasVigentes.contains(autorizacion.getCoberturaId()))
				.toList();
		if (conCobertura.isEmpty()) {
			log.info("Cierre sin cobertura vigente para las autorizaciones del paciente: "
							+ "sesionId={} personaId={} habilitadas={}. NO se consume",
					hecho.sesionId(), hecho.personaId(), habilitadas.size());
			return conDesenlace(resultados, ResultadoDeConsumo.sinCoberturaVigente());
		}

		// AKINE C-4. RF-M17-007: no reutilizar la autorizacion de otro Caso. Quien sabe que
		// autorizacion esta atada a que caso es clinical; el observador del cierre ya lo pregunto.
		List<Autorizacion> candidatas = conCobertura.stream()
				.filter(autorizacion ->
						!hecho.autorizacionesDeOtroCaso().contains(autorizacion.getId()))
				.toList();
		if (candidatas.isEmpty()) {
			log.info("Cierre con autorizaciones de otro caso: sesionId={} personaId={} "
							+ "excluidas={}. NO se consume",
					hecho.sesionId(), hecho.personaId(), hecho.autorizacionesDeOtroCaso());
			return conDesenlace(resultados, ResultadoDeConsumo.autorizacionDeOtroCaso());
		}

		Collection<Autorizacion> involucradas = sinPracticas
				// Sin practicas registradas se conserva 04.05: una unidad, en la que vence antes.
				// Vacio es "no se sabe", no "ninguna": ver ConsumoPorSesion.
				? List.of(candidatas.get(0))
				: involucradasPorPractica(candidatas, practicasPendientes);

		if (involucradas.isEmpty()) {
			// Hay saldo vigente y NINGUNA autorizacion es de una practica que se aplico. NO se
			// consume: ver ResultadoDeConsumo.SIN_AUTORIZACION_PARA_LA_PRACTICA.
			log.info("Cierre sin autorizacion para las practicas realizadas: sesionId={} "
							+ "personaId={} practicas={} vigentes={}. NO se consume: gastar otra "
							+ "practica le come al paciente unidades que si necesita y le declara "
							+ "al financiador algo que no se presto",
					hecho.sesionId(), hecho.personaId(), practicasPendientes, candidatas.size());
			return conDesenlace(resultados, ResultadoDeConsumo.sinAutorizacionParaLaPractica());
		}

		// DP-12: UNA unidad por autorizacion involucrada, en orden de id. El orden importa: dos
		// cierres concurrentes que tocan las mismas dos autorizaciones toman los locks de fila del
		// UPDATE en el mismo orden y no se bloquean en cruz.
		for (Autorizacion autorizacion : involucradas) {
			resultados.add(consumirUna(hecho, autorizacion, cantidad));
		}
		resultados.sort(Comparator.comparing(ResultadoDeConsumo::autorizacionId));
		return resultados;
	}

	/**
	 * DP-12. Cada practica pendiente va a la PRIMERA candidata que la cubre —la que vence antes,
	 * porque es la que se pierde antes— y se agrupan por autorizacion: dos practicas bajo la misma
	 * autorizacion son una sola unidad.
	 */
	private static Collection<Autorizacion> involucradasPorPractica(
			List<Autorizacion> candidatas, Set<Long> practicas) {

		Map<Long, Autorizacion> porId = new TreeMap<>();
		for (Long practica : practicas) {
			candidatas.stream()
					.filter(autorizacion -> practica.equals(autorizacion.getPracticaId()))
					.findFirst()
					.ifPresent(autorizacion -> porId.putIfAbsent(autorizacion.getId(), autorizacion));
		}
		return porId.values();
	}

	/** Si ya hubo consumos previos de la sesion, ellos son el resultado; si no, el desenlace. */
	private static List<ResultadoDeConsumo> conDesenlace(
			List<ResultadoDeConsumo> resultados, ResultadoDeConsumo desenlace) {
		return resultados.isEmpty() ? List.of(desenlace) : resultados;
	}

	/** Descuenta una autorizacion para la sesion. Es el consumo de 04.05, ahora por grupo. */
	private ResultadoDeConsumo consumirUna(
			ConsumoPorSesion hecho, Autorizacion autorizacion, int cantidad) {

		// IDEMPOTENCIA. Ver la cabecera: se consulta antes, no se atrapa el choque despues.
		Optional<AutorizacionMovimiento> yaRegistrado = movimientos.buscarPorOrigen(
				hecho.organizationId(),
				autorizacion.getId(),
				TipoMovimientoAutorizacion.CONSUMO,
				TipoOrigenMovimiento.SESION,
				hecho.sesionId());

		if (yaRegistrado.isPresent()) {
			return ResultadoDeConsumo.yaConsumida(
					autorizacion.getId(), yaRegistrado.get().getId(), autorizacion.saldo());
		}

		// LA BASE DECIDE. Cero filas es "no hay saldo".
		int filas = autorizaciones.descontarSaldo(
				hecho.organizationId(), autorizacion.getId(), cantidad);

		if (filas == 0) {
			// NO se lanza. La atencion ocurrio; que el financiador no tenga saldo es
			// administrativo y DP-06 prohibe bloquear el cierre clinico por eso.
			log.warn("Cierre sin saldo autorizado: sesionId={} autorizacionId={} personaId={} "
							+ "cantidad={}. El cierre NO se bloquea: la atencion ocurrio",
					hecho.sesionId(), autorizacion.getId(), hecho.personaId(), cantidad);
			return ResultadoDeConsumo.sinSaldo(autorizacion.getId());
		}

		AutorizacionMovimiento movimiento = movimientos.save(new AutorizacionMovimiento(
				hecho.organizationId(),
				autorizacion.getId(),
				hecho.personaId(),
				hecho.consultorioId(),
				TipoMovimientoAutorizacion.CONSUMO,
				cantidad,
				TipoOrigenMovimiento.SESION,
				hecho.sesionId(),
				null,
				null,
				Instant.now(),
				hecho.actorCuentaId()));

		// DP-23. Un evento por movimiento: uk_autorizacion_evento_movimiento lo hace cumplir aun
		// si dos transacciones llegaran hasta aca con el mismo movimiento, que el unique del
		// ledger ya impide.
		eventos.registrar(AutorizacionEvento.delLedger(
				autorizacion, TipoEventoAutorizacion.CONSUMO, movimiento.getId(), cantidad,
				"Sesion " + hecho.sesionId(), null, hecho.consultorioId(), hecho.actorCuentaId(),
				movimiento.getOcurrioEn()));

		Integer saldoRestante = autorizacion.getCantidadAutorizada() == null
				? null
				: autorizacion.getCantidadAutorizada()
						- autorizacion.getCantidadConsumida() - cantidad;

		auditar(AuditEvents.AUTORIZACION_CONSUMIDA, autorizacion, hecho.consultorioId(),
				hecho.actorCuentaId(), null, Map.of(
						"movimientoId", String.valueOf(movimiento.getId()),
						"sesionId", String.valueOf(hecho.sesionId()),
						"cantidad", String.valueOf(cantidad)));

		log.info("Autorizacion consumida: autorizacionId={} movimientoId={} sesionId={} "
						+ "cantidad={} saldoRestante={}",
				autorizacion.getId(), movimiento.getId(), hecho.sesionId(), cantidad,
				saldoRestante);

		return ResultadoDeConsumo.consumida(
				autorizacion.getId(), movimiento.getId(), saldoRestante);
	}

	// =================================================================================
	// DP-13. La deuda se anulo: el consumo queda para revisar, el saldo no se toca.
	// =================================================================================

	/**
	 * Alerta "consumo a revisar" sobre cada consumo vivo de la sesion cuya obligacion se anulo
	 * (DP-13, RN-M17-003).
	 *
	 * <p><b>No mueve el saldo ni el ledger.</b> Anular la deuda no prueba que la prestacion no
	 * ocurrio. Un consumo que ya se revirtio no se alerta: no queda nada que revisar.
	 *
	 * <p>Sin permiso evaluado, por el mismo motivo que el consumo: no lo dispara una persona sobre
	 * una autorizacion sino un hecho de {@code billing}, que ya exigio el suyo para anular. La
	 * transaccion es la de la anulacion.
	 */
	@Transactional
	public int consumoARevisar(ConsumoARevisar hecho) {
		long organizationId = hecho.organizationId();
		List<AutorizacionMovimiento> delOrigen = movimientos.listarDeOrigen(
				organizationId, TipoOrigenMovimiento.SESION, hecho.sesionId());

		Set<Long> revertidos = delOrigen.stream()
				.filter(movimiento -> movimiento.getTipo() == TipoMovimientoAutorizacion.REVERSION)
				.map(AutorizacionMovimiento::getMovimientoOrigenId)
				.collect(Collectors.toSet());
		List<AutorizacionMovimiento> vivos = delOrigen.stream()
				.filter(movimiento -> movimiento.getTipo() == TipoMovimientoAutorizacion.CONSUMO)
				.filter(movimiento -> !revertidos.contains(movimiento.getId()))
				.toList();

		String motivo = recortar(hecho.motivo());
		Instant ahora = hecho.ocurrioEn() == null ? Instant.now() : hecho.ocurrioEn();
		for (AutorizacionMovimiento consumo : vivos) {
			alertas.registrarSiFalta(
					organizationId,
					consumo.getAutorizacionId(),
					consumo.getPersonaId(),
					consumo.getId(),
					TipoAlertaAutorizacion.CONSUMO_A_REVISAR.name(),
					hecho.sesionId(),
					hecho.obligacionId(),
					motivo,
					ahora,
					hecho.actorCuentaId());

			auditTrail.record(new AuditEntry(
					organizationId,
					consumo.getConsultorioId(),
					hecho.actorCuentaId(),
					AuditEvents.AUTORIZACION_CONSUMO_A_REVISAR,
					AuditEvents.ENTITY_AUTORIZACION,
					consumo.getAutorizacionId(),
					null,
					null,
					new LinkedHashMap<>(Map.of(
							"movimientoId", String.valueOf(consumo.getId()),
							"sesionId", String.valueOf(hecho.sesionId()),
							"obligacionId", String.valueOf(hecho.obligacionId()))),
					motivo,
					AuditEvents.correlationId(),
					ahora));

			log.info("Consumo a revisar por anulacion de la deuda: autorizacionId={} "
							+ "movimientoId={} sesionId={} obligacionId={}. El saldo NO se toca",
					consumo.getAutorizacionId(), consumo.getId(), hecho.sesionId(),
					hecho.obligacionId());
		}
		return vivos.size();
	}

	private static String recortar(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			return null;
		}
		String limpio = motivo.strip();
		return limpio.length() > AutorizacionMovimiento.MOTIVO_MAXIMO
				? limpio.substring(0, AutorizacionMovimiento.MOTIVO_MAXIMO)
				: limpio;
	}

	// =================================================================================
	// La reversion. Esta SI es un acto humano y exige motivo.
	// =================================================================================

	/**
	 * Compensa un consumo y devuelve la unidad (RF-M17-005).
	 *
	 * <p><b>No borra nada.</b> Escribe una fila {@code REVERSION} con motivo obligatorio y hace el
	 * {@code UPDATE} inverso, <b>en la misma transaccion</b>. El consumo original queda donde
	 * estaba, diciendo que ocurrio: regla maestra 10.
	 *
	 * <p>La reversion apunta al <b>mismo origen</b> que el consumo que compensa —mismo
	 * {@code tipoOrigen} y mismo {@code referenciaOrigen}—, y por eso revertir dos veces el mismo
	 * consumo choca contra el unique. Eso es lo que la hace idempotente en vez de meramente
	 * segura. Se consulta antes para poder responder un 409 explicable
	 * ({@code movimiento-ya-revertido}) en lugar de un error de constraint.
	 *
	 * <p>Exige {@code paciente:manage} sobre la sede del contexto, igual que el resto de las
	 * mutaciones del padron: revertir un consumo cambia lo que el centro le va a presentar al
	 * financiador.
	 */
	@Transactional
	public MovimientoView revertir(
			OperatingActor actor, long autorizacionId, ReversionCommand command) {

		AutorizacionDePadron.exigirGestionDelPadron(
				permissionGuard, actor, "Revertir un consumo de autorizacion");
		long organizationId = actor.contextOrganizationId();

		String motivo = command.motivo() == null || command.motivo().isBlank()
				? null
				: command.motivo().strip();
		if (motivo == null) {
			// 400 y no 409: no hay ningun estado que impida la operacion, falta un dato.
			throw new ReversionSinMotivoException();
		}

		Autorizacion autorizacion = cargar(organizationId, autorizacionId);

		AutorizacionMovimiento consumo = movimientos
				.buscarDeLaAutorizacion(organizationId, autorizacionId, command.movimientoId())
				.orElseThrow(() -> new MovimientoNotAccessibleException(command.movimientoId()));

		if (!consumo.getTipo().descuentaSaldo()) {
			// Revertir una reversion no es una operacion: seria volver a consumir, y eso lo hace
			// una atencion, no un boton. Se responde 404 sobre el movimiento porque el pedido
			// nombra algo que no es revertible, no un estado que se pueda corregir.
			log.info("Reversion rechazada: el movimiento no descuenta. movimientoId={} tipo={}",
					consumo.getId(), consumo.getTipo());
			throw new MovimientoNotAccessibleException(consumo.getId());
		}

		movimientos.buscarPorOrigen(
						organizationId,
						autorizacionId,
						TipoMovimientoAutorizacion.REVERSION,
						consumo.getTipoOrigen(),
						consumo.getReferenciaOrigen())
				.ifPresent(reversion -> {
					throw new MovimientoYaRevertidoException(consumo.getId(), reversion.getId());
				});

		int filas = autorizaciones.devolverSaldo(
				organizationId, autorizacionId, consumo.getCantidad());
		if (filas == 0) {
			// El ledger dice que se consumio y la columna dice que no hay nada que devolver: las
			// dos fuentes divergieron. Se corta en vez de escribir una reversion que dejaria la
			// divergencia peor. GET /saldo es lo que permite verlo.
			log.error("Reversion imposible: cantidad_consumida quedo por debajo de lo que el "
							+ "ledger afirma. autorizacionId={} movimientoId={} cantidad={}",
					autorizacionId, consumo.getId(), consumo.getCantidad());
			throw new AutorizacionSinSaldoException(autorizacionId, consumo.getCantidad());
		}

		AutorizacionMovimiento reversion = movimientos.save(new AutorizacionMovimiento(
				organizationId,
				autorizacionId,
				autorizacion.getPersonaId(),
				actor.consultorioId(),
				TipoMovimientoAutorizacion.REVERSION,
				consumo.getCantidad(),
				consumo.getTipoOrigen(),
				consumo.getReferenciaOrigen(),
				motivo,
				consumo.getId(),
				Instant.now(),
				actor.accountId()));

		eventos.registrar(AutorizacionEvento.delLedger(
				autorizacion, TipoEventoAutorizacion.REVERSION_DE_CONSUMO, reversion.getId(),
				consumo.getCantidad(), "Revierte el consumo " + consumo.getId(), motivo,
				actor.consultorioId(), actor.accountId(), reversion.getOcurrioEn()));

		auditar(AuditEvents.AUTORIZACION_CONSUMO_REVERTIDO, autorizacion, actor.consultorioId(),
				actor.accountId(), motivo, Map.of(
						"movimientoRevertidoId", String.valueOf(consumo.getId()),
						"reversionId", String.valueOf(reversion.getId()),
						"cantidad", String.valueOf(consumo.getCantidad())));

		// DP-13. Revertir es lo que resuelve la alerta "consumo a revisar" que la anulacion de la
		// deuda pudo haber dejado sobre este consumo. Misma transaccion: o las dos cosas o ninguna.
		alertas.resolverDelMovimiento(organizationId, consumo.getId(),
				ResolucionAlertaAutorizacion.REVERTIDO.name(), reversion.getOcurrioEn(),
				actor.accountId());

		log.info("Consumo revertido: autorizacionId={} movimientoId={} reversionId={}",
				autorizacionId, consumo.getId(), reversion.getId());

		return MovimientoView.de(reversion);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * El saldo de una autorizacion, por las dos fuentes (RF-M17-003).
	 *
	 * <p>Se autoriza por <b>pertenencia</b>, mismo criterio que el resto de las lecturas de este
	 * modulo: quien mas necesita este numero es el profesional que esta por atender, y exigirle el
	 * permiso de gestion lo dejaria afuera. Hereda el hueco conocido del alcance {@code OWN} que
	 * {@code PermissionCodes} documenta.
	 */
	@Transactional(readOnly = true)
	public SaldoDeAutorizacionView saldo(
			OperatingActor actor, long autorizacionId, LocalDate fecha) {

		long organizationId = AutorizacionDePadron.exigirContexto(
				actor, "Consultar el saldo de una autorizacion");

		Autorizacion autorizacion = cargar(organizationId, autorizacionId);
		int consumosARevisar = (int) alertas.listarDeAutorizacion(organizationId, autorizacionId)
				.stream()
				.filter(AutorizacionAlerta::pendiente)
				.count();
		return SaldoDeAutorizacionView.de(
				autorizacion,
				movimientos.listarDeAutorizacion(organizationId, autorizacionId),
				fecha == null ? LocalDate.now() : fecha,
				consumosARevisar);
	}

	/**
	 * Las alertas de una autorizacion, de la mas vieja a la mas nueva (DP-13, RN-M17-003).
	 *
	 * <p>Por pertenencia, como el saldo y el ledger: quien atiende necesita saber que hay un
	 * consumo en duda antes de gastar otro.
	 */
	@Transactional(readOnly = true)
	public List<AlertaDeAutorizacionView> alertas(OperatingActor actor, long autorizacionId) {
		long organizationId = AutorizacionDePadron.exigirContexto(
				actor, "Consultar las alertas de una autorizacion");

		cargar(organizationId, autorizacionId);
		return alertas.listarDeAutorizacion(organizationId, autorizacionId).stream()
				.map(AlertaDeAutorizacionView::de)
				.toList();
	}

	/** El ledger de una autorizacion, del hecho mas viejo al mas nuevo (RF-M17-004). */
	@Transactional(readOnly = true)
	public List<MovimientoView> movimientos(OperatingActor actor, long autorizacionId) {
		long organizationId = AutorizacionDePadron.exigirContexto(
				actor, "Consultar los movimientos de una autorizacion");

		cargar(organizationId, autorizacionId);
		return movimientos.listarDeAutorizacion(organizationId, autorizacionId).stream()
				.map(MovimientoView::de)
				.toList();
	}

	/**
	 * Las autorizaciones del paciente con el veredicto de si sirven ese dia (RF-M17-007).
	 *
	 * <p>Devuelve <b>todas</b> las activas y no solo las que habilitan: decirle al mostrador "no
	 * hay ninguna" sin decirle que una vencio anteayer y otra se agoto lo deja sin nada que hacer.
	 * Cada una viaja con {@code motivoNoElegible} cuando no sirve.
	 *
	 * <p>Las que habilitan van primero y entre ellas la que vence antes: es el orden en que hay
	 * que gastarlas, y es el mismo criterio con el que el consumo elige.
	 */
	@Transactional(readOnly = true)
	public List<AutorizacionElegibleView> elegibles(
			OperatingActor actor, long personaId, LocalDate fecha) {

		long organizationId = AutorizacionDePadron.exigirContexto(
				actor, "Consultar las autorizaciones elegibles del paciente");
		personas.findByIdAndOrganizationId(personaId, organizationId)
				.orElseThrow(() -> new PersonaNotAccessibleException(personaId));

		LocalDate dia = fecha == null ? LocalDate.now() : fecha;

		return autorizaciones.historial(organizationId, personaId).stream()
				.filter(Autorizacion::isActive)
				.map(autorizacion -> AutorizacionElegibleView.de(autorizacion, dia))
				.sorted(ConsumoDeAutorizacionService::primeroLasQueSirvenYVencenAntes)
				.toList();
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/** Primero las que habilitan; dentro de cada grupo, la que vence antes. Las que no vencen,
	 * al final: no hay apuro por gastarlas. Mismo desempate que la elegibilidad de 03.06. */
	private static int primeroLasQueSirvenYVencenAntes(
			AutorizacionElegibleView una, AutorizacionElegibleView otra) {

		if (una.habilita() != otra.habilita()) {
			return una.habilita() ? -1 : 1;
		}
		LocalDate finUna = una.vigenciaHasta();
		LocalDate finOtra = otra.vigenciaHasta();
		if (finUna == null && finOtra == null) {
			return Long.compare(una.id(), otra.id());
		}
		if (finUna == null) {
			return 1;
		}
		if (finOtra == null) {
			return -1;
		}
		int porFecha = finUna.compareTo(finOtra);
		return porFecha != 0 ? porFecha : Long.compare(una.id(), otra.id());
	}

	/**
	 * Si la autorizacion sirve para alguna practica que realmente se aplico (AKINE-06.04).
	 *
	 * <h2>El conjunto vacio devuelve {@code true}, y es la decision que hace viable la etapa</h2>
	 *
	 * <p>Vacio significa <b>"no se sabe que practicas fueron"</b>, no "ninguna". Son vacias
	 * <b>todas</b> las sesiones anteriores a 06.04 —el registro de tratamientos no existia— y
	 * tambien las de ofertas que no registran practicas, como una consulta o una evaluacion
	 * inicial.
	 *
	 * <p>Si el filtro se aplicara igual, esas sesiones no matchearian con nada y el sistema
	 * <b>dejaria de consumir autorizaciones por completo</b>. Una etapa que "arregla la
	 * imputacion" y apaga el consumo entero es peor que el defecto que corrige.
	 *
	 * <p>Ante la ignorancia se conserva exactamente el comportamiento anterior: se elige la que
	 * vence antes. El filtro se aplica <b>solo cuando hay dato con el cual filtrar</b>.
	 */
	private static boolean cubreAlgunaPracticaRealizada(
			Autorizacion autorizacion, ConsumoPorSesion hecho) {

		if (hecho.practicasRealizadas().isEmpty()) {
			return true;
		}
		return autorizacion.getPracticaId() != null
				&& hecho.practicasRealizadas().contains(autorizacion.getPracticaId());
	}

	private Autorizacion cargar(long organizationId, long autorizacionId) {
		return autorizaciones.findByIdAndOrganizationId(autorizacionId, organizationId)
				.orElseThrow(() -> new AutorizacionNotAccessibleException(autorizacionId));
	}

	@SuppressWarnings("java:S107")
	private void auditar(
			String eventType,
			Autorizacion autorizacion,
			Long consultorioId,
			Long actorCuentaId,
			String reason,
			Map<String, String> detalles) {

		Map<String, String> copia = new LinkedHashMap<>(detalles);
		auditTrail.record(new AuditEntry(
				autorizacion.getOrganizationId(),
				consultorioId == null ? autorizacion.getConsultorioId() : consultorioId,
				actorCuentaId,
				eventType,
				AuditEvents.ENTITY_AUTORIZACION,
				autorizacion.getId(),
				null,
				null,
				copia,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
