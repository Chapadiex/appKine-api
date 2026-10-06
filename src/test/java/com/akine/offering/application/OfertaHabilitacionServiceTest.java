package com.akine.offering.application;

import com.akine.offering.domain.EsquemaCobro;
import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.OfertaEspacioHabilitado;
import com.akine.offering.domain.OfertaProfesionalHabilitado;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.exception.HabilitacionNoAccesibleException;
import com.akine.offering.domain.exception.OfertaInactivaException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaEspacioHabilitadoRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaProfesionalHabilitadoRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Las reglas de la habilitacion de Ofertas, sin base de datos (AKINE-02.07).
 *
 * <h2>Que se prueba aca y por que</h2>
 *
 * <p>Se cubre lo que los criterios de aceptacion exigen y nada mas. Los casos elegidos son los que
 * <b>fallan en silencio</b> si el codigo se rompe:
 *
 * <ul>
 *   <li><b>Lista vacia significa todos.</b> Si esta regla se invierte, toda oferta recien creada
 *       queda inutilizable y nadie ve un error: la agenda simplemente no ofrece nada.</li>
 *   <li><b>El diff del reemplazo.</b> Si crea de mas o da de baja de menos, el resultado sigue
 *       siendo un 200 con una lista que parece correcta.</li>
 *   <li><b>La capacidad efectiva.</b> Un minimo mal calculado sobrevende un box, y eso se nota el
 *       dia que llegan mas pacientes que camillas.</li>
 *   <li><b>Que la validacion devuelva TODOS los motivos.</b> Devolver el primero obliga al usuario
 *       a dos vueltas y el sintoma es "arregle lo que me dijo y sigue sin andar".</li>
 * </ul>
 *
 * <p>No se testean los getters, ni que el repositorio guarde: eso lo cubre el compilador y el
 * mapping de JPA respectivamente.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfertaHabilitacionServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 3L;
	private static final long OTRA_SEDE = 99L;
	private static final long OFERTA_ID = 34L;
	private static final long ACCOUNT_ID = 170L;
	private static final long MEMBERSHIP_A = 215L;
	private static final long MEMBERSHIP_B = 216L;
	private static final long MEMBERSHIP_C = 217L;
	private static final long ESPACIO_GRANDE = 10L;
	private static final long ESPACIO_CHICO = 11L;
	private static final String ZONA = "America/Argentina/Cordoba";

	@Mock
	private OfertaRepositoryPort ofertas;
	@Mock
	private OfertaProfesionalHabilitadoRepositoryPort profesionales;
	@Mock
	private OfertaEspacioHabilitadoRepositoryPort espacios;
	@Mock
	private ConsultorioDirectory consultorioDirectory;
	@Mock
	private ConsultorioMembershipDirectory membershipDirectory;
	@Mock
	private AccountContextDirectory accountContextDirectory;
	@Mock
	private AccountIdentityDirectory identityDirectory;
	@Mock
	private EspacioDirectory espacioDirectory;
	@Mock
	private PermissionGuard permissionGuard;
	@Mock
	private AuditTrail auditTrail;

	private OfertaHabilitacionService service;

	/** Lo que el repositorio "tiene guardado" durante cada test. */
	private final List<OfertaProfesionalHabilitado> guardadosProfesional = new ArrayList<>();
	private final List<OfertaEspacioHabilitado> guardadosEspacio = new ArrayList<>();

	@BeforeEach
	void setUp() {
		service = new OfertaHabilitacionService(
				ofertas, profesionales, espacios, consultorioDirectory, membershipDirectory,
				accountContextDirectory, identityDirectory, espacioDirectory, permissionGuard,
				auditTrail);

		given(consultorioDirectory.find(ORG, SEDE))
				.willReturn(Optional.of(new ConsultorioSnapshot(SEDE, ORG, "Sede", ZONA, true)));
		given(accountContextDirectory.hasActiveMembership(ACCOUNT_ID, ORG)).willReturn(true);
		// Las dos: la lectura usa la primera y la configuracion la segunda, que ademas le fuerza
		// el avance de version a la oferta.
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(ofertaCapacidad(8)));
		given(ofertas.findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(ofertaCapacidad(8)));

		given(membershipDirectory.find(ORG, MEMBERSHIP_A))
				.willReturn(Optional.of(vinculoVigente(MEMBERSHIP_A, null)));
		given(membershipDirectory.find(ORG, MEMBERSHIP_B))
				.willReturn(Optional.of(vinculoVigente(MEMBERSHIP_B, null)));
		given(identityDirectory.identidadesDe(any()))
				.willReturn(Map.of(ACCOUNT_ID, new AccountIdentity(ACCOUNT_ID, "Ana Gomez", "a@b.test")));

		given(espacioDirectory.find(eq(ORG), eq(ESPACIO_GRANDE), any()))
				.willReturn(Optional.of(espacio(ESPACIO_GRANDE, SEDE, 20, true)));
		given(espacioDirectory.find(eq(ORG), eq(ESPACIO_CHICO), any()))
				.willReturn(Optional.of(espacio(ESPACIO_CHICO, SEDE, 6, true)));

		given(profesionales.save(any())).willAnswer(inv -> {
			guardadosProfesional.add(inv.getArgument(0));
			return inv.getArgument(0);
		});
		given(espacios.save(any())).willAnswer(inv -> {
			guardadosEspacio.add(inv.getArgument(0));
			return inv.getArgument(0);
		});
		sinHabilitaciones();
	}

	// =================================================================================
	// EL TEST DE LA ETAPA: lista vacia significa TODOS
	// =================================================================================

	@Test
	@DisplayName("una oferta sin habilitaciones NO esta restringida, y cualquier profesional con "
			+ "vinculo vigente puede prestarla")
	void lista_vacia_significa_todos_y_no_ninguno() {
		// Si esta regla se invierte, toda oferta recien creada queda inutilizable y el sintoma es
		// silencioso: la agenda no ofrece nada y nadie ve un error.
		HabilitacionesView vista = service.leer(actor(), ORG, SEDE, OFERTA_ID);

		assertThat(vista.restringidaPorProfesional()).isFalse();
		assertThat(vista.restringidaPorEspacio()).isFalse();
		assertThat(vista.profesionales()).isEmpty();

		ValidacionDeOfertaView validacion =
				service.validar(actor(), ORG, SEDE, OFERTA_ID, MEMBERSHIP_A, null);

		assertThat(validacion.puedePrestarse()).isTrue();
		assertThat(validacion.motivos()).isEmpty();
	}

	@Test
	@DisplayName("con una habilitacion activa la oferta pasa a estar restringida, y el que no esta "
			+ "en la lista deja de poder prestarla")
	void la_primera_habilitacion_restringe_la_oferta() {
		conProfesionalesHabilitados(habilitacionProfesional(MEMBERSHIP_A));

		HabilitacionesView vista = service.leer(actor(), ORG, SEDE, OFERTA_ID);
		assertThat(vista.restringidaPorProfesional()).isTrue();

		assertThat(service.validar(actor(), ORG, SEDE, OFERTA_ID, MEMBERSHIP_A, null)
				.puedePrestarse()).isTrue();

		ValidacionDeOfertaView ajeno =
				service.validar(actor(), ORG, SEDE, OFERTA_ID, MEMBERSHIP_B, null);
		assertThat(ajeno.puedePrestarse()).isFalse();
		assertThat(codigos(ajeno)).containsExactly(
				ValidacionDeOfertaView.MotivoDeRechazo.PROFESIONAL_NO_HABILITADO);
	}

	@Test
	@DisplayName("dar de baja la ULTIMA habilitacion vuelve a dejar la oferta sin restringir")
	void quitar_la_ultima_habilitacion_quita_la_restriccion() {
		// Es el caso peligroso que `restringida` existe para hacer visible: quien borra la ultima
		// creyendo que restringe, abre la oferta a todos.
		OfertaProfesionalHabilitado unica = habilitacionProfesional(MEMBERSHIP_A);
		unica.deactivate(Instant.now(), "ya no atiende esto");
		conProfesionalesHabilitados(unica);

		assertThat(service.leer(actor(), ORG, SEDE, OFERTA_ID).restringidaPorProfesional())
				.isFalse();
	}

	// =================================================================================
	// El diff del reemplazo
	// =================================================================================

	@Test
	@DisplayName("el reemplazo crea lo que entra, da de baja lo que sale y NO toca lo que sigue")
	void el_reemplazo_hace_el_diff_del_lado_del_servidor() {
		OfertaProfesionalHabilitado sigue = habilitacionProfesional(MEMBERSHIP_A);
		OfertaProfesionalHabilitado sale = habilitacionProfesional(MEMBERSHIP_B);
		conProfesionalesHabilitados(sigue, sale);
		// El que entra tiene que existir en este tenant: el servicio rechaza con 404 cualquier
		// membership que no pueda resolver, y eso ya lo cubre otro test.
		given(membershipDirectory.find(ORG, MEMBERSHIP_C))
				.willReturn(Optional.of(vinculoVigente(MEMBERSHIP_C, null)));

		service.reemplazarProfesionales(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(MEMBERSHIP_A, MEMBERSHIP_C), 0L);

		// El que sigue no se toco: conserva su fila y su estado.
		assertThat(sigue.isOperable()).isTrue();
		// El que sale quedo dado de baja CON motivo: la baja sin motivo dejaria la auditoria sin
		// poder responder por que seis meses despues.
		assertThat(sale.isOperable()).isFalse();
		assertThat(sale.getDeactivationReason()).isNotBlank();
		// Y el nuevo se creo.
		assertThat(guardadosProfesional)
				.filteredOn(fila -> fila.getMembershipId() == MEMBERSHIP_C)
				.hasSize(1);
	}

	@Test
	@DisplayName("reemplazar con una lista VACIA es la operacion legitima de quitar la restriccion")
	void reemplazar_con_lista_vacia_no_es_un_error() {
		conProfesionalesHabilitados(habilitacionProfesional(MEMBERSHIP_A));
		// Tras la baja el repositorio ya no devuelve ninguna activa.
		given(profesionales.findAllByOrganizationIdAndOfertaId(ORG, OFERTA_ID))
				.willReturn(List.of());

		HabilitacionesView resultado = service.reemplazarProfesionales(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(), 0L);

		assertThat(resultado.restringidaPorProfesional()).isFalse();
	}

	@Test
	@DisplayName("una version desactualizada no pisa nada: 409 antes de tocar una sola fila")
	void el_reemplazo_respeta_el_control_optimista() {
		// Sin esto, el segundo administrador en guardar borra en silencio lo que agrego el
		// primero, y con 200.
		assertThatThrownBy(() -> service.reemplazarProfesionales(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(MEMBERSHIP_A), 99L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(guardadosProfesional).isEmpty();
	}

	@Test
	@DisplayName("configurar carga la oferta por el camino que le fuerza el avance de version, "
			+ "que es lo unico que serializa a dos administradores")
	void el_reemplazo_carga_la_oferta_forzando_el_incremento() {
		// El test de arriba prueba que la COMPARACION funciona, no que la version se mueva. Y no
		// se movia: las habilitaciones viven en otras tablas, un reemplazo no toca ninguna columna
		// de `oferta`, y JPA no incrementa lo que nadie ensucio. Dos administradores leian los dos
		// la version 0, los dos pasaban el control y el segundo borraba lo del primero, con 200.
		//
		// Que la version efectivamente avance lo decide Hibernate al cerrar la transaccion, y eso
		// con mocks no se puede observar: lo verifica el test de integracion contra MySQL. Lo que
		// SI se puede fijar aca es la costura que lo hace posible, o sea que el camino de
		// escritura no vuelva a cargar por el metodo que no bloquea.
		service.reemplazarProfesionales(actor(), ORG, SEDE, OFERTA_ID, Set.of(MEMBERSHIP_A), 0L);

		then(ofertas).should().findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE);
		then(ofertas).should(never())
				.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE);
	}

	@Test
	@DisplayName("ofertaVersion: la lectura devuelve la vigente y cada reemplazo la que queda "
			+ "despues del commit, para que el cliente encadene sin releer")
	void la_vista_publica_la_version_que_sirve_para_el_proximo_reemplazo() {
		OfertaServicioConsultorio leida = ofertaCapacidad(8);
		ReflectionTestUtils.setField(leida, "version", 3L);
		given(ofertas.findByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(leida));
		given(ofertas.findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE))
				.willReturn(Optional.of(leida));

		assertThat(service.leer(actor(), ORG, SEDE, OFERTA_ID).ofertaVersion()).isEqualTo(3L);

		// Con mocks la entidad sigue en 3 al salir: el force-increment lo aplica Hibernate al
		// commitear. La vista tiene que anticiparlo, o el cliente manda 3 y choca contra 4.
		assertThat(service.reemplazarProfesionales(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(MEMBERSHIP_A), 3L).ofertaVersion())
				.isEqualTo(4L);
		assertThat(service.reemplazarEspacios(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(ESPACIO_CHICO), 3L).ofertaVersion())
				.isEqualTo(4L);
	}

	@Test
	@DisplayName("leer NO fuerza el avance de version: seria una escritura disfrazada de lectura")
	void la_lectura_no_mueve_la_version() {
		service.leer(actor(), ORG, SEDE, OFERTA_ID);

		then(ofertas).should(never()).findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE);
	}

	@Test
	@DisplayName("no se puede configurar una oferta dada de baja")
	void una_oferta_inactiva_no_se_configura() {
		OfertaServicioConsultorio inactiva = ofertaCapacidad(8);
		inactiva.deactivate(Instant.now(), "el centro dejo de prestarla");
		given(ofertas.findWithLockByIdAndOrganizationIdAndConsultorioId(OFERTA_ID, ORG, SEDE)).willReturn(Optional.of(inactiva));

		assertThatThrownBy(() -> service.reemplazarProfesionales(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(MEMBERSHIP_A), 0L))
				.isInstanceOf(OfertaInactivaException.class);
	}

	// =================================================================================
	// Aislamiento: 404 y nunca 403
	// =================================================================================

	@Test
	@DisplayName("una membership acotada a OTRA sede responde 404, no 403")
	void una_membership_de_otra_sede_es_indistinguible_de_una_inexistente() {
		// Un 403 confirmaria que ese id existe, y bastaria recorrer numeros para averiguar cuanta
		// gente tiene cada centro del SaaS.
		given(membershipDirectory.find(ORG, MEMBERSHIP_B))
				.willReturn(Optional.of(vinculoVigente(MEMBERSHIP_B, OTRA_SEDE)));

		assertThatThrownBy(() -> service.reemplazarProfesionales(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(MEMBERSHIP_B), 0L))
				.isInstanceOf(HabilitacionNoAccesibleException.class);
	}

	@Test
	@DisplayName("un espacio de OTRA sede responde 404: un espacio no se muda entre sedes")
	void un_espacio_de_otra_sede_no_se_puede_habilitar() {
		given(espacioDirectory.find(eq(ORG), eq(ESPACIO_CHICO), any()))
				.willReturn(Optional.of(espacio(ESPACIO_CHICO, OTRA_SEDE, 6, true)));

		assertThatThrownBy(() -> service.reemplazarEspacios(
				actor(), ORG, SEDE, OFERTA_ID, Set.of(ESPACIO_CHICO), 0L))
				.isInstanceOf(HabilitacionNoAccesibleException.class);
	}

	// =================================================================================
	// Capacidad efectiva
	// =================================================================================

	@Test
	@DisplayName("sin espacios habilitados la capacidad efectiva es la comercial, NO cero")
	void sin_espacios_la_efectiva_es_la_de_la_oferta() {
		// Devolver cero convertiria "sin restringir" en "no entra nadie", que es al reves.
		HabilitacionesView vista = service.leer(actor(), ORG, SEDE, OFERTA_ID);

		assertThat(vista.capacidadComercial()).isEqualTo(8);
		assertThat(vista.capacidadEfectiva()).isEqualTo(8);
		assertThat(vista.espacioQueLimita()).isNull();
	}

	@Test
	@DisplayName("la capacidad efectiva es el MINIMO, y la vista nombra el espacio que la limita")
	void la_efectiva_es_el_minimo_entre_la_oferta_y_los_espacios() {
		conEspaciosHabilitados(
				habilitacionEspacio(ESPACIO_GRANDE), habilitacionEspacio(ESPACIO_CHICO));

		HabilitacionesView vista = service.leer(actor(), ORG, SEDE, OFERTA_ID);

		assertThat(vista.capacidadEfectiva()).isEqualTo(6);
		// Un numero mas chico sin explicacion es un bug reportado.
		assertThat(vista.espacioQueLimita()).isEqualTo("Espacio " + ESPACIO_CHICO);
	}

	@Test
	@DisplayName("un espacio fuera de servicio NO acota la capacidad efectiva")
	void un_espacio_dado_de_baja_no_baja_la_capacidad() {
		// Acotar por la capacidad de un box que hoy no se puede usar daria un numero que no
		// describe ninguna realidad.
		given(espacioDirectory.find(eq(ORG), eq(ESPACIO_CHICO), any()))
				.willReturn(Optional.of(espacio(ESPACIO_CHICO, SEDE, 6, false)));
		conEspaciosHabilitados(
				habilitacionEspacio(ESPACIO_GRANDE), habilitacionEspacio(ESPACIO_CHICO));

		assertThat(service.leer(actor(), ORG, SEDE, OFERTA_ID).capacidadEfectiva()).isEqualTo(8);
	}

	@Test
	@DisplayName("la habilitacion de un espacio dado de baja se muestra, no se esconde")
	void la_habilitacion_que_apunta_a_un_espacio_caido_sigue_visible() {
		// Esconderla dejaria al administrador sin entender por que la capacidad efectiva cambio
		// sola.
		given(espacioDirectory.find(eq(ORG), eq(ESPACIO_CHICO), any()))
				.willReturn(Optional.of(espacio(ESPACIO_CHICO, SEDE, 6, false)));
		conEspaciosHabilitados(habilitacionEspacio(ESPACIO_CHICO));

		HabilitacionesView vista = service.leer(actor(), ORG, SEDE, OFERTA_ID);

		assertThat(vista.espacios()).hasSize(1);
		assertThat(vista.espacios().get(0).enServicio()).isFalse();
		assertThat(vista.espacios().get(0).estado()).isEqualTo("ACTIVO");
	}

	// =================================================================================
	// Validacion explicable
	// =================================================================================

	@Test
	@DisplayName("la validacion devuelve TODOS los motivos que fallan, no el primero")
	void la_validacion_acumula_los_motivos() {
		// Si al profesional le falta habilitacion Y el espacio esta fuera de servicio, arreglar
		// uno solo no alcanza, y decirlo de a uno obliga a dos vueltas.
		conProfesionalesHabilitados(habilitacionProfesional(MEMBERSHIP_A));
		conEspaciosHabilitados(habilitacionEspacio(ESPACIO_GRANDE));
		given(espacioDirectory.find(eq(ORG), eq(ESPACIO_CHICO), any()))
				.willReturn(Optional.of(espacio(ESPACIO_CHICO, SEDE, 6, false)));

		ValidacionDeOfertaView validacion =
				service.validar(actor(), ORG, SEDE, OFERTA_ID, MEMBERSHIP_B, ESPACIO_CHICO);

		assertThat(validacion.puedePrestarse()).isFalse();
		assertThat(codigos(validacion)).contains(
				ValidacionDeOfertaView.MotivoDeRechazo.PROFESIONAL_NO_HABILITADO,
				ValidacionDeOfertaView.MotivoDeRechazo.ESPACIO_FUERA_DE_SERVICIO,
				ValidacionDeOfertaView.MotivoDeRechazo.ESPACIO_NO_HABILITADO);
	}

	@Test
	@DisplayName("un profesional habilitado cuyo vinculo se corto responde vinculo-no-vigente")
	void el_vinculo_cortado_se_nombra_como_tal() {
		// La habilitacion sigue existiendo: el colaborador se desvinculo y la fila quedo colgando.
		// Decir "no habilitado" mandaria al administrador a arreglar la lista equivocada.
		conProfesionalesHabilitados(habilitacionProfesional(MEMBERSHIP_A));
		given(membershipDirectory.find(ORG, MEMBERSHIP_A))
				.willReturn(Optional.of(vinculoCortado(MEMBERSHIP_A)));

		ValidacionDeOfertaView validacion =
				service.validar(actor(), ORG, SEDE, OFERTA_ID, MEMBERSHIP_A, null);

		assertThat(codigos(validacion)).containsExactly(
				ValidacionDeOfertaView.MotivoDeRechazo.VINCULO_NO_VIGENTE);
	}

	@Test
	@DisplayName("capacidad insuficiente es un MOTIVO, no un rechazo aparte: la oferta se puede "
			+ "prestar con menos gente y quien decide es la agenda")
	void la_capacidad_insuficiente_viaja_como_motivo() {
		ValidacionDeOfertaView validacion =
				service.validar(actor(), ORG, SEDE, OFERTA_ID, null, ESPACIO_CHICO);

		assertThat(codigos(validacion)).containsExactly(
				ValidacionDeOfertaView.MotivoDeRechazo.CAPACIDAD_INSUFICIENTE);
		assertThat(validacion.motivos().get(0).detalle()).contains("6").contains("8");
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static List<String> codigos(ValidacionDeOfertaView validacion) {
		return validacion.motivos().stream()
				.map(ValidacionDeOfertaView.MotivoDeRechazo::codigo)
				.toList();
	}

	private void sinHabilitaciones() {
		given(profesionales.findAllByOrganizationIdAndOfertaId(ORG, OFERTA_ID))
				.willReturn(List.of());
		given(profesionales.findAllByOrganizationIdAndOfertaIdAndActive(ORG, OFERTA_ID, true))
				.willReturn(List.of());
		given(espacios.findAllByOrganizationIdAndOfertaId(ORG, OFERTA_ID)).willReturn(List.of());
		given(espacios.findAllByOrganizationIdAndOfertaIdAndActive(ORG, OFERTA_ID, true))
				.willReturn(List.of());
	}

	private void conProfesionalesHabilitados(OfertaProfesionalHabilitado... filas) {
		List<OfertaProfesionalHabilitado> todas = List.of(filas);
		given(profesionales.findAllByOrganizationIdAndOfertaId(ORG, OFERTA_ID)).willReturn(todas);
		given(profesionales.findAllByOrganizationIdAndOfertaIdAndActive(ORG, OFERTA_ID, true))
				.willReturn(todas.stream().filter(OfertaProfesionalHabilitado::isOperable).toList());
	}

	private void conEspaciosHabilitados(OfertaEspacioHabilitado... filas) {
		List<OfertaEspacioHabilitado> todas = List.of(filas);
		given(espacios.findAllByOrganizationIdAndOfertaId(ORG, OFERTA_ID)).willReturn(todas);
		given(espacios.findAllByOrganizationIdAndOfertaIdAndActive(ORG, OFERTA_ID, true))
				.willReturn(todas.stream().filter(OfertaEspacioHabilitado::isOperable).toList());
	}

	private static OfertaProfesionalHabilitado habilitacionProfesional(long membershipId) {
		OfertaProfesionalHabilitado fila = new OfertaProfesionalHabilitado(
				ORG, SEDE, OFERTA_ID, membershipId, Instant.now().minusSeconds(3600), null);
		ReflectionTestUtils.setField(fila, "id", membershipId);
		return fila;
	}

	private static OfertaEspacioHabilitado habilitacionEspacio(long espacioId) {
		OfertaEspacioHabilitado fila = new OfertaEspacioHabilitado(
				ORG, SEDE, OFERTA_ID, espacioId, Instant.now().minusSeconds(3600), null);
		ReflectionTestUtils.setField(fila, "id", espacioId);
		return fila;
	}

	private static OfertaServicioConsultorio ofertaCapacidad(int capacidad) {
		OfertaServicioConsultorio oferta = new OfertaServicioConsultorio(
				ORG, SEDE, 1L, "Kinesiologia", null, Modalidad.GRUPAL, 45, capacidad,
				new BigDecimal("18000.00"), "ARS", new EsquemaCobro("SESION_SUELTA"),
				false, false, true, true, true,
				LocalDate.now().minusDays(1), null);
		ReflectionTestUtils.setField(oferta, "id", OFERTA_ID);
		return oferta;
	}

	private static ConsultorioMembershipSnapshot vinculoVigente(long membershipId, Long sedeId) {
		return new ConsultorioMembershipSnapshot(
				membershipId, ACCOUNT_ID, ORG, sedeId, "PROFESIONAL", "ACTIVA",
				Instant.now().minusSeconds(7200), null, true, true);
	}

	private static ConsultorioMembershipSnapshot vinculoCortado(long membershipId) {
		return new ConsultorioMembershipSnapshot(
				membershipId, ACCOUNT_ID, ORG, null, "PROFESIONAL", "DESVINCULADA",
				Instant.now().minusSeconds(7200), Instant.now().minusSeconds(60), false, false);
	}

	private static EspacioSnapshot espacio(long id, long sedeId, int capacidad, boolean enServicio) {
		return new EspacioSnapshot(
				id, ORG, sedeId, "Espacio " + id, "SALA_GRUPAL", capacidad,
				Instant.now().minusSeconds(7200), null, enServicio, enServicio);
	}

	private static OperatingActor actor() {
		return new OperatingActor(ACCOUNT_ID, false, ORG, SEDE);
	}
}
