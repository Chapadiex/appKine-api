package com.akine.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Convenciones de codigo verificables. Fijadas en AKINE-00.01.
 *
 * <p>Cada regla existe porque su violacion produce un bug concreto en AKINE, no por
 * preferencia estetica.
 */
@AnalyzeClasses(
		packages = ModuleArchitectureTest.ROOT,
		importOptions = ImportOption.DoNotIncludeTests.class)
class CodingConventionsTest {

	private static final String ROOT = ModuleArchitectureTest.ROOT;

	/** Los controllers son la capa HTTP: solo pueden vivir en {@code api}. */
	@ArchTest
	static final ArchRule controllers_solo_en_api =
			classes()
					.that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
					.or().areAnnotatedWith("org.springframework.stereotype.Controller")
					.should().resideInAPackage(ROOT + "..api..");

	/**
	 * Las entities JPA son modelo: solo pueden vivir en {@code domain}.
	 *
	 * <p>{@code allowEmptyShould} porque AKINE-00.01 no crea tablas funcionales y por lo
	 * tanto no hay ninguna entity todavia. La regla queda armada para la primera que exista.
	 */
	@ArchTest
	static final ArchRule entities_solo_en_domain =
			classes()
					.that().areAnnotatedWith("jakarta.persistence.Entity")
					.should().resideInAPackage(ROOT + "..domain..")
					.allowEmptyShould(true);

	/**
	 * Los repositorios son detalle de persistencia: solo pueden vivir en
	 * {@code infrastructure}.
	 *
	 * <p>{@code allowEmptyShould} por el mismo motivo que la regla anterior.
	 */
	@ArchTest
	static final ArchRule repositorios_solo_en_infrastructure =
			classes()
					.that().areAssignableTo("org.springframework.data.repository.Repository")
					.or().areAnnotatedWith("org.springframework.stereotype.Repository")
					.should().resideInAPackage(ROOT + "..infrastructure..")
					.allowEmptyShould(true);

	/**
	 * Ninguna entity puede salir hacia el cliente.
	 *
	 * <p>Serializar una entity filtra columnas internas, dispara lazy loading fuera de
	 * transaccion y ata el contrato HTTP al esquema de la base: cualquier cambio de columna
	 * pasa a ser un breaking change de API.
	 */
	@ArchTest
	static final ArchRule entities_no_salen_por_la_api =
			noClasses()
					.that().resideInAPackage(ROOT + "..api..")
					.should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
					.because("solo los DTO cruzan el borde del backend (AGENT.md sec. 4)");

	/**
	 * Sin inyeccion por campo.
	 *
	 * <p>La inyeccion por constructor deja las dependencias explicitas, permite campos
	 * {@code final} y hace imposible construir el objeto en un estado incompleto.
	 */
	@ArchTest
	static final ArchRule sin_inyeccion_por_campo =
			noFields()
					.should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
					.because("las dependencias van por constructor: explicitas, finales y "
							+ "verificables en tiempo de construccion");

	/**
	 * Importes en {@link java.math.BigDecimal}, nunca en punto flotante.
	 *
	 * <p>AKINE maneja obligaciones, cobros y caja. {@code double} no puede representar
	 * exactamente 0.1, y los errores de redondeo se acumulan hasta que una caja no cierra.
	 */
	@ArchTest
	static final ArchRule sin_punto_flotante_en_el_dominio =
			noFields()
					.that().areDeclaredInClassesThat().resideInAPackage(ROOT + "..domain..")
					.should().haveRawType(Double.class)
					.orShould().haveRawType(double.class)
					.orShould().haveRawType(Float.class)
					.orShould().haveRawType(float.class)
					.because("los importes usan BigDecimal/DECIMAL: el punto flotante "
							+ "acumula error de redondeo y rompe el cierre de caja");

	/**
	 * Sin {@code java.util.Date} ni {@code Calendar}.
	 *
	 * <p>Son mutables y sin zona horaria explicita. AKINE persiste instantes UTC y aplica
	 * reglas locales con una zona IANA explicita: eso exige {@code java.time}.
	 */
	@ArchTest
	static final ArchRule sin_apis_de_fecha_obsoletas =
			noClasses()
					.that().resideInAPackage(ROOT + "..")
					.should().dependOnClassesThat().haveFullyQualifiedName("java.util.Date")
					.orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Calendar")
					.orShould().dependOnClassesThat().haveFullyQualifiedName("java.sql.Date")
					.because("usar java.time: instantes UTC en la base y zona IANA explicita "
							+ "en las reglas locales");

	/**
	 * Nada de {@code System.out} ni {@code System.err}.
	 *
	 * <p>El logging es estructurado y con nivel. Un {@code println} no se puede filtrar,
	 * correlacionar por trace id ni silenciar, y es la via mas facil de filtrar un dato
	 * clinico a la consola.
	 */
	@ArchTest
	static final ArchRule sin_escribir_a_consola =
			noClasses()
					.should().accessField(System.class, "out")
					.orShould().accessField(System.class, "err")
					.because("el logging es estructurado (SLF4J), con nivel y trace id");

	/** Los servicios de aplicacion se anotan como tales, y viven en {@code application}. */
	@ArchTest
	static final ArchRule servicios_solo_en_application =
			classes()
					.that().areAnnotatedWith("org.springframework.stereotype.Service")
					.should().resideInAPackage(ROOT + "..application..");

	/** Los DTO expuestos por la API viven en {@code api.dto}, no sueltos. */
	@ArchTest
	static final ArchRule dtos_agrupados =
			classes()
					.that().haveSimpleNameEndingWith("Response")
					.or().haveSimpleNameEndingWith("Request")
					.should().resideInAPackage(ROOT + "..api.dto..");

	/** Las clases de configuracion de Spring son infraestructura. */
	@ArchTest
	static final ArchRule configuracion_en_infrastructure =
			classes()
					.that().areAnnotatedWith("org.springframework.context.annotation.Configuration")
					.should().resideInAPackage(ROOT + "..infrastructure..");

	/** Los campos de configuracion inyectados deben ser finales (van por constructor). */
	@ArchTest
	static final ArchRule campos_de_servicios_son_finales =
			fields()
					.that().areDeclaredInClassesThat().areAnnotatedWith("org.springframework.stereotype.Service")
					.and().areNotStatic()
					.should().beFinal()
					.because("un servicio con estado mutable no es seguro entre hilos: "
							+ "Spring lo instancia una sola vez y lo comparte");
}
