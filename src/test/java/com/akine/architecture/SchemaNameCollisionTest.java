package com.akine.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos tipos de la capa {@code api} no pueden publicar el mismo nombre de schema.
 *
 * <p>springdoc nombra cada schema por el nombre <b>simple</b> de la clase, y cuando dos clases
 * distintas lo comparten se queda con una sola sin avisar. Paso con {@code IndicadorResponse} y
 * {@code SeccionResponse}: el contrato publicaba los de {@code person} tambien para los reportes,
 * el cliente generado describia campos que el servidor de reportes nunca manda, y ningun gate lo
 * vio. {@code OpenApiContractIT} no puede verlo: compara el contrato contra si mismo.
 *
 * <p>El nombre efectivo es el {@code @Schema(name)} cuando esta, o el nombre simple. Quedan afuera
 * los tipos que no viajan por HTTP ({@code ApiActor}, {@code ApiPaging}): son componentes de
 * Spring, no DTOs, y viven repetidos a proposito, uno por modulo.
 */
class SchemaNameCollisionTest {

	private static final Set<String> NO_SON_DTO = Set.of("ApiActor", "ApiPaging");

	@Test
	@DisplayName("Ningun nombre de schema de la capa api se repite entre clases distintas")
	void sin_colisiones() {
		var clases = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages(ModuleArchitectureTest.ROOT);

		Map<String, Set<String>> porNombre = new TreeMap<>();
		for (JavaClass clase : clases) {
			if (!clase.getPackageName().contains(".api") || clase.isAnonymousClass()
					|| clase.getSimpleName().isEmpty() || NO_SON_DTO.contains(clase.getSimpleName())
					|| !(clase.isRecord() || clase.isEnum())) {
				continue;
			}
			porNombre.computeIfAbsent(nombreDeSchema(clase), k -> new TreeSet<>())
					.add(clase.getFullName());
		}

		Map<String, Set<String>> repetidos = porNombre.entrySet().stream()
				.filter(e -> e.getValue().size() > 1)
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(repetidos)
				.as("Schemas con el mismo nombre: ponele @Schema(name = ...) propio a uno de ellos")
				.isEmpty();
	}

	private static String nombreDeSchema(JavaClass clase) {
		return clase.tryGetAnnotationOfType(Schema.class)
				.map(Schema::name)
				.filter(nombre -> !nombre.isBlank())
				.orElse(clase.getSimpleName());
	}
}
