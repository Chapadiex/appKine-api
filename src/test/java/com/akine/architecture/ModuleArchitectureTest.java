package com.akine.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Reglas del monolito modular. Establecidas en AKINE-00.01 y vinculantes para toda etapa
 * posterior.
 *
 * <p>Estos tests son el unico mecanismo que impide que el monolito modular degenere en un
 * monolito acoplado. Cuando una regla estorbe, la respuesta correcta casi nunca es relajarla:
 * es que el diseno del cambio esta mal. Modificarlas exige una decision documentada en el
 * plan de implementacion.
 *
 * <p><b>Contrato entre modulos.</b> AGENT.md exige que el acceso entre modulos ocurra por
 * "servicio/puerto interno explicito", sin fijar donde vive ese puerto. AKINE-00.01 lo
 * resuelve: el paquete {@code spi} de cada modulo (Service Provider Interface) es lo unico
 * que los demas modulos pueden importar. Todo lo demas —api, application, domain,
 * infrastructure— es privado del modulo.
 *
 * <pre>
 *   com.akine.&lt;modulo&gt;.spi              &lt;- unico punto de entrada desde otros modulos
 *   com.akine.&lt;modulo&gt;.api              &lt;- HTTP: controllers y DTO
 *   com.akine.&lt;modulo&gt;.application      &lt;- reglas de negocio, transacciones
 *   com.akine.&lt;modulo&gt;.domain           &lt;- modelo y entities
 *   com.akine.&lt;modulo&gt;.infrastructure   &lt;- repositorios, config, adaptadores
 * </pre>
 */
@AnalyzeClasses(
		packages = ModuleArchitectureTest.ROOT,
		importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureTest {

	static final String ROOT = "com.akine";

	/** Paquetes que un modulo expone hacia afuera. Todo lo demas es privado. */
	private static final String PUBLIC_SLICE = "spi";

	// =================================================================================
	// Limites entre modulos
	// =================================================================================

	/**
	 * Sin ciclos entre modulos. Las dependencias son unidireccionales (AGENT.md sec. 4).
	 *
	 * <p>Un ciclo significa que dos modulos son en realidad uno solo mal separado, y que
	 * ninguno de los dos puede evolucionar ni testearse de forma aislada.
	 */
	@ArchTest
	static final ArchRule sin_ciclos_entre_modulos =
			SlicesRuleDefinition.slices()
					.matching(ROOT + ".(*)..")
					.should().beFreeOfCycles();

	/**
	 * Un modulo solo puede alcanzar a otro por su paquete {@code spi}.
	 *
	 * <p>Esto es la regla de ownership hecha ejecutable: si un modulo pudiera importar el
	 * repositorio o la entity de otro, dejaria de existir un propietario de esos datos.
	 */
	@ArchTest
	static final ArchRule modulos_solo_se_alcanzan_por_su_spi =
			classes()
					.that().resideInAPackage(ROOT + "..")
					.should(soloDependerDeOtrosModulosPorSuSpi())
					.because("cada modulo es propietario de su modelo y sus tablas: los demas "
							+ "acceden por su spi o por eventos posteriores al commit, nunca "
							+ "importando su repositorio, su entity o su servicio interno");

	// =================================================================================
	// Capas dentro de cada modulo
	// =================================================================================

	/**
	 * Direccion de las dependencias entre capas.
	 *
	 * <p>{@code domain} no depende de nada interno: es el centro. {@code infrastructure} y
	 * {@code api} son los bordes y nadie depende de ellos.
	 */
	@ArchTest
	static final ArchRule capas_respetan_su_direccion =
			layeredArchitecture().consideringOnlyDependenciesInLayers()
					.layer("API").definedBy(ROOT + "..api..")
					// Opcional: en AKINE-00.01 solo existe el modulo platform, que todavia no
					// ofrece nada a otros modulos. La capa aparece al primer contrato interno.
					.optionalLayer("SPI").definedBy(ROOT + "..spi..")
					.layer("Application").definedBy(ROOT + "..application..")
					.layer("Domain").definedBy(ROOT + "..domain..")
					.layer("Infrastructure").definedBy(ROOT + "..infrastructure..")

					.whereLayer("API").mayNotBeAccessedByAnyLayer()
					.whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
					.whereLayer("Application").mayOnlyBeAccessedByLayers("API", "SPI", "Infrastructure")
					.whereLayer("Domain").mayOnlyBeAccessedByLayers("API", "SPI", "Application", "Infrastructure");

	/**
	 * La capa {@code application} no conoce HTTP.
	 *
	 * <p>Si una regla de negocio necesita el request, la regla esta mal ubicada: lo que
	 * necesita es un parametro.
	 */
	@ArchTest
	static final ArchRule application_no_conoce_http =
			noClasses()
					.that().resideInAPackage(ROOT + "..application..")
					.should().dependOnClassesThat().resideInAnyPackage(
							"jakarta.servlet..",
							"org.springframework.web..",
							"org.springframework.http..")
					.because("las reglas de negocio no pueden depender del transporte: "
							+ "deben poder ejecutarse desde un job, un test o un consumidor "
							+ "de eventos, no solo desde un controller");

	/**
	 * El dominio no conoce el framework web ni la capa de presentacion.
	 *
	 * <p>Se permite {@code jakarta.persistence} porque las entities viven en {@code domain}
	 * (AGENT.md sec. 4).
	 */
	@ArchTest
	static final ArchRule domain_no_conoce_web =
			noClasses()
					.that().resideInAPackage(ROOT + "..domain..")
					.should().dependOnClassesThat().resideInAnyPackage(
							"jakarta.servlet..",
							"org.springframework.web..",
							"org.springframework.http..",
							ROOT + "..api..",
							ROOT + "..application..",
							ROOT + "..infrastructure..");

	// =================================================================================
	// Condicion de ownership entre modulos
	// =================================================================================

	private static ArchCondition<JavaClass> soloDependerDeOtrosModulosPorSuSpi() {
		return new ArchCondition<>("depender de otros modulos solo por su paquete " + PUBLIC_SLICE) {

			@Override
			public void check(JavaClass source, ConditionEvents events) {
				String sourceModule = moduloDe(source);
				if (sourceModule == null) {
					return; // clase raiz (la aplicacion Spring Boot): no pertenece a un modulo
				}

				source.getDirectDependenciesFromSelf().forEach(dependency -> {
					JavaClass target = dependency.getTargetClass();
					String targetModule = moduloDe(target);

					if (targetModule == null || targetModule.equals(sourceModule)) {
						return; // fuera de com.akine, o dentro del mismo modulo: permitido
					}

					if (!esPaquetePublico(target)) {
						events.add(SimpleConditionEvent.violated(source, String.format(
								"El modulo '%s' alcanza internals del modulo '%s': %s -> %s. "
										+ "Usar %s.%s.%s o un evento posterior al commit.",
								sourceModule, targetModule,
								source.getName(), target.getName(),
								ROOT, targetModule, PUBLIC_SLICE)));
					}
				});
			}
		};
	}

	/** Devuelve el nombre del modulo de una clase, o {@code null} si no vive en uno. */
	private static String moduloDe(JavaClass clazz) {
		String packageName = clazz.getPackageName();
		String prefix = ROOT + ".";
		if (!packageName.startsWith(prefix)) {
			return null;
		}
		String rest = packageName.substring(prefix.length());
		int firstDot = rest.indexOf('.');
		return firstDot < 0 ? rest : rest.substring(0, firstDot);
	}

	private static boolean esPaquetePublico(JavaClass clazz) {
		String packageName = clazz.getPackageName();
		return packageName.endsWith("." + PUBLIC_SLICE)
				|| packageName.contains("." + PUBLIC_SLICE + ".");
	}
}
