package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.application.AvanceDeItemView;
import com.akine.clinical.application.AvanceDelPlanView;
import com.akine.clinical.application.CasoClinicoAltaCommand;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.CasoClinicoView;
import com.akine.clinical.application.ContenidoDelPlan;
import com.akine.clinical.application.IntegranteDelEquipo;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.application.PlanItemPlanificado;
import com.akine.clinical.application.PlanTratamientoService;
import com.akine.clinical.application.PlanTratamientoView;
import com.akine.clinical.application.PlanVersionView;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.exception.PlanNoEditableException;
import com.akine.clinical.domain.exception.PlanSinItemsException;
import com.akine.clinical.domain.exception.PlanTratamientoNotAccessibleException;
import com.akine.clinical.domain.exception.TransicionDePlanInvalidaException;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El ciclo de vida del Plan de Tratamiento contra una base real, y el avance que se DERIVA.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba</h2>
 *
 * <p><b>"Un paciente con un plan activo de sesiones al que el profesional le cambia la frecuencia
 * y le agrega una practica, mientras las sesiones se van cerrando."</b> Es el caso que rompe el
 * diseño segun el challenge de AKINE-04.04, recorrido de punta a punta:
 * {@code BORRADOR → editar sin versionar → ACTIVO → modificar (versiona) → SUSPENDIDO →
 * ACTIVO → FINALIZADO}.
 *
 * <h2>Las cinco afirmaciones que solo se pueden hacer aca</h2>
 *
 * <ol>
 *   <li><b>El avance de la version 1 y el de la version 2 son dos numeros distintos y los dos
 *       correctos.</b> Es la consecuencia aceptada de colgar los items de la VERSION: "8 de 20" y
 *       "8 de 24" conviven. Con dobles no se ve, porque el reparto de items por version es una
 *       propiedad de las filas, no del servicio.</li>
 *   <li><b>Activar un plan nuevo finaliza el anterior en la MISMA transaccion</b>, y
 *       {@code uk_plan_activo_por_caso} es lo que impide que dos activaciones concurrentes dejen
 *       dos vivos. El unique se apoya en {@code activo_key}, una columna <b>generada</b>: si esa
 *       expresion no hace lo que dice, nada fuera del motor lo puede saber.</li>
 *   <li><b>Completar la cantidad estimada NO finaliza el plan ni cierra el Caso</b> (RN-M11-004).
 *       El sistema avisa —{@code completo} pasa a {@code true}— y la decision sigue siendo
 *       clinica. Es la tentacion que mas se parece a una mejora de producto, y por eso la mas
 *       peligrosa.</li>
 *   <li><b>El avance se deriva de sesiones cerradas reales</b>, contadas por
 *       {@code RealizadoEnElCasoProbe} a traves del camino verdadero —{@code SesionService#cerrar}—
 *       y no de una columna. La unica forma de que ese numero mienta es que {@code encounter}
 *       mienta sobre sus propias sesiones.</li>
 *   <li><b>Editar un BORRADOR reemplaza los items de su version 1</b>, que es el unico borrado
 *       fisico del modulo; en cuanto el plan pasa a ACTIVO ese camino deja de ejecutarse y toda
 *       modificacion escribe filas nuevas.</li>
 * </ol>
 *
 * <h2>Lo que esta clase NO cubre, declarado</h2>
 *
 * <p><b>Dos ediciones simultaneas del mismo BORRADOR.</b> El registro de 04.04 lo declara como
 * concurrencia conocida y no cubierta: esa escritura no ensucia la cabecera, asi que las dos
 * commitean y gana la ultima. Un test que lo ejerciera fallaria por diseño y no por defecto. Lo
 * que si se cubre es el caso que {@code expectedVersion} protege de verdad: que alguien
 * <b>active</b> el plan en el medio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PlanTratamientoIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Sin relacion asistencial no hay acceso clinico: hoy el probe siempre dice "sin evidencia". */
	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	@Autowired private CasoClinicoService casos;
	@Autowired private PlanTratamientoService planes;
	@Autowired private SesionService sesiones;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// El recorrido completo
	// =================================================================================

	@Test
	@DisplayName("crear, editar, activar, modificar, suspender, reanudar y finalizar deja el historial completo")
	void el_ciclo_completo_queda_en_plan_evento() {
		// El historial es lo unico que hace revisable el recorrido: RF-M11-006 pide cuatro
		// transiciones y no cuatro columnas, asi que si los eventos no quedaran, un plan
		// suspendido y reanudado seria indistinguible de uno que nunca se freno.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);

		PlanTratamientoView borrador = planes.crear(fixture.actorClinico(), caso.id(),
				contenido(fixture, "Recuperar rango de movimiento", 2, 8, 10), JUSTIFICACION);

		assertThat(borrador.estado())
				.as("nace en BORRADOR aunque venga completo: activar es una decision explicita")
				.isEqualTo("BORRADOR");
		assertThat(borrador.numeroPlan()).isEqualTo(1);
		assertThat(borrador.versionVigente().numeroVersion()).isEqualTo(1);

		// EDITAR EL BORRADOR: reescribe la version 1 en el lugar, sin versionar.
		PlanTratamientoView editado = planes.modificar(fixture.actorClinico(), borrador.id(),
				contenido(fixture, "Volver a correr cinco kilometros", 3, 8, 12), null,
				borrador.version(), JUSTIFICACION);

		assertThat(editado.versionVigente().numeroVersion())
				.as("un plan que nunca se activo no tiene historia que preservar: sigue la 1")
				.isEqualTo(1);
		assertThat(versionesDe(editado.id()))
				.as("y en la base hay UNA sola fila de version")
				.isEqualTo(1);
		assertThat(editado.versionVigente().items())
				.singleElement()
				.satisfies(item -> assertThat(item.cantidadPlanificada()).isEqualTo(12));

		PlanTratamientoView activo = planes.activar(
				fixture.actorClinico(), editado.id(), editado.version(), JUSTIFICACION);
		assertThat(activo.estado()).isEqualTo("ACTIVO");
		assertThat(activo.activadoEn()).isNotNull();

		// MODIFICAR EL VIGENTE: versiona, y el motivo es obligatorio.
		PlanTratamientoView modificado = planes.modificar(fixture.actorClinico(), activo.id(),
				contenido(fixture, "Volver a correr diez kilometros", 3, 12, 20),
				"El paciente progresa mas rapido de lo previsto",
				activo.version(), JUSTIFICACION);

		assertThat(modificado.versionVigente().numeroVersion()).isEqualTo(2);
		assertThat(versionesDe(modificado.id()))
				.as("dos filas: la version 1 NO se borra ni se da de baja (ADR-0011)")
				.isEqualTo(2);

		PlanTratamientoView suspendido = planes.suspender(fixture.actorClinico(), activo.id(),
				"El paciente viaja por dos meses", modificado.version(), JUSTIFICACION);
		assertThat(suspendido.estado()).isEqualTo("SUSPENDIDO");
		assertThat(suspendido.motivoSuspension()).isEqualTo("El paciente viaja por dos meses");

		PlanTratamientoView reanudado = planes.reanudar(
				fixture.actorClinico(), activo.id(), suspendido.version(), JUSTIFICACION);
		assertThat(reanudado.estado()).isEqualTo("ACTIVO");
		assertThat(reanudado.suspendidoEn())
				.as("reanudar LIMPIA las columnas de la suspension: el CHECK no admite un ACTIVO "
						+ "con instante de suspension, y una consulta por suspendido_en IS NOT "
						+ "NULL devolveria planes vigentes. El motivo queda en plan_evento")
				.isNull();
		assertThat(reanudado.motivoSuspension()).isNull();

		PlanTratamientoView finalizado = planes.finalizar(fixture.actorClinico(), activo.id(),
				"Alta por objetivos cumplidos", reanudado.version(), JUSTIFICACION);
		assertThat(finalizado.estado()).isEqualTo("FINALIZADO");

		assertThat(tiposDeEvento(activo.id()))
				.as("los siete eventos, en el orden en que ocurrieron")
				.containsExactly("CREACION", "EDICION", "ACTIVACION", "MODIFICACION",
						"SUSPENSION", "REANUDACION", "FINALIZACION");
	}

	@Test
	@DisplayName("un plan FINALIZADO no se modifica ni se reanuda: 409, no 403")
	void el_plan_finalizado_no_admite_cambios() {
		// Quien opera TIENE el permiso; lo que no admite cambios es el estado. Un 403 lo mandaria
		// a pedirle a su administrador un permiso que ya tiene. FINALIZADO es terminal: lo que
		// corresponde es crear un plan nuevo.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView plan = crearYActivar(fixture, caso, 10);
		PlanTratamientoView finalizado = planes.finalizar(fixture.actorClinico(), plan.id(),
				"Alta", plan.version(), JUSTIFICACION);

		assertThatThrownBy(() -> planes.modificar(fixture.actorClinico(), plan.id(),
				contenido(fixture, "Otra cosa", 2, 8, 5), "Motivo", finalizado.version(), JUSTIFICACION))
				.isInstanceOf(PlanNoEditableException.class);
		assertThatThrownBy(() -> planes.reanudar(
				fixture.actorClinico(), plan.id(), finalizado.version(), JUSTIFICACION))
				.isInstanceOf(TransicionDePlanInvalidaException.class);
	}

	// =================================================================================
	// Un solo plan activo por Caso
	// =================================================================================

	@Test
	@DisplayName("activar un plan nuevo finaliza el anterior en la misma transaccion")
	void la_activacion_del_nuevo_finaliza_al_vigente() {
		// Entre un pedido y el otro habria una ventana con dos vivos o con ninguno, y las dos son
		// incorrectas. Por eso la finalizacion del anterior va ACA ADENTRO.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView primero = crearYActivar(fixture, caso, 10);

		PlanTratamientoView segundo = planes.crear(fixture.actorClinico(), caso.id(),
				contenido(fixture, "Segundo abordaje", 2, 6, 8), JUSTIFICACION);
		planes.activar(fixture.actorClinico(), segundo.id(), segundo.version(), JUSTIFICACION);

		assertThat(planes.ver(fixture.actorClinico(), primero.id(), JUSTIFICACION))
				.satisfies(anterior -> {
					assertThat(anterior.estado()).isEqualTo("FINALIZADO");
					assertThat(anterior.motivoFinalizacion())
							.as("con motivo propio: el CHECK exige los tres datos juntos")
							.isNotBlank();
				});
		assertThat(activosDelCaso(caso.id()))
				.as("uno solo, siempre")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("un plan sin items no se activa, no finaliza al vigente y se activa al cargarle un item")
	void un_plan_sin_items_no_se_activa() {
		// AC-1: el chequeo va antes de finalizar al plan vigente, y la idempotencia antes del chequeo.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView vigente = crearYActivar(fixture, caso, 10);

		PlanTratamientoView vacio = planes.crear(fixture.actorClinico(), caso.id(),
				new ContenidoDelPlan("Sin practicas todavia", null, 2, 4, List.of()),
				JUSTIFICACION);
		assertThat(vacio.estado()).isEqualTo("BORRADOR");

		assertThatThrownBy(() -> planes.activar(
				fixture.actorClinico(), vacio.id(), vacio.version(), JUSTIFICACION))
				.isInstanceOf(PlanSinItemsException.class)
				.extracting(e -> ((PlanSinItemsException) e).getPlanId())
				.isEqualTo(vacio.id());

		assertThat(planes.ver(fixture.actorClinico(), vacio.id(), JUSTIFICACION).estado())
				.as("sigue BORRADOR")
				.isEqualTo("BORRADOR");
		assertThat(tiposDeEvento(vacio.id()))
				.as("sin evento de activacion")
				.containsExactly("CREACION");
		assertThat(planes.ver(fixture.actorClinico(), vigente.id(), JUSTIFICACION).estado())
				.as("y el vigente del caso no se toco")
				.isEqualTo("ACTIVO");
		assertThat(activosDelCaso(caso.id())).isEqualTo(1);

		// Con un item la misma activacion entra y finaliza al anterior.
		PlanTratamientoView completo = planes.modificar(fixture.actorClinico(), vacio.id(),
				contenido(fixture, "Ya con practicas", 2, 4, 6), null,
				verVersionActual(fixture, vacio.id()), JUSTIFICACION);
		PlanTratamientoView activo = planes.activar(
				fixture.actorClinico(), vacio.id(), completo.version(), JUSTIFICACION);

		assertThat(activo.estado()).isEqualTo("ACTIVO");
		assertThat(planes.ver(fixture.actorClinico(), vigente.id(), JUSTIFICACION).estado())
				.isEqualTo("FINALIZADO");
		assertThat(activosDelCaso(caso.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("activar un plan ya activo responde igual aunque su version no tenga items")
	void activar_lo_activo_no_mira_los_items() {
		// AC-1: la idempotencia se evalua antes del chequeo de items. Se vacia la version por SQL
		// porque por la API un plan ACTIVO ya no puede quedar sin items.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView activo = crearYActivar(fixture, caso, 10);
		jdbc.update("""
				DELETE FROM plan_item WHERE plan_tratamiento_version_id IN
				  (SELECT id FROM plan_tratamiento_version WHERE plan_tratamiento_id = ?)
				""", activo.id());

		assertThat(planes.activar(
				fixture.actorClinico(), activo.id(), activo.version(), JUSTIFICACION).estado())
				.isEqualTo("ACTIVO");
	}

	@Test
	@DisplayName("dos activaciones concurrentes sobre el mismo caso dejan UN solo plan activo")
	void dos_activaciones_concurrentes_no_dejan_dos_vivos() {
		// El unique `uk_plan_activo_por_caso` es lo que decide, y se apoya en `activo_key`, que es
		// una columna GENERADA: `IF(estado = 'ACTIVO', 0, numero_plan)`. Si esa expresion no
		// compila como se cree —MySQL prohibe que una generada referencie un AUTO_INCREMENT, que
		// es por lo que no pudo ser el `id`— este escenario deja dos planes activos y nadie se
		// entera hasta que el avance de un Caso empiece a contar contra dos planes.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);

		PlanTratamientoView uno = planes.crear(fixture.actorClinico(), caso.id(),
				contenido(fixture, "Abordaje A", 2, 8, 10), JUSTIFICACION);
		PlanTratamientoView otro = planes.crear(fixture.actorClinico(), caso.id(),
				contenido(fixture, "Abordaje B", 3, 8, 12), JUSTIFICACION);

		List<Desenlace<PlanTratamientoView>> desenlaces = enParalelo(List.of(
				() -> planes.activar(
						fixture.actorClinico(), uno.id(), uno.version(), JUSTIFICACION),
				() -> planes.activar(
						fixture.actorClinico(), otro.id(), otro.version(), JUSTIFICACION)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("una sola activacion entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(activosDelCaso(caso.id()))
				.as("y en la BASE queda uno solo: el Caso ES el problema terapeutico, y dos planes "
						+ "activos significan que en realidad son dos problemas. Desenlaces: %s",
						desenlaces)
				.isEqualTo(1);
	}

	@Test
	@DisplayName("suspender no abre la puerta a otro plan: el suspendido sigue ocupando el lugar")
	void suspender_no_abre_la_puerta_a_otro_plan() {
		// ESTE TEST FALLA HOY, Y EL DEFECTO ES DE PRODUCCION. Mi alcance es `src/test`: no se
		// corrige aca. Escrito contra la conducta ESPECIFICADA, igual que
		// `el_binario_faltante_es_conflicto_y_no_un_404` de 04.02.
		//
		// `PlanTratamientoService:432-436` y `EstadoPlan:47-49` declaran que "suspender no libera
		// el lugar del plan activo del Caso [...] quien quiera empezar otro tratamiento finaliza
		// este". `V49` dice otra cosa: `activo_key = IF(estado = 'ACTIVO', 0, numero_plan)` libera
		// el 0 en cuanto el plan pasa a SUSPENDIDO.
		//
		// Lo que pasa hoy, SIN concurrencia: activar B entra —el unique ya no lo frena— y
		// `finalizarElVigente` tampoco lo detiene, porque `buscarActivoDelCaso` filtra por
		// `estado = 'ACTIVO'` y A no aparece. El Caso queda con DOS planes vivos y, peor,
		// REANUDAR A pasa a chocar contra el unique con un 409 generico: el tratamiento que el
		// paciente freno queda irrecuperable, y la pantalla no tiene como explicarlo.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView activo = crearYActivar(fixture, caso, 10);

		PlanTratamientoView suspendido = planes.suspender(fixture.actorClinico(), activo.id(),
				"El paciente viaja por dos meses", activo.version(), JUSTIFICACION);
		assertThat(suspendido.estado()).isEqualTo("SUSPENDIDO");

		PlanTratamientoView otro = planes.crear(fixture.actorClinico(), caso.id(),
				contenido(fixture, "Otro abordaje", 2, 6, 8), JUSTIFICACION);

		assertThatThrownBy(() -> planes.activar(
				fixture.actorClinico(), otro.id(), otro.version(), JUSTIFICACION))
				.as("el lugar del plan activo sigue ocupado por el suspendido: activar otro tiene "
						+ "que rechazarse hasta que alguien FINALICE el que esta frenado")
				.isInstanceOf(Exception.class);

		assertThat(planes.ver(fixture.actorClinico(), activo.id(), JUSTIFICACION).estado())
				.as("y el plan suspendido sigue siendo suspendido, no finalizado a sus espaldas")
				.isEqualTo("SUSPENDIDO");

		PlanTratamientoView reanudado = planes.reanudar(fixture.actorClinico(), activo.id(),
				verVersionActual(fixture, activo.id()), JUSTIFICACION);
		assertThat(reanudado.estado())
				.as("REANUDAR TIENE QUE PODER. Hoy, con el otro plan ya activo, esto choca contra "
						+ "el unique y el tratamiento del paciente queda irrecuperable")
				.isEqualTo("ACTIVO");
		assertThat(activosDelCaso(caso.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("dos casos distintos tienen cada uno su plan activo: el unique no es global")
	void el_plan_activo_es_por_caso() {
		// Control negativo del unique. Sin `caso_clinico_id` en la clave, una organizacion entera
		// admitiria un solo plan activo y el modelo seria inservible — y con todos los demas
		// escenarios en verde, porque usan un solo caso.
		Fixture fixture = crearFixture();
		CasoClinicoView unCaso = abrirCaso(fixture);
		CasoClinicoView otroCaso = abrirCaso(fixture);

		crearYActivar(fixture, unCaso, 10);
		crearYActivar(fixture, otroCaso, 6);

		assertThat(activosDelCaso(unCaso.id())).isEqualTo(1);
		assertThat(activosDelCaso(otroCaso.id())).isEqualTo(1);
	}

	// =================================================================================
	// El avance, que se DERIVA
	// =================================================================================

	@Test
	@DisplayName("completar la cantidad estimada NO finaliza el plan ni cierra el caso")
	void completar_no_cierra_nada() {
		// RN-M11-004, y la regla maestra 2 en la direccion contraria: cerrar por contador es
		// confundir realizado con planificado. El sistema AVISA —`completo` pasa a true— y decide
		// el profesional. Es la tentacion que mas se parece a una mejora de producto.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView plan = crearYActivar(fixture, caso, 2);

		cerrarSesionDelCaso(fixture, caso.id());
		cerrarSesionDelCaso(fixture, caso.id());

		AvanceDelPlanView avance =
				planes.avance(fixture.actorClinico(), plan.id(), null, JUSTIFICACION);

		assertThat(avance.completo())
				.as("el aviso si se da")
				.isTrue();
		assertThat(avance.items())
				.singleElement()
				.satisfies(item -> {
					assertThat(item.planificadas()).isEqualTo(2);
					assertThat(item.realizadas()).isEqualTo(2);
					assertThat(item.restantes()).isZero();
				});

		assertThat(planes.ver(fixture.actorClinico(), plan.id(), JUSTIFICACION).estado())
				.as("y el plan sigue ACTIVO")
				.isEqualTo("ACTIVO");
		assertThat(jdbc.queryForObject(
				"SELECT estado FROM caso_clinico WHERE id = ?", String.class, caso.id()))
				.as("y el Caso sigue abierto: son dos maquinas de estado y ninguna manda sobre "
						+ "la otra")
				.isEqualTo("ACTIVO");
	}

	@Test
	@DisplayName("el avance de la version 1 y el de la version 2 son dos numeros distintos y los dos correctos")
	void cada_version_tiene_su_propio_avance() {
		// LA CONSECUENCIA ACEPTADA de colgar los items de la version, y la que la pantalla va a
		// tener que mostrar: "2 de 2" en la version 1 y "2 de 5" en la version 2 son los dos
		// ciertos. Si los items colgaran del plan, modificar la cantidad reescribiria el avance de
		// hace dos meses contra un plan que entonces no existia (RN-M11-003).
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView plan = crearYActivar(fixture, caso, 2);

		cerrarSesionDelCaso(fixture, caso.id());
		cerrarSesionDelCaso(fixture, caso.id());

		PlanTratamientoView modificado = planes.modificar(fixture.actorClinico(), plan.id(),
				contenido(fixture, "Se extiende el tratamiento", 3, 10, 5),
				"El paciente necesita mas sesiones de las previstas",
				verVersionActual(fixture, plan.id()), JUSTIFICACION);
		assertThat(modificado.versionVigente().numeroVersion()).isEqualTo(2);

		AvanceDelPlanView deLaUno =
				planes.avance(fixture.actorClinico(), plan.id(), 1, JUSTIFICACION);
		AvanceDelPlanView deLaDos =
				planes.avance(fixture.actorClinico(), plan.id(), 2, JUSTIFICACION);

		assertThat(deLaUno.items()).singleElement().satisfies(item -> {
			assertThat(item.planificadas()).isEqualTo(2);
			assertThat(item.realizadas()).isEqualTo(2);
		});
		assertThat(deLaUno.completo())
				.as("la version 1 se cumplio: eso paso y sigue siendo cierto")
				.isTrue();

		assertThat(deLaDos.items()).singleElement().satisfies(item -> {
			assertThat(item.planificadas()).isEqualTo(5);
			assertThat(item.realizadas())
					.as("las MISMAS dos sesiones: lo realizado no se reparte entre versiones")
					.isEqualTo(2);
			assertThat(item.restantes()).isEqualTo(3);
		});
		assertThat(deLaDos.completo()).isFalse();

		assertThat(planes.versiones(fixture.actorClinico(), plan.id(), JUSTIFICACION))
				.as("las dos versiones siguen consultables, cada una con SUS items")
				.hasSize(2)
				.extracting(PlanVersionView::numeroVersion)
				.containsExactlyInAnyOrder(1, 2);
	}

	@Test
	@DisplayName("una sesion cerrada con AUSENTE cuenta como cancelada, no como realizada")
	void la_ausencia_no_es_una_realizacion() {
		// "Realizada" = sesion CERRADA con asistencia PRESENTE; "cancelada" = CERRADA con AUSENTE.
		// Es la definicion que 04.04 tuvo que fijar porque `encounter` no tiene estado CANCELADA.
		// Contar un no-show como realizado inflaria el avance del plan y, en 04.05, habria gastado
		// una unidad autorizada que el paciente nunca uso.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView plan = crearYActivar(fixture, caso, 3);

		cerrarSesionDelCaso(fixture, caso.id());
		cerrarSesionDelCaso(fixture, caso.id(), Asistencia.AUSENTE);

		assertThat(planes.avance(fixture.actorClinico(), plan.id(), null, JUSTIFICACION).items())
				.singleElement()
				.satisfies(item -> {
					assertThat(item.realizadas()).isEqualTo(1);
					assertThat(item.canceladas()).isEqualTo(1);
				});
	}

	@Test
	@DisplayName("un plan sin sesiones cerradas avanza en cero, no se rompe")
	void sin_sesiones_el_avance_es_cero() {
		// La sonda devuelve una entrada por oferta CON AL MENOS una sesion cerrada, asi que las
		// ofertas todavia no atendidas no aparecen y el llamador las resuelve como cero. Sin este
		// escenario, un NullPointerException en ese camino se descubriria en produccion el primer
		// dia de uso de cualquier plan.
		Fixture fixture = crearFixture();
		CasoClinicoView caso = abrirCaso(fixture);
		PlanTratamientoView plan = crearYActivar(fixture, caso, 4);

		AvanceDelPlanView avance =
				planes.avance(fixture.actorClinico(), plan.id(), null, JUSTIFICACION);

		assertThat(avance.completo())
				.as("cero de cuatro no esta completo")
				.isFalse();
		assertThat(avance.items())
				.singleElement()
				.extracting(AvanceDeItemView::realizadas)
				.isEqualTo(0);
	}

	// =================================================================================
	// Aislamiento de tenant — AGENT.md seccion 6
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no ve ni toca el plan del tenant A: 404, nunca 403")
	void un_tenant_no_toca_el_plan_del_otro() {
		// Un 403 confirmaria que ese plan existe, y con eso se puede censar por id cuantos
		// tratamientos lleva el centro de al lado. El 404 no confirma nada.
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		CasoClinicoView caso = abrirCaso(tenantA);
		PlanTratamientoView plan = crearYActivar(tenantA, caso, 5);

		assertThatThrownBy(() ->
				planes.ver(tenantB.actorClinico(), plan.id(), JUSTIFICACION))
				.isInstanceOf(PlanTratamientoNotAccessibleException.class);
		assertThatThrownBy(() ->
				planes.avance(tenantB.actorClinico(), plan.id(), null, JUSTIFICACION))
				.isInstanceOf(PlanTratamientoNotAccessibleException.class);
		assertThatThrownBy(() ->
				planes.versiones(tenantB.actorClinico(), plan.id(), JUSTIFICACION))
				.isInstanceOf(PlanTratamientoNotAccessibleException.class);
		assertThatThrownBy(() -> planes.suspender(tenantB.actorClinico(), plan.id(),
				"Intento cruzado", plan.version(), JUSTIFICACION))
				.isInstanceOf(PlanTratamientoNotAccessibleException.class);

		assertThat(planes.ver(tenantA.actorClinico(), plan.id(), JUSTIFICACION).estado())
				.as("y el dueño lo sigue viendo intacto")
				.isEqualTo("ACTIVO");
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	private static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace<T>>> futuros = new ArrayList<>();
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace<>(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace<T>(null, error);
					}
				}));
			}
			List<Desenlace<T>> desenlaces = new ArrayList<>();
			for (Future<Desenlace<T>> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception error) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", error);
		}
	}

	private record Desenlace<T>(T valor, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(" + valor + ")";
		}
	}

	// =================================================================================
	// Operaciones y consultas
	// =================================================================================

	/**
	 * Contenido de plan con un solo item, sobre la oferta de <b>esa</b> fixture.
	 *
	 * <p>La oferta viaja en el parametro y no en un campo de la clase a proposito: los escenarios
	 * de aislamiento crean dos fixtures, y una oferta guardada en un campo seria la del ultimo
	 * tenant creado. Ese error daria un {@code OfertaNoHabilitadaException} que parece un defecto
	 * de produccion y es del test.
	 */
	private static ContenidoDelPlan contenido(
			Fixture fixture, String objetivos, Integer frecuencia, Integer semanas,
			int planificadas) {

		return new ContenidoDelPlan(objetivos, "Indicaciones sinteticas", frecuencia, semanas,
				List.of(new PlanItemPlanificado(fixture.ofertaId(), planificadas, null)));
	}

	private CasoClinicoView abrirCaso(Fixture fixture) {
		return casos.abrir(fixture.actorClinico(), new CasoClinicoAltaCommand(
						fixture.historiaClinicaId(),
						fixture.ofertaId(),
						"Gonalgia derecha " + UUID.randomUUID().toString().substring(0, 8),
						"Recuperar rango de movimiento",
						List.of(new IntegranteDelEquipo(
								fixture.membershipId(), RolEnCaso.RESPONSABLE)),
						true),
				JUSTIFICACION);
	}

	private PlanTratamientoView crearYActivar(
			Fixture fixture, CasoClinicoView caso, int planificadas) {

		PlanTratamientoView borrador = planes.crear(fixture.actorClinico(), caso.id(),
				contenido(fixture, "Objetivos sinteticos", 2, 8, planificadas), JUSTIFICACION);
		return planes.activar(
				fixture.actorClinico(), borrador.id(), borrador.version(), JUSTIFICACION);
	}

	private long verVersionActual(Fixture fixture, long planId) {
		return planes.ver(fixture.actorClinico(), planId, JUSTIFICACION).version();
	}

	private SesionView cerrarSesionDelCaso(Fixture fixture, long casoId) {
		return cerrarSesionDelCaso(fixture, casoId, Asistencia.PRESENTE);
	}

	/**
	 * Una sesion del caso, abierta por SQL y cerrada por el servicio.
	 *
	 * <p>Se inserta directo porque abrirla por {@code iniciar} exigiria un turno por cada una, y lo
	 * que estos escenarios miden es la derivacion del avance, no la cadena de M12. <b>El cierre SI
	 * va por el servicio</b>: ahi es donde la sesion pasa a CERRADA, que es el unico estado que
	 * {@code RealizadoEnElCasoProbe} cuenta.
	 */
	private SesionView cerrarSesionDelCaso(
			Fixture fixture, long casoId, Asistencia asistencia) {

		long sesionId = insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, caso_id,
				                    oferta_id, profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						fixture.organizationId(), fixture.consultorioId(),
						fixture.historiaClinicaId(), casoId, fixture.ofertaId(),
						fixture.membershipId(), fixture.cuentaId()});

		SesionView actual = sesiones.ver(
				fixture.actorEncounter(), fixture.consultorioId(), sesionId);
		return sesiones.cerrar(fixture.actorEncounter(), fixture.consultorioId(), sesionId,
				new CierreDeSesion(asistencia, "Terapia manual", null, null, null, null),
				actual.version());
	}

	private int activosDelCaso(long casoId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM plan_tratamiento WHERE caso_clinico_id = ? AND estado = 'ACTIVO'
				""", Integer.class, casoId);
	}

	private int versionesDe(long planId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM plan_tratamiento_version WHERE plan_tratamiento_id = ?
				""", Integer.class, planId);
	}

	private List<String> tiposDeEvento(long planId) {
		return jdbc.queryForList("""
				SELECT tipo FROM plan_evento WHERE plan_tratamiento_id = ?
				 ORDER BY ocurrio_en ASC, id ASC
				""", String.class, planId);
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long cuentaId, long membershipId,
			long ofertaId, long personaId, long historiaClinicaId,
			OperatingActor actorClinico,
			com.akine.encounter.application.OperatingActor actorEncounter) {
	}

	/**
	 * Un centro con una sede, un profesional, una oferta vigente y un paciente con historia.
	 *
	 * <p>Hacen falta <b>dos</b> {@code OperatingActor} porque son dos clases distintas, una por
	 * modulo: {@code clinical} y {@code encounter} no comparten tipos de aplicacion, que es
	 * justamente lo que ArchUnit hace cumplir.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "plan-it-" + sufijo, ZONA});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, "Sede Sintetica " + sufijo, ZONA});

		String email = "plan-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});

		long membershipId = insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, cuentaId});

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default,
				                      genera_registro_clinico_default, active, version,
				                      created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"PLAN-" + sufijo.toUpperCase(), "Servicio " + sufijo});

		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', 0, 1, 1, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, servicioId, "Oferta " + sufijo});

		Fixture parcial = new Fixture(organizationId, consultorioId, cuentaId, membershipId,
				ofertaId, 0L, 0L,
				new OperatingActor(cuentaId, false, organizationId, consultorioId),
				new com.akine.encounter.application.OperatingActor(
						cuentaId, false, organizationId, consultorioId));

		long personaId = crearPaciente(parcial, "Paciente" + sufijo);
		long historiaClinicaId = abrirHistoria(parcial, personaId);

		return new Fixture(organizationId, consultorioId, cuentaId, membershipId, ofertaId,
				personaId, historiaClinicaId, parcial.actorClinico(), parcial.actorEncounter());
	}

	private long crearPaciente(Fixture fixture, String apellido) {
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						fixture.organizationId(), apellido, apellido.toUpperCase()});

		insertar("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{fixture.organizationId(), personaId, fixture.cuentaId()});
		return personaId;
	}

	private long abrirHistoria(Fixture fixture, long personaId) {
		return insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{fixture.organizationId(), personaId, fixture.cuentaId()});
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
