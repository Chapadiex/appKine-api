package com.akine.offering.application;

import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.PermissionCodes;
import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.exception.ConsultorioNoAccesibleException;
import com.akine.offering.domain.exception.ConsultorioNoOperableException;
import com.akine.offering.domain.exception.OfertaInactivaException;
import com.akine.offering.domain.exception.OfertaNombreComercialTakenException;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.exception.ServicioInactivoException;
import com.akine.offering.domain.exception.ServicioNotAccessibleException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * COMO cada sede presta un {@link Servicio} global: alta, lectura, edicion y baja logica de las
 * Ofertas (M27/M03, regla maestra 14).
 *
 * <h2>Esta clase es el objetivo de la etapa, y conviene decir por que</h2>
 *
 * <p>{@code ServicioService} administra QUE existe como concepto —una sola poblacion global, sin
 * {@code organization_id}, ADR-0023—. Esta clase administra COMO lo presta un centro concreto:
 * precio, duracion, cupo, modalidad efectiva, si abre caso clinico, si necesita profesional o
 * box. <b>Dos sedes pueden ofertar exactamente el mismo Servicio con configuraciones distintas y
 * ninguna de las dos afecta a la otra</b> — CA-M03-006-06 y CA-M27-003-06, el criterio que
 * {@code dos_sedes_pueden_ofertar_el_mismo_servicio_con_configuraciones_distintas} hace
 * ejecutable. Si esa separacion se rompe, se rompe la regla maestra 14 y la etapa entera pierde
 * sentido.
 *
 * <p>Una Oferta <b>nunca vuelve a leer al Servicio</b> despues de creada. Los {@code *Default} del
 * catalogo global se copian UNA VEZ, en el alta, y desde ese momento son datos de esta fila con su
 * propio ciclo de vida (RF-M06-006). Ver {@link OfertaServicioConsultorio}.
 *
 * <h2>Las dos autorizaciones de este modulo, y por que son distintas</h2>
 *
 * <table>
 *   <caption>Autorizacion por tipo de operacion</caption>
 *   <tr><th>Operacion</th><th>Que se exige</th><th>Quien pasa</th></tr>
 *   <tr>
 *     <td>Mutaciones (alta, edicion, baja)</td>
 *     <td>{@code consultorio:manage} <b>con la sede como alcance</b>, sobre el contexto de esa
 *         misma sede</td>
 *     <td>{@code ORG_ADMIN} sobre cualquier sede suya, {@code CONSULTORIO_ADMIN} sobre la suya</td>
 *   </tr>
 *   <tr>
 *     <td>Lecturas (detalle, listado)</td>
 *     <td><b>Pertenencia</b>: membership vigente en el tenant y sede de ese tenant</td>
 *     <td>Todo rol con membership vigente, {@code PACIENTE} incluido</td>
 *   </tr>
 * </table>
 *
 * <p><b>Sin codigos de permiso nuevos</b> (diseno §1, mismo criterio que 02.04 y 02.05: la matriz
 * no la amplia una etapa). Las lecturas autorizan por pertenencia y no por un
 * {@code oferta:read} inventado, exactamente como lo hacian las de espacio antes de que
 * {@code espacio:read} se aprobara y como lo hace hoy el catalogo clinico contextual. La
 * contrapartida, escrita para que no sorprenda: <b>un {@code PACIENTE} con membership vigente
 * puede listar las ofertas de su sede</b>. No es un descuido — la cartelera comercial de un centro
 * es justamente lo que un paciente tiene que poder ver—, pero es una diferencia real con
 * {@code EspacioService}, que desde 02.02 lo deja afuera con {@code espacio:read}. Si alguna vez
 * se aprueba un {@code oferta:read}, este es el unico metodo que cambia.
 *
 * <p><b>El orden de las dos comprobaciones no es cosmetico.</b> Primero pertenencia y tenant,
 * despues permiso. Una sede de otro tenant tiene que salir por <b>404</b> —un 403 confirmaria que
 * esa organizacion existe y bastaria recorrer ids consecutivos para inventariar el SaaS—; dentro
 * del propio tenant, en cambio, quien decide es el permiso, y ahi un 403 no le filtra al actor
 * nada que no sepa ya. La falta de contexto es 403 y <b>jamas 401</b>: el interceptor del frontend
 * borra el token ante cualquier 401 y deja al usuario en un bucle de login.
 *
 * <h2>Por que el rol de plataforma no entra por ninguna puerta de esta clase</h2>
 *
 * <p>Un {@code PLATFORM_ADMIN} no tiene membership en ningun tenant (matriz §1.3) ni contexto de
 * trabajo, asi que cae en el 403 de falta de contexto tanto al leer como al mutar, y <b>eso es lo
 * correcto, no un hueco</b>: la configuracion comercial de un centro —que ofrece y a que precio—
 * es informacion suya. Es la misma linea que {@code ServicioService} traza en su cabecera y la que
 * §10.2 de la matriz traza con los espacios.
 *
 * <p>Corolario practico: <b>esta clase no registra {@code SUPPORT_ACCESS_USED} y no es un
 * olvido</b>. La matriz §7 exige auditar cada operacion amparada por un acceso de soporte, pero
 * ninguna operacion de este modulo puede estarlo: la concesion por soporte solo puede aparecer en
 * un actor de plataforma, y aqui ninguno llega al evaluador. Escribir esa rama seria codigo que
 * no se puede ejecutar haciendose pasar por un control. El dia que una etapa le abra el acceso de
 * soporte a las ofertas, va en {@link #exigirGestion}, con el
 * {@link com.akine.organization.spi.PermissionDecision} que ya devuelve el guard.
 *
 * <h2>Bloqueos y concurrencia: {@code @Version} y el unique, nada mas</h2>
 *
 * <p><b>Ninguna operacion toma un lock pesimista, y es deliberado.</b> {@code EspacioService} usa
 * {@code FOR UPDATE} con {@code READ_COMMITTED} porque decide contra un CONTEO EXTERNO —la
 * ocupacion que reportan las sondas— leido despues de tomar el lock, y ahi el snapshot importa.
 * Aca no hay ninguna decision que dependa del commit de un competidor: la actualizacion perdida la
 * frena {@code @Version} y el duplicado lo frena {@code uk_oferta_sede_nombre_vigente}. Tomar un
 * lock "por las dudas" serializaria toda la administracion de un centro sin comprar nada.
 *
 * <p>Tampoco se bloquea {@code subscription}: el orden de bloqueo unico del sistema aplica a las
 * operaciones que consumen o liberan cupo de plan, y <b>no existe ningun {@code LimitCode} de
 * ofertas</b>. Esta etapa deliberadamente no crea uno: ningun RF de M27 declara un tope por plan e
 * inventarlo bloquearia en silencio a los tenants que ya existen.
 *
 * <p>La suspension de la suscripcion tampoco se comprueba aca: {@code TenantContextFilter} rechaza
 * con 409 toda mutacion bajo {@code /api/v1/organizations/} antes de llegar al controller.
 * Duplicarlo daria dos reglas que se olvidan por separado.
 *
 * <h2>Aislamiento de tenant: descansa entero en los predicados de las consultas</h2>
 *
 * <p><b>No hay FK compuesta {@code (organization_id, consultorio_id)}</b> sobre
 * {@code oferta_servicio_consultorio} —mismo caso que {@code espacio} y las tablas de M05—, asi
 * que a nivel de base una fila podria declarar la organizacion A y apuntar a un consultorio de la
 * B, y nada en el esquema atraparia una consulta que filtre por una sola de las dos columnas. La
 * garantia de aislamiento la da que <b>toda</b> lectura de {@link OfertaRepositoryPort} lleve las
 * dos en el {@code WHERE}, y esta clase no tiene ningun camino que cargue una oferta por id pelado.
 *
 * <h2>Auditoria</h2>
 *
 * <p>Se escribe DENTRO de la transaccion del negocio, nunca en un listener post-commit
 * ({@code AuditTrail.record} es {@code Propagation.MANDATORY} para que no exista la forma de
 * equivocarse). Y <b>con {@code organizationId} y {@code consultorioId}</b>, al reves que la
 * auditoria del catalogo global: una Oferta si es dato de un tenant, y omitirlos dejaria un evento
 * que la consulta de auditoria del centro —que filtra por organizacion— nunca recuperaria.
 *
 * <p>El corolario incomodo: una excepcion de negocio hace rollback de todo lo escrito antes de
 * lanzarla, incluida la auditoria. Por eso ningun rechazo se audita desde aca — el permiso
 * denegado lo registra el evaluador de {@code organization} en su propia transaccion.
 */
@Service
public class OfertaService {

	private static final Logger log = LoggerFactory.getLogger(OfertaService.class);

	private final OfertaRepositoryPort ofertas;
	private final ServicioRepositoryPort servicios;
	private final ConsultorioDirectory consultorioDirectory;
	private final AccountContextDirectory accountContextDirectory;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public OfertaService(
			OfertaRepositoryPort ofertas,
			ServicioRepositoryPort servicios,
			ConsultorioDirectory consultorioDirectory,
			AccountContextDirectory accountContextDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.ofertas = ofertas;
		this.servicios = servicios;
		this.consultorioDirectory = consultorioDirectory;
		this.accountContextDirectory = accountContextDirectory;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/**
	 * Una oferta de la sede por id, <b>activa o no</b>.
	 *
	 * <p>Devolver tambien las dadas de baja es RN-M27-007: los historicos conservan su nombre
	 * comercial y su estado. Un 404 sobre una oferta inactiva estaria borrando historia por la
	 * puerta de atras, y dejaria a la pantalla sin poder explicar por que ese nombre comercial no
	 * se puede reusar.
	 *
	 * @throws ConsultorioNoAccesibleException si la sede no existe o es de otro tenant (404)
	 * @throws OfertaNotAccessibleException si la oferta no existe o es de otra sede (404)
	 */
	@Transactional(readOnly = true)
	public OfertaView buscarPorId(
			OperatingActor actor, long organizationId, long consultorioId, long ofertaId) {

		ConsultorioSnapshot sede = exigirLectura(actor, organizationId, consultorioId);
		return OfertaView.de(cargar(organizationId, consultorioId, ofertaId), hoyEn(sede));
	}

	/**
	 * Las ofertas de la sede segun el filtro de estado, ordenadas por nombre comercial.
	 *
	 * <p>El default {@code ACTIVO} es lo que hace que una seleccion nueva nunca ofrezca una oferta
	 * discontinuada. Las dadas de baja hay que pedirlas.
	 *
	 * <p>El filtro por estado ocurre en la BASE y no en memoria: es la consulta que la agenda de
	 * F5 va a ejecutar, y traer el catalogo entero de la sede para descartarlo en Java no escala.
	 */
	@Transactional(readOnly = true)
	public List<OfertaView> listar(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			OfertaEstadoFiltro estado) {

		ConsultorioSnapshot sede = exigirLectura(actor, organizationId, consultorioId);
		LocalDate hoy = hoyEn(sede);

		List<OfertaServicioConsultorio> filas = switch (estado) {
			case ACTIVO -> ofertas
					.findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNombreComercialAsc(
							organizationId, consultorioId, true);
			case INACTIVO -> ofertas
					.findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNombreComercialAsc(
							organizationId, consultorioId, false);
			case TODOS -> ofertas.findAllByOrganizationIdAndConsultorioIdOrderByNombreComercialAsc(
					organizationId, consultorioId);
		};

		return filas.stream().map(fila -> OfertaView.de(fila, hoy)).toList();
	}

	/**
	 * Las ofertas de ESTA sede que materializan un Servicio dado, activas e historicas.
	 *
	 * <p>Responde "que ofrece este centro sobre este concepto", que es la pregunta con la que una
	 * pantalla evita proponer un alta duplicada. <b>No responde a cuantos centros afecta la baja
	 * de un Servicio</b>: esa es una consulta cross-tenant que el puerto deliberadamente no expone
	 * (diseno §7.8) y que solo el rol de plataforma podria hacer.
	 */
	@Transactional(readOnly = true)
	public List<OfertaView> listarPorServicio(
			OperatingActor actor, long organizationId, long consultorioId, long servicioId) {

		ConsultorioSnapshot sede = exigirLectura(actor, organizationId, consultorioId);
		LocalDate hoy = hoyEn(sede);

		return ofertas
				.findAllByOrganizationIdAndConsultorioIdAndServicioIdOrderByNombreComercialAsc(
						organizationId, consultorioId, servicioId)
				.stream()
				.map(fila -> OfertaView.de(fila, hoy))
				.toList();
	}

	// =================================================================================
	// Alta (RF-M03-006, RF-M03-007, RF-M27-003)
	// =================================================================================

	/**
	 * Da de alta una oferta en la sede.
	 *
	 * <h2>El protocolo, y por que el orden es el que es</h2>
	 *
	 * <pre>
	 *   1. contexto y tenant de la sede            &lt;- 404 si es ajena, ANTES del permiso
	 *   2. consultorio:manage con la sede de alcance
	 *   3. la sede existe en la base y esta ACTIVA
	 *   4. el servicio existe y esta OPERABLE      &lt;- RF-M27-002
	 *   5. copiar los *Default que el comando omitio
	 *   6. persistir con flush + auditar, en la misma transaccion
	 * </pre>
	 *
	 * <p><b>3 va contra la base y no contra el contexto.</b> La FK {@code fk_oferta_consultorio}
	 * garantiza que el id exista, pero no que sea del tenant del request: sin esta consulta, un
	 * {@code consultorioId} ajeno crearia una fila con el {@code organization_id} del atacante y el
	 * {@code consultorio_id} de la victima, y la FK la aceptaria sin objetar nada.
	 *
	 * <p><b>4 se resuelve por el PUERTO, no inyectando {@code ServicioService}</b> (ruling R1, ya
	 * escrito en el javadoc del puerto). Un servicio de aplicacion que llama a otro le arrastra su
	 * autorizacion, su transaccion y su auditoria a un camino que no las pidio: mutar el catalogo
	 * global exige rol de plataforma, y quien crea una oferta no lo tiene ni lo necesita. Aca hace
	 * falta un DATO —{@link Servicio#isOperable()}— y no una operacion.
	 *
	 * <p>Que la baja de un Servicio impida ALTAS nuevas y no toque las ofertas ya creadas es
	 * exactamente lo que pide RF-M27-002 —"al inactivar impide nuevas altas de oferta segun
	 * politica"— junto con RN-M03-006, que prohibe afectar historicos. La base no puede expresar
	 * "solo al insertar": esta linea es la unica que lo hace.
	 *
	 * <p><b>Idempotencia: la garantiza el unique.</b> Ver {@link OfertaAltaCommand}.
	 *
	 * @throws ConsultorioNoAccesibleException si la sede no existe o es de otro tenant (404)
	 * @throws ConsultorioNoOperableException si la sede esta dada de baja (409)
	 * @throws ServicioNotAccessibleException si el servicio no existe (404)
	 * @throws ServicioInactivoException si el servicio esta dado de baja (409)
	 * @throws OfertaNombreComercialTakenException si ya hay una oferta vigente con ese nombre (409)
	 */
	@Transactional
	public OfertaView crear(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			OfertaAltaCommand command) {

		// 1, 2 y la resolucion de la sede contra la base.
		ConsultorioSnapshot sede = exigirGestion(actor, organizationId, consultorioId);

		// 3.
		exigirSedeOperable(sede);

		// 4.
		Servicio servicio = exigirServicioOfertable(command.servicioId());

		LocalDate hoy = hoyEn(sede);
		String nombreComercial = normalizar(
				command.nombreComercial(), "El nombre comercial de la oferta es obligatorio");

		OfertaServicioConsultorio oferta = new OfertaServicioConsultorio(
				organizationId,
				sede.id(),
				servicio.getId(),
				nombreComercial,
				command.descripcion(),
				// 5. Los *Default del Servicio se copian UNA VEZ y desde aca son datos de esta
				//    oferta: RF-M06-006 exige que no reemplacen su configuracion concreta, y la
				//    unica forma de garantizarlo en el tiempo es que la fila no vuelva a mirar al
				//    Servicio nunca mas.
				command.modalidad() == null ? servicio.getModalidadDefault() : command.modalidad(),
				exigirDuracion(command.duracionMinutos()),
				exigirCapacidad(command.capacidad()),
				command.precioBase(),
				normalizarMoneda(command.moneda()),
				command.esquemaCobro(),
				Boolean.TRUE.equals(command.admiteObraSocial()),
				command.requiereCasoClinico() == null
						? servicio.isRequiereCasoClinicoDefault()
						: command.requiereCasoClinico(),
				command.generaRegistroClinico() == null
						? servicio.isGeneraRegistroClinicoDefault()
						: command.generaRegistroClinico(),
				// Estos dos NO tienen default en el Servicio: son decisiones operativas de la sede
				// y el catalogo global no tiene como opinar sobre ellas. Ausentes, false, que es el
				// valor menos invasivo (RN-M06-005).
				Boolean.TRUE.equals(command.requiereProfesional()),
				Boolean.TRUE.equals(command.requiereEspacio()),
				command.vigenciaDesde() == null ? hoy : command.vigenciaDesde(),
				command.vigenciaHasta());

		OfertaServicioConsultorio creada;
		try {
			// saveAndFlush: la clave duplicada tiene que aparecer ACA y no al commit, donde el
			// catch ya no la ve y el advice generico devuelve 500.
			creada = ofertas.saveAndFlush(oferta);
		} catch (DataIntegrityViolationException nombreRepetido) {
			// Y desde aca NO se vuelve a tocar la sesion JPA: ni una lectura, ni la auditoria. Una
			// sesion reusada despues de un flush fallido tira AssertionFailure y convierte este 409
			// legitimo en un 500.
			log.info("Alta de oferta rechazada por nombre comercial repetido: "
					+ "organizationId={} consultorioId={}", organizationId, consultorioId);
			throw new OfertaNombreComercialTakenException(consultorioId, nombreComercial);
		}

		// 6.
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("servicioId", String.valueOf(creada.getServicioId()));
		detalles.put("modalidad", creada.getModalidad().name());
		detalles.put("duracionMinutos", String.valueOf(creada.getDuracionMinutos()));
		detalles.put("capacidad", String.valueOf(creada.getCapacidad()));
		auditar(AuditEvents.OFERTA_CREATED, creada, actor, null, "ACTIVO", null, detalles);

		log.info("Oferta creada: organizationId={} consultorioId={} ofertaId={} servicioId={}",
				organizationId, consultorioId, creada.getId(), creada.getServicioId());

		return OfertaView.de(creada, hoy);
	}

	// =================================================================================
	// Edicion (RF-M03-006)
	// =================================================================================

	/**
	 * Edita la configuracion de una oferta vigente.
	 *
	 * <p><b>Ni el servicio ni la sede se pueden cambiar</b> (ruling R3): no viajan en el comando y
	 * las dos columnas son {@code updatable = false}. Ver {@link OfertaEdicionCommand}.
	 *
	 * <p><b>Una oferta INACTIVA no se edita: 409.</b> Reabrir la ficha de algo dado de baja para
	 * cambiarle el precio reescribiria el historico que RN-M27-007 protege, y no existe la
	 * reactivacion.
	 *
	 * <p><b>La edicion NO reconsulta al Servicio.</b> Si el concepto global se dio de baja despues
	 * del alta, esta oferta se sigue editando sin problema: RF-M27-002 impide ALTAS nuevas, no
	 * mantener las que ya existen, y RN-M03-006 prohibe afectar historicos. Un centro que tiene
	 * turnos vendidos sobre una oferta tiene que poder corregirle un horario aunque la plataforma
	 * haya discontinuado el concepto.
	 *
	 * @throws OfertaNotAccessibleException si no existe, es de otro tenant o de otra sede (404)
	 * @throws OfertaInactivaException si la oferta esta dada de baja (409)
	 * @throws OptimisticLockingFailureException si la version enviada quedo vieja (409
	 *         {@code conflict})
	 * @throws OfertaNombreComercialTakenException si el nombre pedido ya esta tomado (409)
	 */
	@Transactional
	public OfertaView editar(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			OfertaEdicionCommand command) {

		ConsultorioSnapshot sede = exigirGestion(actor, organizationId, consultorioId);

		OfertaServicioConsultorio oferta = cargar(organizationId, consultorioId, ofertaId);
		exigirOperable(oferta, "editar");
		exigirVersion(oferta, command.expectedVersion());

		String nombreComercial = command.nombreComercial() == null
				? null
				: normalizar(command.nombreComercial(),
						"El nombre comercial de la oferta es obligatorio");
		Map<String, String> detalles = cambios(oferta, nombreComercial, command);

		oferta.updateDatos(
				nombreComercial,
				command.descripcion(),
				command.modalidad(),
				exigirDuracionOpcional(command.duracionMinutos()),
				exigirCapacidadOpcional(command.capacidad()),
				command.precioBase(),
				normalizarMoneda(command.moneda()),
				command.limpiarPrecio(),
				command.esquemaCobro(),
				command.limpiarEsquemaCobro(),
				command.admiteObraSocial(),
				command.requiereCasoClinico(),
				command.generaRegistroClinico(),
				command.requiereProfesional(),
				command.requiereEspacio(),
				command.vigenciaDesde(),
				command.vigenciaHasta(),
				command.limpiarVigenciaHasta());

		OfertaServicioConsultorio guardada;
		try {
			guardada = ofertas.saveAndFlush(oferta);
		} catch (DataIntegrityViolationException nombreRepetido) {
			// El unico unique que una edicion puede violar es el de nombre comercial: el servicio y
			// la sede son updatable = false y ni siquiera viajan en el comando.
			log.info("Edicion de oferta rechazada por nombre comercial repetido: ofertaId={}",
					ofertaId);
			throw new OfertaNombreComercialTakenException(consultorioId, nombreComercial);
		}

		// Se audita aunque no haya cambiado ningun valor: el intento de edicion tambien es un
		// hecho, y omitirlo dejaria un hueco en el historial justo cuando alguien lo revisa.
		auditar(AuditEvents.OFERTA_UPDATED, guardada, actor, null, null, null, detalles);

		return OfertaView.de(guardada, hoyEn(sede));
	}

	// =================================================================================
	// Baja logica (RN-M27-007)
	// =================================================================================

	/**
	 * Da de baja una oferta. Motivo obligatorio, sin borrado fisico.
	 *
	 * <p>La fila NO se borra: queda {@code active = false} con su {@code deleted_at} y su motivo, y
	 * sigue siendo legible por id con todos sus valores. La oferta deja de admitir reservas nuevas
	 * y <b>nada de lo que ya se presto sobre ella se modifica</b> (RN-M27-007). Libera ademas su
	 * nombre comercial para una oferta nueva de la misma sede, porque el unique lleva
	 * {@code deleted_key} como discriminador.
	 *
	 * <p><b>No hay reactivacion</b>, y por eso la segunda baja no es idempotente sino 409: una
	 * oferta que vuelve es un alta nueva, no una baja deshecha. Modelarlo al reves borraria el
	 * rastro de que la sede dejo de ofrecerla alguna vez, que es justo lo que la baja logica
	 * existe para conservar.
	 *
	 * <p><b>El caso borde "baja con turnos futuros" no se controla</b>, y no se finge resuelto: el
	 * paquete E-1 enchufo sondas de turnos para sedes, espacios, profesionales y disponibilidad,
	 * pero no para ofertas, asi que la baja procede aunque haya turnos reservados sobre la oferta.
	 * Que debe pasar con esos turnos es una decision abierta, y ADR-0011 prohibe una cancelacion en
	 * cascada sin confirmacion explicita, motivo y auditoria por turno.
	 *
	 * @throws OfertaNotAccessibleException si no existe, es de otro tenant o de otra sede (404)
	 * @throws OfertaInactivaException si ya estaba dada de baja (409)
	 * @throws IllegalArgumentException si no viene motivo (400)
	 */
	@Transactional
	public OfertaView darDeBaja(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			String motivo) {

		ConsultorioSnapshot sede = exigirGestion(actor, organizationId, consultorioId);
		String razon = exigirMotivo(motivo);

		OfertaServicioConsultorio oferta = cargar(organizationId, consultorioId, ofertaId);
		exigirOperable(oferta, "dar de baja");

		oferta.deactivate(Instant.now(), razon);
		OfertaServicioConsultorio guardada = ofertas.save(oferta);

		auditar(AuditEvents.OFERTA_DEACTIVATED, guardada, actor, "ACTIVO", "INACTIVO", razon,
				Map.of());

		log.info("Oferta dada de baja: organizationId={} consultorioId={} ofertaId={}",
				organizationId, consultorioId, ofertaId);

		return OfertaView.de(guardada, hoyEn(sede));
	}

	// =================================================================================
	// Politica de prepago (AKINE E-6, DP-06 / ADR-0013)
	// =================================================================================

	/**
	 * Cambia la politica de prepago de una oferta vigente.
	 *
	 * <p>Mismo permiso y mismas reglas que editar —{@code consultorio:manage} sobre la sede, oferta
	 * operable, version vigente—: es configuracion de la oferta. Lo que decide no es si se atiende
	 * sino si la recepcion alerta que falta el prepago (diseno de E-6).
	 *
	 * @throws OfertaNotAccessibleException si no existe, es de otro tenant o de otra sede (404)
	 * @throws OfertaInactivaException si la oferta esta dada de baja (409)
	 * @throws OptimisticLockingFailureException si la version enviada quedo vieja (409)
	 */
	@Transactional
	public OfertaView cambiarPoliticaDePrepago(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			boolean exigePrepago,
			long expectedVersion) {

		ConsultorioSnapshot sede = exigirGestion(actor, organizationId, consultorioId);

		OfertaServicioConsultorio oferta = cargar(organizationId, consultorioId, ofertaId);
		exigirOperable(oferta, "cambiar la politica de prepago de");
		exigirVersion(oferta, expectedVersion);

		boolean anterior = oferta.isExigePrepago();
		oferta.cambiarPoliticaDePrepago(exigePrepago);
		OfertaServicioConsultorio guardada = ofertas.saveAndFlush(oferta);

		auditar(AuditEvents.OFERTA_POLITICA_PREPAGO_CHANGED, guardada, actor,
				String.valueOf(anterior), String.valueOf(exigePrepago), null, Map.of());

		log.info("Politica de prepago de oferta: ofertaId={} exigePrepago={} -> {}",
				ofertaId, anterior, exigePrepago);

		return OfertaView.de(guardada, hoyEn(sede));
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	/**
	 * Exige {@code consultorio:manage} SOBRE ESA SEDE, en una MUTACION.
	 *
	 * <p><b>Primero el contexto y el tenant, despues el permiso.</b> El evaluador responde 403
	 * cuando el actor no tiene el permiso, y un 403 sobre una sede ajena confirmaria que existe.
	 * Por eso {@link #exigirContextoDeLaSede} corre antes y traduce lo ajeno a 404.
	 *
	 * <p><b>La sede que se pasa al evaluador es la de la RUTA, y antes se comprueba que sea la del
	 * contexto.</b> Mismo motivo que {@code AuthorizationGuard.requireSameContext}: un actor puede
	 * administrar la sede A y estar operando con un token acotado a la B; sin la comparacion, un id
	 * de A en la URL le daria acceso administrativo mientras trabaja en B.
	 *
	 * <p>Con la sede como alcance, un {@code CONSULTORIO_ADMIN} pasa sobre la suya y no sobre las
	 * demas, y un {@code ORG_ADMIN} pasa sobre todas: es la formula del evaluador operando, sin
	 * ningun caso especial escrito para {@code offering}.
	 */
	private ConsultorioSnapshot exigirGestion(
			OperatingActor actor, long organizationId, long consultorioId) {

		exigirContextoDeLaSede(actor, organizationId, consultorioId);
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(),
				PermissionCodes.CONSULTORIO_MANAGE,
				organizationId,
				consultorioId,
				null,
				Instant.now()));

		// La sede se resuelve contra la BASE, y siempre ANTES de mutar nada. La FK
		// fk_oferta_consultorio garantiza que el id exista, pero no que sea del tenant del request:
		// sin esta consulta un consultorioId ajeno crearia una fila con el organization_id del
		// atacante y el consultorio_id de la victima, y la FK la aceptaria sin objetar nada.
		// Resolverla al final —solo para saber la zona horaria con la que se arma la vista— dejaria
		// un 404 posible DESPUES de haber escrito: hace rollback, pero confunde el diagnostico.
		return exigirSedeDelTenant(organizationId, consultorioId);
	}

	/**
	 * Exige que la organizacion Y la sede pedidas sean las del contexto ya revalidado.
	 *
	 * <p>Son dos comprobaciones y hacen falta las dos: la de organizacion impide operar sobre un
	 * tenant ajeno, y la de sede impide que un {@code ORG_ADMIN} con contexto en la sede A mute la
	 * B por URL sin haber cambiado de contexto. La segunda es mas estricta de lo que la matriz
	 * exige —{@code ORG_ADMIN} tiene alcance organizacion— y es deliberado: la sede del contexto es
	 * la unica que el sistema revalido contra la base en este request, y
	 * {@code OperatingActor.consultorioId} sale de ahi y nunca del cliente.
	 *
	 * <p>Sin contexto es 403 y nunca 401 ni 404: el actor todavia no eligio donde trabaja, el
	 * frontend tiene que poder traducirlo a "elegi un consultorio", y un 401 le haria borrar el
	 * token. Con contexto de otro tenant es 404, nunca 403.
	 */
	private void exigirContextoDeLaSede(
			OperatingActor actor, long organizationId, long consultorioId) {

		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			log.info("Mutacion de ofertas sin contexto validado: accountId={} organizationId={}",
					actor.accountId(), organizationId);
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| actor.consultorioId() != consultorioId) {
			log.info("Mutacion de ofertas fuera del contexto del request: "
					+ "accountId={} organizationId={} consultorioId={}",
					actor.accountId(), organizationId, consultorioId);
			throw new ConsultorioNoAccesibleException(consultorioId);
		}
	}

	/**
	 * Exige poder LEER las ofertas de esa sede: <b>pertenencia, y nada mas</b>.
	 *
	 * <p>No hay codigo de permiso de por medio porque esta etapa no crea ninguno (diseno §1) y
	 * ninguno de los existentes sirve: {@code consultorio:manage} se lo niega la matriz §6 justo a
	 * los roles que necesitan consultar la cartelera, y {@code espacio:read} es de otro recurso —
	 * reusarlo haria que aprobar o revocar el permiso de los boxes moviera en silencio quien ve los
	 * precios. Se autoriza como lo hacian las lecturas de espacio antes de 02.02 y como lo hace hoy
	 * el catalogo clinico contextual: membership vigente en el tenant, y sede de ese tenant.
	 *
	 * <p><b>El orden importa y es el mismo de siempre:</b> falta de contexto es 403; contexto de
	 * otro tenant o sin membership vigente es 404; y recien despues se resuelve la sede contra la
	 * base, que tambien es 404 si no es de este tenant. Se lee ACTIVA O NO: las ofertas de una sede
	 * dada de baja siguen siendo consultables, igual que la sede misma (RF-M03-004).
	 *
	 * <p>La sede se devuelve porque su {@code timezone} es la zona EFECTIVA de toda regla local del
	 * centro (02.01), y {@code vigenteHoy} se decide en esa zona y no en la del servidor: en una
	 * sede al oeste, "hoy" empieza y termina en otro momento.
	 */
	private ConsultorioSnapshot exigirLectura(
			OperatingActor actor, long organizationId, long consultorioId) {

		if (actor.contextOrganizationId() == null) {
			log.info("Lectura de ofertas sin contexto validado: accountId={} organizationId={}",
					actor.accountId(), organizationId);
			throw new AccessDeniedException("La operacion requiere un contexto de trabajo activo");
		}
		if (actor.contextOrganizationId() != organizationId
				|| !accountContextDirectory.hasActiveMembership(actor.accountId(), organizationId)) {
			log.info("Lectura de ofertas de organizacion ajena rechazada: "
					+ "accountId={} organizationId={}", actor.accountId(), organizationId);
			throw new ConsultorioNoAccesibleException(consultorioId);
		}
		return exigirSedeDelTenant(organizationId, consultorioId);
	}

	// =================================================================================
	// Invariantes
	// =================================================================================

	/**
	 * Carga la oferta acotada al tenant Y a la sede, activa o no.
	 *
	 * <p><b>Nunca por id pelado.</b> Las tres columnas del filtro son necesarias y ninguna es
	 * redundante: sin {@code organizationId} un id ajeno resolveria, y sin {@code consultorioId}
	 * una oferta de otra sede del mismo tenant respondería a una ruta que no le corresponde. No hay
	 * FK compuesta que ataje el descuido — ver la cabecera de la clase.
	 */
	private OfertaServicioConsultorio cargar(
			long organizationId, long consultorioId, long ofertaId) {

		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorioDirectory.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private static void exigirSedeOperable(ConsultorioSnapshot sede) {
		if (!sede.active()) {
			throw new ConsultorioNoOperableException(sede.id());
		}
	}

	/**
	 * El servicio existe y admite ofertas nuevas (RF-M27-002).
	 *
	 * <p>Se lee por el PUERTO —ruling R1— y se pregunta {@link Servicio#isOperable()}. Los dos
	 * rechazos son distintos a proposito: 404 si no existe, porque el catalogo es global y ahi 404
	 * significa literalmente eso; 409 si existe pero esta dado de baja, porque el servicio es
	 * perfectamente visible y lo que no admite es la operacion.
	 */
	private Servicio exigirServicioOfertable(Long servicioId) {
		if (servicioId == null) {
			throw new IllegalArgumentException(
					"Una oferta tiene que declarar que servicio del catalogo global materializa");
		}
		Servicio servicio = servicios.findById(servicioId)
				.orElseThrow(() -> new ServicioNotAccessibleException(servicioId));
		if (!servicio.isOperable()) {
			log.info("Alta de oferta rechazada: el servicio esta dado de baja. servicioId={}",
					servicioId);
			throw new ServicioInactivoException(servicioId);
		}
		return servicio;
	}

	private static void exigirOperable(OfertaServicioConsultorio oferta, String operacion) {
		if (!oferta.isOperable()) {
			log.info("Operacion sobre oferta inactiva rechazada: ofertaId={} operacion={}",
					oferta.getId(), operacion);
			throw new OfertaInactivaException(oferta.getId(), operacion);
		}
	}

	private static void exigirVersion(OfertaServicioConsultorio oferta, long esperada) {
		if (oferta.getVersion() != esperada) {
			// OptimisticLockingFailureException PLANO, no la subclase de JPA: el handler global lo
			// mapea a 409 con type = conflict. concurrent-modification lo emite solo el advice de
			// organization, para la subclase, y prometerlo aca repetiria la inexactitud que
			// arrastran los contratos publicados de 02.02 y 02.05.
			throw new OptimisticLockingFailureException(
					"La oferta fue modificada por otra operacion");
		}
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	/**
	 * Los cambios efectivos, para el detalle de la auditoria.
	 *
	 * <p>Solo entra lo que realmente cambia de valor: un PATCH que reenvia el mismo precio no es un
	 * cambio de precio, y registrarlo como tal llenaria la auditoria de ruido que despues nadie
	 * sabe leer. Mismo criterio que {@code ServicioService.cambios} y {@code EspacioService.cambios}.
	 *
	 * <p>El nombre comercial anterior SI va: es dato del propio tenant y sin el la fila no responde
	 * que cambio. Lo que nunca va son secretos ni contenido clinico.
	 */
	private static Map<String, String> cambios(
			OfertaServicioConsultorio oferta,
			String nombreComercial,
			OfertaEdicionCommand command) {

		Map<String, String> detalles = new LinkedHashMap<>();
		if (nombreComercial != null && !nombreComercial.equals(oferta.getNombreComercial())) {
			detalles.put("nombreComercial", oferta.getNombreComercial() + " -> " + nombreComercial);
		}
		if (command.modalidad() != null && command.modalidad() != oferta.getModalidad()) {
			detalles.put("modalidad", oferta.getModalidad() + " -> " + command.modalidad());
		}
		if (command.duracionMinutos() != null
				&& command.duracionMinutos() != oferta.getDuracionMinutos()) {
			detalles.put("duracionMinutos",
					oferta.getDuracionMinutos() + " -> " + command.duracionMinutos());
		}
		if (command.capacidad() != null && command.capacidad() != oferta.getCapacidad()) {
			detalles.put("capacidad", oferta.getCapacidad() + " -> " + command.capacidad());
		}
		if (command.limpiarPrecio()) {
			detalles.put("precioBase", oferta.getPrecioBase() + " -> (sin precio)");
		} else if (command.precioBase() != null
				&& (oferta.getPrecioBase() == null
						|| command.precioBase().compareTo(oferta.getPrecioBase()) != 0)) {
			detalles.put("precioBase", oferta.getPrecioBase() + " -> " + command.precioBase());
		}
		if (command.vigenciaDesde() != null
				&& !command.vigenciaDesde().equals(oferta.getVigenciaDesde())) {
			detalles.put("vigenciaDesde",
					oferta.getVigenciaDesde() + " -> " + command.vigenciaDesde());
		}
		if (command.limpiarVigenciaHasta()) {
			detalles.put("vigenciaHasta", oferta.getVigenciaHasta() + " -> (sin fin)");
		} else if (command.vigenciaHasta() != null
				&& !command.vigenciaHasta().equals(oferta.getVigenciaHasta())) {
			detalles.put("vigenciaHasta",
					oferta.getVigenciaHasta() + " -> " + command.vigenciaHasta());
		}
		agregarSiCambia(detalles, "admiteObraSocial",
				oferta.isAdmiteObraSocial(), command.admiteObraSocial());
		agregarSiCambia(detalles, "requiereCasoClinico",
				oferta.isRequiereCasoClinico(), command.requiereCasoClinico());
		agregarSiCambia(detalles, "generaRegistroClinico",
				oferta.isGeneraRegistroClinico(), command.generaRegistroClinico());
		agregarSiCambia(detalles, "requiereProfesional",
				oferta.isRequiereProfesional(), command.requiereProfesional());
		agregarSiCambia(detalles, "requiereEspacio",
				oferta.isRequiereEspacio(), command.requiereEspacio());
		return detalles;
	}

	private static void agregarSiCambia(
			Map<String, String> detalles, String campo, boolean anterior, Boolean pedido) {

		if (pedido != null && pedido != anterior) {
			detalles.put(campo, anterior + " -> " + pedido);
		}
	}

	private void auditar(
			String eventType,
			OfertaServicioConsultorio oferta,
			OperatingActor actor,
			String previousState,
			String newState,
			String motivo,
			Map<String, String> details) {

		auditTrail.record(new AuditEntry(
				// CON organizacion y sede, al reves que la auditoria del catalogo global: una
				// Oferta si es dato de un tenant, y sin ellas el evento no se puede atribuir ni
				// recuperar en la consulta de auditoria del centro, que filtra por organizacion.
				oferta.getOrganizationId(),
				oferta.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_OFERTA,
				oferta.getId(),
				previousState,
				newState,
				details,
				motivo,
				AuditEvents.correlationId(),
				Instant.now()));
	}

	/**
	 * "Hoy" en la zona de la sede, que es la unica zona con sentido para una regla local (02.01).
	 *
	 * <p>Usar la del servidor haria que una oferta que vence el 31 apareciera vencida —o vigente—
	 * unas horas antes o despues segun donde corra el proceso, y ese desfasaje solo se nota el dia
	 * del corte, que es el peor dia para notarlo.
	 */
	private static LocalDate hoyEn(ConsultorioSnapshot sede) {
		return LocalDate.now(ZoneId.of(sede.timezone()));
	}

	/**
	 * Normaliza lo que la collation no puede: espacios al principio, al final y dobles internos.
	 *
	 * <p>Mayusculas y acentos NO se tocan: eso lo resuelve {@code utf8mb4_0900_ai_ci} tanto para el
	 * unique como para cualquier comparacion. Normalizarlos ademas en Java seria una segunda
	 * definicion de "igual" que tarde o temprano diverge de la del motor, y guardaria el nombre
	 * distinto de como lo escribio el usuario. Mismo criterio, y mismo texto, que
	 * {@code ServicioService.normalizar}.
	 */
	private static String normalizar(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip().replaceAll("\\s{2,}", " ");
	}

	/** La moneda viaja en mayusculas: el {@code VARCHAR(3)} de V24 espera un ISO-4217. */
	private static String normalizarMoneda(String moneda) {
		return moneda == null || moneda.isBlank()
				? null
				: moneda.strip().toUpperCase(Locale.ROOT);
	}

	private static int exigirDuracion(Integer duracionMinutos) {
		if (duracionMinutos == null) {
			throw new IllegalArgumentException(
					"La duracion de una oferta es obligatoria: es lo que ocupa en la agenda");
		}
		return exigirRangoDuracion(duracionMinutos);
	}

	private static Integer exigirDuracionOpcional(Integer duracionMinutos) {
		return duracionMinutos == null ? null : exigirRangoDuracion(duracionMinutos);
	}

	/**
	 * Acota la duracion a un rango con sentido operativo.
	 *
	 * <p>El piso lo garantizan ademas {@code ck_oferta_duracion_positiva} en la base y la propia
	 * entidad. El techo —24 horas— <b>no sale de ningun RF</b> y es deliberadamente amplio: lo
	 * unico que impide son los valores que no pueden ser una intencion. Vive en la aplicacion y no
	 * en el esquema para poder cambiarlo sin migracion, mismo criterio que el techo de capacidad de
	 * {@code EspacioService}.
	 */
	private static int exigirRangoDuracion(int duracionMinutos) {
		if (duracionMinutos < 1 || duracionMinutos > 1440) {
			throw new IllegalArgumentException(
					"La duracion de una oferta debe estar entre 1 minuto y 24 horas");
		}
		return duracionMinutos;
	}

	private static int exigirCapacidad(Integer capacidad) {
		if (capacidad == null) {
			throw new IllegalArgumentException(
					"La capacidad de una oferta es obligatoria (RF-M27-003)");
		}
		return exigirRangoCapacidad(capacidad);
	}

	private static Integer exigirCapacidadOpcional(Integer capacidad) {
		return capacidad == null ? null : exigirRangoCapacidad(capacidad);
	}

	/**
	 * Acota la capacidad. La coherencia con la modalidad NO se decide aca: la decide la entidad
	 * ({@code exigirCapacidadGrupalCoherente}) porque depende de los DOS campos ya resueltos —el
	 * pedido y el que quedo—, y una edicion parcial puede cambiar solo uno de los dos.
	 */
	private static int exigirRangoCapacidad(int capacidad) {
		if (capacidad < 1 || capacidad > 1000) {
			throw new IllegalArgumentException(
					"La capacidad de una oferta debe estar entre 1 y 1000 personas");
		}
		return capacidad;
	}

	private static String exigirMotivo(String motivo) {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"Se exige un motivo declarado para la baja de una oferta: sin el, la auditoria "
							+ "no responde por que seis meses despues");
		}
		return motivo.strip();
	}
}
