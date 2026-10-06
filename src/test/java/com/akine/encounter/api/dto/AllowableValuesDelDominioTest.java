package com.akine.encounter.api.dto;

import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.EstadoSesion;
import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ModoSesion;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Tolerancia;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Los {@code allowableValues} de los DTO de {@code encounter} tienen que ser EXACTAMENTE los
 * valores del enum de dominio que representan.
 *
 * <p>Springdoc publica esa lista tal cual, y es texto escrito a mano: el gate de drift no la
 * protege, porque compara el YAML contra lo que la anotacion genera. Asi fue como
 * {@code SesionResponse.estado} se publico con {@code [BORRADOR]} mientras el servicio devolvia
 * {@code CERRADA}: un cliente generado que tipa ese enum no puede representar una sesion cerrada.
 *
 * <p>El barrido es automatico sobre todo el paquete: un campo nuevo con {@code allowableValues}
 * y tipo enum se compara solo contra su tipo; uno de tipo {@code String} tiene que estar en
 * {@link #ENUM_DE_CADA_CAMPO_TEXTO} o el test falla pidiendo que se declare.
 */
class AllowableValuesDelDominioTest {

	/** Campos que viajan como {@code String} y el enum de dominio que los origina. */
	private static final Map<String, Class<? extends Enum<?>>> ENUM_DE_CADA_CAMPO_TEXTO = Map.ofEntries(
			Map.entry("SesionResponse.estado", EstadoSesion.class),
			Map.entry("CierreResponse.asistencia", Asistencia.class),
			Map.entry("CierreResponse.tolerancia", Tolerancia.class),
			Map.entry("CierreResponse.proximaConducta", ProximaConducta.class),
			Map.entry("EvaluacionResponse.modo", ModoSesion.class),
			Map.entry("EvaluacionResponse.dolorLateralidad", Lateralidad.class),
			Map.entry("EvaluacionResponse.evolucion", Evolucion.class),
			Map.entry("SesionVersionResponse.dolorLateralidad", Lateralidad.class),
			Map.entry("SesionVersionResponse.evolucion", Evolucion.class),
			Map.entry("SesionVersionResponse.tolerancia", Tolerancia.class),
			Map.entry("SesionVersionResponse.proximaConducta", ProximaConducta.class),
			Map.entry("TratamientoResponse.lateralidad", Lateralidad.class));

	@Test
	@DisplayName("Los allowableValues de encounter coinciden exactamente con su enum de dominio")
	void los_allowable_values_coinciden_con_el_dominio() {
		List<String> errores = new ArrayList<>();
		Set<String> usados = new HashSet<>();
		int verificados = 0;

		for (Class<?> dto : dtosDelPaquete()) {
			for (Field campo : dto.getDeclaredFields()) {
				Schema schema = campo.getAnnotation(Schema.class);
				if (schema == null || schema.allowableValues().length == 0) {
					continue;
				}
				String clave = dto.getSimpleName() + "." + campo.getName();
				Class<?> enumDelCampo = campo.getType().isEnum()
						? campo.getType()
						: ENUM_DE_CADA_CAMPO_TEXTO.get(clave);
				if (enumDelCampo == null) {
					errores.add(clave + " declara allowableValues sobre un String y no esta en "
							+ "ENUM_DE_CADA_CAMPO_TEXTO: declarar de que enum de dominio sale");
					continue;
				}
				usados.add(clave);
				verificados++;
				List<String> esperados = Arrays.stream(enumDelCampo.getEnumConstants())
						.map(valor -> ((Enum<?>) valor).name())
						.toList();
				List<String> publicados = List.of(schema.allowableValues());
				if (!publicados.equals(esperados)) {
					errores.add(clave + " publica " + publicados + " y " + enumDelCampo.getSimpleName()
							+ " tiene " + esperados);
				}
			}
		}

		Set<String> sobrantes = new HashSet<>(ENUM_DE_CADA_CAMPO_TEXTO.keySet());
		sobrantes.removeAll(usados);
		sobrantes.forEach(clave -> errores.add(clave + " esta en la tabla y ya no tiene allowableValues"));

		assertThat(verificados).as("el barrido tiene que encontrar campos").isPositive();
		if (!errores.isEmpty()) {
			fail(String.join("\n", errores));
		}
	}

	private static List<Class<?>> dtosDelPaquete() {
		var escaner = new ClassPathScanningCandidateComponentProvider(false);
		escaner.addIncludeFilter((lector, fabrica) -> true);
		String paquete = AllowableValuesDelDominioTest.class.getPackageName();
		return escaner.findCandidateComponents(paquete).stream()
				.<Class<?>>map(definicion -> {
					try {
						return Class.forName(definicion.getBeanClassName());
					}
					catch (ClassNotFoundException e) {
						throw new IllegalStateException(e);
					}
				})
				.filter(clase -> !clase.equals(AllowableValuesDelDominioTest.class))
				.toList();
	}
}
