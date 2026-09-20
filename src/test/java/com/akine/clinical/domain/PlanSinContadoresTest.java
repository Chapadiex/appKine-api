package com.akine.clinical.domain;

import com.akine.clinical.api.dto.CrearPlanTratamientoRequest;
import com.akine.clinical.api.dto.ModificarPlanTratamientoRequest;
import com.akine.clinical.api.dto.PlanItemRequest;
import com.akine.clinical.api.dto.PlanItemResponse;
import com.akine.clinical.application.ContenidoDelPlan;
import com.akine.clinical.application.PlanItemPlanificado;
import com.akine.clinical.application.PlanItemView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La regla que ordena AKINE-04.04, verificada donde se puede romper: <b>planificado no es
 * realizado</b> (RN-M11-001).
 *
 * <h2>Por que este test existe y por que mira reflexion</h2>
 *
 * <p>El diseño no resuelve la regla con una validacion —una validacion se saca— sino con una
 * <b>ausencia</b>: no hay columna donde guardar lo realizado y no hay campo por donde recibirlo. Una
 * ausencia no se puede probar llamando a un metodo, porque el metodo que habria que llamar es
 * justamente el que no existe.
 *
 * <p>El defecto que esto impide volver a cometer es el mas barato de todos: alguien agrega
 * {@code cantidadRealizada} a un DTO "para que el frontend no tenga que pedir el avance aparte", la
 * migracion siguiente agrega la columna para poder guardarlo, y a partir de ahi el plan tiene una
 * segunda verdad sobre cuantas sesiones ocurrieron — mantenida por {@code clinical} y conocida por
 * {@code encounter}. Ese contador se desincroniza, y cuando se desincroniza nadie sabe cual de los
 * dos numeros es el bueno.
 *
 * <p>Si este test falla, <b>la respuesta correcta casi nunca es cambiarlo</b>: es que el campo que
 * se acaba de agregar no va ahi. Lo realizado se consulta en el avance, que lo deriva.
 */
@DisplayName("El plan no guarda ni recibe lo realizado (RN-M11-001)")
class PlanSinContadoresTest {

	/** Lo que ninguna de estas clases puede nombrar, en minusculas y sin acentos. */
	private static final List<String> PROHIBIDOS = List.of("realizad", "cancelad", "consumid");

	@Test
	@DisplayName("ninguna entidad del plan tiene una columna de realizadas o canceladas")
	void el_modelo_no_tiene_donde_guardarlo() {
		// Si existieran, su dueño real seria encounter —el unico que sabe cuando una sesion se
		// cerro— y clinical tendria una columna que solo otro modulo puede mantener correcta.
		sinCamposProhibidos(PlanItem.class);
		sinCamposProhibidos(PlanTratamiento.class);
		sinCamposProhibidos(PlanTratamientoVersion.class);
	}

	@Test
	@DisplayName("ningun DTO de entrada acepta un contador de realizadas")
	void la_api_no_tiene_por_donde_recibirlo() {
		// "Sin aceptar contadores realizados desde frontend" no depende de la disciplina de quien
		// escriba el proximo endpoint: no hay donde ponerlo.
		sinComponentesProhibidos(PlanItemRequest.class);
		sinComponentesProhibidos(CrearPlanTratamientoRequest.class);
		sinComponentesProhibidos(ModificarPlanTratamientoRequest.class);
	}

	@Test
	@DisplayName("tampoco la capa de aplicacion, ni la ficha que sale por la API")
	void ni_la_aplicacion_ni_la_ficha() {
		// La ficha del plan NO trae avance a proposito: si lo trajera, abrir un plan consultaria
		// sesiones en cada lectura y la ficha y el avance podrian discrepar sin que se note cual
		// de los dos vale. El avance tiene su propia operacion, y sus records SI nombran lo
		// realizado, que es donde corresponde.
		sinComponentesProhibidos(PlanItemPlanificado.class);
		sinComponentesProhibidos(ContenidoDelPlan.class);
		sinComponentesProhibidos(PlanItemView.class);
		sinComponentesProhibidos(PlanItemResponse.class);
	}

	@Test
	@DisplayName("el item nace siempre con origen DECLARADO y sin referencia a una autorizacion")
	void la_cantidad_autorizada_se_declara() {
		// La costura real con M17 es 04.05. Que el constructor no admita otro origen es lo que
		// impide que esta etapa quede con medio consumo de autorizaciones cableado.
		PlanItem item = new PlanItem(1L, 2L, 3L, 4L, "Kinesiologia", 5L, 20, 10);

		assertThat(item.getOrigenAutorizacion()).isEqualTo(OrigenCantidadAutorizada.DECLARADA);
		assertThat(item.getAutorizacionId()).isNull();
	}

	@Test
	@DisplayName("la cantidad planificada no puede ser cero ni negativa")
	void la_cantidad_planificada_es_positiva() {
		// Un item de cero sesiones no planifica nada y ensuciaria el avance con una practica que
		// esta completa desde que nace.
		assertThatThrownBy(() -> new PlanItem(1L, 2L, 3L, 4L, "Kinesiologia", 5L, 0, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static void sinCamposProhibidos(Class<?> clase) {
		for (Field campo : clase.getDeclaredFields()) {
			assertThat(prohibido(campo.getName()))
					.as("%s.%s nombra algo realizado: eso se deriva, no se guarda",
							clase.getSimpleName(), campo.getName())
					.isFalse();
		}
	}

	private static void sinComponentesProhibidos(Class<?> clase) {
		assertThat(clase.isRecord())
				.as("%s tendria que ser un record", clase.getSimpleName())
				.isTrue();
		for (RecordComponent componente : clase.getRecordComponents()) {
			assertThat(prohibido(componente.getName()))
					.as("%s.%s nombra algo realizado: eso se consulta en el avance",
							clase.getSimpleName(), componente.getName())
					.isFalse();
		}
	}

	private static boolean prohibido(String nombre) {
		String normalizado = nombre.toLowerCase(Locale.ROOT);
		return PROHIBIDOS.stream().anyMatch(normalizado::contains);
	}
}
