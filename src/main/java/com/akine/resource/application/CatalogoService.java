package com.akine.resource.application;

import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CatalogoAlcance;
import com.akine.resource.domain.CatalogoAlcanceFiltro;
import com.akine.resource.domain.CatalogoConcepto;
import com.akine.resource.domain.CatalogoTipo;
import com.akine.resource.domain.Especialidad;
import com.akine.resource.domain.Nomenclador;
import com.akine.resource.domain.NomencladorItem;
import com.akine.resource.domain.PermissionCodes;
import com.akine.resource.domain.Practica;
import com.akine.resource.domain.exception.CatalogoCodeTakenException;
import com.akine.resource.domain.exception.CatalogoHasActiveReferencesException;
import com.akine.resource.domain.exception.CatalogoInactiveException;
import com.akine.resource.domain.exception.CatalogoNameTakenException;
import com.akine.resource.domain.exception.CatalogoNotAccessibleException;
import com.akine.resource.domain.exception.CatalogoScopeMismatchException;
import com.akine.resource.domain.exception.NomencladorVigenciaOverlapException;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Catalogo clinico: especialidades, practicas, nomencladores y vigencias (M06).
 *
 * <h2>La distincion que sostiene toda la etapa</h2>
 *
 * <p>Un concepto es <b>GLOBAL</b> —del catalogo de plataforma, sin {@code organizationId}— o
 * <b>CONTEXTUAL</b> —de un tenant—. Todo lo demas de esta clase se deriva de esa unica
 * diferencia:
 *
 * <pre>
 *   QUIEN LO VE      global: todos los tenants        contextual: solo su duenio
 *   QUIEN LO MUTA    global: PLATFORM_ADMIN           contextual: admin del tenant
 *   DE QUE DEPENDE   global: solo de otros globales   contextual: de globales o propios
 * </pre>
 *
 * <p>La tercera fila es la menos obvia y la mas facil de romper: si una practica global colgara
 * de la especialidad de un tenant, ese tenant decidiria por si solo el destino de un concepto
 * que ven todos los demas. La FK no puede expresarlo —compara ids, no alcances— asi que lo
 * sostiene {@link #exigirAlcanceCompatible}.
 *
 * <h2>Autorizacion: lo que esta etapa usa y lo que corresponderia</h2>
 *
 * <p><b>El catalogo de permisos es vinculante y una etapa no le agrega filas</b> (matriz
 * §5). M06 necesita dos codigos que hoy no existen —{@code catalogo:read} y
 * {@code catalogo:manage}— y esta etapa <b>no los inventa</b>: quedan PROPUESTOS en la seccion
 * 11 de {@code docs/seguridad/matriz-permisos-minima.md}, exactamente como 02.02 dejo propuesto
 * {@code espacio:read} antes de que se aprobara. Mientras tanto:
 *
 * <ul>
 *   <li><b>Lecturas</b>: autorizadas por <b>pertenencia</b>. Cualquier membership vigente en la
 *       organizacion del contexto puede consultar el catalogo —global y propio—. Es lo que hizo
 *       02.02 con las lecturas de espacios durante toda su vigencia, y el efecto practico es el
 *       mismo que tendria {@code catalogo:read} salvo por {@code PACIENTE}, que hoy pasa y con
 *       el codigo aprobado no pasaria.</li>
 *   <li><b>Mutaciones contextuales</b>: exigen {@code consultorio:manage} sobre la sede del
 *       contexto, que es el permiso que la matriz §5 le asigna a la administracion del centro.
 *       Pasan {@code ORG_ADMIN} y {@code CONSULTORIO_ADMIN}, que es literalmente lo que la
 *       etapa pide ("admin consultorio para conceptos contextuales/solicitudes").</li>
 *   <li><b>Mutaciones globales</b>: exigen rol de plataforma, sin ningun permiso de tenant de
 *       por medio. Es el mismo tratamiento que el catalogo de planes: no es dato de un tenant,
 *       es la oferta comun.</li>
 * </ul>
 *
 * <p><b>Y lo que deliberadamente NO se concede:</b> un {@code PLATFORM_ADMIN} <b>no ve ni muta
 * conceptos contextuales de un tenant</b>. No es un olvido: el catalogo propio de un centro es
 * informacion comercial suya, no hay ninguna operacion de rescate que exija tocarlo, y
 * concederlo obligaria ademas a exigir {@code support_access} y a auditar cada lectura. Se
 * aparta hacia el lado que no concede de mas, igual que §10.2 de la matriz hizo con los
 * espacios.
 *
 * <h2>Codigos, y por que no son intercambiables</h2>
 *
 * <ul>
 *   <li><b>404</b> para un concepto de otro tenant o inexistente. Un 403 confirmaria que ese id
 *       existe y bastaria recorrer numeros para averiguar que practicas propias tiene cada
 *       centro del SaaS (ADR-0018).</li>
 *   <li><b>403</b> cuando el actor ve el concepto y le falta el permiso —un admin de tenant
 *       editando uno global— o cuando no eligio contexto de trabajo. <b>Nunca 401</b>: el
 *       interceptor del frontend borra el token ante cualquier 401 y dejaria al usuario en un
 *       bucle de login.</li>
 *   <li><b>409</b> para los invariantes de estado: codigo o nombre repetidos, concepto ya
 *       inactivo, version desactualizada, vigencias solapadas, alcance incompatible,
 *       referencias vigentes.</li>
 * </ul>
 *
 * <p>Un concepto INACTIVO se lee con <b>200</b>, no con 404: RN-M06-001 y RN-M06-002 exigen que
 * los historicos sigan resolviendo, y responder "no existe" seria borrar historia por la puerta
 * de atras.
 */
@Service
public class CatalogoService {

	/**
	 * Centinela del duenio "plataforma" en {@code owner_key}.
	 *
	 * <p>0 no es ni puede ser el id de ninguna organizacion: {@code organization.id} es
	 * AUTO_INCREMENT y arranca en 1. Ver la cabecera de la migracion V20.
	 */
	private static final long OWNER_PLATAFORMA = 0L;

	/** Duenio que no existe. Se usa para que una consulta imposible devuelva vacio sin romper. */
	private static final long OWNER_NINGUNO = -1L;

	private static final Logger log = LoggerFactory.getLogger(CatalogoService.class);

	private final CatalogoRepositoryPorts.EspecialidadPort especialidades;
	private final CatalogoRepositoryPorts.PracticaPort practicas;
	private final CatalogoRepositoryPorts.NomencladorPort nomencladores;
	private final CatalogoRepositoryPorts.NomencladorItemPort vigencias;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public CatalogoService(
			CatalogoRepositoryPorts.EspecialidadPort especialidades,
			CatalogoRepositoryPorts.PracticaPort practicas,
			CatalogoRepositoryPorts.NomencladorPort nomencladores,
			CatalogoRepositoryPorts.NomencladorItemPort vigencias,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.especialidades = especialidades;
		this.practicas = practicas;
		this.nomencladores = nomencladores;
		this.vigencias = vigencias;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Un concepto por id.
	 *
	 * <p>Devuelve tambien los INACTIVOS, con 200: RN-M06-001 y RN-M06-002 exigen que los
	 * historicos conserven su nombre y su estado. Lo que un concepto inactivo rechaza son las
	 * operaciones nuevas, y eso se responde con 409.
	 */
	@Transactional(readOnly = true)
	public CatalogoConceptoView find(OperatingActor actor, CatalogoTipo tipo, long conceptoId) {
		List<Long> owners = ownersVisibles(actor);
		Instant ahora = Instant.now();
		return switch (tipo) {
			case ESPECIALIDAD -> CatalogoConceptoView.de(
					cargarEspecialidad(conceptoId, owners), tipo, ahora);
			case PRACTICA -> CatalogoConceptoView.dePractica(
					cargarPractica(conceptoId, owners), ahora);
			case NOMENCLADOR -> CatalogoConceptoView.de(
					cargarNomenclador(conceptoId, owners), tipo, ahora);
		};
	}

	/**
	 * Busqueda incremental sobre el catalogo (RF-M06-004).
	 *
	 * <p>Devuelve el catalogo de plataforma y el propio del centro en la misma lista, que es
	 * como se elige una practica en la realidad, y con {@code estado = ACTIVO} por defecto, que
	 * es lo que hace que un selector nunca ofrezca un concepto dado de baja.
	 */
	@Transactional(readOnly = true)
	public List<CatalogoConceptoView> buscar(
			OperatingActor actor, CatalogoTipo tipo, CatalogoBusqueda filtros) {

		List<Long> owners = ownersSegunFiltro(actor, filtros.alcance());
		Instant ahora = Instant.now();
		String patron = filtros.patron();
		int activo = filtros.activoFiltro();

		return switch (tipo) {
			case ESPECIALIDAD -> especialidades.buscar(owners, patron, activo).stream()
					.map(e -> CatalogoConceptoView.de(e, tipo, ahora))
					.toList();
			case PRACTICA -> practicas
					.buscar(owners, patron, activo, filtros.especialidadFiltro()).stream()
					.map(p -> CatalogoConceptoView.dePractica(p, ahora))
					.toList();
			case NOMENCLADOR -> nomencladores.buscar(owners, patron, activo).stream()
					.map(n -> CatalogoConceptoView.de(n, tipo, ahora))
					.toList();
		};
	}

	/**
	 * Vigencias de un nomenclador (RF-M06-003).
	 *
	 * <p>Ordenadas por codigo y, dentro de cada codigo, de la mas nueva a la mas vieja: es el
	 * orden en el que la pantalla las tiene que mostrar, porque lo que se consulta primero es
	 * que rige hoy y despues que regia antes.
	 */
	@Transactional(readOnly = true)
	public List<CatalogoConceptoView> listarVigencias(
			OperatingActor actor, long nomencladorId, CatalogoBusqueda filtros, String codigo) {

		List<Long> owners = ownersVisibles(actor);
		cargarNomenclador(nomencladorId, owners);
		Instant ahora = Instant.now();

		return vigencias.listar(
						nomencladorId,
						owners,
						filtros.activoFiltro(),
						filtros.especialidadFiltro(),
						codigo == null ? "" : codigo.strip())
				.stream()
				.map(item -> CatalogoConceptoView.deVigencia(item, ahora))
				.toList();
	}

	// =================================================================================
	// Altas
	// =================================================================================

	/**
	 * Alta de una especialidad, una practica o un nomenclador.
	 *
	 * <p>No lleva {@code Idempotency-Key}, y es deliberado: un concepto de catalogo no consume
	 * cupo de ningun plan, asi que lo unico que un reintento podria producir es una fila
	 * duplicada, y contra eso el unique de codigo entre los conceptos vigentes es una garantia
	 * mas fuerte que una clave —no depende de que el cliente la mande ni de que la reuse bien—.
	 * El reintento responde 409 y no crea nada. La contrapartida: despues de un timeout de red
	 * hay que releer el listado para saber si el alta original entro.
	 */
	@Transactional
	public CatalogoConceptoView crear(
			OperatingActor actor, CatalogoTipo tipo, CatalogoAltaCommand command) {

		CatalogoAlcance alcance = command.alcance() == null
				? CatalogoAlcance.ORGANIZACION
				: command.alcance();
		Long duenio = exigirGestionDe(actor, alcance);

		Instant ahora = Instant.now();
		Instant desde = command.validFrom() == null ? ahora : command.validFrom();
		String codigo = normalizar(command.codigo());
		String nombre = normalizar(command.name());

		CatalogoConcepto creado = switch (tipo) {
			case ESPECIALIDAD -> persistir(
					especialidades::saveAndFlush,
					new Especialidad(duenio, codigo, nombre, command.descripcion(),
							desde, command.validUntil()),
					codigo, nombre, alcance);
			case NOMENCLADOR -> persistir(
					nomencladores::saveAndFlush,
					new Nomenclador(duenio, codigo, nombre, command.descripcion(),
							desde, command.validUntil()),
					codigo, nombre, alcance);
			case PRACTICA -> persistir(
					practicas::saveAndFlush,
					new Practica(duenio, especialidadDeLaPractica(actor, alcance, command),
							codigo, nombre, command.descripcion(), desde, command.validUntil()),
					codigo, nombre, alcance);
		};

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("tipo", tipo.name());
		detalles.put("alcance", alcance.name());
		detalles.put("codigo", codigo);
		auditar(AuditEvents.CATALOGO_CREATED, creado, tipo, actor, null, "ACTIVO", null,
				detalles, ahora);

		log.info("Concepto de catalogo creado: tipo={} alcance={} conceptoId={}",
				tipo, alcance, creado.getId());

		return vistaDe(creado, tipo, ahora);
	}

	/**
	 * Alta de una vigencia dentro de un nomenclador (RF-M06-003, RN-M06-003).
	 *
	 * <h2>El orden de las sentencias, que es lo unico que hace correcto este metodo</h2>
	 *
	 * <ol>
	 *   <li><b>Lock exclusivo sobre la fila del nomenclador padre</b>, y es la PRIMERA sentencia
	 *       que toca datos. Dos altas simultaneas del mismo codigo se serializan aca.</li>
	 *   <li>Autorizacion, ya conocido el alcance del padre.</li>
	 *   <li>Consulta de solapamientos, <b>despues</b> del lock. Adelantarla no serviria de nada:
	 *       una lectura no bloqueante fija el snapshot y devolveria datos anteriores al commit
	 *       del competidor aunque el lock ya se hubiera adquirido. El lock serializa el ACCESO,
	 *       no la VISIBILIDAD.</li>
	 *   <li>Insercion del hijo.</li>
	 * </ol>
	 *
	 * <p><b>{@code READ_COMMITTED} no es decorativo.</b> Bajo el {@code REPEATABLE READ} que
	 * MySQL trae por defecto, cualquier lectura anterior —la del evaluador de permisos, sin ir
	 * mas lejos— fijaria el snapshot de la transaccion y el paso 3 leeria datos viejos. Con
	 * {@code READ_COMMITTED} cada sentencia toma su propia foto y el conteo posterior al lock ve
	 * lo que el competidor acaba de commitear.
	 *
	 * <p>Y bloquear el PADRE antes de insertar el HIJO tambien evita el otro problema: un
	 * {@code INSERT} hijo toma lock compartido sobre el padre y lo escala a exclusivo al commit,
	 * con lo que dos altas simetricas se abrazan. El orden de bloqueo de este modulo queda fijo
	 * en {@code nomenclador -> nomenclador_item}.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CatalogoConceptoView crearVigencia(
			OperatingActor actor, long nomencladorId, VigenciaAltaCommand command) {

		List<Long> owners = ownersVisibles(actor);

		// 1. El lock, primero. Ver el javadoc.
		Nomenclador nomenclador = nomencladores.findVisibleForUpdate(nomencladorId, owners)
				.orElseThrow(() -> new CatalogoNotAccessibleException(nomencladorId));

		// 2. Autorizacion, ya conocido el alcance del padre.
		exigirGestionSobre(actor, nomenclador);
		if (!nomenclador.isOperable()) {
			throw new CatalogoInactiveException(
					nomencladorId, CatalogoInactiveException.Operacion.REFERENCIA);
		}

		Practica practica = cargarPractica(exigirId(command.practicaId(), "practicaId"), owners);
		if (!practica.isOperable()) {
			throw new CatalogoInactiveException(
					practica.getId(), CatalogoInactiveException.Operacion.REFERENCIA);
		}
		exigirAlcanceCompatible(nomenclador, practica);

		Instant ahora = Instant.now();
		Instant desde = command.validFrom() == null ? ahora : command.validFrom();
		String codigo = normalizar(command.codigo());

		// 3. Los solapamientos, DESPUES del lock.
		exigirSinSolapamiento(nomencladorId, codigo, desde, command.validUntil());

		NomencladorItem item = new NomencladorItem(
				nomenclador.getOrganizationId(),
				nomencladorId,
				practica.getId(),
				codigo,
				normalizar(command.name()),
				command.descripcion(),
				command.valorReferencia(),
				desde,
				command.validUntil());

		NomencladorItem persistido;
		try {
			persistido = vigencias.saveAndFlush(item);
		} catch (DataIntegrityViolationException choque) {
			// Dos vigencias que arrancan en el MISMO instante: el unico solapamiento que la base
			// atrapa sola. Despues de un flush fallido no se vuelve a tocar la sesion JPA.
			log.info("Alta de vigencia rechazada por la base: nomencladorId={} codigo={}",
					nomencladorId, codigo);
			throw new NomencladorVigenciaOverlapException(codigo, desde, command.validUntil());
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("nomencladorId", String.valueOf(nomencladorId));
		detalles.put("practicaId", String.valueOf(practica.getId()));
		detalles.put("codigo", codigo);
		detalles.put("validFrom", String.valueOf(desde));
		auditar(AuditEvents.CATALOGO_VIGENCIA_CREATED, persistido, CatalogoTipo.NOMENCLADOR,
				actor, null, "ACTIVO", null, detalles, ahora);

		log.info("Vigencia de nomenclador creada: nomencladorId={} itemId={}",
				nomencladorId, persistido.getId());

		return CatalogoConceptoView.deVigencia(persistido, ahora);
	}

	// =================================================================================
	// Edicion y baja
	// =================================================================================

	/** Edicion parcial. Ni el codigo ni el alcance se pueden cambiar: ver el comando. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CatalogoConceptoView editar(
			OperatingActor actor,
			CatalogoTipo tipo,
			long conceptoId,
			CatalogoEdicionCommand command) {

		List<Long> owners = ownersVisibles(actor);
		CatalogoConcepto concepto = cargar(tipo, conceptoId, owners);
		exigirGestionSobre(actor, concepto);
		exigirMutable(concepto, CatalogoInactiveException.Operacion.EDICION);
		exigirVersion(concepto, command.expectedVersion());

		Instant ahora = Instant.now();
		String nombre = command.name() == null ? null : normalizar(command.name());
		Map<String, String> detalles = cambios(concepto, nombre, command);

		concepto.updateDatos(
				nombre, command.descripcion(), command.validFrom(),
				command.validUntil(), command.clearValidUntil());
		if (concepto instanceof NomencladorItem item) {
			item.cambiarValor(command.valorReferencia());
		}

		CatalogoConcepto guardado;
		try {
			guardado = guardarConFlush(tipo, concepto);
		} catch (DataIntegrityViolationException choque) {
			log.info("Edicion de catalogo rechazada por nombre repetido: conceptoId={}",
					conceptoId);
			throw new CatalogoNameTakenException(nombre, concepto.esGlobal());
		}

		auditar(AuditEvents.CATALOGO_UPDATED, guardado, tipo, actor, null, null, null,
				detalles, ahora);
		return vistaDe(guardado, tipo, ahora);
	}

	/**
	 * Baja logica con motivo obligatorio (RN-M06-001).
	 *
	 * <p>NO borra nada: el concepto queda INACTIVO, sigue siendo legible por id y conserva su
	 * nombre y su codigo. Deja de ofrecerse para selecciones nuevas y libera su codigo y su
	 * nombre para un concepto nuevo del mismo duenio.
	 *
	 * <p><b>No hay reactivacion</b>: un concepto que vuelve es una vigencia nueva, no una baja
	 * deshecha, y modelarlo como baja deshecha borraria el rastro de que dejo de ofrecerse
	 * alguna vez. Si lo que se quiere es sacarlo de circulacion temporalmente, el camino es
	 * editar {@code validUntil}.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CatalogoConceptoView darDeBaja(
			OperatingActor actor, CatalogoTipo tipo, long conceptoId, String motivo) {

		List<Long> owners = ownersVisibles(actor);
		CatalogoConcepto concepto = cargar(tipo, conceptoId, owners);
		exigirGestionSobre(actor, concepto);
		exigirMutable(concepto, CatalogoInactiveException.Operacion.BAJA);
		exigirSinDependientesVigentes(tipo, concepto);

		Instant ahora = Instant.now();
		concepto.deactivate(ahora, exigirMotivo(motivo));
		CatalogoConcepto guardado = guardar(tipo, concepto);

		auditar(AuditEvents.CATALOGO_DEACTIVATED, guardado, tipo, actor,
				"ACTIVO", "INACTIVO", motivo, Map.of(), ahora);

		log.info("Concepto de catalogo dado de baja: tipo={} conceptoId={}", tipo, conceptoId);
		return vistaDe(guardado, tipo, ahora);
	}

	/**
	 * Baja logica de una vigencia.
	 *
	 * <p>Es el camino para deshacer una vigencia cargada por error. <b>No es el camino para
	 * cerrarla</b>: una vigencia que termino se cierra poniendole {@code validUntil}, y darla de
	 * baja en cambio la saca de la resolucion de conceptos futuros pero deja intacto lo que ya
	 * resolvio —{@code resolverEn} no filtra por {@code active}, justamente para que un convenio
	 * viejo siga diciendo lo que decia—.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CatalogoConceptoView darDeBajaVigencia(
			OperatingActor actor, long nomencladorId, long itemId, String motivo) {

		List<Long> owners = ownersVisibles(actor);
		Nomenclador nomenclador = cargarNomenclador(nomencladorId, owners);
		exigirGestionSobre(actor, nomenclador);

		NomencladorItem item = vigencias.findVisible(itemId, owners)
				.filter(candidato -> candidato.getNomencladorId().equals(nomencladorId))
				.orElseThrow(() -> new CatalogoNotAccessibleException(itemId));
		exigirMutable(item, CatalogoInactiveException.Operacion.BAJA);

		Instant ahora = Instant.now();
		item.deactivate(ahora, exigirMotivo(motivo));
		NomencladorItem guardado = vigencias.save(item);

		auditar(AuditEvents.CATALOGO_VIGENCIA_DEACTIVATED, guardado, CatalogoTipo.NOMENCLADOR,
				actor, "ACTIVO", "INACTIVO", motivo, Map.of(), ahora);

		return CatalogoConceptoView.deVigencia(guardado, ahora);
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/**
	 * Los duenios cuyos conceptos ve el actor.
	 *
	 * <p>La lista la arma el servidor <b>despues</b> de validar el contexto, nunca el cliente.
	 * Un id de otro tenant no aparece en ella y por lo tanto no resuelve nunca: es el
	 * aislamiento de ADR-0004 dicho sobre {@code owner_key}.
	 *
	 * <p><b>No se vuelve a comprobar la membership</b>, a diferencia de 02.02, y no es un
	 * descuido: alli el {@code organizationId} venia de la RUTA y podia no ser el del contexto.
	 * Aca el tenant ES el del contexto, ya revalidado contra la base por
	 * {@code TenantContextFilter} en este mismo request. Repetir la consulta agregaria una
	 * rama que ningun request puede recorrer.
	 */
	private List<Long> ownersVisibles(OperatingActor actor) {
		if (actor.platformAdmin()) {
			// Un administrador de plataforma ve el catalogo comun y NADA de ningun tenant.
			return List.of(OWNER_PLATAFORMA);
		}
		return List.of(OWNER_PLATAFORMA, exigirContexto(actor));
	}

	/** Los duenios visibles, recortados por el filtro de alcance que pidio el cliente. */
	private List<Long> ownersSegunFiltro(OperatingActor actor, CatalogoAlcanceFiltro filtro) {
		List<Long> visibles = ownersVisibles(actor);
		return switch (filtro) {
			case TODOS -> visibles;
			case GLOBAL -> List.of(OWNER_PLATAFORMA);
			// Para un administrador de plataforma esto es la lista vacia, y una lista vacia en un
			// IN es un error de sintaxis: se usa un duenio imposible para que devuelva cero filas.
			case ORGANIZACION -> visibles.size() > 1
					? List.of(visibles.get(1))
					: List.of(OWNER_NINGUNO);
		};
	}

	/**
	 * Autoriza la creacion de un concepto con el alcance pedido y devuelve su duenio.
	 *
	 * @return {@code null} para un concepto global, el id del tenant para uno contextual
	 */
	private Long exigirGestionDe(OperatingActor actor, CatalogoAlcance alcance) {
		if (alcance == CatalogoAlcance.GLOBAL) {
			exigirPlataforma(actor);
			return null;
		}
		if (actor.platformAdmin()) {
			// No tiene contexto de tenant y no se le concede uno: el catalogo propio de un
			// centro es del centro. Ver el javadoc de la clase.
			throw new AccessDeniedException(
					"La administracion de plataforma no crea conceptos de un tenant");
		}
		long organizationId = exigirContexto(actor);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				actor.consultorioId(),
				null,
				Instant.now()));
		return organizationId;
	}

	/** Autoriza mutar un concepto que ya existe, cuyo alcance decide quien puede tocarlo. */
	private void exigirGestionSobre(OperatingActor actor, CatalogoConcepto concepto) {
		exigirGestionDe(actor, CatalogoAlcance.de(concepto.getOrganizationId()));
	}

	private void exigirPlataforma(OperatingActor actor) {
		if (!actor.platformAdmin()) {
			log.info("Mutacion del catalogo global rechazada: accountId={}", actor.accountId());
			throw new AccessDeniedException(
					"El catalogo global lo administra unicamente la plataforma");
		}
	}

	/**
	 * Exige un contexto de trabajo elegido.
	 *
	 * <p>403 y jamas 401: el interceptor del frontend borra el token ante cualquier 401 y el
	 * usuario entra en un bucle de login del que no sale.
	 */
	private long exigirContexto(OperatingActor actor) {
		Long organizationId = actor.contextOrganizationId();
		if (organizationId == null) {
			log.info("Operacion de catalogo sin contexto validado: accountId={}",
					actor.accountId());
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		return organizationId;
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * Un concepto GLOBAL no puede depender de uno CONTEXTUAL.
	 *
	 * <p>Al reves si vale: un tenant cuelga su practica propia de una especialidad de
	 * plataforma, que es el caso frecuente y el que hace util al catalogo comun.
	 */
	private static void exigirAlcanceCompatible(
			CatalogoConcepto dependiente, CatalogoConcepto referencia) {

		if (dependiente.esGlobal() && !referencia.esGlobal()) {
			throw new CatalogoScopeMismatchException(referencia.getId());
		}
	}

	/** La misma regla, cuando el dependiente todavia no existe como entidad. */
	private static void exigirAlcanceCompatible(
			CatalogoAlcance alcance, CatalogoConcepto referencia) {

		if (alcance == CatalogoAlcance.GLOBAL && !referencia.esGlobal()) {
			throw new CatalogoScopeMismatchException(referencia.getId());
		}
	}

	private void exigirSinSolapamiento(
			long nomencladorId, String codigo, Instant desde, Instant hasta) {

		for (NomencladorItem existente : vigencias.vigenciasDelCodigo(nomencladorId, codigo)) {
			if (existente.seSolapaCon(desde, hasta)) {
				log.info("Alta de vigencia rechazada por solapamiento: nomencladorId={} codigo={}",
						nomencladorId, codigo);
				throw new NomencladorVigenciaOverlapException(
						codigo, existente.getValidFrom(), existente.getValidUntil());
			}
		}
	}

	/**
	 * Impide dejar colgando algo que todavia se ofrece.
	 *
	 * <p><b>No confundir con "esta usado historicamente".</b> Un concepto referenciado por
	 * sesiones o convenios viejos SI se puede dar de baja: RN-M06-001 pide que el historico
	 * sobreviva a la baja, no que la impida. Lo que se bloquea es distinto —una especialidad
	 * con practicas VIGENTES colgando, un nomenclador con vigencias abiertas— porque el
	 * resultado serian conceptos huerfanos que ningun listado sabria explicar.
	 */
	private void exigirSinDependientesVigentes(CatalogoTipo tipo, CatalogoConcepto concepto) {
		long dependientes = switch (tipo) {
			case ESPECIALIDAD -> practicas.contarVigentesPorEspecialidad(concepto.getId());
			case NOMENCLADOR -> vigencias.contarVigentesPorNomenclador(concepto.getId());
			case PRACTICA -> 0L;
		};
		if (dependientes > 0) {
			throw new CatalogoHasActiveReferencesException(
					concepto.getId(),
					tipo == CatalogoTipo.ESPECIALIDAD ? "PRACTICA" : "VIGENCIA",
					dependientes);
		}
	}

	private static void exigirMutable(
			CatalogoConcepto concepto, CatalogoInactiveException.Operacion operacion) {

		if (!concepto.isOperable()) {
			throw new CatalogoInactiveException(concepto.getId(), operacion);
		}
	}

	private static void exigirVersion(CatalogoConcepto concepto, long esperada) {
		if (concepto.getVersion() != esperada) {
			throw new OptimisticLockingFailureException(
					"El concepto fue modificado por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private Long especialidadDeLaPractica(
			OperatingActor actor, CatalogoAlcance alcance, CatalogoAltaCommand command) {

		long especialidadId = exigirId(command.especialidadId(), "especialidadId");
		Especialidad especialidad = cargarEspecialidad(especialidadId, ownersVisibles(actor));
		if (!especialidad.isOperable()) {
			throw new CatalogoInactiveException(
					especialidadId, CatalogoInactiveException.Operacion.REFERENCIA);
		}
		exigirAlcanceCompatible(alcance, especialidad);
		return especialidad.getId();
	}

	/**
	 * Guarda forzando el flush y traduce el choque de unique.
	 *
	 * <p><b>Como se distingue un choque de codigo de uno de nombre.</b> MySQL nombra el indice
	 * violado en el mensaje del error, y ese nombre es la unica senal disponible: la excepcion
	 * de Spring no lleva ningun campo estructurado con la constraint. Se busca el sufijo
	 * {@code _name_} y, si no aparece, se responde como conflicto de codigo. La degradacion es
	 * benigna: los dos son 409 con {@code type} distinto, y el peor caso es un mensaje que
	 * nombra el campo equivocado, nunca un dato mal guardado.
	 *
	 * <p><b>Despues de un flush fallido no se vuelve a tocar la sesion JPA</b> — ni una lectura
	 * para averiguar cual de los dos choco—. La especificacion lo prohibe y lo que sale de ahi
	 * es un 500 en vez de un 409 legitimo.
	 */
	private <T extends CatalogoConcepto> T persistir(
			java.util.function.UnaryOperator<T> guardar,
			T concepto,
			String codigo,
			String nombre,
			CatalogoAlcance alcance) {

		try {
			return guardar.apply(concepto);
		} catch (DataIntegrityViolationException choque) {
			boolean global = alcance == CatalogoAlcance.GLOBAL;
			log.info("Alta de catalogo rechazada por unique: alcance={} codigo={}", alcance, codigo);
			if (esConflictoDeNombre(choque)) {
				throw new CatalogoNameTakenException(nombre, global);
			}
			throw new CatalogoCodeTakenException(codigo, global);
		}
	}

	private static boolean esConflictoDeNombre(DataIntegrityViolationException choque) {
		Throwable causa = choque.getMostSpecificCause();
		String mensaje = causa.getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains("_name_");
	}

	private CatalogoConcepto cargar(CatalogoTipo tipo, long conceptoId, List<Long> owners) {
		return switch (tipo) {
			case ESPECIALIDAD -> cargarEspecialidad(conceptoId, owners);
			case PRACTICA -> cargarPractica(conceptoId, owners);
			case NOMENCLADOR -> cargarNomenclador(conceptoId, owners);
		};
	}

	private Especialidad cargarEspecialidad(long conceptoId, List<Long> owners) {
		return especialidades.findVisible(conceptoId, owners)
				.orElseThrow(() -> new CatalogoNotAccessibleException(conceptoId));
	}

	private Practica cargarPractica(long conceptoId, List<Long> owners) {
		return practicas.findVisible(conceptoId, owners)
				.orElseThrow(() -> new CatalogoNotAccessibleException(conceptoId));
	}

	private Nomenclador cargarNomenclador(long conceptoId, List<Long> owners) {
		return nomencladores.findVisible(conceptoId, owners)
				.orElseThrow(() -> new CatalogoNotAccessibleException(conceptoId));
	}

	private CatalogoConcepto guardarConFlush(CatalogoTipo tipo, CatalogoConcepto concepto) {
		return switch (concepto) {
			case Especialidad e -> especialidades.saveAndFlush(e);
			case Practica p -> practicas.saveAndFlush(p);
			case Nomenclador n -> nomencladores.saveAndFlush(n);
			case NomencladorItem i -> vigencias.saveAndFlush(i);
			default -> throw new IllegalStateException("Concepto de catalogo desconocido: " + tipo);
		};
	}

	private CatalogoConcepto guardar(CatalogoTipo tipo, CatalogoConcepto concepto) {
		return switch (concepto) {
			case Especialidad e -> especialidades.save(e);
			case Practica p -> practicas.save(p);
			case Nomenclador n -> nomencladores.save(n);
			case NomencladorItem i -> vigencias.save(i);
			default -> throw new IllegalStateException("Concepto de catalogo desconocido: " + tipo);
		};
	}

	private static CatalogoConceptoView vistaDe(
			CatalogoConcepto concepto, CatalogoTipo tipo, Instant ahora) {

		if (concepto instanceof Practica practica) {
			return CatalogoConceptoView.dePractica(practica, ahora);
		}
		if (concepto instanceof NomencladorItem item) {
			return CatalogoConceptoView.deVigencia(item, ahora);
		}
		return CatalogoConceptoView.de(concepto, tipo, ahora);
	}

	private static Map<String, String> cambios(
			CatalogoConcepto concepto, String nombre, CatalogoEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombre != null && !nombre.equals(concepto.getName())) {
			detalles.put("name", concepto.getName() + " -> " + nombre);
		}
		if (command.validFrom() != null && !command.validFrom().equals(concepto.getValidFrom())) {
			detalles.put("validFrom", concepto.getValidFrom() + " -> " + command.validFrom());
		}
		if (command.clearValidUntil()) {
			detalles.put("validUntil", concepto.getValidUntil() + " -> (sin fin)");
		} else if (command.validUntil() != null
				&& !command.validUntil().equals(concepto.getValidUntil())) {
			detalles.put("validUntil", concepto.getValidUntil() + " -> " + command.validUntil());
		}
		return detalles;
	}

	private void auditar(
			String eventType,
			CatalogoConcepto concepto,
			CatalogoTipo tipo,
			OperatingActor actor,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details,
			Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>(details);
		detalles.putIfAbsent("tipo", tipo.name());

		auditTrail.record(new AuditEntry(
				// NULL cuando el concepto es global: es un evento de plataforma sin tenant, la
				// forma que ADR-0019 ya admite para audit_event.
				concepto.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_CATALOGO,
				concepto.getId(),
				previousState,
				newState,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}

	/**
	 * Normaliza lo que la collation no puede: espacios al principio, al final y dobles espacios
	 * internos.
	 *
	 * <p>Mayusculas y acentos NO se tocan aca: eso lo resuelve {@code utf8mb4_0900_ai_ci} tanto
	 * para el unique de duplicados como para el {@code LIKE} de la busqueda. Normalizarlos ademas
	 * en Java seria una segunda definicion de "igual" que tarde o temprano diverge de la del
	 * motor, y guardaria el nombre distinto de como lo escribio el usuario.
	 */
	private static String normalizar(String valor) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException("El codigo y el nombre son obligatorios");
		}
		return valor.strip().replaceAll("\\s{2,}", " ");
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de un concepto de catalogo: sin "
							+ "el, la auditoria no responde por que seis meses despues");
		}
		return motivo.strip();
	}

	private static long exigirId(Long valor, String campo) {
		if (valor == null) {
			throw new IllegalArgumentException(campo + " es obligatorio");
		}
		return valor;
	}
}
