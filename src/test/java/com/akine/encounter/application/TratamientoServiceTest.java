package com.akine.encounter.application;

import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ParametroAplicado;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.TipoDatoParametro;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.domain.TratamientoEnmendado;
import com.akine.encounter.domain.TratamientoParametro;
import com.akine.encounter.domain.exception.EnmiendaCambiaPracticasException;
import com.akine.encounter.domain.TratamientoRealizado;
import com.akine.encounter.domain.exception.EspacioNoAccesibleException;
import com.akine.encounter.domain.exception.EspacioNoOperableException;
import com.akine.encounter.domain.exception.ParametroInvalidoException;
import com.akine.encounter.domain.exception.PracticaNoUtilizableException;
import com.akine.encounter.domain.exception.ProfesionalNoAsignableException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TratamientoNoAccesibleException;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoParametroRepositoryPort;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoRepositoryPort;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Los tratamientos realizados de una sesion (M14, AKINE-06.04).
 *
 * <h2>Que decide la correctitud aca</h2>
 *
 * <p>Tres cosas, y ninguna es el CRUD: <b>que no se escriba en la atencion ajena</b> —que es 409 y
 * no 403, porque la propiedad no es un permiso—, <b>que el orden no se reutilice</b> aunque haya
 * bajas en el medio, y <b>que un parametro sin tipo se rechace en vez de normalizarse
 * adivinando</b>, que es el caso borde con el que la etapa se escribio: adivinar es como se cuela
 * un valor de dosificacion interpretado al reves.
 *
 * <p>Lo que estos tests NO pueden decir: que el UPDATE de version efectivamente
 * serialice a dos escritores concurrentes. Eso lo contesta la base, no un mock, y vive en los ITs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TratamientoServiceTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;
	private static final long SESION_ID = 500L;
	private static final long TRATAMIENTO_ID = 900L;
	private static final long PRACTICA_ID = 77L;
	private static final long ESPACIO_ID = 12L;
	private static final long HISTORIA_ID = 88L;
	private static final long OFERTA_ID = 42L;
	private static final long TURNO_ID = 301L;

	private static final long CUENTA_PROPIA = 99L;
	private static final long MEMBERSHIP_PROPIA = 31L;
	private static final long MEMBERSHIP_AJENA = 32L;

	@Mock private SesionRepositoryPort sesiones;
	@Mock private TratamientoRepositoryPort tratamientos;
	@Mock private TratamientoParametroRepositoryPort parametros;
	@Mock private ConsultorioDirectory consultorios;
	@Mock private ConsultorioMembershipDirectory memberships;
	@Mock private PermissionGuard permissionGuard;
	@Mock private CatalogoDirectory catalogo;
	@Mock private EspacioDirectory espacios;
	@Mock private AuditTrail auditTrail;

	private TratamientoService service;

	private final OperatingActor actor =
			new OperatingActor(CUENTA_PROPIA, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new TratamientoService(sesiones, tratamientos, parametros, consultorios,
				memberships, permissionGuard, catalogo, espacios, auditTrail);

		given(consultorios.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(
						CONSULTORIO_ID, ORG_ID, "Sede", "America/Argentina/Cordoba", true)));
		given(memberships.findByAccount(ORG_ID, CUENTA_PROPIA)).willReturn(List.of(
				new ConsultorioMembershipSnapshot(MEMBERSHIP_PROPIA, CUENTA_PROPIA, ORG_ID,
						CONSULTORIO_ID, "PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true, true)));
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.of(sesionAbierta()));
		given(sesiones.avanzarVersion(eq(ORG_ID), eq(CONSULTORIO_ID), eq(SESION_ID), anyLong()))
				.willReturn(1);
		given(catalogo.findPractica(anyLong(), anyLong(), any()))
				.willReturn(Optional.of(practica(true)));
		given(tratamientos.ultimoOrden(ORG_ID, SESION_ID)).willReturn(0);
		given(tratamientos.save(any())).willAnswer(TratamientoServiceTest::conIdComoJpa);
		given(parametros.save(any())).willAnswer(i -> i.getArgument(0));
	}

	// =================================================================================
	// Registrar
	// =================================================================================

	@Test
	@DisplayName("El orden sale del ultimo mas uno, no del conteo: una baja no devuelve su numero")
	void el_orden_no_se_reutiliza() {
		// `ultimoOrden` cuenta INCLUIDAS las bajas justamente para esto. Si saliera de contar las
		// vigentes, dar de baja la tercera haria que la siguiente volviera a ser la tercera y dos
		// intervenciones distintas compartirian orden en el historial de la misma sesion.
		given(tratamientos.ultimoOrden(ORG_ID, SESION_ID)).willReturn(5);

		service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L);

		ArgumentCaptor<TratamientoRealizado> guardado =
				ArgumentCaptor.forClass(TratamientoRealizado.class);
		verify(tratamientos).save(guardado.capture());
		assertThat(guardado.getValue().getOrden()).isEqualTo(6);
	}

	@Test
	@DisplayName("Registrar deja el tratamiento, sus parametros y el evento de auditoria")
	void registrar_escribe_todo_lo_que_tiene_que_escribir() {
		TratamientoView vista = service.registrar(actor, CONSULTORIO_ID, SESION_ID,
				aplicado(new ParametroAplicado("intensidad", TipoDatoParametro.NUMERICO,
						new BigDecimal("2.5"), null, null, "mA")),
				0L);

		verify(tratamientos).save(any());
		verify(parametros).borrarDe(ORG_ID, TRATAMIENTO_ID);
		verify(parametros).save(any(TratamientoParametro.class));
		verify(auditTrail).record(any(AuditEntry.class));
		assertThat(vista.sesionVersion())
				.as("la vista anuncia la version que la sesion tiene tras el UPDATE de version: la "
						+ "entidad leida conserva la vieja y la respuesta suma uno")
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("La version de la sesion avanza con un UPDATE contra la version leida")
	void la_version_avanza_con_update_condicionado() {
		// Lo unico que la mueve en la base: ninguna columna de la sesion cambia. La prueba de que
		// la base queda en leida+1 vive en TratamientoRealizadoIT (escenario 41).
		service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L);

		verify(sesiones).avanzarVersion(ORG_ID, CONSULTORIO_ID, SESION_ID, 0L);
	}

	@Test
	@DisplayName("Si el UPDATE no afecta ninguna fila, alguien se adelanto: 409 sin escribir")
	void si_el_update_de_version_no_afecta_filas_es_conflicto() {
		given(sesiones.avanzarVersion(ORG_ID, CONSULTORIO_ID, SESION_ID, 0L)).willReturn(0);

		assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(tratamientos, never()).save(any());
	}

	@Test
	@DisplayName("Escribir en la atencion ajena es 409, no 403")
	void la_sesion_ajena_no_se_edita() {
		// La propiedad NO es un permiso: quien opera tiene `sesion:register` perfectamente. Un 403
		// mandaria a la pantalla a decir "no tenes permiso", que es falso, y a pedir un permiso que
		// ya tiene.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.of(sesionDe(MEMBERSHIP_AJENA)));

		assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L))
				.isInstanceOf(SesionAjenaException.class);

		verify(tratamientos, never()).save(any());
	}

	@Test
	@DisplayName("Una version vieja no escribe: 409 antes de tocar nada")
	void la_version_desactualizada_no_escribe() {
		assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 7L))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(tratamientos, never()).save(any());
		verify(parametros, never()).borrarDe(anyLong(), anyLong());
	}

	@Test
	@DisplayName("Una sesion que no existe o no esta viva da 404, no 409")
	void sesion_inexistente() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L))
				.isInstanceOf(SesionNotAccessibleException.class);
	}

	@Nested
	@DisplayName("La practica")
	class Practica {

		@Test
		@DisplayName("Inexistente y de otro tenant son el MISMO desenlace: 404 indistinguible")
		void practica_inexistente() {
			// Distinguirlas permitiria censar por ids el catalogo propio de otro centro.
			given(catalogo.findPractica(anyLong(), anyLong(), any())).willReturn(Optional.empty());

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L))
					.isInstanceOf(PracticaNoUtilizableException.class);
		}

		@Test
		@DisplayName("Dada de baja es 409 y no 404: existe, se ve, y corresponde elegir otra")
		void practica_no_vigente() {
			given(catalogo.findPractica(anyLong(), anyLong(), any()))
					.willReturn(Optional.of(practica(false)));

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L))
					.isInstanceOf(PracticaNoUtilizableException.class);
		}
	}

	@Nested
	@DisplayName("El espacio")
	class Espacio {

		@Test
		@DisplayName("Sin espacio declarado no se consulta el directorio")
		void sin_espacio_no_se_pregunta() {
			service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L);

			verify(espacios, never()).find(anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("Un espacio de OTRA sede da 404, igual que uno inexistente")
		void espacio_de_otra_sede() {
			given(espacios.find(anyLong(), anyLong(), any())).willReturn(Optional.of(
					espacio(CONSULTORIO_ID + 1, true)));

			assertThatThrownBy(() -> service.registrar(
					actor, CONSULTORIO_ID, SESION_ID, conEspacio(), 0L))
					.isInstanceOf(EspacioNoAccesibleException.class);
		}

		@Test
		@DisplayName("Un espacio fuera de servicio es 409: existe y es de la sede, pero no se usa")
		void espacio_fuera_de_servicio() {
			given(espacios.find(anyLong(), anyLong(), any())).willReturn(Optional.of(
					espacio(CONSULTORIO_ID, false)));

			assertThatThrownBy(() -> service.registrar(
					actor, CONSULTORIO_ID, SESION_ID, conEspacio(), 0L))
					.isInstanceOf(EspacioNoOperableException.class);
		}

		@Test
		@DisplayName("El espacio valido queda copiado en la fila")
		void espacio_valido_se_copia() {
			given(espacios.find(anyLong(), anyLong(), any())).willReturn(Optional.of(
					espacio(CONSULTORIO_ID, true)));

			service.registrar(actor, CONSULTORIO_ID, SESION_ID, conEspacio(), 0L);

			ArgumentCaptor<TratamientoRealizado> guardado =
					ArgumentCaptor.forClass(TratamientoRealizado.class);
			verify(tratamientos).save(guardado.capture());
			assertThat(guardado.getValue().getEspacioId()).isEqualTo(ESPACIO_ID);
		}
	}

	@Nested
	@DisplayName("El co-atendiente")
	class Profesional {

		@Test
		@DisplayName("Sin declarar, la intervencion es de quien atiende: no se consulta membership")
		void sin_declarar_es_el_de_la_sesion() {
			service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(), 0L);

			verify(memberships, never()).find(anyLong(), anyLong());
		}

		@Test
		@DisplayName("Un co-atendiente sin vinculo vigente en la sede es 409, no 403")
		void co_atendiente_sin_vinculo() {
			// Quien opera SI tiene permiso; lo que no sirve es el dato que declaro.
			given(memberships.find(ORG_ID, MEMBERSHIP_AJENA)).willReturn(Optional.empty());

			assertThatThrownBy(() -> service.registrar(
					actor, CONSULTORIO_ID, SESION_ID, conProfesional(MEMBERSHIP_AJENA), 0L))
					.isInstanceOf(ProfesionalNoAsignableException.class);
		}

		@Test
		@DisplayName("Un co-atendiente vigente en la sede entra y queda en la fila")
		void co_atendiente_vigente() {
			given(memberships.find(ORG_ID, MEMBERSHIP_AJENA)).willReturn(Optional.of(
					new ConsultorioMembershipSnapshot(MEMBERSHIP_AJENA, 100L, ORG_ID,
							CONSULTORIO_ID, "PROFESIONAL", "ACTIVA", Instant.EPOCH, null, true,
							true)));

			service.registrar(actor, CONSULTORIO_ID, SESION_ID, conProfesional(MEMBERSHIP_AJENA),
					0L);

			ArgumentCaptor<TratamientoRealizado> guardado =
					ArgumentCaptor.forClass(TratamientoRealizado.class);
			verify(tratamientos).save(guardado.capture());
			assertThat(guardado.getValue().getProfesionalMembershipId())
					.isEqualTo(MEMBERSHIP_AJENA);
		}
	}

	@Nested
	@DisplayName("Los parametros")
	class Parametros {

		@Test
		@DisplayName("Un parametro SIN TIPO se rechaza: no se normaliza adivinando")
		void parametro_sin_tipo() {
			// El caso borde de la etapa. Adivinar el tipo es como se cuela un valor de dosificacion
			// clinica interpretado al reves.
			TratamientoAplicado sinTipo = aplicado(
					new ParametroAplicado("intensidad", null, new BigDecimal("2.5"), null, null,
							"mA"));

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, SESION_ID, sinTipo, 0L))
					.isInstanceOf(ParametroInvalidoException.class);

			verify(tratamientos, never()).save(any());
		}

		@Test
		@DisplayName("Dos parametros con la misma clave no entran")
		void parametro_repetido() {
			ParametroAplicado uno = new ParametroAplicado("intensidad",
					TipoDatoParametro.NUMERICO, BigDecimal.ONE, null, null, "mA");
			ParametroAplicado otro = new ParametroAplicado("intensidad",
					TipoDatoParametro.NUMERICO, BigDecimal.TEN, null, null, "mA");

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, SESION_ID, aplicado(uno, otro), 0L))
					.isInstanceOf(ParametroInvalidoException.class);
		}

		@Test
		@DisplayName("Un parametro con dos valores no entra: lleva exactamente uno")
		void parametro_con_dos_valores() {
			TratamientoAplicado ambiguo = aplicado(new ParametroAplicado("intensidad",
					TipoDatoParametro.NUMERICO, BigDecimal.ONE, "fuerte", null, "mA"));

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, SESION_ID, ambiguo, 0L))
					.isInstanceOf(ParametroInvalidoException.class);
		}

		@Test
		@DisplayName("Los parametros se BORRAN y se reescriben: no se acumulan entre ediciones")
		void los_parametros_se_reemplazan() {
			given(tratamientos.findEnSesion(ORG_ID, SESION_ID, TRATAMIENTO_ID))
					.willReturn(Optional.of(tratamientoVigente()));

			service.reemplazar(actor, CONSULTORIO_ID, SESION_ID, TRATAMIENTO_ID, aplicado(), 0L);

			verify(parametros).borrarDe(ORG_ID, TRATAMIENTO_ID);
		}
	}

	@Nested
	@DisplayName("La lateralidad")
	class LateralidadDeclarada {

		@Test
		@DisplayName("Sin zona tratada no significa nada: derecha de que")
		void lateralidad_sin_zona() {
			TratamientoAplicado sinZona = new TratamientoAplicado(PRACTICA_ID, "US", null,
					Lateralidad.DERECHA, 15, null, null, null, List.of());

			assertThatThrownBy(() ->
					service.registrar(actor, CONSULTORIO_ID, SESION_ID, sinZona, 0L))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Con zona entra sin chistar")
		void lateralidad_con_zona() {
			TratamientoAplicado conZona = new TratamientoAplicado(PRACTICA_ID, "US", "rodilla",
					Lateralidad.DERECHA, 15, null, null, null, List.of());

			assertThatCode(() ->
					service.registrar(actor, CONSULTORIO_ID, SESION_ID, conZona, 0L))
					.doesNotThrowAnyException();
		}
	}

	// =================================================================================
	// Baja
	// =================================================================================

	@Test
	@DisplayName("La baja SIN motivo se rechaza: sin el, la auditoria no responde por que")
	void baja_sin_motivo() {
		assertThatThrownBy(() -> service.darDeBaja(
				actor, CONSULTORIO_ID, SESION_ID, TRATAMIENTO_ID, "   ", 0L))
				.isInstanceOf(IllegalArgumentException.class);

		verify(tratamientos, never()).save(any());
	}

	@Test
	@DisplayName("La baja con motivo marca la fila y deja su evento propio de auditoria")
	void baja_con_motivo() {
		given(tratamientos.findEnSesion(ORG_ID, SESION_ID, TRATAMIENTO_ID))
				.willReturn(Optional.of(tratamientoVigente()));

		service.darDeBaja(actor, CONSULTORIO_ID, SESION_ID, TRATAMIENTO_ID, "Se cargo mal", 0L);

		ArgumentCaptor<TratamientoRealizado> guardado =
				ArgumentCaptor.forClass(TratamientoRealizado.class);
		verify(tratamientos).save(guardado.capture());
		assertThat(guardado.getValue().estaVigente())
				.as("baja logica: la fila sigue, con su motivo")
				.isFalse();
		verify(auditTrail).record(any(AuditEntry.class));
	}

	@Test
	@DisplayName("Dar de baja algo que no es de esa sesion da 404")
	void baja_de_otro_tratamiento() {
		given(tratamientos.findEnSesion(ORG_ID, SESION_ID, TRATAMIENTO_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.darDeBaja(
				actor, CONSULTORIO_ID, SESION_ID, TRATAMIENTO_ID, "Se cargo mal", 0L))
				.isInstanceOf(TratamientoNoAccesibleException.class);
	}

	@Test
	@DisplayName("Reemplazar un tratamiento ya dado de baja da 404: no revive")
	void reemplazo_de_una_baja() {
		TratamientoRealizado baja = tratamientoVigente();
		baja.darDeBaja("Se cargo mal", Instant.EPOCH);
		given(tratamientos.findEnSesion(ORG_ID, SESION_ID, TRATAMIENTO_ID))
				.willReturn(Optional.of(baja));

		assertThatThrownBy(() -> service.reemplazar(
				actor, CONSULTORIO_ID, SESION_ID, TRATAMIENTO_ID, aplicado(), 0L))
				.isInstanceOf(TratamientoNoAccesibleException.class);
	}

	// =================================================================================
	// C-6 — enmienda de una sesion cerrada
	// =================================================================================

	@Test
	@DisplayName("C-6: la enmienda corrige el tratamiento sin avanzar la version de la sesion")
	void la_enmienda_corrige_sin_force_increment() {
		// La reciproca de 02.07: la enmienda ya ensucio la cabecera, asi que avanzar aca la haria
		// subir dos veces y el cliente comeria un 409 del que no puede salir.
		TratamientoRealizado vigente = tratamientoVigente();
		given(tratamientos.listarVigentes(ORG_ID, SESION_ID)).willReturn(List.of(vigente));

		service.aplicarEnmienda(actor, sesionAbierta(), List.of(new TratamientoEnmendado(
				TRATAMIENTO_ID, new TratamientoAplicado(PRACTICA_ID, "Ultrasonido", "hombro",
						Lateralidad.DERECHA, 20, null, null, null, List.of()))),
				"Era hombro, no rodilla", Instant.EPOCH);

		assertThat(vigente.getZona()).isEqualTo("hombro");
		assertThat(vigente.getLateralidad()).isEqualTo(Lateralidad.DERECHA);
		assertThat(vigente.estaVigente()).isTrue();
		verify(sesiones, never()).avanzarVersion(anyLong(), anyLong(), anyLong(), anyLong());
		verify(parametros).borrarDe(ORG_ID, TRATAMIENTO_ID);
	}

	@Test
	@DisplayName("C-6: un tratamiento que no aparece en la enmienda se da de BAJA con su motivo")
	void la_enmienda_da_de_baja_lo_que_falta() {
		TratamientoRealizado quitado = tratamientoVigente();
		TratamientoRealizado otro = new TratamientoRealizado(ORG_ID, CONSULTORIO_ID, SESION_ID, 2,
				PRACTICA_ID, "US-01", "Ultrasonido", MEMBERSHIP_PROPIA, Instant.EPOCH, CUENTA_PROPIA);
		ReflectionTestUtils.setField(otro, "id", TRATAMIENTO_ID + 1);
		given(tratamientos.listarVigentes(ORG_ID, SESION_ID)).willReturn(List.of(quitado, otro));

		service.aplicarEnmienda(actor, sesionAbierta(),
				List.of(new TratamientoEnmendado(TRATAMIENTO_ID + 1, aplicado())),
				"Se habia cargado dos veces", Instant.EPOCH);

		assertThat(quitado.estaVigente()).as("baja logica, nunca DELETE").isFalse();
		assertThat(quitado.getDeactivationReason()).isEqualTo("Se habia cargado dos veces");
		assertThat(otro.estaVigente()).isTrue();
	}

	@Test
	@DisplayName("C-6: la enmienda no puede cambiar el conjunto de practicas realizadas: 409")
	void la_enmienda_no_cambia_las_practicas() {
		// Esas practicas eligieron al cierre que autorizacion se consumio (06.04).
		given(tratamientos.listarVigentes(ORG_ID, SESION_ID)).willReturn(List.of(tratamientoVigente()));

		assertThatThrownBy(() -> service.aplicarEnmienda(actor, sesionAbierta(),
				List.of(new TratamientoEnmendado(TRATAMIENTO_ID, new TratamientoAplicado(
						PRACTICA_ID + 1, null, null, null, null, null, null, null, List.of()))),
				"Era otra practica", Instant.EPOCH))
				.isInstanceOf(EnmiendaCambiaPracticasException.class);

		assertThatThrownBy(() -> service.aplicarEnmienda(actor, sesionAbierta(), List.of(),
				"No hubo nada", Instant.EPOCH))
				.as("quitar el unico tratamiento tambien cambia el conjunto")
				.isInstanceOf(EnmiendaCambiaPracticasException.class);

		verify(tratamientos, never()).save(any());
	}

	@Test
	@DisplayName("C-6: corregir un tratamiento que no es vigente de la sesion es 404")
	void la_enmienda_de_un_tratamiento_ajeno_es_404() {
		given(tratamientos.listarVigentes(ORG_ID, SESION_ID)).willReturn(List.of(tratamientoVigente()));

		assertThatThrownBy(() -> service.aplicarEnmienda(actor, sesionAbierta(),
				List.of(new TratamientoEnmendado(TRATAMIENTO_ID + 50, aplicado())),
				"motivo", Instant.EPOCH))
				.isInstanceOf(TratamientoNoAccesibleException.class);
	}

	@Test
	@DisplayName("C-6: una practica que se dio de baja despues no impide corregir la zona")
	void la_practica_conservada_no_exige_vigencia() {
		// La enmienda corrige un hecho pasado: revalidar contra el catalogo de hoy impediria
		// corregir una sesion de hace dos años.
		given(catalogo.findPractica(anyLong(), anyLong(), any())).willReturn(Optional.of(practica(false)));
		given(tratamientos.listarVigentes(ORG_ID, SESION_ID)).willReturn(List.of(tratamientoVigente()));

		assertThatCode(() -> service.aplicarEnmienda(actor, sesionAbierta(),
				List.of(new TratamientoEnmendado(TRATAMIENTO_ID, aplicado())), "motivo",
				Instant.EPOCH))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	@Test
	@DisplayName("Leer los tratamientos DEJA RASTRO: en lo clinico leer tambien se audita")
	void la_lectura_se_audita() {
		// DP-03 no distingue entre leer y escribir en una historia clinica, y en lo clinico el
		// riesgo esta mas del lado de quien lee sin motivo.
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.of(sesionAbierta()));
		given(tratamientos.listarVigentes(ORG_ID, SESION_ID))
				.willReturn(List.of(tratamientoVigente()));
		given(parametros.listarDe(anyLong(), anyLong())).willReturn(List.of());

		List<TratamientoView> vistas = service.listar(actor, CONSULTORIO_ID, SESION_ID);

		assertThat(vistas).hasSize(1);
		verify(auditTrail).record(any(AuditEntry.class));
	}

	@Test
	@DisplayName("Listar sobre una sesion que no es de esa sede da 404")
	void listar_sesion_ajena_a_la_sede() {
		given(sesiones.findByIdInScope(ORG_ID, CONSULTORIO_ID, SESION_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.listar(actor, CONSULTORIO_ID, SESION_ID))
				.isInstanceOf(SesionNotAccessibleException.class);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static TratamientoAplicado aplicado(ParametroAplicado... declarados) {
		return new TratamientoAplicado(PRACTICA_ID, "Ultrasonido", "rodilla", null, 15, null, null,
				"sin dolor", List.of(declarados));
	}

	private static TratamientoAplicado conEspacio() {
		return new TratamientoAplicado(PRACTICA_ID, "Ultrasonido", "rodilla", null, 15, null,
				ESPACIO_ID, null, List.of());
	}

	private static TratamientoAplicado conProfesional(long membershipId) {
		return new TratamientoAplicado(PRACTICA_ID, "Ultrasonido", "rodilla", null, 15,
				membershipId, null, null, List.of());
	}

	private static Sesion sesionAbierta() {
		return sesionDe(MEMBERSHIP_PROPIA);
	}

	private static Sesion sesionDe(long profesionalMembershipId) {
		Sesion sesion = new Sesion(ORG_ID, CONSULTORIO_ID, HISTORIA_ID, null, TURNO_ID, OFERTA_ID,
				profesionalMembershipId, Instant.EPOCH, CUENTA_PROPIA);
		ReflectionTestUtils.setField(sesion, "id", SESION_ID);
		return sesion;
	}

	private static CatalogoSnapshot practica(boolean vigente) {
		return new CatalogoSnapshot(PRACTICA_ID, ORG_ID, "US-01", "Ultrasonido", Instant.EPOCH,
				null, true, vigente, null, null, 0L);
	}

	private static EspacioSnapshot espacio(long consultorioId, boolean enServicio) {
		return new EspacioSnapshot(ESPACIO_ID, ORG_ID, consultorioId, "Box 1", "BOX", 1,
				Instant.EPOCH, null, true, enServicio);
	}

	private static TratamientoRealizado tratamientoVigente() {
		TratamientoRealizado tratamiento = new TratamientoRealizado(ORG_ID, CONSULTORIO_ID,
				SESION_ID, 1, PRACTICA_ID, "US-01", "Ultrasonido", MEMBERSHIP_PROPIA,
				Instant.EPOCH, CUENTA_PROPIA);
		ReflectionTestUtils.setField(tratamiento, "id", TRATAMIENTO_ID);
		return tratamiento;
	}

	/** JPA asigna el id al persistir; el doble tiene que hacer lo mismo o el fixture mentiria. */
	private static TratamientoRealizado conIdComoJpa(org.mockito.invocation.InvocationOnMock i) {
		TratamientoRealizado guardado = i.getArgument(0);
		if (guardado.getId() == null) {
			ReflectionTestUtils.setField(guardado, "id", TRATAMIENTO_ID);
		}
		return guardado;
	}
}
