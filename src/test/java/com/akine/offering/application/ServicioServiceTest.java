package com.akine.offering.application;

import com.akine.offering.domain.EsquemaCobro;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.Naturaleza;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.Servicio;
import com.akine.offering.domain.exception.ServicioCodigoTakenException;
import com.akine.offering.domain.exception.ServicioNombreTakenException;
import com.akine.offering.domain.exception.ServicioNotAccessibleException;
import com.akine.offering.domain.exception.ServicioYaInactivoException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.ServicioRepositoryPort;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los invariantes del catalogo global de Servicios, sin base de datos.
 *
 * <h2>Que puede y que no puede probar este test</h2>
 *
 * <p><b>Puede</b> probar quien esta autorizado a mutar el catalogo, que la traduccion de un
 * choque de unique llega como 409 y no como 500, que una version vieja no pisa cambios ajenos,
 * que la auditoria se emite dentro de la transaccion del negocio, y —el que importa— que la baja
 * de un servicio global no cascadea sobre las ofertas que lo referencian.
 *
 * <p><b>No puede</b> probar que el unique realmente impida dos servicios vigentes homonimos: eso
 * lo garantiza {@code uk_servicio_codigo_vigente} de la migracion V24 y solo se comprueba contra
 * MySQL real ({@code ServicioYOfertaMigrationIT} ya lo hace). Lo que este test fija es que el
 * servicio de aplicacion <b>traduzca</b> esa violacion en vez de dejarla escapar, y que
 * <b>no</b> la anticipe con un SELECT previo — un pre-chequeo tendria una ventana de carrera y,
 * peor, romperia el reuso de un codigo liberado por una baja logica.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServicioServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long SERVICIO_ID = 700L;

	@Mock
	private ServicioRepositoryPort servicios;

	@Mock
	private AuditTrail auditTrail;

	private ServicioService service;

	/** Rol de plataforma: el unico que puede mutar el catalogo global. Sin contexto de tenant. */
	private final OperatingActor plataforma = new OperatingActor(ACCOUNT_ID, true, null, null);

	/** Un usuario de un centro cualquiera, con contexto validado. Ve el catalogo, no lo muta. */
	private final OperatingActor delCentro = new OperatingActor(ACCOUNT_ID, false, 10L, 20L);

	@BeforeEach
	void setUp() {
		service = new ServicioService(servicios, auditTrail);
		given(servicios.saveAndFlush(any())).willAnswer(invocacion -> conId(invocacion.getArgument(0)));
		given(servicios.save(any())).willAnswer(invocacion -> invocacion.getArgument(0));
	}

	// =================================================================================
	// Autorizacion: rol de plataforma, sin permiso de tenant de por medio
	// =================================================================================

	@Test
	@DisplayName("Un usuario sin rol de plataforma no puede crear un servicio global: 403 y nada escrito")
	void sin_rol_de_plataforma_el_alta_da_403() {
		assertThatThrownBy(() -> service.crear(delCentro, altaValida()))
				.isInstanceOf(AccessDeniedException.class);

		// 403 y no 401: el interceptor del frontend borra el token ante cualquier 401 y el usuario
		// entra en un bucle de login. Y no 404: el servicio es global, ocultarlo no protege nada.
		// Que no se haya escrito NADA es la mitad que importa del assert: un rechazo que igual
		// persiste o audita seria peor que no tener el control.
		verifyNoInteractions(servicios, auditTrail);
	}

	@Test
	@DisplayName("Tampoco puede editar ni dar de baja: las tres mutaciones piden lo mismo")
	void sin_rol_de_plataforma_editar_y_dar_de_baja_dan_403() {
		assertThatThrownBy(() -> service.editar(delCentro, SERVICIO_ID, edicionDeNombre("Otro")))
				.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.darDeBaja(delCentro, SERVICIO_ID, "ya no se ofrece"))
				.isInstanceOf(AccessDeniedException.class);

		// El chequeo va ANTES de leer: sin esto, un 404 delataria que el id no existe a alguien
		// que ni siquiera tiene derecho a preguntar.
		verifyNoInteractions(servicios, auditTrail);
	}

	// =================================================================================
	// Codigo y nombre: el unique traducido, y el reuso despues de una baja
	// =================================================================================

	@Test
	@DisplayName("Dos servicios vigentes con el mismo codigo: 409, traducido del unique")
	void un_codigo_repetido_entre_servicios_vigentes_da_409() {
		willThrow(choqueDeUnique("uk_servicio_codigo_vigente"))
				.given(servicios).saveAndFlush(any());

		assertThatThrownBy(() -> service.crear(plataforma, altaValida()))
				.isInstanceOf(ServicioCodigoTakenException.class)
				.extracting(problema -> ((ServicioCodigoTakenException) problema).getCodigo())
				.isEqualTo("KINE-DEPORTIVA");

		// Despues de un flush fallido no se vuelve a tocar la sesion JPA: ni una lectura para
		// averiguar cual de los dos uniques choco, ni la auditoria. Lo que sale de ahi es un 500
		// en vez del 409 legitimo.
		verify(servicios, never()).findById(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("El mismo choque, pero de nombre, se distingue por el nombre del indice")
	void un_nombre_repetido_entre_servicios_vigentes_da_409_de_nombre() {
		willThrow(choqueDeUnique("uk_servicio_nombre_vigente"))
				.given(servicios).saveAndFlush(any());

		// La unica senal disponible es el nombre del indice en el mensaje de MySQL: la excepcion de
		// Spring no lleva ningun campo estructurado con la constraint violada.
		assertThatThrownBy(() -> service.crear(plataforma, altaValida()))
				.isInstanceOf(ServicioNombreTakenException.class);
	}

	/**
	 * El brief nombra este test {@code un_codigo_de_un_servicio_dado_de_baja_se_puede_reusar}, y
	 * ese nombre prometia mas de lo que un test unitario puede dar: <b>aca no se construye ningun
	 * servicio dado de baja</b>, porque el reuso lo garantiza el {@code deleted_key} del unique
	 * {@code uk_servicio_codigo_vigente} y eso solo es observable contra MySQL real —le toca al IT
	 * de la Tarea 8—.
	 *
	 * <p>Lo que si es de esta capa, y es load-bearing, es la <b>precondicion</b> del reuso: que el
	 * alta no pre-chequee el codigo. Un pre-chequeo escrito de la forma natural —mirando activos e
	 * inactivos— rechazaria un alta perfectamente legitima y ningun {@code deleted_key} lo salvaria.
	 * El nombre del metodo dice ahora eso, que es lo que realmente se prueba.
	 */
	@Test
	@DisplayName("El alta no pre-chequea el codigo, que es la precondicion de que uno liberado se reuse")
	void el_alta_no_pre_chequea_el_codigo() {
		ServicioView creado = service.crear(plataforma, altaValida());

		assertThat(creado.codigo()).isEqualTo("KINE-DEPORTIVA");
		assertThat(creado.estado()).isEqualTo("ACTIVO");

		// La aserción que importa: el alta NO consulta el catalogo antes de insertar. Un pre-chequeo
		// "¿ya existe este codigo?" tendria dos defectos, y el segundo es el grave: una ventana de
		// carrera entre el SELECT y el INSERT, y —si mirara activos e inactivos, que es como se
		// escribe mal— rechazaria el alta que reusa un codigo liberado por una baja logica. La
		// unica comprobacion sin ventana es el unique.
		verify(servicios, never()).buscar(anyString(), anyInt());
		verify(servicios, never()).findById(any());
	}

	@Test
	@DisplayName("Un fallo de integridad que NO es el unique de nombre se propaga: no se disfraza de 409")
	void una_violacion_de_integridad_ajena_al_unique_de_nombre_se_propaga() {
		// El caso concreto: PATCH que solo manda una descripcion demasiado larga. El nombre ni se
		// toca —queda null—, pero MySQL en modo estricto rechaza el UPDATE y Spring lo envuelve en
		// la MISMA DataIntegrityViolationException que levanta un unique.
		Servicio servicio = servicioVigente();
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(servicio));
		DataIntegrityViolationException largoExcedido = new DataIntegrityViolationException(
				"could not execute statement",
				new RuntimeException("Data too long for column 'descripcion' at row 1"));
		willThrow(largoExcedido).given(servicios).saveAndFlush(any());

		ServicioEdicionCommand soloDescripcion =
				new ServicioEdicionCommand(null, "x".repeat(3000), null, null, null, null, 0L);

		// Un catch incondicional responderia 409 "ya existe un servicio vigente con ese nombre" con
		// nombre = null, cuando el problema es un largo y corresponde un 400. Un 409 que miente es
		// peor que no traducir: manda al usuario a cambiar un nombre que nunca fue el problema.
		assertThatThrownBy(() -> service.editar(plataforma, SERVICIO_ID, soloDescripcion))
				.isSameAs(largoExcedido);

		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// El caso que rompe el diseno (§7.8): la baja NO cascadea
	// =================================================================================

	@Test
	@DisplayName("Dar de baja un servicio global no toca ninguna oferta que lo referencie")
	void la_baja_no_cascadea_sobre_las_ofertas_que_lo_referencian() {
		// Tres centros de DOS tenants distintos ofertando el mismo servicio global, cada uno con su
		// configuracion. Es el escenario textual de §7.8 del diseno.
		OfertaServicioConsultorio unCentro = oferta(10L, 20L, "Kinesiologia deportiva", 45, 8500);
		OfertaServicioConsultorio otraSede = oferta(10L, 21L, "Kine deportiva - anexo", 30, 7000);
		OfertaServicioConsultorio otroTenant = oferta(99L, 90L, "Rehabilitacion deportiva", 60, 12000);
		List<OfertaServicioConsultorio> ofertas = List.of(unCentro, otraSede, otroTenant);

		Servicio servicio = servicioVigente();
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(servicio));

		ServicioView vista = service.darDeBaja(plataforma, SERVICIO_ID, "se discontinua el concepto");

		// El servicio SI cambia.
		assertThat(vista.estado()).isEqualTo("INACTIVO");
		assertThat(servicio.isOperable()).isFalse();
		assertThat(servicio.getDeactivationReason()).isEqualTo("se discontinua el concepto");
		// Y conserva su identidad: la baja logica no borra nada (RF-M27-002).
		assertThat(vista.codigo()).isEqualTo("KINE-DEPORTIVA");
		assertThat(vista.nombre()).isEqualTo("Kinesiologia deportiva");

		// Las ofertas NO cambian: siguen vigentes y con todos sus valores operativos intactos.
		// RN-M03-006 prohibe afectar historicos, asi que un centro que tenia turnos vendidos sobre
		// esta oferta sigue pudiendo prestarlos. Se asevera el estado REAL de cada objeto, no la
		// ausencia de una excepcion: un test que solo mirara que no se lanzo nada pasaria igual
		// con una cascada que desactivara las tres.
		assertThat(ofertas).allSatisfy(sobreviviente -> {
			assertThat(sobreviviente.isActive()).isTrue();
			assertThat(sobreviviente.getDeletedAt()).isNull();
			assertThat(sobreviviente.getDeactivationReason()).isNull();
			assertThat(sobreviviente.getServicioId()).isEqualTo(SERVICIO_ID);
		});
		assertThat(unCentro.getDuracionMinutos()).isEqualTo(45);
		assertThat(unCentro.getPrecioBase()).isEqualByComparingTo(BigDecimal.valueOf(8500));
		assertThat(otraSede.getDuracionMinutos()).isEqualTo(30);
		assertThat(otroTenant.getNombreComercial()).isEqualTo("Rehabilitacion deportiva");

		// La garantia de fondo no es que este metodo se porte bien: es que NO TIENE COMO portarse
		// mal. ServicioService no recibe el puerto de ofertas, asi que no hay ninguna sentencia
		// que pudiera alcanzarlas. Introducir una cascada exigiria primero inyectar ese puerto, y
		// eso es lo que este assert vigila: sin el, el test de arriba pasaria por vacuidad, porque
		// los objetos que mira ni siquiera estan al alcance del codigo bajo prueba.
		assertThat(tiposQueAlcanzaElServicio())
				.as("ServicioService no puede alcanzar las ofertas: la no-cascada es estructural")
				.doesNotContain(OfertaRepositoryPort.class);
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	@Test
	@DisplayName("La auditoria se escribe en la transaccion del negocio, no en un listener post-commit")
	void la_auditoria_se_escribe_en_la_misma_transaccion() throws NoSuchMethodException {
		service.crear(plataforma, altaValida());

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("SERVICIO_CREATED");
		assertThat(fila.getValue().entityType()).isEqualTo("Servicio");
		assertThat(fila.getValue().entityId()).isEqualTo(SERVICIO_ID);
		assertThat(fila.getValue().actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(fila.getValue().newState()).isEqualTo("ACTIVO");
		assertThat(fila.getValue().details()).containsEntry("codigo", "KINE-DEPORTIVA");

		// Sin organizacion ni sede, SIEMPRE: un Servicio es global y no es dato de ningun tenant.
		// Atribuirle a un centro un cambio del catalogo comun seria una linea de auditoria falsa.
		assertThat(fila.getValue().organizationId()).isNull();
		assertThat(fila.getValue().consultorioId()).isNull();

		// Que la escritura ocurra DENTRO de la transaccion no lo prueba el verify de arriba por si
		// solo: lo prueba que el metodo que la produjo sea transaccional y de escritura.
		// AuditTrail.record es Propagation.MANDATORY, asi que un listener post-commit ni siquiera
		// podria llamarlo — y este assert impide que alguien "arregle" eso aflojando la demarcacion.
		// Se fijan las TRES mutaciones, no solo la que este test ejercita: editar y darDeBaja
		// tambien auditan, y una demarcacion floja en cualquiera de ellas produce el mismo agujero
		// —una mutacion sin rastro— sin que este test se entere si solo mira crear.
		assertThat(demarcacionDe("crear", OperatingActor.class, ServicioAltaCommand.class))
				.isNotNull()
				.extracting(Transactional::readOnly).isEqualTo(false);
		assertThat(demarcacionDe(
						"editar", OperatingActor.class, long.class, ServicioEdicionCommand.class))
				.isNotNull()
				.extracting(Transactional::readOnly).isEqualTo(false);
		assertThat(demarcacionDe("darDeBaja", OperatingActor.class, long.class, String.class))
				.isNotNull()
				.extracting(Transactional::readOnly).isEqualTo(false);
	}

	private static Transactional demarcacionDe(String metodo, Class<?>... firma)
			throws NoSuchMethodException {

		return ServicioService.class.getMethod(metodo, firma).getAnnotation(Transactional.class);
	}

	// =================================================================================
	// Edicion: version, identidad y estado
	// =================================================================================

	@Test
	@DisplayName("Editar con una version vieja da 409 con type conflict, y no pisa el cambio ajeno")
	void editar_con_version_desactualizada_da_conflict() {
		Servicio servicio = servicioVigente();
		ReflectionTestUtils.setField(servicio, "version", 3L);
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(servicio));

		assertThatThrownBy(() -> service.editar(plataforma, SERVICIO_ID, edicionDeNombre("Otro nombre")))
				// La clase EXACTA importa: OptimisticLockingFailureException plano lo mapea el
				// handler global a type = conflict. La subclase de JPA la mapea el advice de
				// organization a concurrent-modification, que es la inexactitud que arrastran los
				// contratos publicados de 02.02 y 02.05 y que esta etapa no repite.
				.isExactlyInstanceOf(OptimisticLockingFailureException.class);

		assertThat(servicio.getNombre()).isEqualTo("Kinesiologia deportiva");
		verify(servicios, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("El codigo es inmutable: el comando de edicion ni siquiera lo puede expresar")
	void el_codigo_de_un_servicio_no_se_puede_editar() {
		// Ruling R3 de la etapa, y updatable = false en la entidad. El codigo es la clave estable
		// por la que otros referencian el servicio: mutarlo haria que una referencia vieja a
		// KINE-DEPORTIVA pase a significar otra cosa sin que nadie lo haya pedido. Se verifica
		// sobre la FORMA del comando y no sobre el resultado de una llamada, porque lo que hay que
		// impedir es que el campo llegue a existir: con el campo puesto, olvidarse de ignorarlo en
		// el servicio es un descuido de una linea.
		assertThat(Arrays.stream(ServicioEdicionCommand.class.getRecordComponents())
						.map(RecordComponent::getName))
				.doesNotContain("codigo");
	}

	@Test
	@DisplayName("Editar un id inexistente da 404: en un catalogo global, 404 significa que no existe")
	void editar_un_servicio_inexistente_da_404() {
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.editar(plataforma, SERVICIO_ID, edicionDeNombre("Otro")))
				.isInstanceOf(ServicioNotAccessibleException.class);
	}

	@Test
	@DisplayName("Un servicio ya dado de baja no se edita ni se vuelve a dar de baja: 409")
	void un_servicio_inactivo_no_admite_mutaciones() {
		Servicio servicio = servicioVigente();
		servicio.deactivate(Instant.now(), "se discontinua");
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(servicio));

		// 409 y no 404: el servicio existe, es global y se lee con 200 —RN-M03-006 exige que el
		// historico conserve su nombre y su codigo—. Lo que no admite son operaciones nuevas.
		assertThatThrownBy(() -> service.editar(plataforma, SERVICIO_ID, edicionDeNombre("Otro")))
				.isInstanceOf(ServicioYaInactivoException.class);
		// Y la segunda baja NO es idempotente: no existe la reactivacion, asi que volver a darlo de
		// baja es un conflicto y no un no-op silencioso.
		assertThatThrownBy(() -> service.darDeBaja(plataforma, SERVICIO_ID, "otra vez"))
				.isInstanceOf(ServicioYaInactivoException.class);

		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("La baja exige un motivo declarado: sin el, la auditoria no responde por que")
	void la_baja_sin_motivo_se_rechaza() {
		given(servicios.findById(SERVICIO_ID)).willReturn(Optional.of(servicioVigente()));

		assertThatThrownBy(() -> service.darDeBaja(plataforma, SERVICIO_ID, "   "))
				.isInstanceOf(IllegalArgumentException.class);

		verify(servicios, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	@Test
	@DisplayName("Leer el catalogo no exige rol de plataforma ni contexto: es global y autenticado")
	void cualquier_usuario_autenticado_lee_el_catalogo_global() {
		given(servicios.buscar("%", 1)).willReturn(List.of(conId(servicioVigente())));

		// La firma no recibe OperatingActor, y eso ES la aserción: no hay nada que decidir con el.
		List<ServicioView> catalogo = service.buscar(new ServicioBusqueda(null, null));

		assertThat(catalogo).hasSize(1);
		assertThat(catalogo.get(0).nombre()).isEqualTo("Kinesiologia deportiva");
		// El default del filtro es ACTIVO (centinela 1): un selector nunca ofrece un servicio dado
		// de baja, que es lo que evita el 409 de RF-M27-002 en la pantalla de alta de una Oferta.
		verify(servicios).buscar("%", 1);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ServicioAltaCommand altaValida() {
		return new ServicioAltaCommand(
				"KINE-DEPORTIVA",
				"Kinesiologia deportiva",
				"Rehabilitacion de lesiones deportivas",
				Naturaleza.CLINICO,
				Modalidad.INDIVIDUAL,
				true,
				true);
	}

	private static ServicioEdicionCommand edicionDeNombre(String nombre) {
		return new ServicioEdicionCommand(nombre, null, null, null, null, null, 0L);
	}

	private static Servicio servicioVigente() {
		return conId(new Servicio(
				"KINE-DEPORTIVA",
				"Kinesiologia deportiva",
				"Rehabilitacion de lesiones deportivas",
				Naturaleza.CLINICO,
				Modalidad.INDIVIDUAL,
				true,
				true));
	}

	/** La base asigna el id en el INSERT; en un test unitario lo pone la reflexion. */
	private static Servicio conId(Servicio servicio) {
		ReflectionTestUtils.setField(servicio, "id", SERVICIO_ID);
		return servicio;
	}

	private static OfertaServicioConsultorio oferta(
			long organizationId, long consultorioId, String nombreComercial,
			int duracionMinutos, int precio) {

		return new OfertaServicioConsultorio(
				organizationId,
				consultorioId,
				SERVICIO_ID,
				nombreComercial,
				null,
				Modalidad.INDIVIDUAL,
				duracionMinutos,
				1,
				BigDecimal.valueOf(precio),
				"ARS",
				new EsquemaCobro("POR_SESION"),
				true,
				true,
				true,
				true,
				true,
				LocalDate.of(2026, 1, 1),
				null);
	}

	/**
	 * Los tipos que {@code ServicioService} tiene a mano: sus colaboradores declarados.
	 *
	 * <p>Mira los campos y los parametros del constructor, no solo los campos: una inyeccion por
	 * constructor que no se guarde en un campo igual le daria acceso al puerto de ofertas dentro
	 * del propio constructor.
	 */
	private static List<Class<?>> tiposQueAlcanzaElServicio() {
		return java.util.stream.Stream.concat(
						Arrays.stream(ServicioService.class.getDeclaredFields()).map(Field::getType),
						Arrays.stream(ServicioService.class.getDeclaredConstructors())
								.map(Constructor::getParameterTypes)
								.flatMap(Arrays::stream))
				.distinct()
				.toList();
	}

	/** Un choque de unique tal como llega desde MySQL: el nombre del indice, en el root cause. */
	private static DataIntegrityViolationException choqueDeUnique(String indice) {
		return new DataIntegrityViolationException(
				"could not execute statement",
				new RuntimeException(
						"Duplicate entry 'KINE-DEPORTIVA-1970-01-01 00:00:00' for key 'servicio."
								+ indice + "'"));
	}
}
