package com.akine.person.application;

import com.akine.contracting.spi.ArancelDirectory;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ResolucionDeArancel;
import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.CoberturaPaciente;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.OrdenMedica;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.CoberturaNotAccessibleException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.OrdenMedicaRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.contracting.spi.ReferenciaDeCobertura;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La logica que cierra el hueco de 03.05: confrontar lo que el convenio exige con lo que hay.
 *
 * <p>Lo que estos casos fijan y no se puede perder:
 *
 * <ul>
 *   <li><b>RN-M17-006.</b> Sin convenio, sin arancel o con cobertura particular, la lista de
 *       requisitos viene VACIA y la prestacion es elegible. Pedir una orden ahi seria un bug.
 *   <li><b>Se lee el convenio VIVO.</b> Ninguna copia congelada de ninguna autorizacion participa
 *       de la decision.
 *   <li><b>Nada se consume.</b> Es una lectura, y los dobles lo hacen ejecutable: ningun
 *       repositorio de escritura participa.
 * </ul>
 */
@DisplayName("Elegibilidad administrativa (M17)")
class ElegibilidadAdministrativaServiceTest {

	private static final long ORG = 7L;
	private static final long SEDE = 20L;
	private static final long PERSONA = 1204L;
	private static final long COBERTURA = 412L;
	private static final long PRACTICA = 33L;
	private static final LocalDate HOY = LocalDate.of(2027, 6, 15);
	private static final OperatingActor ACTOR = new OperatingActor(1L, false, ORG, SEDE);

	private CoberturaPacienteRepositoryPort coberturas;
	private OrdenMedicaRepositoryPort ordenes;
	private AutorizacionRepositoryPort autorizaciones;
	private PersonaRepositoryPort personas;
	private ArancelDirectory aranceles;
	private ElegibilidadAdministrativaService servicio;

	@BeforeEach
	void setUp() {
		coberturas = mock(CoberturaPacienteRepositoryPort.class);
		ordenes = mock(OrdenMedicaRepositoryPort.class);
		autorizaciones = mock(AutorizacionRepositoryPort.class);
		personas = mock(PersonaRepositoryPort.class);
		aranceles = mock(ArancelDirectory.class);
		servicio = new ElegibilidadAdministrativaService(
				coberturas, ordenes, autorizaciones, personas, aranceles);

		given(personas.findByIdAndOrganizationId(anyLong(), anyLong()))
				.willReturn(Optional.of(persona()));
	}

	// =================================================================================
	// RN-M17-006: sin convenio no se pide nada
	// =================================================================================

	@Test
	@DisplayName("una cobertura PARTICULAR es elegible sin requisitos y NO consulta el catalogo")
	void particular_no_pide_nada() {
		darCobertura(coberturaParticular());

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.elegible()).isTrue();
		assertThat(veredicto.requisitos()).isEmpty();
		assertThat(veredicto.motivo()).isEqualTo(ElegibilidadAdministrativa.PARTICULAR);
		// No hay financiador: consultar el catalogo seria una llamada que nunca puede aportar nada.
		verifyNoInteractions(aranceles);
	}

	@Test
	@DisplayName("sin convenio vigente la lista viene vacia y el motivo lo dice, con el mismo nombre que publica M16")
	void sin_convenio_no_pide_nada() {
		darCobertura(coberturaFinanciada("AF-1", null));
		given(aranceles.resolver(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_CONVENIO_VIGENTE));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		// El desenlace MAS frecuente: el paciente se atiende como particular. Pedirle orden y
		// autorizacion aca seria exactamente lo que RN-M17-006 prohibe.
		assertThat(veredicto.elegible()).isTrue();
		assertThat(veredicto.requisitos()).isEmpty();
		assertThat(veredicto.motivo()).isEqualTo("SIN_CONVENIO_VIGENTE");
		assertThat(veredicto.convenioId()).isNull();
	}

	@Test
	@DisplayName("con convenio pero sin arancel de esa practica tampoco se pide nada")
	void sin_arancel_no_pide_nada() {
		darCobertura(coberturaFinanciada("AF-1", null));
		given(aranceles.resolver(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_ARANCEL_VIGENTE));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		// Es un hueco de configuracion del centro, no un motivo para negarle la atencion al
		// paciente. Los dos motivos son distintos y hacen falta los dos: este manda a cargar el
		// arancel, el otro manda a cobrar como particular.
		assertThat(veredicto.elegible()).isTrue();
		assertThat(veredicto.motivo()).isEqualTo("SIN_ARANCEL_VIGENTE");
	}

	@Test
	@DisplayName("un convenio que no exige nada devuelve la lista vacia: DP-08, ningun requisito por defecto")
	void convenio_sin_exigencias() {
		darCobertura(coberturaFinanciada("AF-1", null));
		darConvenio(false, false, false, null);

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.elegible()).isTrue();
		assertThat(veredicto.requisitos()).isEmpty();
		assertThat(veredicto.motivo()).isNull();
		assertThat(veredicto.convenioId()).isEqualTo(12L);
	}

	// =================================================================================
	// Los tres requisitos
	// =================================================================================

	@Test
	@DisplayName("el convenio exige orden y el paciente no tiene ninguna vigente: no elegible, con detalle")
	void falta_la_orden() {
		darCobertura(coberturaFinanciada("AF-1", null));
		darConvenio(true, false, false, null);
		given(ordenes.activasDe(ORG, PERSONA)).willReturn(List.of());

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		// Un requisito faltante NO es un error: 200 con el detalle de que conseguir.
		assertThat(veredicto.elegible()).isFalse();
		assertThat(veredicto.requisitos()).singleElement().satisfies(requisito -> {
			assertThat(requisito.tipo()).isEqualTo(TipoRequisito.ORDEN);
			assertThat(requisito.cumplido()).isFalse();
			assertThat(requisito.referenciaId()).isNull();
		});
	}

	@Test
	@DisplayName("una orden SIN cobertura declarada satisface el requisito: la firma un medico, no un financiador")
	void la_orden_sin_cobertura_sirve_para_cualquiera() {
		darCobertura(coberturaFinanciada("AF-1", null));
		darConvenio(true, false, false, null);
		OrdenMedica orden = orden(null);
		given(ordenes.activasDe(ORG, PERSONA)).willReturn(List.of(orden));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.elegible()).isTrue();
		assertThat(veredicto.requisitos()).singleElement().satisfies(requisito -> {
			assertThat(requisito.cumplido()).isTrue();
			assertThat(requisito.referenciaId()).isEqualTo(51L);
		});
	}

	@Test
	@DisplayName("una orden atada a OTRA cobertura no satisface el requisito de esta")
	void la_orden_de_otra_cobertura_no_sirve() {
		darCobertura(coberturaFinanciada("AF-1", null));
		darConvenio(true, false, false, null);
		given(ordenes.activasDe(ORG, PERSONA)).willReturn(List.of(orden(999L)));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.elegible()).isFalse();
	}

	@Test
	@DisplayName("el convenio exige autorizacion y hay una aprobada con saldo: elegible, y el saldo viaja")
	void autorizacion_cumplida() {
		darCobertura(coberturaFinanciada("AF-1", null));
		darConvenio(false, true, false, 20);
		given(autorizaciones.aprobadasDe(ORG, COBERTURA, PRACTICA))
				.willReturn(List.of(autorizacion(77L, 10, LocalDate.of(2027, 12, 31))));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.elegible()).isTrue();
		assertThat(veredicto.requisitos()).singleElement().satisfies(requisito -> {
			assertThat(requisito.tipo()).isEqualTo(TipoRequisito.AUTORIZACION);
			assertThat(requisito.referenciaId()).isEqualTo(77L);
			// El saldo INICIAL: nadie mueve el consumo todavia (RN-M17-001).
			assertThat(requisito.saldo()).isEqualTo(10);
		});
		// El tope mensual viaja INFORMATIVO y sin veredicto: verificarlo exige contar sesiones ya
		// atendidas, o sea el consumo que esta etapa no cablea.
		assertThat(veredicto.limiteSesionesMensual()).isEqualTo(20);
	}

	@Test
	@DisplayName("entre varias autorizaciones aprobadas se elige la que vence antes: es la que hay que gastar primero")
	void desempate_por_vencimiento_mas_proximo() {
		darCobertura(coberturaFinanciada("AF-1", null));
		darConvenio(false, true, false, null);
		given(autorizaciones.aprobadasDe(ORG, COBERTURA, PRACTICA)).willReturn(List.of(
				autorizacion(90L, 5, LocalDate.of(2027, 12, 31)),
				autorizacion(88L, 5, LocalDate.of(2027, 7, 31))));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.requisitos()).singleElement()
				.extracting(RequisitoAdministrativo::referenciaId)
				.isEqualTo(88L);
	}

	@Test
	@DisplayName("una autorizacion agotada no habilita aunque este aprobada y vigente")
	void autorizacion_agotada_no_habilita() {
		darCobertura(coberturaFinanciada("AF-1", null));
		darConvenio(false, true, false, null);
		Autorizacion agotada = autorizacion(77L, 3, LocalDate.of(2027, 12, 31));
		ReflectionTestUtils.setField(agotada, "cantidadConsumida", 3);
		given(autorizaciones.aprobadasDe(ORG, COBERTURA, PRACTICA)).willReturn(List.of(agotada));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.elegible()).isFalse();
	}

	@Test
	@DisplayName("el convenio exige credencial y la cobertura no tiene numero de afiliado: no elegible")
	// El plan de M15 NO exigia credencial —si la exigiera, 03.04 ya habria obligado el numero de
	// afiliado en el alta— pero el CONVENIO de esta sede si. Es el caso que hace falta cubrir: las
	// dos exigencias son de dos entidades distintas y no siempre coinciden.
	void falta_la_credencial() {
		darCobertura(coberturaFinanciada(null, null));
		darConvenio(false, false, true, null);

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.elegible()).isFalse();
		assertThat(veredicto.requisitos()).singleElement()
				.extracting(RequisitoAdministrativo::tipo)
				.isEqualTo(TipoRequisito.CREDENCIAL);
	}

	@Test
	@DisplayName("una credencial vencida no invalida la cobertura, pero deja el requisito sin cumplir")
	void credencial_vencida() {
		darCobertura(coberturaFinanciada("AF-1", LocalDate.of(2027, 1, 31)));
		darConvenio(false, false, true, null);

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		// 03.04 fijo que vencerla NO da de baja la cobertura. Esto no lo contradice: lo que dice
		// es que ese dia, para ese convenio, el requisito no esta cumplido.
		assertThat(veredicto.elegible()).isFalse();
		assertThat(veredicto.requisitos()).singleElement()
				.extracting(RequisitoAdministrativo::cumplido)
				.isEqualTo(false);
	}

	@Test
	@DisplayName("los tres requisitos a la vez: elegible solo si los tres estan")
	void los_tres_requisitos() {
		darCobertura(coberturaFinanciada("AF-1", LocalDate.of(2028, 1, 1)));
		darConvenio(true, true, true, null);
		given(ordenes.activasDe(ORG, PERSONA)).willReturn(List.of(orden(COBERTURA)));
		given(autorizaciones.aprobadasDe(ORG, COBERTURA, PRACTICA))
				.willReturn(List.of(autorizacion(77L, 4, null)));

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY);

		assertThat(veredicto.requisitos()).hasSize(3);
		assertThat(veredicto.elegible()).isTrue();
	}

	// =================================================================================
	// Contexto y pertenencia
	// =================================================================================

	@Test
	@DisplayName("sin consultorio en el contexto se rechaza: el convenio que fija los requisitos es de la SEDE")
	void sin_sede_no_hay_convenio_que_resolver() {
		OperatingActor sinSede = new OperatingActor(1L, false, ORG, null);

		assertThatThrownBy(() -> servicio.consultar(sinSede, PERSONA, COBERTURA, PRACTICA, HOY))
				.isInstanceOf(AccessDeniedException.class);

		// Responder "elegible sin requisitos" seria PEOR que rechazar: afirmaria que no hace falta
		// nada, cuando lo que pasa es que no se pudo averiguar.
		verifyNoInteractions(aranceles);
	}

	@Test
	@DisplayName("sin contexto de organizacion se rechaza")
	void sin_contexto() {
		OperatingActor sinContexto = new OperatingActor(1L, false, null, null);

		assertThatThrownBy(() -> servicio.consultar(sinContexto, PERSONA, COBERTURA, PRACTICA, HOY))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("una persona de otra organizacion responde 404, nunca 403")
	void persona_ajena() {
		given(personas.findByIdAndOrganizationId(anyLong(), anyLong()))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY))
				.isInstanceOf(PersonaNotAccessibleException.class);
	}

	@Test
	@DisplayName("una cobertura de otro paciente responde 404")
	void cobertura_ajena() {
		given(coberturas.findByIdAndOrganizationIdAndPersonaId(anyLong(), anyLong(), anyLong()))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, HOY))
				.isInstanceOf(CoberturaNotAccessibleException.class);
	}

	@Test
	@DisplayName("evaluar (spi, E-4): misma regla sin actor; persona o cobertura ajenas dan vacio")
	void evaluar_sin_actor() {
		darCobertura(coberturaParticular());
		assertThat(servicio.evaluar(ORG, SEDE, PERSONA, COBERTURA, PRACTICA, HOY))
				.hasValueSatisfying(veredicto -> {
					assertThat(veredicto.elegible()).isTrue();
					assertThat(veredicto.motivo()).isEqualTo(ElegibilidadAdministrativa.PARTICULAR);
				});
		assertThat(servicio.evaluar(ORG, SEDE, PERSONA, COBERTURA + 1, PRACTICA, null)).isEmpty();

		given(personas.findByIdAndOrganizationId(anyLong(), anyLong())).willReturn(Optional.empty());
		assertThat(servicio.evaluar(ORG, SEDE, PERSONA, COBERTURA, PRACTICA, HOY)).isEmpty();
	}

	@Test
	@DisplayName("sin fecha se evalua contra hoy")
	void fecha_por_defecto() {
		darCobertura(coberturaParticular());

		ElegibilidadAdministrativa veredicto =
				servicio.consultar(ACTOR, PERSONA, COBERTURA, PRACTICA, null);

		assertThat(veredicto.fecha()).isEqualTo(LocalDate.now());
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private void darCobertura(CoberturaPaciente cobertura) {
		given(coberturas.findByIdAndOrganizationIdAndPersonaId(COBERTURA, ORG, PERSONA))
				.willReturn(Optional.of(cobertura));
	}

	/** El convenio VIVO que devuelve el catalogo, con las tres exigencias que M16 declara. */
	private void darConvenio(
			boolean orden, boolean autorizacion, boolean credencial, Integer limiteMensual) {

		given(aranceles.resolver(ORG, SEDE, 88L, 99L, PRACTICA, HOY))
				.willReturn(ResolucionDeArancel.resuelta(new ArancelVigente(
						12L, "CONV-1", "Convenio Sintetico", "PRESTACION", 88L, 99L, PRACTICA, 5L,
						new BigDecimal("12000.00"), new BigDecimal("10000.00"),
						new BigDecimal("2000.00"), "ARS",
						orden, autorizacion, credencial, limiteMensual,
						LocalDate.of(2027, 1, 1), null, LocalDate.of(2027, 1, 1), null, HOY)));
	}

	private static Persona persona() {
		return new Persona(ORG, TipoDocumento.DNI, "30111222", "Sintetica", "Paciente", null,
				null, null, null);
	}

	private static CoberturaPaciente coberturaParticular() {
		CoberturaPaciente cobertura = CoberturaPaciente.particular(
				ORG, PERSONA, LocalDate.of(2027, 1, 1), null, true, null);
		ReflectionTestUtils.setField(cobertura, "id", COBERTURA);
		return cobertura;
	}

	private static CoberturaPaciente coberturaFinanciada(
			String numeroAfiliado, LocalDate credencialHasta) {

		CoberturaPaciente cobertura = CoberturaPaciente.financiada(
				ORG, PERSONA,
				new ReferenciaDeCobertura(
						88L, "OS-1", "Financiador Sintetico", "PREPAGA",
						99L, "P-1", "Plan Sintetico", true, false,
						new BigDecimal("500.00"), "ARS", LocalDate.of(2027, 1, 1), Instant.now()),
				numeroAfiliado, credencialHasta, LocalDate.of(2027, 1, 1), null, true, null);
		ReflectionTestUtils.setField(cobertura, "id", COBERTURA);
		return cobertura;
	}

	private static OrdenMedica orden(Long coberturaId) {
		OrdenMedica orden = new OrdenMedica(
				ORG, PERSONA, SEDE, coberturaId, "OM-1", "Dra. Sintetica", "MP 1",
				LocalDate.of(2027, 1, 1), "Kinesiologia", 10,
				LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), null);
		ReflectionTestUtils.setField(orden, "id", 51L);
		return orden;
	}

	private static Autorizacion autorizacion(long id, Integer cantidad, LocalDate hasta) {
		Autorizacion autorizacion = new Autorizacion(
				ORG, PERSONA, SEDE, COBERTURA, null, PRACTICA, "AUT-" + id,
				EstadoAutorizacion.APROBADA, cantidad, LocalDate.of(2027, 1, 1), hasta, null, null);
		ReflectionTestUtils.setField(autorizacion, "id", id);
		return autorizacion;
	}
}
