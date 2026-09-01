package com.akine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Gate de drift del contrato OpenAPI (AKINE-00.01).
 *
 * <p>El backend es propietario del contrato canonico. La estrategia adoptada es code-first
 * con contrato commiteado:
 *
 * <ul>
 *   <li>springdoc genera la especificacion desde los controllers anotados.</li>
 *   <li>El resultado vive versionado en {@code openapi/akine-api.yaml}.</li>
 *   <li>Este test compara lo generado contra lo commiteado y falla si difieren.</li>
 * </ul>
 *
 * <p>Consecuencia buscada: ningun cambio de API puede entrar sin que el diff del contrato
 * sea visible en el pull request. El frontend genera su cliente desde ese archivo, asi que
 * un contrato desactualizado significa un cliente desactualizado.
 *
 * <p>Para actualizar el contrato despues de cambiar la API:
 *
 * <pre>./mvnw verify -Dakine.contract.update=true</pre>
 *
 * <p>El orden de claves es determinista ({@code springdoc.writer-with-order-by-keys=true}):
 * sin eso, la comparacion daria falsos positivos en cada corrida.
 *
 * <p><b>Requiere Docker corriendo.</b>
 */
// El perfil de desarrollo es OBLIGATORIO aca, y por dos motivos que son el mismo arreglo:
// el secreto de firma ya no tiene default —sin un perfil de desarrollo activo la aplicacion no
// arranca— y la documentacion del contrato solo se publica en desarrollo, asi que sin el este
// test recibiria un 401 al pedir /v3/api-docs.yaml. Ver PerfilesDeEjecucion.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class OpenApiContractIT {

	/** Ruta del contrato canonico, relativa a la raiz del repositorio. */
	private static final Path CONTRATO = Path.of("openapi", "akine-api.yaml");

	private static final String FLAG_ACTUALIZAR = "akine.contract.update";

	private static final String COMANDO_ACTUALIZAR = "./mvnw verify -D" + FLAG_ACTUALIZAR + "=true";

	@Autowired
	private RestTestClient restTestClient;

	@Test
	@DisplayName("El contrato commiteado coincide con el que genera la implementacion")
	void el_contrato_no_tiene_drift() throws IOException {
		String generado = normalizar(descargarContrato());

		if (Boolean.getBoolean(FLAG_ACTUALIZAR)) {
			Files.createDirectories(CONTRATO.getParent());
			Files.writeString(CONTRATO, generado, StandardCharsets.UTF_8);
			System.out.println("[contrato] Regenerado: " + CONTRATO.toAbsolutePath());
			return;
		}

		if (!Files.exists(CONTRATO)) {
			fail("""
					No existe el contrato canonico en %s.

					Generalo con:
					    %s""".formatted(CONTRATO, COMANDO_ACTUALIZAR));
		}

		String commiteado = normalizar(Files.readString(CONTRATO, StandardCharsets.UTF_8));

		assertThat(generado)
				.as("""
						El contrato OpenAPI commiteado quedo desactualizado respecto de la \
						implementacion.

						Regeneralo y revisa el diff antes de commitear:
						    %s

						Si el cambio es incompatible, ademas hay que subir la version mayor de \
						akine.contract.version y coordinar la regeneracion del cliente en \
						appKine-web.""".formatted(COMANDO_ACTUALIZAR))
				.isEqualTo(commiteado);
	}

	@Test
	@DisplayName("El contrato declara su version SemVer y el endpoint tecnico")
	void el_contrato_declara_lo_esperado() {
		String contrato = descargarContrato();

		assertThat(contrato).contains("version:");
		assertThat(contrato).contains("/api/v1/version");
	}

	/**
	 * La version del contrato vive en DOS lugares y hasta AKINE-02.05 <b>nada verificaba que
	 * coincidieran</b>.
	 *
	 * <p>{@code pom.xml} declara la propiedad {@code akine.contract.version} y
	 * {@code application.yml} declara {@code akine.contract.version} otra vez, y es esta ultima
	 * la que springdoc publica en {@code info.version}. Bumpear una sola de las dos regenera el
	 * YAML con la version vieja y <b>el build pasa igual</b>: el gate de drift compara el
	 * contenido del contrato contra si mismo, y un contrato coherente con una version
	 * equivocada es exactamente igual de coherente.
	 *
	 * <p>El sintoma aparece rio abajo y es caro: el frontend fija su cliente contra "0.8.0",
	 * recibe un contrato que dice 0.8.0 y trae operaciones de 0.9.0, y la matriz de
	 * compatibilidad entre repos deja de significar nada. Ya se colo una vez.
	 *
	 * <p>Se lee el {@code pom.xml} del disco por la misma razon por la que este test ya lee
	 * {@code openapi/akine-api.yaml}: son los dos artefactos versionados que el gate compara, y
	 * filtrar la propiedad a un recurso para poder inyectarla agregaria configuracion de build
	 * para verificar la configuracion de build.
	 */
	@Test
	@DisplayName("La version del contrato coincide entre pom.xml y application.yml")
	void la_version_del_contrato_no_esta_bifurcada() throws IOException {
		String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
		Matcher enElPom = Pattern
				.compile("<akine\\.contract\\.version>([^<]+)</akine\\.contract\\.version>")
				.matcher(pom);
		assertThat(enElPom.find())
				.as("pom.xml tiene que declarar la propiedad akine.contract.version")
				.isTrue();

		String publicada = versionPublicada(descargarContrato());

		assertThat(publicada)
				.as("""
						La version del contrato esta bifurcada.

						pom.xml declara %s y el contrato publicado dice %s. Las dos salen de \
						propiedades distintas —pom.xml y application.yml— y nada mas las \
						compara: bumpear una sola regenera el YAML con la version vieja y el \
						gate de drift pasa igual, porque compara el contrato contra si mismo.

						Corregir application.yml (akine.contract.version) o pom.xml, y \
						regenerar:
						    %s""".formatted(enElPom.group(1), publicada, COMANDO_ACTUALIZAR))
				.isEqualTo(enElPom.group(1));
	}

	/**
	 * Ningun {@code operationId} puede venir desambiguado por springdoc.
	 *
	 * <p>Un {@code operationId} es un identificador <b>unico por documento</b>. Cuando dos metodos
	 * de controllers distintos se llaman igual —{@code ver} en Cobros y {@code ver} en
	 * Obligaciones—, springdoc no falla: publica el segundo como {@code ver_1} y sigue. El contrato
	 * queda valido, el build pasa, y el problema aparece dos repos mas abajo.
	 *
	 * <p><b>Lo que rompe rio abajo, y ya rompio.</b> El generador de TypeScript convierte ese
	 * sufijo en el nombre del metodo del cliente ({@code deLaPersona1}), asi que el frontend
	 * termina llamando a algo cuyo nombre no describe nada. Y no es estable: el numero se asigna
	 * por orden de aparicion, de modo que agregar una tercera operacion homonima —o renombrar
	 * cualquiera de las otras dos— se lo pasa a otra. Ese dia el frontend deja de compilar en un
	 * archivo que nadie toco, o peor, sigue compilando apuntando a la operacion equivocada.
	 *
	 * <p>Por eso el gate mira el sufijo y no la duplicacion: el sufijo es la huella que deja
	 * springdoc al resolver el choque solo, y es lo unico que sobrevive hasta el YAML.
	 */
	@Test
	@DisplayName("Ningun operationId quedo desambiguado con un sufijo numerico")
	void los_operation_id_son_unicos_sin_ayuda_del_generador() {
		Matcher desambiguado = Pattern
				.compile("^\\s*operationId: (\\S+_\\d+)$", Pattern.MULTILINE)
				.matcher(normalizar(descargarContrato()));

		StringBuilder encontrados = new StringBuilder();
		while (desambiguado.find()) {
			encontrados.append("\n    ").append(desambiguado.group(1));
		}

		assertThat(encontrados.toString())
				.as("""
						springdoc desambiguo uno o mas operationId agregandoles un sufijo \
						numerico, lo que significa que hay metodos de controller con el mismo \
						nombre en controllers distintos.%s

						Renombrar los metodos para que sean unicos en toda la API —por ejemplo \
						verCobro y verObligacion en vez de dos ver— y regenerar:
						    %s

						No alcanza con editar el YAML: springdoc lo vuelve a generar desde los \
						nombres de los metodos.""".formatted(encontrados, COMANDO_ACTUALIZAR))
				.isEmpty();
	}

	/**
	 * {@code info.version} del contrato generado.
	 *
	 * <p>Se busca dentro del bloque {@code info:} y no con un {@code contains} del numero: el
	 * mismo texto puede aparecer en un ejemplo o en una descripcion, y un test que pasa por
	 * casualidad es peor que ninguno.
	 */
	private static String versionPublicada(String contrato) {
		Matcher enElContrato = Pattern
				.compile("^info:$.*?^  version: \"?([^\"\\s]+)\"?$",
						Pattern.MULTILINE | Pattern.DOTALL)
				.matcher(normalizar(contrato));

		assertThat(enElContrato.find())
				.as("el contrato generado tiene que declarar info.version")
				.isTrue();
		return enElContrato.group(1);
	}

	private String descargarContrato() {
		byte[] cuerpo = restTestClient.get().uri("/v3/api-docs.yaml")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.returnResult()
				.getResponseBody();

		assertThat(cuerpo)
				.as("springdoc debe publicar el contrato en /v3/api-docs.yaml")
				.isNotNull();

		return new String(cuerpo, StandardCharsets.UTF_8);
	}

	/** Normaliza fin de linea y espacios finales: el gate compara contenido, no formato. */
	private static String normalizar(String contenido) {
		return contenido == null ? "" : contenido.replace("\r\n", "\n").strip() + "\n";
	}
}
