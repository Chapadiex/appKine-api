package com.akine.encounter.application;

import com.akine.encounter.domain.ParametroAplicado;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.domain.TratamientoParametro;
import com.akine.encounter.domain.TratamientoRealizado;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.EspacioNoAccesibleException;
import com.akine.encounter.domain.exception.EspacioNoOperableException;
import com.akine.encounter.domain.exception.PracticaNoUtilizableException;
import com.akine.encounter.domain.exception.ProfesionalNoAsignableException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TratamientoNoAccesibleException;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoParametroRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tratamientos realmente aplicados en una sesion y espacios realmente utilizados (M14/M04/M06,
 * RF-M14-005 y RF-M04-005, AKINE-06.04).
 *
 * <h2>1. RN-M14-004: planificado no equivale a realizado</h2>
 *
 * <p>04.04 registro lo que un profesional <b>decidio</b> hacer; 06.01–06.05 registraron que
 * <b>hubo una atencion</b>. Este servicio escribe el dato del medio, que nunca existio: <b>que
 * prestaciones se aplicaron en esa atencion</b>.
 *
 * <p><b>Ninguna operacion de esta clase toca el Plan de Tratamiento</b>, ni lee {@code plan_item},
 * ni marca ningun item como cumplido. Atarlos haria que registrar una intervencion moviera el
 * plan, que es exactamente la confusion que 04.04 se nego a cometer cuando decidio no tener
 * columna {@code cantidad_realizada}.
 *
 * <h2>2. Lo que serializa las escrituras es el @Version de la SESION, forzado</h2>
 *
 * <p>Escribir un tratamiento <b>no toca ni una columna de {@code sesion}</b>, y un {@code @Version}
 * sobre el padre no protege una escritura que solo toca tablas hijas — la leccion de 02.07,
 * {@code b8bbc67}, pagada con un 409 que no aparecia nunca. Por eso las tres mutaciones avanzan la
 * version con un {@code UPDATE ... WHERE version = :esperada} propio
 * ({@code SesionRepositoryPort#avanzarVersion}): cero filas es conflicto, y la fila queda bloqueada
 * hasta el commit. No se usa {@code OPTIMISTIC_FORCE_INCREMENT} sobre la lectura: contra MySQL no
 * emitia ningun {@code UPDATE} y la base quedaba en la version leida.
 *
 * <p><b>Y la reciproca se respeta</b>, que es la otra mitad de la regla y la que 04.02 pago: estas
 * escrituras no ensucian la sesion por ningun otro camino, asi que la version avanza <b>una</b> vez
 * y la respuesta devuelve {@code leida+1}. Si alguna vez se agregara a {@code sesion} una columna
 * de resumen —cuantos tratamientos, minutos totales— <b>habria que sacar el force-increment</b>.
 * Ese resumen no debe existir: se deriva al leer.
 *
 * <h2>3. La propiedad no es un permiso, y la co-atencion no la relaja</h2>
 *
 * <p>Dos profesionales de la misma sede tienen el mismo {@code sesion:register}. Lo que impide que
 * uno escriba en la atencion del otro es {@code Sesion#exigirPropiedadDe}, y escribir en la
 * atencion ajena es <b>409, no 403</b>.
 *
 * <p><b>Anotar que otro profesional co-atendio no le da permiso de escritura.</b> El
 * co-atendiente queda registrado por quien conduce la atencion, que es como funciona en la
 * practica clinica.
 *
 * <h2>4. Solo se escribe en una sesion abierta</h2>
 *
 * <p>Una sesion cerrada no se edita: se enmienda, y la enmienda es 06.06. Fail-closed, igual que
 * el borrador y la evaluacion — es preferible no poder corregir a corregir sin dejar rastro,
 * porque lo segundo es historia clinica reescrita en silencio.
 *
 * <h2>5. Lo que este servicio NO valida, y es deliberado</h2>
 *
 * <ul>
 *   <li><b>Ocupacion y capacidad del espacio.</b> El plan las nombra entre las validaciones y
 *       aplicarlas aca seria la regla maestra 4 al reves: un tratamiento realizado es un
 *       <b>hecho consumado</b>, y rechazarlo porque el box figuraba ocupado no impide la
 *       sobreocupacion —ya paso— sino que impide <b>documentarla</b>, dejando la historia clinica
 *       diciendo que la sesion no tuvo espacio. La ocupacion es regla de RESERVA y su lugar es
 *       05.02 y RF-M04-004.</li>
 *   <li><b>Que parametros exige cada practica.</b> {@code plan_sesiones.txt} 10.4 los pide
 *       condicionales por tipo de practica y <b>no existe configuracion que los declare</b>: M06
 *       tiene practicas, no esquemas de parametros. Inventar ese catalogo seria decidir por el
 *       usuario. Se valida que cada parametro este <b>tipado</b>, que es lo que el caso borde del
 *       plan exige.</li>
 * </ul>
 */
@Service
public class TratamientoService {

	private static final Logger log = LoggerFactory.getLogger(TratamientoService.class);

	private final SesionRepositoryPort sesiones;
	private final TratamientoRepositoryPort tratamientos;
	private final TratamientoParametroRepositoryPort parametros;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;
	private final CatalogoDirectory catalogo;
	private final EspacioDirectory espacios;
	private final AuditTrail auditTrail;

	@SuppressWarnings("java:S107")
	public TratamientoService(
			SesionRepositoryPort sesiones,
			TratamientoRepositoryPort tratamientos,
			TratamientoParametroRepositoryPort parametros,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard,
			CatalogoDirectory catalogo,
			EspacioDirectory espacios,
			AuditTrail auditTrail) {

		this.sesiones = sesiones;
		this.tratamientos = tratamientos;
		this.parametros = parametros;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
		this.catalogo = catalogo;
		this.espacios = espacios;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Escrituras
	// =================================================================================

	/**
	 * Registra una intervencion aplicada en la sesion (RF-M14-005).
	 *
	 * <p>El {@code orden} lo asigna el servidor, nunca el cliente: es la secuencia <b>cronologica</b>
	 * de la visita, no una preferencia, y dejarlo entrar desde afuera permitiria intercalar una
	 * intervencion en un pasado que no ocurrio asi.
	 *
	 * @param expectedVersion version de la SESION que el cliente leyo
	 * @throws SesionNotAccessibleException  la sesion no existe o es de otro tenant/sede (404)
	 * @throws SesionCerradaException        la sesion ya se cerro; corregir es 06.06 (409)
	 * @throws PracticaNoUtilizableException la practica no existe (404) o no es vigente (409)
	 */
	@Transactional
	public TratamientoView registrar(
			OperatingActor actor,
			long consultorioId,
			long sesionId,
			TratamientoAplicado aplicado,
			long expectedVersion) {

		long organizationId = exigirContexto(actor);
		Sesion sesion = sesionParaEscribir(actor, organizationId, consultorioId, sesionId,
				expectedVersion);

		aplicado.exigirCoherente();
		CatalogoSnapshot practica = exigirPracticaUtilizable(organizationId, aplicado.practicaId());
		EspacioSnapshot espacio = resolverEspacio(organizationId, consultorioId, sesion, aplicado);
		long profesional = resolverProfesional(organizationId, consultorioId, sesion, aplicado);

		Instant ahora = Instant.now();

		// El proximo orden sale del maximo INCLUIDAS las bajas: el orden no se reutiliza. No es
		// el MAX+1 que el repositorio prohibe —ver el javadoc del puerto—: la fila `sesion` que
		// se leyo con force-increment serializa a los escritores.
		int orden = tratamientos.ultimoOrden(organizationId, sesionId) + 1;

		TratamientoRealizado tratamiento = new TratamientoRealizado(
				organizationId,
				consultorioId,
				sesionId,
				orden,
				practica.id(),
				practica.codigo(),
				practica.name(),
				profesional,
				ahora,
				actor.accountId());

		aplicar(tratamiento, aplicado, practica, espacio, profesional, ahora);

		TratamientoRealizado guardado = tratamientos.save(tratamiento);
		List<TratamientoParametro> guardados =
				reemplazarParametros(organizationId, guardado.getId(), aplicado.parametros(), ahora);

		auditar(actor, guardado, AuditEvents.TRATAMIENTO_REGISTRADO, null);

		log.info("Tratamiento registrado: sesionId={} tratamientoId={} orden={} practicaId={} "
						+ "espacioId={}",
				sesionId, guardado.getId(), orden, practica.id(), guardado.getEspacioId());

		return TratamientoView.de(guardado, guardados, versionDe(sesion));
	}

	/**
	 * Reemplaza una intervencion completa, <b>con sus parametros</b> (RF-M14-005).
	 *
	 * <p>Es un reemplazo y no un parche, y por eso no hay endpoint por parametro: el enunciado de
	 * la etapa pide "evitar endpoint por cada tipo si modelo polimorfico existente resuelve".
	 * Los parametros se borran y se reinsertan <b>en la misma transaccion</b>: son hijos sin
	 * identidad propia hacia afuera.
	 *
	 * <p>No cambia {@code orden} ni la autoria del registro: son la identidad del hecho. Lo que
	 * cambia es <b>que</b> se hizo, no <b>cuando</b> en la secuencia ni <b>quien</b> lo asento.
	 */
	@Transactional
	public TratamientoView reemplazar(
			OperatingActor actor,
			long consultorioId,
			long sesionId,
			long tratamientoId,
			TratamientoAplicado aplicado,
			long expectedVersion) {

		long organizationId = exigirContexto(actor);
		Sesion sesion = sesionParaEscribir(actor, organizationId, consultorioId, sesionId,
				expectedVersion);

		aplicado.exigirCoherente();
		TratamientoRealizado tratamiento = cargarVigente(organizationId, sesionId, tratamientoId);

		CatalogoSnapshot practica = exigirPracticaUtilizable(organizationId, aplicado.practicaId());
		EspacioSnapshot espacio = resolverEspacio(organizationId, consultorioId, sesion, aplicado);
		long profesional = resolverProfesional(organizationId, consultorioId, sesion, aplicado);

		Instant ahora = Instant.now();
		aplicar(tratamiento, aplicado, practica, espacio, profesional, ahora);

		TratamientoRealizado guardado = tratamientos.save(tratamiento);
		List<TratamientoParametro> guardados =
				reemplazarParametros(organizationId, guardado.getId(), aplicado.parametros(), ahora);

		auditar(actor, guardado, AuditEvents.TRATAMIENTO_MODIFICADO, null);

		log.info("Tratamiento reemplazado: sesionId={} tratamientoId={} practicaId={}",
				sesionId, tratamientoId, practica.id());

		return TratamientoView.de(guardado, guardados, versionDe(sesion));
	}

	/**
	 * Da de baja una intervencion registrada por error.
	 *
	 * <p><b>Baja logica con motivo obligatorio</b>, nunca {@code DELETE}: el motivo es el mismo un
	 * dato clinico —"se suspendio la electroterapia porque el paciente refirio molestia"— y sin el
	 * la auditoria no responde por que seis meses despues.
	 *
	 * <p><b>El {@code orden} no se libera.</b> El proximo tratamiento sigue tomando el maximo mas
	 * uno, y por eso {@code uk_tratamiento_orden} lleva {@code deleted_key}.
	 *
	 * <p>Es idempotente: dar de baja dos veces no cambia el motivo ni el instante originales.
	 */
	@Transactional
	public void darDeBaja(
			OperatingActor actor,
			long consultorioId,
			long sesionId,
			long tratamientoId,
			String motivo,
			long expectedVersion) {

		long organizationId = exigirContexto(actor);
		sesionParaEscribir(actor, organizationId, consultorioId, sesionId, expectedVersion);

		String razon = motivo == null || motivo.isBlank() ? null : motivo.strip();
		if (razon == null) {
			// 400 y no 409: no hay ningun estado que impida la operacion, falta un dato. Es el
			// mismo reparto que `reversion-sin-motivo` en 04.05.
			throw new IllegalArgumentException(
					"La baja de un tratamiento exige un motivo: sin el, la auditoria no responde "
							+ "por que seis meses despues");
		}

		// Sin filtrar por vigente: la baja es idempotente y tiene que poder encontrar la fila ya
		// dada de baja para no inventarle un 404 al segundo click.
		TratamientoRealizado tratamiento = tratamientos
				.findEnSesion(organizationId, sesionId, tratamientoId)
				.orElseThrow(() -> new TratamientoNoAccesibleException(tratamientoId));

		tratamiento.darDeBaja(razon, Instant.now());
		tratamientos.save(tratamiento);

		auditar(actor, tratamiento, AuditEvents.TRATAMIENTO_DADO_DE_BAJA, razon);

		log.info("Tratamiento dado de baja: sesionId={} tratamientoId={}", sesionId, tratamientoId);
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	/**
	 * Las intervenciones vigentes de la sesion, en orden cronologico.
	 *
	 * <p><b>Se audita la lectura</b>, no solo la escritura: DP-03 no distingue entre leer y
	 * escribir en una historia clinica, y un tratamiento realizado <b>es</b> contenido clinico
	 * —dice que se le hizo al paciente y en que zona—.
	 *
	 * <p>No exige propiedad de la sesion: leer lo que se le hizo al paciente es lo que necesita
	 * quien continua su tratamiento. Lo que exige es {@code sesion:register} en la sede, igual que
	 * el resto del modulo, y hereda el hueco conocido del alcance {@code OWN}.
	 */
	@Transactional(readOnly = true)
	public List<TratamientoView> listar(OperatingActor actor, long consultorioId, long sesionId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		List<TratamientoView> vistas = tratamientos.listarVigentes(organizationId, sesionId).stream()
				.map(tratamiento -> TratamientoView.de(
						tratamiento,
						parametros.listarDe(organizationId, tratamiento.getId()),
						sesion.getVersion()))
				.toList();

		auditarLectura(actor, sesion, vistas.size());
		return vistas;
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * La sesion sobre la que se va a escribir, con su version ya forzada a avanzar.
	 *
	 * <p>Concentra los cuatro controles que toda mutacion comparte: sede del tenant, permiso,
	 * propiedad de la atencion y control optimista. Y avanza la version con {@code avanzarVersion}
	 * porque la escritura que viene no toca ninguna columna de la sesion — ver el punto 2 de la
	 * cabecera.
	 */
	private Sesion sesionParaEscribir(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long sesionId,
			long expectedVersion) {

		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		Sesion sesion = sesiones.findByIdInScope(organizationId, consultorioId, sesionId)
				.filter(Sesion::estaViva)
				.orElseThrow(() -> new SesionNotAccessibleException(sesionId));

		// Propiedad, no permiso: escribir en la atencion ajena es 409, no 403.
		sesion.exigirPropiedadDe(membershipDe(actor, organizationId, consultorioId));

		// Una sesion cerrada no se edita: se enmienda, y eso es 06.06.
		if (sesion.estaCerrada()) {
			throw new SesionCerradaException(sesionId);
		}

		exigirVersion(sesion, expectedVersion);

		// Una sola vez, y en la base: la entidad leida queda con la version vieja (no se ensucia,
		// asi que nada mas la escribe) y las respuestas anuncian expectedVersion + 1.
		if (sesiones.avanzarVersion(organizationId, consultorioId, sesionId, expectedVersion) != 1) {
			throw new OptimisticLockingFailureException(
					"La sesion " + sesionId + " cambio desde que se leyo: version " + expectedVersion);
		}
		return sesion;
	}

	/**
	 * La practica del catalogo M06, exigiendo que se pueda <b>elegir hoy</b>.
	 *
	 * <p>Se usa {@code findPractica} —que devuelve tambien las dadas de baja— y la vigencia se
	 * decide aca, porque las dos situaciones tienen respuestas distintas: no existe es <b>404</b>
	 * e indistinguible de "es de otro tenant" —distinguirlas permitiria censar el catalogo propio
	 * de otro centro—, y existe pero no es vigente es <b>409</b>, porque lleva a otra accion:
	 * elegir otra practica, no buscar el id.
	 *
	 * <p>La vigencia se evalua <b>al registrar</b>. Lo ya guardado sigue resolviendo aunque la
	 * practica se de de baja despues: para eso estan los snapshots de codigo y nombre.
	 */
	private CatalogoSnapshot exigirPracticaUtilizable(long organizationId, long practicaId) {
		CatalogoSnapshot practica = catalogo
				.findPractica(organizationId, practicaId, Instant.now())
				.orElseThrow(() -> new PracticaNoUtilizableException(
						practicaId, PracticaNoUtilizableException.Motivo.INEXISTENTE));

		if (!practica.vigente()) {
			throw new PracticaNoUtilizableException(
					practicaId, PracticaNoUtilizableException.Motivo.NO_VIGENTE);
		}
		return practica;
	}

	/**
	 * El espacio realmente utilizado (RF-M04-005), o {@code null} si no se declaro.
	 *
	 * <p><b>La vigencia se evalua en el instante de la ATENCION</b> ({@code sesion.iniciadaEn}) y
	 * no en el de la carga. Evaluarla "ahora" haria que registrar el viernes una atencion del
	 * lunes fallara porque el box se dio de baja el miercoles, y eso es negar un hecho ocurrido.
	 *
	 * <p><b>No se valida ocupacion ni capacidad.</b> Ver el punto 5 de la cabecera: es un hecho
	 * consumado, no una reserva.
	 */
	private EspacioSnapshot resolverEspacio(
			long organizationId, long consultorioId, Sesion sesion, TratamientoAplicado aplicado) {

		if (aplicado.espacioId() == null) {
			return null;
		}
		long espacioId = aplicado.espacioId();

		EspacioSnapshot espacio = espacios.find(organizationId, espacioId, sesion.getIniciadaEn())
				.orElseThrow(() -> new EspacioNoAccesibleException(espacioId));

		// De otra sede es 404 y no 409: un 409 confirmaria que el id existe en el tenant, y
		// bastaria probar ids consecutivos para censar los boxes de otra sede.
		if (espacio.consultorioId() != consultorioId) {
			throw new EspacioNoAccesibleException(espacioId);
		}
		if (!espacio.enServicio()) {
			throw new EspacioNoOperableException(espacioId);
		}
		return espacio;
	}

	/**
	 * El profesional que aplico la intervencion: el declarado, o el de la sesion.
	 *
	 * <p>El declarado tiene que tener membership <b>vigente en esa sede</b>; si no, <b>409</b> y
	 * no 403, porque quien opera si tiene permiso —lo que no sirve es el dato que declaro—.
	 *
	 * <p>Declarar al profesional de la propia sesion es el caso normal y no vuelve a validarse:
	 * ya paso por {@code exigirPropiedadDe}.
	 */
	private long resolverProfesional(
			long organizationId, long consultorioId, Sesion sesion, TratamientoAplicado aplicado) {

		Long declarado = aplicado.profesionalMembershipId();
		if (declarado == null || declarado.equals(sesion.getProfesionalMembershipId())) {
			return sesion.getProfesionalMembershipId();
		}

		boolean vigenteEnLaSede = memberships.find(organizationId, declarado)
				.filter(ConsultorioMembershipSnapshot::active)
				.filter(membership -> membership.cubreConsultorio(consultorioId))
				.isPresent();

		if (!vigenteEnLaSede) {
			throw new ProfesionalNoAsignableException(declarado);
		}
		return declarado;
	}

	/** Vuelca el comando sobre la entity, con los snapshots ya resueltos. */
	@SuppressWarnings("java:S107")
	private static void aplicar(
			TratamientoRealizado tratamiento,
			TratamientoAplicado aplicado,
			CatalogoSnapshot practica,
			EspacioSnapshot espacio,
			long profesionalMembershipId,
			Instant ahora) {

		tratamiento.redefinir(
				practica.id(),
				practica.codigo(),
				practica.name(),
				limpiar(aplicado.tecnica()),
				limpiar(aplicado.zona()),
				aplicado.lateralidad(),
				aplicado.duracionMinutos(),
				profesionalMembershipId,
				espacio == null ? null : espacio.id(),
				espacio == null ? null : espacio.name(),
				limpiar(aplicado.observacion()),
				ahora);
	}

	/**
	 * Borra y reinserta los parametros del tratamiento.
	 *
	 * <p>El borrado es <b>fisico</b> y es la unica excepcion a la baja logica de la etapa: un
	 * parametro es un atributo del tratamiento, como lo seria una columna. Ver el javadoc del
	 * puerto y la cabecera de V55.
	 *
	 * <p>El {@code orden} sale de la posicion en la lista: es lo que el cliente mando, y es lo que
	 * la pantalla va a querer mostrar.
	 */
	private List<TratamientoParametro> reemplazarParametros(
			long organizationId,
			long tratamientoId,
			List<ParametroAplicado> declarados,
			Instant ahora) {

		parametros.borrarDe(organizationId, tratamientoId);

		List<TratamientoParametro> guardados = new java.util.ArrayList<>(declarados.size());
		for (int posicion = 0; posicion < declarados.size(); posicion++) {
			guardados.add(parametros.save(new TratamientoParametro(
					organizationId, tratamientoId, declarados.get(posicion), posicion, ahora)));
		}
		return List.copyOf(guardados);
	}

	private TratamientoRealizado cargarVigente(
			long organizationId, long sesionId, long tratamientoId) {

		return tratamientos.findEnSesion(organizationId, sesionId, tratamientoId)
				.filter(TratamientoRealizado::estaVigente)
				.orElseThrow(() -> new TratamientoNoAccesibleException(tratamientoId));
	}

	/**
	 * La version que la respuesta tiene que devolver.
	 *
	 * <p>Es {@code leida + 1} y se calcula en vez de releerse: el force-increment todavia no
	 * commiteo, asi que la entity en memoria sigue diciendo la version vieja. Devolver esa
	 * dejaria al cliente con una version que ya no existe y su proxima operacion comeria un 409
	 * del que no puede salir — que es exactamente el defecto que 04.02 pago, visto desde el otro
	 * lado.
	 */
	private static long versionDe(Sesion sesion) {
		return sesion.getVersion() + 1;
	}

	private static String limpiar(String texto) {
		return texto == null || texto.isBlank() ? null : texto.strip();
	}

	private static void exigirVersion(Sesion sesion, long expectedVersion) {
		if (sesion.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"La sesion " + sesion.getId() + " cambio desde que se leyo: version "
							+ expectedVersion + " contra " + sesion.getVersion());
		}
	}

	// =================================================================================
	// Autorizacion y auditoria
	// =================================================================================

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException(
					"Registrar un tratamiento requiere un contexto de trabajo activo");
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

	private long membershipDe(OperatingActor actor, long organizationId, long consultorioId) {
		return memberships.findByAccount(organizationId, actor.accountId()).stream()
				.filter(ConsultorioMembershipSnapshot::active)
				.filter(membership -> membership.cubreConsultorio(consultorioId))
				.map(ConsultorioMembershipSnapshot::membershipId)
				.findFirst()
				.orElseThrow(() -> new AccessDeniedException(
						"La cuenta no tiene un vinculo activo con la sede " + consultorioId));
	}

	/**
	 * Deja rastro del hecho, <b>dentro de la transaccion del negocio</b>.
	 *
	 * <p>Es la regla que 01.01 dejo fijada: un listener posterior al commit que falla deja la
	 * mutacion sin rastro, y en un modulo clinico eso es peor que perder la mutacion.
	 */
	private void auditar(
			OperatingActor actor, TratamientoRealizado tratamiento, String eventType, String razon) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("sesionId", String.valueOf(tratamiento.getSesionId()));
		detalles.put("orden", String.valueOf(tratamiento.getOrden()));
		detalles.put("practicaId", String.valueOf(tratamiento.getPracticaId()));
		if (tratamiento.getEspacioId() != null) {
			detalles.put("espacioId", String.valueOf(tratamiento.getEspacioId()));
		}

		auditTrail.record(new AuditEntry(
				tratamiento.getOrganizationId(),
				tratamiento.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_TRATAMIENTO,
				tratamiento.getId(),
				null,
				null,
				detalles,
				razon,
				AuditEvents.correlationId(),
				Instant.now()));
	}

	/** DP-03: toda lectura clinica se audita, no solo las mutaciones. */
	private void auditarLectura(OperatingActor actor, Sesion sesion, int cuantos) {
		auditTrail.record(new AuditEntry(
				sesion.getOrganizationId(),
				sesion.getConsultorioId(),
				actor.accountId(),
				AuditEvents.TRATAMIENTO_CONSULTADO,
				AuditEvents.ENTITY_TRATAMIENTO,
				sesion.getId(),
				null,
				null,
				Map.of("sesionId", String.valueOf(sesion.getId()),
						"cantidad", String.valueOf(cuantos)),
				null,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
