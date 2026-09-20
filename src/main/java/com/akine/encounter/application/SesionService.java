package com.akine.encounter.application;

import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.HistoriaClinicaDirectory;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.spi.CierreDeSesionObserver;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.clinical.spi.HistoriaClinicaSnapshot;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.EvaluacionBase;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
import com.akine.encounter.domain.port.SesionNumeradorPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.scheduling.spi.TurnoDirectory;
import com.akine.scheduling.spi.TurnoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Inicio de la atencion y autosave del borrador (M14, RF-M14-001, RF-M14-002 y RF-M14-009).
 *
 * <h2>Recableado de DP-10</h2>
 *
 * <p>El plan escribe esta etapa contra el check-in (05.04) y el consumo de autorizaciones (04.05),
 * y hace que la Sesion pertenezca a un Caso (04.03). Las tres etapas quedaron fuera del Paquete B.
 * En consecuencia: la sesion arranca <b>directamente desde el Turno</b>, sin paso de recepcion, y
 * cuelga de la <b>Historia Clinica</b>, que existe desde 04.01.
 *
 * <h2>El doble inicio es idempotente, y no es una concesion</h2>
 *
 * <p>RN-M14-001: un turno produce como mucho una sesion. Un profesional que aprieta dos veces
 * "iniciar" —o que recarga la pantalla— es el caso normal, no el raro. La segunda llamada devuelve
 * la sesion que ya existe, no un 409: el 409 le exigiria a la pantalla distinguir dos situaciones
 * que para el usuario son la misma. El {@code uk_sesion_turno} de V33 lo respalda del lado del
 * motor por si dos llamadas llegan a la vez.
 *
 * <h2>La propiedad no es un permiso</h2>
 *
 * <p>Dos profesionales de la misma sede tienen el mismo {@code sesion:register}. Lo que impide que
 * uno escriba en la atencion del otro no es el evaluador de permisos sino
 * {@code Sesion#exigirPropiedadDe}, y por eso vive en la entidad. Es la instruccion de la etapa:
 * "bloqueo de edicion ajena".
 *
 * <h2>Lo que esta etapa NO hace</h2>
 *
 * <p>No cierra la sesion —06.05— ni modela la evaluacion —06.02—, y el borrador se guarda como
 * contenido opaco: que campos tiene una evaluacion es asunto de etapas que todavia no corrieron, y
 * 06.03 quedo cortada. Tampoco crea obligacion economica: eso es 07.01, y DP-06 ya establecio que
 * el cierre clinico no depende del cobro.
 */
@Service
public class SesionService {

	private static final Logger log = LoggerFactory.getLogger(SesionService.class);

	private final SesionRepositoryPort sesiones;
	private final TurnoDirectory turnos;
	private final HistoriaClinicaDirectory historias;
	private final CasoDirectory casos;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;
	private final SesionNumeradorPort numerador;
	private final NumeradorIniciador numeradorIniciador;
	private final OfertaDirectory ofertas;
	private final List<CierreDeSesionObserver> observadores;
	private final AuditTrail auditTrail;

	public SesionService(
			SesionRepositoryPort sesiones,
			TurnoDirectory turnos,
			HistoriaClinicaDirectory historias,
			CasoDirectory casos,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard,
			SesionNumeradorPort numerador,
			NumeradorIniciador numeradorIniciador,
			OfertaDirectory ofertas,
			List<CierreDeSesionObserver> observadores,
			AuditTrail auditTrail) {

		this.sesiones = sesiones;
		this.turnos = turnos;
		this.historias = historias;
		this.casos = casos;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
		this.numerador = numerador;
		this.numeradorIniciador = numeradorIniciador;
		this.ofertas = ofertas;
		this.observadores = List.copyOf(observadores);
		this.auditTrail = auditTrail;
	}

	/**
	 * Abre la atencion de un turno, o devuelve la que ya estaba abierta.
	 *
	 * @throws ConsultorioNoAccesibleException si la sede no existe o es de otro tenant (404)
	 * @throws TurnoNoAtendibleException       si el turno esta de baja o no es de quien atiende (409)
	 * <h2>El caso es opcional, y eso es 04.03 entrando sin romper nada</h2>
	 *
	 * <p>{@code casoId} puede venir en {@code null} y es lo que hace hoy toda pantalla existente:
	 * RF-M14-002 admite atencion sin caso, y <b>todas</b> las sesiones anteriores a 04.03 no lo
	 * tienen. Exigirlo es RF-M10-007, que toca tambien {@code scheduling} y que necesita su propia
	 * ventana de migracion — encenderlo hoy romperia la vertical que funciona.
	 *
	 * <p>Cuando viene, se valida contra {@code clinical.spi.CasoDirectory}: tiene que existir en el
	 * tenant, colgar de <b>la misma historia</b> que la sesion y estar activo. Un caso de otro
	 * paciente es 404 —indistinguible de "no existe", para no poder censar casos ajenos por id— y
	 * uno cerrado es 409, porque lleva a otra accion: reabrirlo.
	 *
	 * <p><b>La idempotencia manda sobre el caso.</b> Si la sesion del turno ya existe se devuelve
	 * tal cual, con el caso que tenga, aunque esta llamada traiga otro: reasignar el caso de una
	 * atencion ya empezada no es "iniciar", y por eso {@code caso_id} es {@code updatable = false}.
	 *
	 * @throws AccessDeniedException           sin {@code sesion:register} o sin contexto (403)
	 * @throws CasoNoAsignableException        si el caso no existe, es de otra historia o esta
	 *                                         cerrado
	 */
	@Transactional
	public SesionView iniciar(
			OperatingActor actor, long consultorioId, long turnoId, Long casoId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		// Idempotencia ANTES de cualquier validacion de estado. Un turno cuya sesion ya existe se
		// resuelve igual aunque el turno haya cambiado despues: la atencion ya empezo y negarla
		// ahora perderia el borrador que el profesional venia escribiendo.
		var yaAbierta = sesiones.findVivaPorTurno(organizationId, turnoId);
		if (yaAbierta.isPresent()) {
			// Con la evaluacion previa: la pantalla la necesita apenas abre, no despues.
			return conPrevia(yaAbierta.get(), organizationId);
		}

		TurnoSnapshot turno = turnos.find(organizationId, consultorioId, turnoId)
				.orElseThrow(() -> new TurnoNoAtendibleException(turnoId, "no existe en esta sede"));
		if (!turno.vivo()) {
			throw new TurnoNoAtendibleException(turnoId, "esta dado de baja");
		}

		long profesionalMembershipId = resolverProfesional(actor, organizationId, consultorioId, turno);

		// La HC se crea si no existia. `asegurar` es de 04.01 y exige perfil de paciente vigente:
		// una atencion sobre alguien que solo es "persona" tiene que fallar aca y no crear una
		// historia clinica a nombre de quien no es paciente.
		HistoriaClinicaSnapshot historia =
				historias.asegurar(organizationId, turno.personaId(), actor.accountId());

		// El caso se valida DESPUES de asegurar la historia: la pertenencia se comprueba contra
		// esa historia, y sin ella no hay contra que comprobar.
		exigirCasoAsignable(organizationId, historia.id(), casoId);

		Sesion sesion = sesiones.save(new Sesion(
				organizationId,
				consultorioId,
				historia.id(),
				casoId,
				turnoId,
				turno.ofertaId(),
				profesionalMembershipId,
				Instant.now(),
				actor.accountId()));

		auditar(actor, AuditEvents.SESION_INICIADA, sesion.getId(), null, "ABIERTA",
				Map.of("historiaClinicaId", String.valueOf(historia.id()),
						"turnoId", String.valueOf(turnoId),
						"casoId", String.valueOf(casoId)),
				sesion.getIniciadaEn());

		log.info("Sesion iniciada: sesionId={} turnoId={} historiaClinicaId={} casoId={} "
						+ "profesional={}",
				sesion.getId(), turnoId, historia.id(), casoId, profesionalMembershipId);

		return conPrevia(sesion, organizationId);
	}

	/**
	 * Guarda el borrador de una sesion abierta.
	 *
	 * <p><b>El control optimista es el punto de la operacion, no un chequeo de rutina.</b> Dos
	 * pestanas del mismo profesional sobre la misma sesion son el caso normal; sin la version, la
	 * segunda pisa a la primera en silencio y el profesional pierde lo que escribio. El cliente
	 * manda la version que leyo y un conflicto le dice que recargue.
	 *
	 * <p>La version la controla JPA con {@code @Version}: pasarla explicitamente y compararla a
	 * mano seria un segundo control que puede divergir del que realmente decide.
	 */
	@Transactional
	public SesionView guardarBorrador(
			OperatingActor actor, long consultorioId, long sesionId, String contenido, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		// Propiedad, no permiso: ver la cabecera de la clase.
		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));

		exigirVersion(sesion, expectedVersion);

		sesion.guardarBorrador(contenido, Instant.now());
		return conPrevia(sesiones.save(sesion), organizationId);
	}


	/**
	 * Guarda la evaluacion base de una sesion abierta (RF-M14-003).
	 *
	 * <p>Mismos dos controles que el borrador: <b>propiedad</b> —la atencion es de un profesional, y
	 * eso no es cuestion de permiso— y <b>version</b>, porque dos pestanas sobre la misma sesion son
	 * el caso normal.
	 *
	 * <p>Lo que NO valida es que los campos esten. "Seguimiento no exige examen completo", asi que
	 * una evaluacion con solo dolor y evolucion es valida. Ver {@link EvaluacionBase}.
	 */
	@Transactional
	public SesionView evaluar(
			OperatingActor actor, long consultorioId, long sesionId,
			EvaluacionBase evaluacion, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));
		exigirVersion(sesion, expectedVersion);

		sesion.evaluar(evaluacion, Instant.now());
		return conPrevia(sesiones.save(sesion), organizationId);
	}

	/**
	 * Cierra la atencion y le asigna su correlativo (RF-M14-006..008, RN-M14-005).
	 *
	 * <h2>El orden importa y no es negociable</h2>
	 *
	 * <pre>
	 *   1. idempotencia   &lt;- ANTES de pedir un numero
	 *   2. propiedad y version
	 *   3. minimos del cierre
	 *   4. asegurar el numerador  &lt;- en su PROPIA transaccion
	 *   5. incrementar y leer     &lt;- toma el lock de fila y serializa
	 *   5b. numero DENTRO DEL CASO, si la sesion tiene caso  &lt;- SIEMPRE despues del 5
	 *   6. cerrar
	 * </pre>
	 *
	 * <h2>Los dos numeradores van en este orden y nunca en el otro</h2>
	 *
	 * <p>Desde 04.03 esta transaccion puede tomar <b>dos</b> numeradores: el de la Historia Clinica
	 * (paso 5, {@code sesion_numerador} de V35) y el del Caso (paso 5b,
	 * {@code caso_sesion_numerador} de V47, pedido por {@code clinical.spi.CasoDirectory}).
	 * <b>Es la primera transaccion de este sistema que toma dos</b>, y eso introduce un riesgo que
	 * ninguna etapa anterior tuvo.
	 *
	 * <p>Cada numerador es un lock exclusivo de fila. Si dos cierres concurrentes los tomaran en
	 * orden distinto —uno historia&rarr;caso y el otro caso&rarr;historia— cada uno esperaria el
	 * lock que el otro ya tiene y <b>se bloquearian mutuamente</b>. Pasa apenas dos pacientes
	 * comparten caso, o un paciente tiene dos casos y se cierran dos sesiones a la vez: no hace
	 * falta nada exotico.
	 *
	 * <p>La regla es <b>historia primero, caso despues</b>, siempre, aunque la sesion no tenga
	 * caso y aunque el numero del caso parezca "el importante". Un orden total fijo sobre los
	 * recursos es lo unico que evita el ciclo de espera; no hay reintento que lo arregle sin
	 * devolverle un 409 al profesional. <b>Si alguna vez hay un tercer numerador, entra al final
	 * de esta lista, no en el medio.</b>
	 *
	 * <p>El caso ya fue validado al <b>iniciar</b> la sesion y {@code caso_id} no se puede cambiar
	 * despues, asi que el cierre no lo vuelve a validar: un caso que se cerro mientras la atencion
	 * transcurria <b>no</b> impide cerrarla. La atencion ocurrio, y negarle el cierre obligaria a
	 * elegir entre perder el registro clinico o reabrir el caso para poder guardarlo.
	 *
	 * <p><b>El paso 1 va primero</b>: si no, cada reintento consume un correlativo que despues nadie
	 * usa, y la numeracion del paciente queda con huecos que parecen sesiones borradas. Una sesion ya
	 * cerrada se devuelve tal cual, con su numero — RN-M14-005 pide resultado estable ante retry, y
	 * apretar dos veces "cerrar" es el caso normal.
	 *
	 * <p><b>El paso 4 va en una transaccion aparte</b> por la misma razon que en 05.02: crear la fila
	 * dentro de esta produce un deadlock entre los primeros cierres concurrentes de una historia
	 * clinica, y atrapar la excepcion no alcanza porque no des-marca la transaccion.
	 *
	 * <h2>Cerrar no cobra</h2>
	 *
	 * <p>DP-06 y la regla de la etapa: "cierre clinico != cobro". Este metodo no crea ninguna
	 * obligacion economica; la deriva despues AKINE-07.01 leyendo las sesiones cerradas. Atarlas
	 * haria que un problema de facturacion bloquee una historia clinica.
	 *
	 * <h2>READ_COMMITTED, como toda mutacion que toma un numerador</h2>
	 *
	 * <p>Es la regla que 05.02 dejo fijada: con {@code REPEATABLE READ} InnoDB fija la foto en la
	 * primera lectura consistente, que ocurre <b>antes</b> del lock, asi que las mutaciones que
	 * serializan van en {@code READ_COMMITTED}. Este metodo era la <b>unica</b> de las once que
	 * toman un numerador que no lo declaraba — se detecto al escribir los tests de integracion de
	 * AKINE-04.03, comparandolo contra las otras diez.
	 *
	 * <p>Que el descuido no se notara tiene una explicacion y no sirve como defensa: una
	 * transaccion lee siempre sus propias escrituras, asi que leer el numerador <b>despues</b> de
	 * incrementarlo devuelve el valor nuevo aun bajo {@code REPEATABLE READ}. Depender de eso es
	 * depender del orden de dos lineas dentro del metodo, no de una garantia declarada — y
	 * AKINE-04.03 acaba de convertir este metodo en el que toma <b>dos</b> numeradores.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public SesionView cerrar(
			OperatingActor actor, long consultorioId, long sesionId,
			CierreDeSesion cierre, long expectedVersion) {

		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));

		// PASO 1. Antes de tocar el numerador. Ver la cabecera.
		if (sesion.estaCerrada()) {
			return conPrevia(sesion, organizationId);
		}

		exigirVersion(sesion, expectedVersion);
		cierre.exigirMinimos();

		// PASO 4 y 5. La fila se asegura afuera; el incremento toma el lock y serializa.
		numeradorIniciador.asegurar(organizationId, sesion.getHistoriaClinicaId());
		numerador.incrementar(organizationId, sesion.getHistoriaClinicaId());
		int numero = numerador.leerUltimo(organizationId, sesion.getHistoriaClinicaId());

		// PASO 5b. SIEMPRE despues del 5: ver "Los dos numeradores" en la cabecera del metodo.
		Integer numeroEnCaso = sesion.getCasoId() == null
				? null
				: casos.siguienteNumeroDeSesion(organizationId, sesion.getCasoId());

		Instant ahora = Instant.now();
		long cerradaPor = actor.accountId();
		sesion.cerrar(cierre, numero, numeroEnCaso, ahora, cerradaPor);

		// Dentro de la transaccion, a proposito: una prestacion sin deuda NO se nota —nadie
		// reclama una factura que nunca existio— y el centro descubre el agujero cuando cuadra
		// la caja del mes. La contrapartida esta asumida en CierreDeSesionObserver.
		notificarCierre(sesion, cierre, numero, ahora, cerradaPor, organizationId);

		auditar(actor, AuditEvents.SESION_CERRADA, sesion.getId(), "ABIERTA", "CERRADA",
				Map.of("historiaClinicaId", String.valueOf(sesion.getHistoriaClinicaId()),
						"numero", String.valueOf(numero),
						"numeroEnCaso", String.valueOf(numeroEnCaso),
						"asistencia", String.valueOf(cierre.asistencia())),
				ahora);

		log.info("Sesion cerrada: sesionId={} numero={} numeroEnCaso={} historiaClinicaId={} "
						+ "asistencia={}",
				sesionId, numero, numeroEnCaso, sesion.getHistoriaClinicaId(), cierre.asistencia());

		return conPrevia(sesiones.save(sesion), organizationId);
	}

	/**
	 * Adjunta la evaluacion de la sesion ANTERIOR del mismo paciente.
	 *
	 * <p>Es la mitad "cambio" del requisito de la etapa, y viaja con la sesion en vez de en un
	 * endpoint aparte porque la pantalla la necesita en el mismo momento: mostrar "la vez pasada
	 * tenia 7" al lado del campo de dolor es lo que hace que el profesional cargue una evolucion
	 * real en vez de la que recuerda.
	 */
	/**
	 * Avisa del cierre a quien tenga que reaccionar.
	 *
	 * <p>El precio se lee de la Oferta ACA y no en el observador: quien reacciona no tiene por que
	 * conocer M27, y si cada observador lo leyera por su cuenta, dos de ellos podrian devengar
	 * contra precios distintos si alguien edita la oferta en el medio.
	 *
	 * <p>La lista puede estar vacia y eso es legitimo: { encounter} no sabe quien lo escucha.
	 */
	@SuppressWarnings("java:S107")
	private void notificarCierre(
			Sesion sesion,
			CierreDeSesion cierre,
			int numero,
			Instant ahora,
			long cerradaPorCuentaId,
			long organizationId) {

		var precio = ofertas.precioDe(organizationId, sesion.getConsultorioId(), sesion.getOfertaId());
		var aviso = new SesionCerrada(
				sesion.getId(),
				organizationId,
				sesion.getConsultorioId(),
				personaDe(sesion, organizationId),
				sesion.getOfertaId(),
				numero,
				cierre.asistencia() == Asistencia.PRESENTE,
				ahora,
				cerradaPorCuentaId,
				precio.map(PrecioDeOferta::precioBase).orElse(null),
				precio.map(PrecioDeOferta::moneda).orElse(null));

		observadores.forEach(observador -> observador.alCerrar(aviso));
	}

	/**
	 * La persona de la sesion, via su Historia Clinica.
	 *
	 * <p>La sesion no guarda { persona_id}: cuelga de la HC, y la HC es de quien es. Duplicar
	 * la persona en la sesion habilitaria que las dos discrepen, y no hay ninguna consulta que lo
	 * justifique.
	 */
	private long personaDe(Sesion sesion, long organizationId) {
		return historias.findPorId(organizationId, sesion.getHistoriaClinicaId())
				.map(HistoriaClinicaSnapshot::personaId)
				.orElseThrow(() -> new IllegalStateException(
						"La sesion " + sesion.getId() + " apunta a una historia clinica que no existe"));
	}

	private SesionView conPrevia(Sesion sesion, long organizationId) {
		return SesionView.de(sesion, sesiones
				.findPreviaEvaluada(organizationId, sesion.getHistoriaClinicaId(), sesion.getIniciadaEn())
				.map(previa -> new SesionView.EvaluacionPrevia(
						previa.getIniciadaEn(),
						previa.getDolorEva(),
						previa.getEvolucion() == null ? null : previa.getEvolucion().name()))
				.orElse(null));
	}

	/**
	 * El control optimista, en un solo lugar.
	 *
	 * <p>Se lanza el mismo tipo que JPA usaria para que el handler global lo mapee igual y el
	 * cliente vea un solo comportamiento; la diferencia es que aca se detecta antes de escribir,
	 * con un mensaje que nombra la situacion.
	 */
	private static void exigirVersion(Sesion sesion, long expectedVersion) {
		if (sesion.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La sesion " + sesion.getId() + " cambio desde que se leyo: version "
							+ expectedVersion + " contra " + sesion.getVersion());
		}
	}

	/**
	 * Lectura de una sesion. Exige {@code sesion:register} igual que la escritura.
	 *
	 * <h2>Se audita, y por eso ya no es {@code readOnly}</h2>
	 *
	 * <p>AKINE-04.01 fijo que <b>toda lectura clinica se audita</b> y no solo las mutaciones:
	 * "en una historia clinica el riesgo esta mas en quien la lee sin motivo que en quien la
	 * modifica". Esta operacion devuelve la evolucion entera de una atencion —dolor, objetivo,
	 * limitacion funcional, nota de cierre— y hasta AKINE-07.07 no dejaba rastro de nada.
	 *
	 * <p>La transaccion deja de ser {@code readOnly} por la misma razon por la que las trece
	 * lecturas de {@code clinical} tampoco lo son: con {@code readOnly} el flush de Hibernate
	 * queda en MANUAL y la fila de auditoria no llegaria nunca a la base. Es el precedente del
	 * modulo que ya resolvio este problema, no una invencion de esta etapa.
	 */
	@Transactional
	public SesionView ver(OperatingActor actor, long consultorioId, long sesionId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		// Despues de resolver: auditar un id que no existe o que es de otro tenant construiria
		// dentro de audit_event el mismo padron de existencia que el 404 uniforme existe para no
		// entregar. Es el mismo criterio que PermissionGuard aplica al rechazo por alcance.
		auditar(actor, AuditEvents.SESION_ACCEDIDA, sesion.getId(), null, null,
				Map.of("historiaClinicaId", String.valueOf(sesion.getHistoriaClinicaId()),
						"casoId", String.valueOf(sesion.getCasoId()),
						"cerrada", String.valueOf(sesion.estaCerrada())),
				Instant.now());

		return conPrevia(sesion, organizationId);
	}

	/**
	 * Una fila de auditoria de este modulo.
	 *
	 * <p><b>El {@code reason} va nulo</b>, y es una carencia declarada, no un olvido:
	 * {@code encounter} no pide justificacion de acceso. {@code clinical} la exige por el header
	 * {@code X-Justificacion-Acceso} y la guarda ahi; sumarla aca es un header obligatorio nuevo
	 * en cinco operaciones que el frontend ya consume, o sea un cambio de contrato, y queda
	 * elevado como decision.
	 *
	 * <p>Los detalles llevan ids y banderas, nunca contenido: ni la evolucion, ni el objetivo, ni
	 * la nota de cierre. {@code AuditEntry} lo prohibe y la tabla se consulta con
	 * {@code auditoria:read}, que no es un permiso clinico.
	 */
	private void auditar(
			OperatingActor actor, String eventType, Long sesionId,
			String estadoAnterior, String estadoNuevo,
			Map<String, String> detalles, Instant ahora) {

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_SESION,
				sesionId,
				estadoAnterior,
				estadoNuevo,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));
	}

	// =================================================================================
	// Precondiciones
	// =================================================================================

	/**
	 * Quien atiende: el profesional del turno, y si el turno no tiene uno, quien inicia.
	 *
	 * <p>Una oferta sin profesional requerido produce turnos sin profesional asignado —lo permite
	 * M27— pero una ATENCION siempre la da alguien. Cuando el turno lo trae, se exige que coincida
	 * con quien inicia: dejar que otro abra la sesion de un turno ajeno rompe la propiedad antes de
	 * que la sesion exista, y el control de {@code Sesion#exigirPropiedadDe} ya no podria salvarla.
	 */
	private long resolverProfesional(
			OperatingActor actor, long organizationId, long consultorioId, TurnoSnapshot turno) {

		long propio = membershipDe(actor, organizationId, consultorioId);
		if (turno.profesionalMembershipId() == null) {
			return propio;
		}
		if (turno.profesionalMembershipId() != propio) {
			throw new TurnoNoAtendibleException(turno.id(),
					"lo atiende otro profesional");
		}
		return propio;
	}

	/**
	 * La membership del actor en esta sede.
	 *
	 * <p>Es lo que identifica al profesional, y no la cuenta: la misma persona puede ser
	 * profesional en un centro y administrativa en otro. Es la misma decision que V23 tomo para la
	 * disponibilidad y V28 para las habilitaciones.
	 */
	/**
	 * El caso tiene que existir, ser de esta historia y estar activo (04.03).
	 *
	 * <p>Se resuelve por {@code clinical.spi.CasoDirectory} y no leyendo {@code caso_clinico}: esa
	 * tabla es de {@code clinical} y la regla 1 de AGENT.md seccion 4 no admite que otro modulo la
	 * toque. El spi responde existencia, pertenencia y vigencia, y <b>no contenido clinico</b>.
	 *
	 * <p>El rechazo lo produce este modulo, no aquel: el spi no autoriza nada. Es la misma division
	 * que {@code HistoriaClinicaDirectory} ya tenia.
	 */
	private void exigirCasoAsignable(long organizationId, long historiaClinicaId, Long casoId) {
		if (casoId == null) {
			return;
		}
		var caso = casos.find(organizationId, casoId)
				.orElseThrow(() -> new CasoNoAsignableException(
						casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));

		if (!caso.perteneceAHistoria(historiaClinicaId)) {
			throw new CasoNoAsignableException(
					casoId, CasoNoAsignableException.Motivo.DE_OTRA_HISTORIA);
		}
		if (!caso.activo()) {
			throw new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.CERRADO);
		}
	}

	private long membershipDe(OperatingActor actor, long organizationId, long consultorioId) {
		return memberships.findByAccount(organizationId, actor.accountId()).stream()
				.filter(ConsultorioMembershipSnapshot::active)
				.filter(membership -> membership.cubreConsultorio(consultorioId))
				.map(ConsultorioMembershipSnapshot::membershipId)
				.findFirst()
				.orElseThrow(() -> new AccessDeniedException(
						"La cuenta no tiene un vinculo activo con la sede " + consultorioId));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException("La atencion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirRegistro(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.SESION_REGISTER,
				organizationId,
				consultorioId,
				null,
				Instant.now()));
	}
}
