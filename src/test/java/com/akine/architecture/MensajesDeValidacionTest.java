package com.akine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.platform.spi.listado.TextoDeBusqueda;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El {@code errors} de un 400 de validacion se muestra tal cual: cada mensaje tiene que decir por si
 * solo que campo falla.
 *
 * <p>El mensaje por defecto de Bean Validation no lo dice ("no debe estar vacio") y ademas sigue el
 * {@code Accept-Language}: el mismo error salia en castellano o en ingles segun el navegador. Por eso
 * toda propiedad con restricciones de la capa {@code api} declara un {@code message} propio.
 *
 * <p>Lee la metadata del validador y no el codigo fuente, asi que ve lo mismo que se ejecuta,
 * incluidas las restricciones de elementos como {@code List<@NotNull Long>}.
 *
 * <p><b>Limites:</b> "nombra el campo" se comprueba con una raiz de cualquiera de sus palabras.
 * Detecta el mensaje que no menciona el campo, no el que lo menciona mal ni el que solo nombra la
 * parte generica de un campo compuesto. Tampoco mira los parametros de un controller
 * ({@code @Min} en un {@code @RequestParam}), que no llegan a {@code errors}.
 */
class MensajesDeValidacionTest {

	/** Palabras de un nombre de campo que no describen nada. */
	private static final Set<String> SIN_SIGNIFICADO = Set.of("id", "ids");

	/** Los campos en ingles del contrato, y la palabra con la que los nombran los mensajes. */
	private static final Map<String, List<String>> GLOSARIO = Map.ofEntries(
			Map.entry("account", List.of("cuenta")),
			Map.entry("address", List.of("direccion")),
			Map.entry("duration", List.of("duracion")),
			Map.entry("email", List.of("correo")),
			Map.entry("last", List.of("apellido")),
			Map.entry("legal", List.of("razon")),
			Map.entry("name", List.of("nombre", "denominacion")),
			Map.entry("notes", List.of("observacion")),
			Map.entry("password", List.of("contrasena")),
			Map.entry("phone", List.of("telefono")),
			Map.entry("reason", List.of("motivo")),
			Map.entry("role", List.of("rol")),
			Map.entry("status", List.of("estado")),
			Map.entry("tax", List.of("identificacion")),
			Map.entry("timezone", List.of("zona")));

	private static final int LARGO_DE_RAIZ = 4;

	private static final Pattern IDENTIFICADOR = Pattern.compile("\\b\\p{Ll}+\\p{Lu}\\w*\\b");

	private static final ValidatorFactory FABRICA = Validation.buildDefaultValidatorFactory();

	private static final Validator VALIDADOR = FABRICA.getValidator();

	@AfterAll
	static void cerrar() {
		FABRICA.close();
	}

	@Test
	@DisplayName("Toda restriccion de la capa api nombra su campo en un mensaje propio")
	void toda_restriccion_nombra_su_campo() {
		List<Class<?>> clases = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.withImportOption(ubicacion -> ubicacion.contains("/api/"))
				.importPackages(ModuleArchitectureTest.ROOT)
				.stream()
				.<Class<?>>map(JavaClass::reflect)
				.toList();

		assertThat(clases).isNotEmpty();
		assertThat(clases.stream().flatMap(clase -> problemasDe(clase).stream()).sorted()).isEmpty();
	}

	@Test
	@DisplayName("La regla rechaza el mensaje por defecto, la minuscula, el identificador y el que no nombra")
	void la_regla_rechaza_cada_forma_de_no_nombrar() {
		assertThat(problemasDe(SinMensaje.class)).singleElement().asString().contains("por defecto");
		assertThat(problemasDe(EnMinuscula.class)).singleElement().asString().contains("minuscula");
		assertThat(problemasDe(ConIdentificador.class)).singleElement().asString().contains("identificador");
		assertThat(problemasDe(QueNoNombra.class)).singleElement().asString().contains("no nombra");
		assertThat(problemasDe(ElementoSinMensaje.class)).singleElement().asString().contains("por defecto");
	}

	@Test
	@DisplayName("La regla acepta el mensaje que nombra el campo en castellano")
	void la_regla_acepta_el_mensaje_correcto() {
		assertThat(problemasDe(Correcto.class)).isEmpty();
	}

	private static List<String> problemasDe(Class<?> clase) {
		return VALIDADOR.getConstraintsForClass(clase).getConstrainedProperties().stream()
				.flatMap(propiedad -> restriccionesDe(propiedad)
						.flatMap(restriccion -> problemaDe(clase, propiedad.getPropertyName(), restriccion)))
				.toList();
	}

	private static <D extends ElementDescriptor & ContainerDescriptor> Stream<ConstraintDescriptor<?>> restriccionesDe(
			D descriptor) {
		return Stream.concat(
				descriptor.getConstraintDescriptors().stream(),
				descriptor.getConstrainedContainerElementTypes().stream()
						.flatMap(MensajesDeValidacionTest::restriccionesDe));
	}

	private static Stream<String> problemaDe(Class<?> clase, String campo, ConstraintDescriptor<?> restriccion) {
		String mensaje = restriccion.getMessageTemplate();
		String defecto = defectoDe(mensaje, campo);
		return defecto == null ? Stream.empty() : Stream.of("%s.%s @%s: %s: \"%s\"".formatted(clase.getName(), campo,
				restriccion.getAnnotation().annotationType().getSimpleName(), defecto, mensaje));
	}

	private static String defectoDe(String mensaje, String campo) {
		if (mensaje.startsWith("{")) {
			return "usa el mensaje por defecto";
		}
		if (!Character.isUpperCase(mensaje.charAt(0))) {
			return "empieza en minuscula";
		}
		if (IDENTIFICADOR.matcher(mensaje).find()) {
			return "muestra un identificador";
		}
		return nombra(mensaje, campo) ? null : "no nombra el campo";
	}

	private static boolean nombra(String mensaje, String campo) {
		List<String> palabras = Arrays.asList(TextoDeBusqueda.plegar(mensaje).split("[^a-z0-9]+"));
		List<String> partes = Arrays.stream(campo.split("(?=\\p{Lu})"))
				.map(TextoDeBusqueda::plegar)
				.filter(parte -> !SIN_SIGNIFICADO.contains(parte))
				.toList();
		// Un campo que se llama solo "ids" no tiene palabra que buscar: lo nombra su contexto.
		return partes.isEmpty() || partes.stream()
				.flatMap(parte -> Stream.concat(Stream.of(parte), GLOSARIO.getOrDefault(parte, List.of()).stream()))
				.map(parte -> parte.substring(0, Math.min(LARGO_DE_RAIZ, parte.length())))
				.anyMatch(raiz -> palabras.stream().anyMatch(palabra -> palabra.startsWith(raiz)));
	}

	record SinMensaje(@NotBlank String motivo) {
	}

	record EnMinuscula(@NotNull(message = "hasta es obligatorio") String hasta) {
	}

	record ConIdentificador(@NotNull(message = "El expectedVersion es obligatorio") Long expectedVersion) {
	}

	record QueNoNombra(@Positive(message = "Debe ser mayor que cero") Long profesionalId) {
	}

	record ElementoSinMensaje(List<@NotNull Long> dias) {
	}

	record Correcto(
			@NotNull(message = "El profesional es obligatorio") Long profesionalId,
			List<@NotNull(message = "Los dias no pueden tener valores vacios") Long> dias) {
	}
}
