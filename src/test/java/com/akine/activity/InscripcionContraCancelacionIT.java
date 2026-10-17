package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.CarreraDirigida.Retencion;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ResultadoDeInscripcion;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Inscribir mientras se cancela la clase</b> (AKINE-09.03, carrera de F9 sin IT hasta ahora).
 *
 * <p>{@code InscripcionService.inscribir} lee la clase <b>sin lock</b> para validar que esta
 * {@code PROGRAMADA}, y recien despues pide el lugar con el {@code UPDATE} condicional, que si
 * filtra {@code estado = 'PROGRAMADA'}. Si la cancelacion commitea en el medio, el {@code UPDATE}
 * devuelve cero filas —que el servicio leia como "no hay lugar"— y con la lista de espera aceptada
 * la persona quedaba <b>en la cola de una clase cancelada</b>: una inscripcion viva que nadie avisa
 * y que ningun cierre ni promocion va a resolver nunca.
 *
 * <p>Lo que se exige, gane quien gane: ninguna inscripcion viva en una clase cancelada, el contador
 * en cero, y el perdedor del lado de la inscripcion recibe {@code clase-transicion-no-permitida}
 * —"esta cancelada"—, no {@code clase-completa}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class InscripcionContraCancelacionIT {

	/** La sentencia de {@code tomarCupo}: si esta en curso con la fila retenida, esta esperando. */
	private static final String TOMAR_CUPO = "%cupo_ocupado = cupo_ocupado + 1%";
	private static final String BLOQUEAR_CLASE = "%FROM clase_programada%FOR UPDATE%";

	@Autowired private InscripcionService inscripcionService;
	@Autowired private ClaseService claseService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@ParameterizedTest(name = "acepta lista de espera = {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("La cancelacion commitea entre la lectura y el UPDATE de la inscripcion: nadie queda vivo en la clase cancelada")
	void cancelacion_entre_la_lectura_y_el_cupo(boolean aceptaEspera) {
		Tenant tenant = fx.crearTenant(10, 3);
		long clase = fx.programarClase(tenant, 2, 1);
		// La clase ya esta llena: sin la cancelacion, la tercera persona iria a la cola o seria
		// clase-completa. Es el caso donde el cero del UPDATE se confunde con "no hay lugar".
		fx.inscribir(tenant, clase, tenant.persona(0), false);
		fx.inscribir(tenant, clase, tenant.persona(1), false);

		Desenlace<ClaseView> cancelacion;
		Desenlace<ResultadoDeInscripcion> inscripcion;
		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion fila = Retencion.de(dataSource, "clase_programada", clase)) {
			// Primero la cancelacion, en la cola del lock; despues la inscripcion, que ya leyo la
			// clase PROGRAMADA y espera en el UPDATE del cupo. Al soltar, la cancelacion es la
			// primera en la cola.
			Future<Desenlace<ClaseView>> cancelar = carrera.largarHastaBloquearse(
					() -> claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Lluvia"),
					BLOQUEAR_CLASE);
			Future<Desenlace<ResultadoDeInscripcion>> inscribir = carrera.largarHastaBloquearse(
					() -> inscripcionService.inscribir(tenant.actor(), tenant.consultorioId(), clase,
							new InscribirCommand(tenant.persona(2), aceptaEspera, null)),
					TOMAR_CUPO);
			fila.soltar();
			cancelacion = CarreraDirigida.resultado(cancelar);
			inscripcion = CarreraDirigida.resultado(inscribir);
		}

		assertThat(cancelacion.fallo()).as("la cancelacion no falla: %s", cancelacion).isFalse();
		assertThat(estadoDeLaClase(clase)).isEqualTo("CANCELADA");
		assertThat(vivas(clase))
				.as("ninguna inscripcion viva en una clase cancelada (inscripcion: %s)", inscripcion)
				.isZero();
		assertThat(fx.cupoOcupado(clase)).isZero();
		if (inscripcion.fallo()) {
			assertThat(ActividadItFixture.causaEs(inscripcion.error(), TransicionDeClaseNoPermitidaException.class))
					.as("el perdedor sabe que la clase esta cancelada, no que esta completa: %s", inscripcion)
					.isTrue();
		}
	}

	@Test
	@DisplayName("Rafaga: altas con y sin espera contra la cancelacion de la clase, cinco rondas, siempre sin inscripciones vivas")
	void rafaga_de_altas_contra_la_cancelacion() {
		for (int ronda = 0; ronda < 5; ronda++) {
			Tenant tenant = fx.crearTenant(10, 6);
			long clase = fx.programarClase(tenant, 3, 1 + ronda);
			List<Callable<Object>> tareas = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				long persona = tenant.persona(i);
				boolean espera = i % 2 == 0;
				tareas.add(() -> inscripcionService.inscribir(tenant.actor(), tenant.consultorioId(), clase,
						new InscribirCommand(persona, espera, null)));
			}
			tareas.add(() -> claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Sin profe"));

			List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(tareas);

			assertThat(desenlaces.get(6).fallo()).as("ronda %d: %s", ronda, desenlaces).isFalse();
			assertThat(vivas(clase)).as("ronda %d: %s", ronda, desenlaces).isZero();
			assertThat(fx.cupoOcupado(clase)).as("ronda %d", ronda).isZero();
		}
	}

	private int vivas(long clase) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM inscripcion_clase
				 WHERE clase_id = ? AND estado NOT IN ('CANCELADA')
				""", Integer.class, clase);
	}

	private String estadoDeLaClase(long clase) {
		return jdbc.queryForObject("SELECT estado FROM clase_programada WHERE id = ?", String.class, clase);
	}
}
