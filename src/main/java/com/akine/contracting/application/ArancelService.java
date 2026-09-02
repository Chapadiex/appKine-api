package com.akine.contracting.application;

import com.akine.contracting.application.ArancelCommands.ArancelAltaCommand;
import com.akine.contracting.application.ArancelCommands.ArancelEdicionCommand;
import com.akine.contracting.domain.Convenio;
import com.akine.contracting.domain.ConvenioArancel;
import com.akine.contracting.domain.ResolutorDeArancel;
import com.akine.contracting.domain.Vigencia;
import com.akine.contracting.domain.exception.ArancelNotAccessibleException;
import com.akine.contracting.domain.exception.ArancelSolapadoException;
import com.akine.contracting.domain.exception.ArancelYaInactivoException;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import com.akine.contracting.domain.exception.ConvenioYaInactivoException;
import com.akine.contracting.domain.exception.PracticaNoAccesibleException;
import com.akine.contracting.domain.exception.SedeNoAccesibleException;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioArancelRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;
import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioRepositoryPort;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.CatalogoDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aranceles por practica dentro de un convenio, y la resolucion del arancel efectivo (M16).
 *
 * <h2>La misma regla de no-solapamiento que los convenios, un nivel mas abajo</h2>
 *
 * <p>Dos aranceles de la MISMA practica en el mismo convenio no se pueden solapar (RN-M16-002).
 * Dos aranceles de la misma practica <b>si</b> conviven —el de 2026 y el de 2027 son el caso
 * normal— y por eso no hay ningun unique de {@code (convenio_id, practica_id)}: lo prohibido es la
 * interseccion de periodos, que ningun indice sabe expresar.
 *
 * <p>Se hace cumplir igual que en {@link ConvenioService}, con las mismas tres condiciones y en el
 * mismo orden: {@code READ_COMMITTED}, fila-lock creada en transaccion aparte, y lock tomado ANTES
 * de leer. <b>Y es el MISMO lock</b>, el de la sede: un solo punto de serializacion para las dos
 * tablas evita tener que ordenar dos locks entre si, que es de donde salen los deadlocks.
 *
 * <h2>Subir un precio es cerrar una vigencia y abrir otra, no pisar un importe</h2>
 *
 * <p>RN-M16-003 prohibe recalcular lo historico. La forma correcta de aumentar un arancel es
 * cerrar el vigente el 31/12 y crear otro desde el 01/01; editar el importe de una ventana ya
 * transcurrida no esta prohibido por el codigo —a veces hay que corregir un error de carga— pero
 * <b>no reescribe nada de lo ya liquidado</b>, porque eso guardo su propio snapshot congelado.
 *
 * <h2>La resolucion</h2>
 *
 * <p>{@link #resolver} implementa RF-M16-006 y RF-M16-010 y devuelve un resultado <b>unico y
 * explicable</b>. El algoritmo vive en {@code ResolutorDeArancel}, compartido con el {@code spi},
 * para que el importe que muestra la pantalla y el que devenga {@code billing} no puedan divergir.
 */
@Service
public class ArancelService {

	private static final Logger log = LoggerFactory.getLogger(ArancelService.class);

	private final ConvenioRepositoryPort convenios;
	private final ConvenioArancelRepositoryPort aranceles;
	private final ConvenioLockRepositoryPort locks;
	private final ConvenioLockIniciador lockIniciador;
	private final ConsultorioDirectory consultorios;
	private final CatalogoDirectory catalogo;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	@SuppressWarnings("java:S107")
	public ArancelService(
			ConvenioRepositoryPort convenios,
			ConvenioArancelRepositoryPort aranceles,
			ConvenioLockRepositoryPort locks,
			ConvenioLockIniciador lockIniciador,
			ConsultorioDirectory consultorios,
			CatalogoDirectory catalogo,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.convenios = convenios;
		this.aranceles = aranceles;
		this.locks = locks;
		this.lockIniciador = lockIniciador;
		this.consultorios = consultorios;
		this.catalogo = catalogo;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Los aranceles de un convenio, activos e historicos, del mas nuevo al mas viejo.
	 *
	 * <p>Es la grilla de vigencias de la etapa. Devuelve los vencidos y los dados de baja a
	 * proposito: sin ellos no se puede explicar por que una prestacion de marzo se cobro distinto
	 * que una de octubre.
	 */
	@Transactional(readOnly = true)
	public List<ArancelView> listar(
			OperatingActor actor,
			long consultorioId,
			long convenioId,
			EstadoFiltro estado,
			LocalDate fecha) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Listar aranceles");
		exigirSedeDelTenant(organizationId, consultorioId);
		cargarConvenio(organizationId, consultorioId, convenioId);

		EstadoFiltro filtro = estado == null ? EstadoFiltro.ACTIVO : estado;
		LocalDate contra = fecha == null ? LocalDate.now() : fecha;

		return aranceles.findAllByConvenio(organizationId, convenioId).stream()
				.filter(arancel -> switch (filtro) {
					case ACTIVO -> arancel.isActive();
					case INACTIVO -> !arancel.isActive();
					case TODOS -> true;
				})
				.map(arancel -> ArancelView.de(arancel, contra))
				.toList();
	}

	/**
	 * El arancel efectivo de una practica para una fecha, con la explicacion (RF-M16-006,
	 * RF-M16-010).
	 *
	 * <p><b>No lanza cuando no hay.</b> Devuelve la resolucion con su motivo: no encontrar convenio
	 * es un desenlace normal —el paciente se atiende como particular, RN-M16-005— y quien pregunta
	 * tiene que poder distinguirlo de un error. Un 404 aca obligaria a la pantalla a tratar el caso
	 * mas frecuente como una excepcion.
	 *
	 * <p>El resultado es unico porque no puede haber dos candidatas, no porque haya una regla de
	 * prioridad que las desempate. Ver {@code ResolutorDeArancel}.
	 */
	@Transactional(readOnly = true)
	@SuppressWarnings("java:S107")
	public ResolucionDeArancel resolver(
			OperatingActor actor,
			long consultorioId,
			long financiadorId,
			long planId,
			long practicaId,
			LocalDate fecha) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Resolver un arancel");
		exigirSedeDelTenant(organizationId, consultorioId);
		LocalDate contra = fecha == null ? LocalDate.now() : fecha;

		List<Convenio> candidatos =
				convenios.findActivosPorAlcance(organizationId, consultorioId, financiadorId, planId);

		// Los aranceles se leen SOLO si hay convenio aplicable: pedirlos antes seria una consulta
		// que en el caso mas frecuente —paciente particular, sin convenio— no se usa para nada.
		return ResolutorDeArancel.convenioAplicable(candidatos, contra)
				.map(convenio -> ResolutorDeArancel.resolver(
						List.of(convenio),
						aranceles.findActivosPorPractica(
								organizationId, convenio.getId(), practicaId),
						practicaId,
						contra))
				.orElseGet(() -> ResolutorDeArancel.resolver(List.of(), List.of(), practicaId, contra));
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de un arancel bajo un convenio (RF-M16-004).
	 *
	 * <p>La moneda la hereda del convenio y no viene del cliente: un arancel en otra moneda que su
	 * convenio no seria un dato distinto, seria un dato roto.
	 *
	 * <p>La vigencia del arancel tiene que estar CONTENIDA en la del convenio. Un arancel que
	 * empieza antes que su convenio, o que sigue despues, es dato muerto: nunca puede resolver,
	 * porque la resolucion exige primero un convenio aplicable. Aceptarlo dejaria en la grilla
	 * filas que prometen un precio que el motor no va a usar nunca.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ArancelView crear(
			OperatingActor actor, long consultorioId, long convenioId, ArancelAltaCommand command) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Crear un arancel");
		exigirSedeDelTenant(organizationId, consultorioId);
		AutorizacionDeCatalogo.exigirGestionDeLaSede(
				permissionGuard, actor, consultorioId, "Crear un arancel");

		Convenio convenio = cargarConvenio(organizationId, consultorioId, convenioId);
		exigirConvenioOperable(convenio, "agregar un arancel");
		exigirPracticaDelTenant(organizationId, command.practicaId());

		ConvenioArancel arancel = new ConvenioArancel(
				organizationId,
				consultorioId,
				convenioId,
				command.practicaId(),
				command.importeTotal(),
				command.importeFinanciador(),
				command.coseguro(),
				convenio.getMoneda(),
				command.vigenciaDesde(),
				command.vigenciaHasta());

		exigirContenidaEnElConvenio(arancel.vigencia(), convenio);

		// EL ORDEN DE ESTAS TRES LINEAS ES LA GARANTIA. Ver la cabecera de ConvenioService.
		lockIniciador.asegurar(organizationId, consultorioId);
		BloqueoDeConvenios.tomar(locks, organizationId, consultorioId);
		exigirSinSolapamiento(
				organizationId, convenioId, command.practicaId(), arancel.vigencia(), null);

		ConvenioArancel creado = aranceles.saveAndFlush(arancel);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("practicaId", String.valueOf(command.practicaId()));
		detalles.put("vigencia", creado.vigencia().toString());
		detalles.put("importeTotal", creado.getImporteTotal() + " " + creado.getMoneda());
		detalles.put("importeFinanciador", creado.getImporteFinanciador().toString());
		detalles.put("coseguro", creado.getCoseguro().toString());
		auditar(AuditEvents.ARANCEL_CREATED, creado, actor, null, "ACTIVO", null, detalles);

		log.info("Arancel creado: arancelId={} convenioId={} practicaId={}",
				creado.getId(), convenioId, command.practicaId());
		return ArancelView.de(creado, LocalDate.now());
	}

	/**
	 * Edicion parcial: importes o vigencia.
	 *
	 * <p><b>Tambien toma el lock</b>, por el mismo motivo que la edicion de un convenio: mover una
	 * vigencia puede crear el solapamiento que el alta impide.
	 *
	 * <p>Los importes se validan como terna aunque llegue uno solo, y eso lo hace la entidad: subir
	 * el total sin tocar las partes rompe la invariante de que sumen.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ArancelView editar(
			OperatingActor actor,
			long consultorioId,
			long convenioId,
			long arancelId,
			ArancelEdicionCommand command) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Editar un arancel");
		exigirSedeDelTenant(organizationId, consultorioId);
		AutorizacionDeCatalogo.exigirGestionDeLaSede(
				permissionGuard, actor, consultorioId, "Editar un arancel");

		lockIniciador.asegurar(organizationId, consultorioId);
		BloqueoDeConvenios.tomar(locks, organizationId, consultorioId);

		Convenio convenio = cargarConvenio(organizationId, consultorioId, convenioId);
		ConvenioArancel arancel = cargarArancel(organizationId, convenioId, arancelId);
		exigirArancelOperable(arancel, "editar");
		exigirVersion(arancel, command.expectedVersion());

		Map<String, String> detalles = cambios(arancel, command);

		arancel.updateDatos(
				command.importeTotal(),
				command.importeFinanciador(),
				command.coseguro(),
				command.vigenciaDesde(),
				command.vigenciaHasta());

		exigirContenidaEnElConvenio(arancel.vigencia(), convenio);
		exigirSinSolapamiento(
				organizationId, convenioId, arancel.getPracticaId(), arancel.vigencia(), arancelId);

		ConvenioArancel guardado = aranceles.saveAndFlush(arancel);

		auditar(AuditEvents.ARANCEL_UPDATED, guardado, actor, null, null, null, detalles);
		return ArancelView.de(guardado, LocalDate.now());
	}

	/**
	 * Baja logica con motivo obligatorio.
	 *
	 * <p>Libera el PERIODO: el no-solapamiento solo mira los activos, asi que se puede volver a
	 * cargar un arancel de esa practica para las mismas fechas. Lo ya liquidado guarda su propio
	 * snapshot y no se toca.
	 *
	 * <p>No toma el lock: quitar un arancel del conjunto activo nunca puede crear un solapamiento.
	 */
	@Transactional
	public ArancelView darDeBaja(
			OperatingActor actor,
			long consultorioId,
			long convenioId,
			long arancelId,
			String motivo) {

		long organizationId = AutorizacionDeCatalogo.exigirContexto(actor, "Dar de baja un arancel");
		exigirSedeDelTenant(organizationId, consultorioId);
		AutorizacionDeCatalogo.exigirGestionDeLaSede(
				permissionGuard, actor, consultorioId, "Dar de baja un arancel");

		cargarConvenio(organizationId, consultorioId, convenioId);
		ConvenioArancel arancel = cargarArancel(organizationId, convenioId, arancelId);
		exigirArancelOperable(arancel, "dar de baja");

		arancel.deactivate(Instant.now(), exigirMotivo(motivo));
		ConvenioArancel guardado = aranceles.save(arancel);

		auditar(AuditEvents.ARANCEL_DEACTIVATED, guardado, actor, "ACTIVO", "INACTIVO", motivo,
				Map.of("practicaId", String.valueOf(arancel.getPracticaId())));

		log.info("Arancel dado de baja: arancelId={}", arancelId);
		return ArancelView.de(guardado, LocalDate.now());
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/** RN-M16-002 al nivel del arancel. <b>Se llama SIEMPRE con el lock ya tomado.</b> */
	private void exigirSinSolapamiento(
			long organizationId,
			long convenioId,
			long practicaId,
			Vigencia vigencia,
			Long excluirId) {

		aranceles.findActivosPorPractica(organizationId, convenioId, practicaId).stream()
				.filter(existente -> !existente.getId().equals(excluirId))
				.filter(existente -> existente.vigencia().seSolapaCon(vigencia))
				.findFirst()
				.ifPresent(choque -> {
					log.info("Arancel rechazado por solapamiento: choca con arancelId={}",
							choque.getId());
					throw new ArancelSolapadoException(choque.getId(), choque.vigencia().toString());
				});
	}

	private static void exigirContenidaEnElConvenio(Vigencia vigencia, Convenio convenio) {
		if (!vigencia.estaContenidaEn(convenio.vigencia())) {
			throw new IllegalArgumentException(
					"La vigencia del arancel (" + vigencia + ") tiene que estar contenida en la del "
							+ "convenio (" + convenio.vigencia() + "): fuera de ella el arancel no "
							+ "podria resolver nunca");
		}
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new SedeNoAccesibleException(consultorioId));
	}

	/**
	 * La practica tiene que existir y ser visible para el tenant.
	 *
	 * <p>La FK garantiza que el id exista, no que el tenant lo vea: una practica es GLOBAL o propia
	 * de una organizacion (ADR-0021), y una FK compara ids y no alcances. Lo resuelve
	 * {@code CatalogoDirectory}, que devuelve tambien las dadas de baja <b>a proposito</b> —un
	 * arancel historico sobre una practica discontinuada tiene que seguir resolviendo (RN-M06-002)—
	 * asi que aca no se filtra por estado.
	 */
	private void exigirPracticaDelTenant(long organizationId, long practicaId) {
		catalogo.findPractica(organizationId, practicaId, Instant.now())
				.orElseThrow(() -> new PracticaNoAccesibleException(practicaId));
	}

	private Convenio cargarConvenio(long organizationId, long consultorioId, long convenioId) {
		return convenios.findByIdAndScope(convenioId, organizationId, consultorioId)
				.orElseThrow(() -> new ConvenioNotAccessibleException(convenioId));
	}

	private ConvenioArancel cargarArancel(
			long organizationId, long convenioId, long arancelId) {

		return aranceles.findByIdAndScope(arancelId, organizationId, convenioId)
				.orElseThrow(() -> new ArancelNotAccessibleException(arancelId));
	}

	private static void exigirConvenioOperable(Convenio convenio, String operacion) {
		if (!convenio.isOperable()) {
			throw new ConvenioYaInactivoException(convenio.getId(), operacion);
		}
	}

	private static void exigirArancelOperable(ConvenioArancel arancel, String operacion) {
		if (!arancel.isOperable()) {
			throw new ArancelYaInactivoException(arancel.getId(), operacion);
		}
	}

	private static void exigirVersion(ConvenioArancel arancel, long esperada) {
		if (arancel.getVersion() != esperada) {
			throw new OptimisticLockingFailureException(
					"El arancel fue modificado por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/** §37 pide auditoria reforzada de importes y vigencias: entra el antes y el despues. */
	private static Map<String, String> cambios(
			ConvenioArancel arancel, ArancelEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (command.importeTotal() != null
				&& arancel.getImporteTotal().compareTo(command.importeTotal()) != 0) {
			detalles.put("importeTotal", arancel.getImporteTotal() + " -> " + command.importeTotal());
		}
		if (command.importeFinanciador() != null
				&& arancel.getImporteFinanciador().compareTo(command.importeFinanciador()) != 0) {
			detalles.put("importeFinanciador",
					arancel.getImporteFinanciador() + " -> " + command.importeFinanciador());
		}
		if (command.coseguro() != null
				&& arancel.getCoseguro().compareTo(command.coseguro()) != 0) {
			detalles.put("coseguro", arancel.getCoseguro() + " -> " + command.coseguro());
		}
		LocalDate desde =
				command.vigenciaDesde() == null ? arancel.getVigenciaDesde() : command.vigenciaDesde();
		LocalDate hasta =
				command.vigenciaHasta() == null ? arancel.getVigenciaHasta() : command.vigenciaHasta();
		if (!desde.equals(arancel.getVigenciaDesde())
				|| !Objects.equals(hasta, arancel.getVigenciaHasta())) {
			detalles.put("vigencia", arancel.vigencia() + " -> " + new Vigencia(desde, hasta));
		}
		return detalles;
	}

	@SuppressWarnings("java:S107")
	private void auditar(
			String eventType,
			ConvenioArancel arancel,
			OperatingActor actor,
			String previousState,
			String newState,
			String reason,
			Map<String, String> detalles) {

		auditTrail.record(new AuditEntry(
				arancel.getOrganizationId(),
				arancel.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_ARANCEL,
				arancel.getId(),
				previousState,
				newState,
				detalles,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("La baja de un arancel exige un motivo declarado");
		}
		return motivo.strip();
	}
}
