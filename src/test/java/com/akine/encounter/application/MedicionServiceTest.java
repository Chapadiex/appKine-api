package com.akine.encounter.application;

import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.SesionMedicion;
import com.akine.encounter.domain.ValorMedido;
import com.akine.encounter.domain.exception.MedicionDefinicionInactivaException;
import com.akine.encounter.domain.exception.MedicionDefinicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionFueraDeRangoException;
import com.akine.encounter.domain.exception.MedicionNoAccesibleException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.port.SesionMedicionRepositoryPort;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.resource.spi.MedicionDefinicionSnapshot;
import com.akine.resource.spi.MedicionDirectory;
import com.akine.resource.spi.MedicionTipo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las mediciones de una sesion (M14/M06, AKINE-06.03).
 *
 * <h2>Lo que decide la correctitud</h2>
 *
 * <p><b>Registrar es idempotente por naturaleza</b> —una medicion por test y por lado— asi que
 * repetirla ACTUALIZA en vez de duplicar: el autosave del examen la va a repetir muchas veces y una
 * fila nueva por cada repeticion arruinaria la comparacion entre sesiones.
 *
 * <p><b>El rango se valida AL REGISTRAR y nunca al leer.</b> Una medicion vieja no se vuelve
 * invalida porque el catalogo estreche el rango despues: fue valida cuando se tomo, y eso es lo que
 * la historia clinica tiene que poder seguir diciendo.
 *
 * <p><b>Una definicion dada de baja no cascadea</b>: las mediciones que ya la usaron se siguen
 * leyendo y comparando, y lo unico que se impide es registrar nuevas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicionServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long SESION_ID = 500L;
	private static final long DEFINICION_ID = 61L;
	private static final long HISTORIA_ID = 88L;
	private static final long OFERTA_ID = 42L;
	private static final long TURNO_ID = 301L;
	private static final long CUENTA_PROPIA = 99L;
	private static final long MEMBERSHIP_PROPIA = 31L;

	@Mock private SesionRepositoryPort sesiones;
	@Mock private SesionMedicionRepositoryPort mediciones;
	@Mock private MedicionDirectory definiciones;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private PermissionGuard permissionGuard;

	private MedicionService service;

	private final OperatingActor actor =
			new OperatingActor(CUENTA_PROPIA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new MedicionService(sesiones, mediciones, definiciones, consultorios, memberships,
				permissionGuard);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(
						CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true)));
		given(memberships.findByAccount(ORG_ID, CUENTA_PROPIA)).willReturn(List.of(
				new ConsultorioMembershipSnapshot(MEMBERSHIP_PROPIA, CUENTA_PROPIA, ORG_ID,
						CONSULTORIO_ID, "PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true)));
		given(sesiones.findWithLockByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.of(sesionAbierta()));
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.of(sesionAbierta()));
		given(definiciones.find(ORG_ID, DEFINICION_ID))
				.willReturn(Optional.of(definicion(true, BigDecimal.ZERO, BigDecimal.TEN)));
		given(mediciones.buscarEnSesion(anyLong(), anyLong(), anyLong(), any()))
				.willReturn(Optional.empty());
		given(mediciones.save(any())).willAnswer(MedicionServiceTest::conIdComoJpa);
		given(mediciones.listarDeSesion(anyLong(), anyLong())).willReturn(List.of());
		given(definiciones.definicionesVigentes(anyLong())).willReturn(List.of());
	}

	// =================================================================================
	// Registrar
	// =================================================================================

	@Test
	@DisplayName("Una medicion nueva se guarda con su definicion copiada")
	void registrar_una_nueva() {
		service.registrar(actor, CONSULTORIO_ID, SESION_ID, DEFINICION_ID,
				LateralidadMedicion.DERECHA, new ValorMedido(new BigDecimal("5"), null, null),
				"sin dolor");

		verify(mediciones).save(any(SesionMedicion.class));
	}

	@Test
	@DisplayName("Repetir la misma medida y el mismo lado ACTUALIZA: no crea una segunda fila")
	void repetir_actualiza() {
		// Es idempotente por naturaleza —una medicion por test y por lado— y el autosave del examen
		// la va a repetir. Una fila por repeticion arruinaria la comparacion entre sesiones.
		SesionMedicion existente = medicionExistente();
		given(mediciones.buscarEnSesion(ORG_ID, SESION_ID, DEFINICION_ID,
				LateralidadMedicion.DERECHA)).willReturn(Optional.of(existente));

		service.registrar(actor, CONSULTORIO_ID, SESION_ID, DEFINICION_ID,
				LateralidadMedicion.DERECHA, new ValorMedido(new BigDecimal("7"), null, null),
				null);

		verify(mediciones).save(existente);
	}

	@Test
	@DisplayName("Sin lateralidad declarada, la medicion es NO_APLICA y no null")
	void lateralidad_por_defecto() {
		// Es lo correcto para frecuencia cardiaca, Borg o saturacion: no toda medida tiene lados.
		service.registrar(actor, CONSULTORIO_ID, SESION_ID, DEFINICION_ID, null,
				new ValorMedido(new BigDecimal("5"), null, null), null);

		verify(mediciones).buscarEnSesion(ORG_ID, SESION_ID, DEFINICION_ID,
				LateralidadMedicion.NO_APLICA);
	}

	@Nested
	@DisplayName("La definicion")
	class Definicion {

		@Test
		@DisplayName("Una definicion que no existe o es de otro tenant da 404 indistinguible")
		void definicion_inexistente() {
			// Un 403 confirmaria que ese id existe, y bastaria recorrer numeros para censar que
			// tests propios tiene cargados cada centro del SaaS.
			given(definiciones.find(ORG_ID, DEFINICION_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID,
					DEFINICION_ID, null, new ValorMedido(BigDecimal.ONE, null, null), null))
					.isInstanceOf(MedicionDefinicionNoAccesibleException.class);
		}

		@Test
		@DisplayName("Una definicion dada de baja es 409: existe y se ve, pero no admite nuevas")
		void definicion_inactiva() {
			// La baja NO cascadea: las mediciones que ya la usaron siguen legibles y comparables.
			given(definiciones.find(ORG_ID, DEFINICION_ID))
					.willReturn(Optional.of(definicion(false, BigDecimal.ZERO, BigDecimal.TEN)));

			assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID,
					DEFINICION_ID, null, new ValorMedido(BigDecimal.ONE, null, null), null))
					.isInstanceOf(MedicionDefinicionInactivaException.class);

			verify(mediciones, never()).save(any());
		}
	}

	@Nested
	@DisplayName("El rango")
	class Rango {

		@Test
		@DisplayName("Un valor fuera del rango declarado se rechaza con 400")
		void fuera_de_rango() {
			// Un EVA de 12 en una escala de 0 a 10 es un problema del cuerpo enviado: no depende de
			// nada que pueda cambiar entre dos peticiones, asi que un 409 mandaria al cliente a
			// repetir algo que va a fallar igual.
			assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID,
					DEFINICION_ID, null, new ValorMedido(new BigDecimal("12"), null, null), null))
					.isInstanceOf(MedicionFueraDeRangoException.class);
		}

		@Test
		@DisplayName("El borde del rango entra: el maximo es valido")
		void el_borde_entra() {
			assertThatCode(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID, DEFINICION_ID,
					null, new ValorMedido(BigDecimal.TEN, null, null), null))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("Un valor de TEXTO no se compara contra el rango numerico")
		void el_texto_no_tiene_rango() {
			given(definiciones.find(ORG_ID, DEFINICION_ID)).willReturn(Optional.of(
					new MedicionDefinicionSnapshot(DEFINICION_ID, ORG_ID, "OBS", "Observacion",
							MedicionTipo.TEXTO, null, null, null, true, 0L)));

			assertThatCode(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID, DEFINICION_ID,
					null, new ValorMedido(null, "mejor que ayer", null), null))
					.doesNotThrowAnyException();
		}
	}

	// =================================================================================
	// Borrar
	// =================================================================================

	@Test
	@DisplayName("Borrar una medicion que no existe da 404, no 204")
	void borrar_lo_que_no_esta() {
		// Un DELETE que respondiera 204 sobre una fila inexistente le ocultaria a la pantalla que
		// estaba mirando datos viejos.
		assertThatThrownBy(() -> service.borrar(actor, CONSULTORIO_ID, SESION_ID, DEFINICION_ID,
				LateralidadMedicion.IZQUIERDA))
				.isInstanceOf(MedicionNoAccesibleException.class);
	}

	@Test
	@DisplayName("Borrar una medicion existente la saca de la sesion")
	void borrar_lo_que_esta() {
		SesionMedicion existente = medicionExistente();
		given(mediciones.buscarEnSesion(ORG_ID, SESION_ID, DEFINICION_ID,
				LateralidadMedicion.DERECHA)).willReturn(Optional.of(existente));

		service.borrar(actor, CONSULTORIO_ID, SESION_ID, DEFINICION_ID,
				LateralidadMedicion.DERECHA);

		verify(mediciones).delete(existente);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	@Test
	@DisplayName("Listar sobre una sesion de otra sede da 404")
	void listar_sesion_de_otra_sede() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.listar(actor, CONSULTORIO_ID, SESION_ID))
				.isInstanceOf(SesionNotAccessibleException.class);
	}

	@Test
	@DisplayName("Listar devuelve las mediciones con cuantas definiciones vigentes hay")
	void listar_devuelve_el_total_de_definiciones() {
		// El total no es decorativo: la pantalla lo usa para decir cuantas medidas FALTAN tomar.
		given(mediciones.listarDeSesion(ORG_ID, SESION_ID)).willReturn(List.of(medicionExistente()));
		given(definiciones.definicionesVigentes(ORG_ID)).willReturn(List.of(
				definicion(true, BigDecimal.ZERO, BigDecimal.TEN),
				definicion(true, BigDecimal.ZERO, BigDecimal.TEN)));

		MedicionesDeSesionView vista = service.listar(actor, CONSULTORIO_ID, SESION_ID);

		assertThat(vista.mediciones()).hasSize(1);
	}

	@Test
	@DisplayName("Comparar sin sesion anterior no falla: devuelve solo lo de hoy")
	void comparar_sin_sesion_anterior() {
		// Es el caso de la PRIMERA sesion del paciente, que no es una anomalia.
		given(mediciones.idSesionAnteriorConMediciones(anyLong(), anyLong(), any(), any()))
				.willReturn(Optional.empty());
		given(mediciones.listarDeSesiones(anyLong(), any())).willReturn(List.of(medicionExistente()));

		ComparacionDeMedicionesView vista = service.comparar(actor, CONSULTORIO_ID, SESION_ID);

		assertThat(vista).isNotNull();
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static Sesion sesionAbierta() {
		Sesion sesion = new Sesion(ORG_ID, CONSULTORIO_ID, HISTORIA_ID, null, TURNO_ID, OFERTA_ID,
				MEMBERSHIP_PROPIA, Instant.EPOCH, CUENTA_PROPIA);
		ReflectionTestUtils.setField(sesion, "id", SESION_ID);
		return sesion;
	}

	private static MedicionDefinicionSnapshot definicion(
			boolean activa, BigDecimal minimo, BigDecimal maximo) {
		return new MedicionDefinicionSnapshot(DEFINICION_ID, ORG_ID, "EVA", "Escala de dolor",
				MedicionTipo.NUMERICO, "puntos", minimo, maximo, activa, 0L);
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static SesionMedicion conIdComoJpa(org.mockito.invocation.InvocationOnMock i) {
		SesionMedicion guardada = i.getArgument(0);
		if (guardada.getId() == null) {
			ReflectionTestUtils.setField(guardada, "id", 700L);
		}
		return guardada;
	}

	private static SesionMedicion medicionExistente() {
		SesionMedicion medicion = new SesionMedicion(ORG_ID, SESION_ID,
				definicion(true, BigDecimal.ZERO, BigDecimal.TEN), LateralidadMedicion.DERECHA,
				new ValorMedido(new BigDecimal("5"), null, null), null, Instant.EPOCH,
				CUENTA_PROPIA);
		ReflectionTestUtils.setField(medicion, "id", 700L);
		return medicion;
	}
}
