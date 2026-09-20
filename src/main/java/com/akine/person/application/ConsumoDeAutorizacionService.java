package com.akine.person.application;

import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.AutorizacionMovimiento;
import com.akine.person.domain.TipoMovimientoAutorizacion;
import com.akine.person.domain.TipoOrigenMovimiento;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionSinSaldoException;
import com.akine.person.domain.exception.MovimientoNotAccessibleException;
import com.akine.person.domain.exception.MovimientoYaRevertidoException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.ReversionSinMotivoException;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionMovimientoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

	public ConsumoDeAutorizacionService(
			AutorizacionRepositoryPort autorizaciones,
			AutorizacionMovimientoRepositoryPort movimientos,
			PersonaRepositoryPort personas,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.autorizaciones = autorizaciones;
		this.movimientos = movimientos;
		this.personas = personas;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
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
	public ResultadoDeConsumo consumirPorSesion(ConsumoPorSesion hecho) {
		LocalDate fecha = hecho.fecha() == null ? LocalDate.now() : hecho.fecha();

		// Se elige la que vence antes: es la que hay que gastar primero, porque es la que se
		// pierde antes. La consulta ya viene en ese orden.
		Optional<Autorizacion> elegida = autorizaciones
				.aprobadasDePersona(hecho.organizationId(), hecho.personaId()).stream()
				.filter(autorizacion -> autorizacion.habilitaEl(fecha))
				.findFirst();

		if (elegida.isEmpty()) {
			// El caso MAS frecuente, no una anomalia: un paciente particular, o uno cuya obra
			// social no exige autorizacion previa, cierra todas sus sesiones asi.
			log.debug("Cierre sin autorizacion elegible: sesionId={} personaId={}",
					hecho.sesionId(), hecho.personaId());
			return ResultadoDeConsumo.sinAutorizacionElegible();
		}

		Autorizacion autorizacion = elegida.get();

		// IDEMPOTENCIA. Ver la cabecera: se consulta antes, no se atrapa el choque despues.
		Optional<AutorizacionMovimiento> yaRegistrado = movimientos.buscarPorOrigen(
				hecho.organizationId(),
				autorizacion.getId(),
				TipoMovimientoAutorizacion.CONSUMO,
				TipoOrigenMovimiento.SESION,
				hecho.sesionId());

		if (yaRegistrado.isPresent()) {
			AutorizacionMovimiento previo = yaRegistrado.get();
			return ResultadoDeConsumo.yaConsumida(
					autorizacion.getId(), previo.getId(), autorizacion.saldo());
		}

		int cantidad = hecho.cantidad() <= 0 ? 1 : hecho.cantidad();

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

		auditar(AuditEvents.AUTORIZACION_CONSUMO_REVERTIDO, autorizacion, actor.consultorioId(),
				actor.accountId(), motivo, Map.of(
						"movimientoRevertidoId", String.valueOf(consumo.getId()),
						"reversionId", String.valueOf(reversion.getId()),
						"cantidad", String.valueOf(consumo.getCantidad())));

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
		return SaldoDeAutorizacionView.de(
				autorizacion,
				movimientos.listarDeAutorizacion(organizationId, autorizacionId),
				fecha == null ? LocalDate.now() : fecha);
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
