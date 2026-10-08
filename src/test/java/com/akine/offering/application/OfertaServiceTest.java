package com.akine.offering.application;

import com.akine.offering.domain.EsquemaCobro;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.Naturaleza;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.exception.ConsultorioNoAccesibleException;
import com.akine.offering.domain.exception.OfertaInactivaException;
import com.akine.offering.domain.exception.OfertaNombreComercialTakenException;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.exception.ServicioInactivoException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los invariantes de la Oferta por consultorio, sin base de datos.
 *
 * <h2>Que puede y que no puede probar este test</h2>
 *
 * <p><b>Puede</b> probar el objetivo de la etapa —dos sedes ofertando el mismo Servicio global con
 * configuraciones distintas y sin tocarse—, el orden de la autorizacion (tenant antes que permiso,
 * 404 antes que 403), que un servicio dado de baja no admite ofertas nuevas, que la coherencia
 * GRUPAL/capacidad se aplica, que una version vieja no pisa un cambio ajeno, y que la baja es
 * logica y conserva la fila.
 *
 * <p><b>No puede</b> probar que las cuatro consultas derivadas del puerto realmente aislen por
 * tenant contra MySQL: aca el puerto esta mockeado y lo que se afirma es que <b>el servicio les
 * pase siempre las dos columnas</b>, que es la mitad que depende de este codigo. La otra mitad —que
 * el {@code WHERE} generado sea el correcto— la cubre el IT de la Tarea 8, y pesa mas de lo
 * habitual porque <b>no hay FK compuesta {@code (organization_id, consultorio_id)}</b> sobre la
 * tabla: a nivel base una fila puede nombrar el tenant A apuntando a un consultorio del B, y nada
 * en el esquema atrapa una consulta que filtre por una sola de las dos.
 *
 * <p>Tampoco puede probar que el unique impida dos ofertas vigentes homonimas en la misma sede: eso
 * lo garantiza {@code uk_oferta_sede_nombre_vigente} de V24. Lo que se fija aca es que el servicio
 * <b>traduzca</b> esa violacion a 409 en vez de dejarla escapar como 500.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfertaServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG = 10L;
	private static final long SEDE = 20L;
	private static final long OTRA_ORG = 99L;
	private static final long OTRA_SEDE = 90L;
	private static final long SERVICIO_ID = 700L;
	private static final long OFERTA_ID = 800L;

	/** Zona fija: "hoy" se decide en la zona de la SEDE, no en la del servidor que corre el test. */
	private static final String ZONA = "America/Argentina/Cordoba";

	@Mock
	private OfertaRepositoryPort ofertas;

	@Mock
	private ServicioRepositoryPort servicios;

	@Mock
	private ConsultorioDirectory consultorioDirectory;

	@Mock
	private AccountContextDirectory accountContextDirectory;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	private OfertaService service;

	/** Administra la sede 20 de la organizacion 10, con contexto revalidado en este request. */
	private final OperatingActor admin = new OperatingActor(ACCOUNT_ID, false, ORG, SEDE);

	/** Mismo actor, pero el request apunta a una sede de OTRO tenant. */
	private final OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

	private final AtomicLong secuenciaDeIds = new AtomicLong(OFERTA_ID);

	@BeforeEach
	void setUp() {
		service = new OfertaService(
				ofertas, servicios, consultorioDirectory, accountContextDirectory,
				permissionGuard, auditTrail);

		given(consultorioDirectory.find(ORG, SEDE)).willReturn(Optional.of(sede(ORG, SEDE, true)));
		given(consultorioDirectory.find(OTRA_ORG, OTRA_SEDE))
				.willReturn(Optional.of(sede(OTRA_ORG, OTRA_SEDE, true)));
		given(accountContextDirectory.hasActiveMembership(ACCOUNT_ID, ORG)).willReturn(true);
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(servicioVigente()));
		// La base asigna el id en el INSERT; aca lo pone la secuencia, y una distinta por fila para
		// que dos ofertas del mismo servicio no sean "la misma" por accidente.
		given(ofertas.saveAndFlush(any())).willAnswer(invocacion -> conId(invocacion.getArgument(0)));
		given(ofertas.save(any())).willAnswer(invocacion -> invocacion.getArgument(0));
	}

	// =================================================================================
	// EL TEST DE LA ETAPA: CA-M03-006-06 y CA-M27-003-06
	// =================================================================================

	@Test
	@DisplayName("Dos sedes ofertan el MISMO servicio global con duracion, cupo, precio y "
			+ "modalidad distintos, y ninguna afecta a la otra")
	void dos_sedes_pueden_ofertar_el_mismo_servicio_con_configuraciones_distintas() {
		// El escenario literal del criterio: "dos consultorios pueden ofrecer Pilates Reformer con
		// duracion y capacidad diferentes". Es el objetivo entero de la etapa y la regla maestra 14.
		Servicio pilatesReformer = servicioVigente();
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(pilatesReformer));

		// Sede A: clases grupales de 60 minutos para 6 personas, con obra social.
		OfertaView enLaSedeA = service.crear(admin, ORG, SEDE, new OfertaAltaCommand(
				SERVICIO_ID, "Pilates Reformer grupal", "Clase grupal en reformer",
				Modalidad.GRUPAL, 60, 6,
				BigDecimal.valueOf(9000), "ars", new EsquemaCobro("POR_SESION"),
				true, false, false, true, true,
				LocalDate.of(2026, 3, 1), null));

		// Sede B: MISMO servicio, atencion individual de 45 minutos, mas cara, sin obra social y
		// con registro clinico. Otro contexto, porque una mutacion siempre va con el de su sede.
		OperatingActor adminDeB = new OperatingActor(ACCOUNT_ID, false, ORG, 21L);
		given(consultorioDirectory.find(ORG, 21L)).willReturn(Optional.of(sede(ORG, 21L, true)));

		OfertaView enLaSedeB = service.crear(adminDeB, ORG, 21L, new OfertaAltaCommand(
				SERVICIO_ID, "Pilates Reformer individual", "Sesion uno a uno",
				Modalidad.INDIVIDUAL, 45, 1,
				BigDecimal.valueOf(15000), "ARS", new EsquemaCobro("POR_SESION"),
				false, true, true, true, true,
				LocalDate.of(2026, 4, 15), LocalDate.of(2026, 12, 31)));

		// Las dos materializan EL MISMO concepto global...
		assertThat(enLaSedeA.servicioId()).isEqualTo(SERVICIO_ID);
		assertThat(enLaSedeB.servicioId()).isEqualTo(SERVICIO_ID);
		assertThat(enLaSedeA.id()).isNotEqualTo(enLaSedeB.id());

		// ...y NADA de su configuracion coincide. Se asevera valor por valor, y no "que sean
		// distintas": dos filas que difieren solo en el id demostrarian nada.
		assertThat(enLaSedeA.consultorioId()).isEqualTo(SEDE);
		assertThat(enLaSedeB.consultorioId()).isEqualTo(21L);
		assertThat(enLaSedeA.modalidad()).isEqualTo("GRUPAL");
		assertThat(enLaSedeB.modalidad()).isEqualTo("INDIVIDUAL");
		assertThat(enLaSedeA.duracionMinutos()).isEqualTo(60);
		assertThat(enLaSedeB.duracionMinutos()).isEqualTo(45);
		assertThat(enLaSedeA.capacidad()).isEqualTo(6);
		assertThat(enLaSedeB.capacidad()).isEqualTo(1);
		assertThat(enLaSedeA.precioBase()).isEqualByComparingTo(BigDecimal.valueOf(9000));
		assertThat(enLaSedeB.precioBase()).isEqualByComparingTo(BigDecimal.valueOf(15000));
		assertThat(enLaSedeA.admiteObraSocial()).isTrue();
		assertThat(enLaSedeB.admiteObraSocial()).isFalse();
		assertThat(enLaSedeA.generaRegistroClinico()).isFalse();
		assertThat(enLaSedeB.generaRegistroClinico()).isTrue();
		assertThat(enLaSedeA.vigenciaHasta()).isNull();
		assertThat(enLaSedeB.vigenciaHasta()).isEqualTo(LocalDate.of(2026, 12, 31));
		assertThat(enLaSedeA.nombreComercial()).isEqualTo("Pilates Reformer grupal");
		assertThat(enLaSedeB.nombreComercial()).isEqualTo("Pilates Reformer individual");

		// Y el Servicio global no cambio en nada por haber sido ofertado dos veces: es un concepto,
		// no un acumulador de la configuracion de quien lo presta.
		assertThat(pilatesReformer.getModalidadDefault()).isEqualTo(Modalidad.INDIVIDUAL);
		assertThat(pilatesReformer.isGeneraRegistroClinicoDefault()).isTrue();
		assertThat(pilatesReformer.isOperable()).isTrue();
		verify(servicios, never()).save(any());
		verify(servicios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Los *Default del servicio se COPIAN una vez al omitirlos, y no quedan vinculados")
	void los_defaults_del_servicio_se_copian_al_crear_y_no_se_vuelven_a_leer() {
		Servicio servicio = servicioVigente();
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(servicio));

		// El comando omite modalidad, requiereCasoClinico y generaRegistroClinico: se copian del
		// Servicio. requiereProfesional y requiereEspacio NO tienen default y quedan en false.
		OfertaView creada = service.crear(admin, ORG, SEDE, new OfertaAltaCommand(
				SERVICIO_ID, "Kinesiologia deportiva", null,
				null, 30, 1, null, null, null,
				null, null, null, null, null, null, null));

		assertThat(creada.modalidad()).isEqualTo("INDIVIDUAL");
		assertThat(creada.requiereCasoClinico()).isTrue();
		assertThat(creada.generaRegistroClinico()).isTrue();
		assertThat(creada.requiereProfesional()).isFalse();
		assertThat(creada.requiereEspacio()).isFalse();

		// Es una COPIA, no un vinculo (RF-M06-006): el servicio se leyo una sola vez, en el alta.
		// Si esto fuera un puntero, cambiar el default del catalogo global reescribiria en silencio
		// la configuracion de todos los centros que lo ofertan.
		verify(servicios).findById(SERVICIO_ID);
	}

	// =================================================================================
	// Autorizacion: tenant PRIMERO (404), permiso DESPUES (403)
	// =================================================================================

	@Test
	@DisplayName("Una sede de otro tenant da 404 y no 403, y el evaluador de permisos ni se consulta")
	void una_sede_de_otro_tenant_da_404_y_no_403() {
		// El actor tiene contexto valido en su propia organizacion, pero apunta a la sede de otra.
		assertThatThrownBy(() -> service.crear(admin, OTRA_ORG, OTRA_SEDE, altaValida()))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		// Lo mismo en las otras dos mutaciones y en las dos lecturas: una sola puerta.
		assertThatThrownBy(() -> service.editar(admin, OTRA_ORG, OTRA_SEDE, OFERTA_ID, edicion()))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> service.darDeBaja(admin, OTRA_ORG, OTRA_SEDE, OFERTA_ID, "porque si"))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> service.listar(admin, OTRA_ORG, OTRA_SEDE, OfertaEstadoFiltro.ACTIVO))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> service.buscarPorId(admin, OTRA_ORG, OTRA_SEDE, OFERTA_ID))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		// ESTA es la aserción que fija el ORDEN, y no es cosmetica: el evaluador de permisos
		// responde 403, y un 403 sobre una sede ajena confirmaria que esa organizacion existe —
		// bastaria recorrer ids consecutivos para inventariar el SaaS (ADR-0018). Que el guard no
		// se haya consultado NUNCA es lo que prueba que el tenant se resuelve primero.
		verifyNoInteractions(permissionGuard);
		// Y no se leyo ni se escribio una sola fila de otro tenant.
		verifyNoInteractions(ofertas, auditTrail);
	}

	@Test
	@DisplayName("Sin contexto de trabajo es 403, nunca 401 ni 404: el frontend borra el token con un 401")
	void sin_contexto_de_trabajo_es_403() {
		assertThatThrownBy(() -> service.crear(sinContexto, ORG, SEDE, altaValida()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.listar(sinContexto, ORG, SEDE, OfertaEstadoFiltro.ACTIVO))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(permissionGuard, ofertas, auditTrail);
	}

	@Test
	@DisplayName("Sin consultorio:manage el alta da 403, y no escribe nada")
	void sin_consultorio_manage_el_alta_da_403() {
		// El tenant ya se resolvio bien: aca decide el permiso, y un 403 no le filtra al actor nada
		// que no sepa — la sede es suya.
		willThrow(new AccessDeniedException("sin permiso"))
				.given(permissionGuard).requirePermission(any(PermissionQuery.class));

		assertThatThrownBy(() -> service.crear(admin, ORG, SEDE, altaValida()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.editar(admin, ORG, SEDE, OFERTA_ID, edicion()))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.darDeBaja(admin, ORG, SEDE, OFERTA_ID, "ya no se ofrece"))
				.isInstanceOf(AccessDeniedException.class);

		// El permiso se pide con la SEDE como alcance: con ella, un CONSULTORIO_ADMIN pasa sobre la
		// suya y no sobre las demas, y un ORG_ADMIN pasa sobre todas. Es la formula del evaluador
		// operando, sin ningun caso especial escrito para offering.
		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard, org.mockito.Mockito.atLeastOnce())
				.requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo("consultorio:manage");
		assertThat(consulta.getValue().organizationId()).isEqualTo(ORG);
		assertThat(consulta.getValue().consultorioId()).isEqualTo(SEDE);

		// Rechazo sin efectos: ni una fila escrita ni una linea de auditoria.
		verifyNoInteractions(ofertas, auditTrail);
		verify(servicios, never()).findById(anyLong());
	}

	@Test
	@DisplayName("Las lecturas autorizan por PERTENENCIA: sin membership vigente es 404, y sin permiso de por medio")
	void las_lecturas_autorizan_por_pertenencia() {
		given(accountContextDirectory.hasActiveMembership(ACCOUNT_ID, ORG)).willReturn(false);

		assertThatThrownBy(() -> service.listar(admin, ORG, SEDE, OfertaEstadoFiltro.ACTIVO))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		// Con la membership vigente la lectura procede sin consultar ningun codigo de permiso: esta
		// etapa no crea codigos nuevos y ninguno de los existentes sirve — consultorio:manage se lo
		// niega la matriz justo a los roles que necesitan ver la cartelera.
		given(accountContextDirectory.hasActiveMembership(ACCOUNT_ID, ORG)).willReturn(true);
		given(ofertas.findAllByOrganizationIdAndConsultorioIdAndActiveOrderByNombreComercialAsc(
				ORG, SEDE, true)).willReturn(List.of(ofertaVigente()));

		assertThat(service.listar(admin, ORG, SEDE, OfertaEstadoFiltro.ACTIVO)).hasSize(1);
		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("Toda consulta de ofertas lleva tenant Y sede: no hay FK compuesta que ataje el descuido")
	void ninguna_consulta_carga_una_oferta_por_id_pelado() {
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(ofertaVigente()));

		service.buscarPorId(admin, ORG, SEDE, OFERTA_ID);
		service.listar(admin, ORG, SEDE, OfertaEstadoFiltro.TODOS);
		service.listarPorServicio(admin, ORG, SEDE, SERVICIO_ID);

		// Las tres columnas son necesarias y ninguna es redundante: sin organizationId un id ajeno
		// resolveria, y sin consultorioId una oferta de otra sede del mismo tenant respondería a una
		// ruta que no le corresponde. A nivel base NO hay FK (organization_id, consultorio_id), asi
		// que el aislamiento descansa entero en estos predicados.
		verify(ofertas).findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE);
		verify(ofertas).findAllByOrganizationIdAndConsultorioIdOrderByNombreComercialAsc(ORG, SEDE);
		verify(ofertas).findAllByOrganizationIdAndConsultorioIdAndServicioIdOrderByNombreComercialAsc(
				ORG, SEDE, SERVICIO_ID);
	}

	@Test
	@DisplayName("Una oferta de otra sede del mismo tenant no resuelve: 404 y no 200")
	void una_oferta_de_otra_sede_del_mismo_tenant_da_404() {
		// La consulta filtra por (id, organizationId, consultorioId): con la sede equivocada no hay
		// fila, aunque el tenant sea el correcto y el id exista.
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.buscarPorId(admin, ORG, SEDE, OFERTA_ID))
				.isInstanceOf(OfertaNotAccessibleException.class);
	}

	// =================================================================================
	// RF-M27-002: un servicio dado de baja no admite ofertas NUEVAS
	// =================================================================================

	@Test
	@DisplayName("No se puede crear una oferta sobre un servicio inactivo: 409, leido por el puerto")
	void no_se_puede_crear_una_oferta_sobre_un_servicio_inactivo() {
		Servicio dadoDeBaja = servicioVigente();
		dadoDeBaja.deactivate(Instant.now(), "se discontinua el concepto");
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(dadoDeBaja));

		// 409 y no 404: el servicio existe y es visible —es global—, lo que no admite es la
		// operacion. La base no puede expresar "solo al insertar": la FK fk_oferta_servicio es
		// RESTRICT y solo impide el borrado FISICO. Esta comprobacion es la unica que lo hace.
		assertThatThrownBy(() -> service.crear(admin, ORG, SEDE, altaValida()))
				.isInstanceOf(ServicioInactivoException.class);

		verify(ofertas, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);

		// Y se leyo por el PUERTO (ruling R1). ServicioService NO se inyecta: un servicio de
		// aplicacion que llama a otro le arrastraria su autorizacion —mutar el catalogo global
		// exige rol de plataforma— a un camino que no la pidio. Aca hace falta un dato, no una
		// operacion. Se afirma sobre la FORMA de la clase para que el dia que alguien "simplifique"
		// inyectandolo, este test lo detecte.
		verify(servicios).findById(SERVICIO_ID);
		assertThat(Arrays.stream(OfertaService.class.getDeclaredConstructors()[0].getParameterTypes()))
				.as("OfertaService lee el estado del servicio por el puerto, no llamando a ServicioService")
				.doesNotContain(ServicioService.class)
				.contains(ServicioRepositoryPort.class);
	}

	@Test
	@DisplayName("Pero una oferta YA creada sobre ese servicio se sigue editando: la baja no cascadea")
	void editar_una_oferta_no_reconsulta_el_estado_del_servicio() {
		OfertaServicioConsultorio oferta = ofertaVigente();
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(oferta));
		// El servicio global esta dado de baja...
		Servicio dadoDeBaja = servicioVigente();
		dadoDeBaja.deactivate(Instant.now(), "se discontinua");
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(dadoDeBaja));

		// ...y la edicion procede igual. RF-M27-002 impide ALTAS nuevas, no mantener las que ya
		// existen, y RN-M03-006 prohibe afectar historicos: un centro con turnos vendidos sobre
		// esta oferta tiene que poder corregirle la duracion.
		OfertaView editada = service.editar(admin, ORG, SEDE, OFERTA_ID, edicionDeDuracion(50));

		assertThat(editada.duracionMinutos()).isEqualTo(50);
		assertThat(editada.estado()).isEqualTo("ACTIVO");
		// La edicion ni siquiera mira al servicio: no hay ninguna consulta que pudiera empezar a
		// cascadear por descuido.
		verify(servicios, never()).findById(anyLong());
	}

	// =================================================================================
	// GRUPAL exige capacidad > 1
	// =================================================================================

	@Test
	@DisplayName("Una oferta GRUPAL exige capacidad mayor que uno, al crear y al editar")
	void una_oferta_grupal_exige_capacidad_mayor_que_uno() {
		// ck_oferta_grupal_capacidad de V24: una oferta GRUPAL de capacidad uno es una INDIVIDUAL
		// mal rotulada, y el motor de inscripciones de F2 la trataria como un grupo de una persona.
		assertThatThrownBy(() -> service.crear(admin, ORG, SEDE, new OfertaAltaCommand(
				SERVICIO_ID, "Pilates Reformer grupal", null,
				Modalidad.GRUPAL, 60, 1, null, null, null,
				null, null, null, null, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("GRUPAL");

		verify(ofertas, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);

		// Y tambien por el otro lado: una oferta grupal de capacidad 6 a la que se le baja el cupo
		// a 1. Es el camino que un pre-chequeo escrito solo en el alta dejaria abierto.
		OfertaServicioConsultorio grupal = ofertaGrupal();
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(grupal));

		assertThatThrownBy(() -> service.editar(admin, ORG, SEDE, OFERTA_ID, edicionDeCapacidad(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("GRUPAL");

		// TRAMPA REAL, encontrada al escribir este test: NO se puede aseverar aca que
		// grupal.getCapacidad() siga siendo 6. OfertaServicioConsultorio.updateDatos ASIGNA los
		// campos y recien despues valida, asi que la edicion rechazada deja el OBJETO en memoria
		// con capacidad 1. Lo unico que protege la FILA es el rollback de la transaccion, y por eso
		// lo que este test asevera es que no se escribio nada: aseverar el estado del objeto seria
		// aseverar algo que el codigo no garantiza, y "arreglarlo" cambiando la entidad es tarea de
		// otra. Queda anotado en el reporte de la tarea.
		verify(ofertas, never()).saveAndFlush(any());

		// Bajar el cupo a 1 SI se puede si al mismo tiempo deja de ser grupal: la coherencia se
		// evalua sobre los DOS campos ya resueltos, no sobre el que vino en el comando. Se usa una
		// oferta nueva justamente por la trampa de arriba.
		OfertaServicioConsultorio otraGrupal = ofertaGrupal();
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(otraGrupal));

		OfertaView pasadaAIndividual = service.editar(admin, ORG, SEDE, OFERTA_ID,
				edicionDeModalidadYCapacidad(Modalidad.INDIVIDUAL, 1));
		assertThat(pasadaAIndividual.capacidad()).isEqualTo(1);
		assertThat(pasadaAIndividual.modalidad()).isEqualTo("INDIVIDUAL");
	}

	// =================================================================================
	// Concurrencia y nombre comercial
	// =================================================================================

	@Test
	@DisplayName("Editar con una version vieja da 409 concurrent-modification, y no pisa el cambio ajeno")
	void la_edicion_con_version_vieja_da_409_concurrent_modification() {
		OfertaServicioConsultorio oferta = ofertaVigente();
		ReflectionTestUtils.setField(oferta, "version", 3L);
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(oferta));

		assertThatThrownBy(() -> service.editar(admin, ORG, SEDE, OFERTA_ID, edicionDeDuracion(50)))
				// GlobalExceptionHandler mapea el plano —y la subclase de JPA— a
				// concurrent-modification (DP-21).
				.isExactlyInstanceOf(OptimisticLockingFailureException.class);

		assertThat(oferta.getDuracionMinutos()).isEqualTo(45);
		verify(ofertas, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Dos ofertas vigentes con el mismo nombre comercial en la sede: 409 traducido del unique")
	void un_nombre_comercial_repetido_en_la_sede_da_409() {
		willThrow(new DataIntegrityViolationException(
				"Duplicate entry for key 'uk_oferta_sede_nombre_vigente'"))
				.given(ofertas).saveAndFlush(any());

		assertThatThrownBy(() -> service.crear(admin, ORG, SEDE, altaValida()))
				.isInstanceOf(OfertaNombreComercialTakenException.class)
				.extracting(problema ->
						((OfertaNombreComercialTakenException) problema).getConsultorioId())
				.isEqualTo(SEDE);

		// Despues de un flush fallido no se vuelve a tocar la sesion JPA: ni una lectura ni la
		// auditoria. Lo que sale de ahi es un 500 en vez del 409 legitimo.
		verifyNoInteractions(auditTrail);
		verify(ofertas, never()).save(any());
	}

	// =================================================================================
	// Baja logica (RN-M27-007)
	// =================================================================================

	@Test
	@DisplayName("La baja es logica y conserva la fila: nada se borra y la oferta sigue siendo legible")
	void la_baja_es_logica_y_conserva_la_fila() {
		OfertaServicioConsultorio oferta = ofertaVigente();
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(oferta));

		OfertaView baja = service.darDeBaja(admin, ORG, SEDE, OFERTA_ID, "se discontinua el turno tarde");

		// La fila sobrevive con TODOS sus valores: RN-M27-007 exige que el historico se conserve, y
		// no hay ningun DELETE fisico en este modulo.
		assertThat(baja.estado()).isEqualTo("INACTIVO");
		assertThat(baja.vigenteHoy()).isFalse();
		assertThat(baja.deletedAt()).isNotNull();
		assertThat(baja.deactivationReason()).isEqualTo("se discontinua el turno tarde");
		assertThat(baja.nombreComercial()).isEqualTo("Kinesiologia deportiva");
		assertThat(baja.duracionMinutos()).isEqualTo(45);
		assertThat(baja.capacidad()).isEqualTo(1);
		assertThat(baja.precioBase()).isEqualByComparingTo(BigDecimal.valueOf(8500));
		assertThat(baja.servicioId()).isEqualTo(SERVICIO_ID);
		assertThat(oferta.isActive()).isFalse();

		// La segunda baja NO es idempotente: no existe la reactivacion, asi que volver a darla de
		// baja es un conflicto y no un no-op silencioso. Editarla, tampoco.
		assertThatThrownBy(() -> service.darDeBaja(admin, ORG, SEDE, OFERTA_ID, "otra vez"))
				.isInstanceOf(OfertaInactivaException.class);
		assertThatThrownBy(() -> service.editar(admin, ORG, SEDE, OFERTA_ID, edicionDeDuracion(50)))
				.isInstanceOf(OfertaInactivaException.class);
	}

	@Test
	@DisplayName("La baja exige un motivo declarado: sin el, la auditoria no responde por que")
	void la_baja_sin_motivo_se_rechaza() {
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(ofertaVigente()));

		assertThatThrownBy(() -> service.darDeBaja(admin, ORG, SEDE, OFERTA_ID, "   "))
				.isInstanceOf(IllegalArgumentException.class);

		verify(ofertas, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	@Test
	@DisplayName("La auditoria se escribe en la transaccion del negocio, CON organizacion y sede")
	void la_auditoria_se_escribe_en_la_misma_transaccion_y_con_tenant()
			throws NoSuchMethodException {

		service.crear(admin, ORG, SEDE, altaValida());

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("OFERTA_CREATED");
		assertThat(fila.getValue().entityType()).isEqualTo("OfertaServicioConsultorio");
		assertThat(fila.getValue().actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(fila.getValue().newState()).isEqualTo("ACTIVO");
		assertThat(fila.getValue().details()).containsEntry("servicioId", String.valueOf(SERVICIO_ID));

		// CON organizacion y sede, al reves que la auditoria del catalogo global: una Oferta SI es
		// dato de un tenant, y sin ellas el evento no lo recuperaria la consulta de auditoria del
		// centro, que filtra por organizacion.
		assertThat(fila.getValue().organizationId()).isEqualTo(ORG);
		assertThat(fila.getValue().consultorioId()).isEqualTo(SEDE);

		// Que la escritura ocurra DENTRO de la transaccion no lo prueba el verify por si solo: lo
		// prueba que el metodo que la produjo sea transaccional y de escritura. AuditTrail.record es
		// Propagation.MANDATORY, asi que un listener post-commit ni podria llamarlo — y este assert
		// impide que alguien "arregle" eso aflojando la demarcacion.
		Transactional demarcacion = OfertaService.class
				.getMethod("crear", OperatingActor.class, long.class, long.class,
						OfertaAltaCommand.class)
				.getAnnotation(Transactional.class);
		assertThat(demarcacion).isNotNull();
		assertThat(demarcacion.readOnly()).isFalse();
	}

	// =================================================================================
	// Ruling R3: el servicio y la sede de una oferta son inmutables
	// =================================================================================

	@Test
	@DisplayName("Mover una oferta a otro servicio o a otra sede no es editarla: el comando no lo expresa")
	void el_servicio_y_la_sede_de_una_oferta_son_inmutables() {
		// Ruling R3, y updatable = false en las dos columnas. Se verifica sobre la FORMA del
		// comando y no sobre el resultado de una llamada, porque lo que hay que impedir es que el
		// campo llegue a existir: con el campo puesto, olvidarse de ignorarlo es un descuido de una
		// linea. Misma tecnica que el_codigo_de_un_servicio_no_se_puede_editar de la Tarea 4.
		assertThat(Arrays.stream(OfertaEdicionCommand.class.getRecordComponents())
						.map(java.lang.reflect.RecordComponent::getName))
				.doesNotContain("servicioId", "consultorioId", "organizationId");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsultorioSnapshot sede(long organizationId, long id, boolean activa) {
		return new ConsultorioSnapshot(id, organizationId, "Sede " + id, ZONA, activa);
	}

	private static OfertaAltaCommand altaValida() {
		return new OfertaAltaCommand(
				SERVICIO_ID, "Kinesiologia deportiva", "Rehabilitacion de lesiones",
				Modalidad.INDIVIDUAL, 45, 1,
				BigDecimal.valueOf(8500), "ARS", new EsquemaCobro("POR_SESION"),
				true, true, true, true, true,
				LocalDate.of(2026, 1, 1), null);
	}

	private static OfertaEdicionCommand edicion() {
		return new OfertaEdicionCommand(
				null, null, null, null, null, null, null, false, null, false,
				null, null, null, null, null, null, null, false, 0L);
	}

	private static OfertaEdicionCommand edicionDeDuracion(int duracionMinutos) {
		return new OfertaEdicionCommand(
				null, null, null, duracionMinutos, null, null, null, false, null, false,
				null, null, null, null, null, null, null, false, 0L);
	}

	private static OfertaEdicionCommand edicionDeCapacidad(int capacidad) {
		return new OfertaEdicionCommand(
				null, null, null, null, capacidad, null, null, false, null, false,
				null, null, null, null, null, null, null, false, 0L);
	}

	private static OfertaEdicionCommand edicionDeModalidadYCapacidad(
			Modalidad modalidad, int capacidad) {

		return new OfertaEdicionCommand(
				null, null, modalidad, null, capacidad, null, null, false, null, false,
				null, null, null, null, null, null, null, false, 0L);
	}

	private static Servicio servicioVigente() {
		Servicio servicio = new Servicio(
				"PILATES-REFORMER",
				"Pilates Reformer",
				"Trabajo en camilla reformer",
				Naturaleza.TERAPEUTICO,
				Modalidad.INDIVIDUAL,
				true,
				true);
		ReflectionTestUtils.setField(servicio, "id", SERVICIO_ID);
		return servicio;
	}

	private OfertaServicioConsultorio ofertaVigente() {
		return conId(new OfertaServicioConsultorio(
				ORG, SEDE, SERVICIO_ID, "Kinesiologia deportiva", "Rehabilitacion de lesiones",
				Modalidad.INDIVIDUAL, 45, 1,
				BigDecimal.valueOf(8500), "ARS", new EsquemaCobro("POR_SESION"),
				true, true, true, true, true,
				LocalDate.of(2026, 1, 1), null));
	}

	private OfertaServicioConsultorio ofertaGrupal() {
		return conId(new OfertaServicioConsultorio(
				ORG, SEDE, SERVICIO_ID, "Pilates Reformer grupal", null,
				Modalidad.GRUPAL, 60, 6,
				null, null, null,
				false, false, false, true, true,
				LocalDate.of(2026, 1, 1), null));
	}

	/** La base asigna el id en el INSERT; en un test unitario lo pone la reflexion. */
	private OfertaServicioConsultorio conId(OfertaServicioConsultorio oferta) {
		if (oferta.getId() == null) {
			ReflectionTestUtils.setField(oferta, "id", secuenciaDeIds.getAndIncrement());
		}
		return oferta;
	}
}
