package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Datos que declara el paciente (DP-47) por HTTP real y contra PostgreSQL.
 *
 * <p>Una secretaria ({@code ADMINISTRATIVO}, con {@code hc:declarar} y sin {@code hc:read}) y un
 * {@code PROFESIONAL} de la misma sede. El profesional entra por el alta self-service, como en
 * {@code HistoriaClinicaApiIT}; la secretaria se registra aparte y se le agrega por SQL una
 * membership en la sede del profesional, que es lo que en produccion haria una invitacion.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "akine.security.rate-limit.enabled=false")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DatosDeclaradosIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final String JUSTIFICACION = "X-Justificacion-Acceso";
	private static final String MOTIVO = "Carga de datos que declara el paciente";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort private int puerto;
	@Autowired private DataSource dataSource;

	private JdbcTemplate jdbc;
	private HttpClient http;

	@BeforeEach
	void preparar() {
		jdbc = new JdbcTemplate(dataSource);
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	// =================================================================================
	// Migracion y CHECK
	// =================================================================================

	@Test
	@DisplayName("V16: un antecedente PROFESIONAL no admite revision (CHECK)")
	void checkImpideRevisionEnDatoProfesional() {
		Centro c = crearCentro("check");
		long personaId = crearPaciente(c.profesional());
		assertThat(put(hc(personaId), c.profesional().token(), null).status()).isEqualTo(200);
		Respuesta alta = post(hc(personaId) + "/antecedentes", c.profesional().token(),
				"{\"tipo\":\"ALERGIA\",\"descripcion\":\"Penicilina\"}");
		assertThat(alta.status()).as(alta.body()).isEqualTo(201);
		long id = alta.json().get("id").asLong();

		assertThat(jdbc.queryForObject(
				"SELECT origen FROM historia_clinica_antecedente WHERE id = ?", String.class, id))
				.isEqualTo("PROFESIONAL");
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE historia_clinica_antecedente
				   SET revisado_por = 1, revisado_en = CURRENT_TIMESTAMP WHERE id = ?
				""", id))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_hc_antecedente_revision_solo_declarado");
	}

	// =================================================================================
	// Declarar y ver
	// =================================================================================

	@Test
	@DisplayName("la secretaria declara sin historia: la abre y ve solo lo declarado")
	void secretariaDeclaraSinHistoria_laAbreYVeSoloLoDeclarado() {
		Centro c = crearCentro("ver");
		long personaId = crearPaciente(c.profesional());

		Respuesta vacio = get(declarados(personaId), c.secretaria().token());
		assertThat(vacio.status()).as(vacio.body()).isEqualTo(200);
		assertThat(vacio.json().path("historiaClinicaId").isValueNode()).as("sin historia no viaja id").isFalse();
		assertThat(vacio.json().path("antecedentes").size()).isZero();
		assertThat(historiasDe(personaId)).as("ver no abre la historia").isZero();

		Respuesta declarado = declararAntecedente(c, personaId, "Alergia a la penicilina");
		assertThat(declarado.status()).as(declarado.body()).isEqualTo(201);
		assertThat(declarado.json().get("origen").asString()).isEqualTo("DECLARADO");
		assertThat(historiasDe(personaId)).as("la primera declaracion abre la historia").isEqualTo(1);

		Respuesta delProfesional = post(hc(personaId) + "/antecedentes", c.profesional().token(),
				"{\"tipo\":\"QUIRURGICO\",\"descripcion\":\"Artroscopia\"}");
		assertThat(delProfesional.status()).as(delProfesional.body()).isEqualTo(201);

		Respuesta lista = get(declarados(personaId), c.secretaria().token());
		assertThat(lista.status()).as(lista.body()).isEqualTo(200);
		assertThat(lista.json().get("historiaClinicaId").isNull()).isFalse();
		assertThat(lista.json().get("antecedentes").size())
				.as("lo del profesional no aparece por la pestaña de declarados").isEqualTo(1);
		assertThat(lista.body()).doesNotContain("Artroscopia");
	}

	@Test
	@DisplayName("hc:declarar no abre ninguna lectura de la historia: 403")
	void secretariaNoLeeLaHistoria() {
		Centro c = crearCentro("noleer");
		long personaId = crearPaciente(c.profesional());
		assertThat(declararAntecedente(c, personaId, "Asma").status()).isEqualTo(201);

		assertThat(get(hc(personaId), c.secretaria().token()).status())
				.as("ver la historia").isEqualTo(403);
		assertThat(get(hc(personaId) + "/antecedentes", c.secretaria().token()).status())
				.as("listar antecedentes").isEqualTo(403);
	}

	@Test
	@DisplayName("persona sin perfil de paciente: 409 y no se abre historia")
	void personaSinPerfilPaciente_409() {
		Centro c = crearCentro("sinperfil");
		long personaId = crearPersonaSinPerfil(c.profesional());

		Respuesta r = declararAntecedente(c, personaId, "Alergia");
		assertThat(r.status()).as(r.body()).isEqualTo(409);
		assertThat(tipo(r)).endsWith("persona-sin-perfil-paciente");
		assertThat(historiasDe(personaId)).isZero();
	}

	@Test
	@DisplayName("persona de otra organizacion: 404")
	void otraOrganizacion_404() {
		Centro c = crearCentro("orga");
		Usuario otro = crearTenant("orgb", "PROFESIONAL");
		long personaAjena = crearPaciente(otro);

		assertThat(get(declarados(personaAjena), c.secretaria().token()).status()).isEqualTo(404);
		assertThat(declararAntecedente(c, personaAjena, "intruso").status()).isEqualTo(404);
		assertThat(historiasDe(personaAjena)).isZero();
	}

	// =================================================================================
	// Adjuntos
	// =================================================================================

	@Test
	@DisplayName("un adjunto del profesional no se abre ni se da de baja por declarados: 404")
	void adjuntoDelProfesional_invisibleParaSecretaria() {
		Centro c = crearCentro("invisible");
		long personaId = crearPaciente(c.profesional());
		long historiaId = abrirHistoria(c, personaId);
		Respuesta subida = subirComoProfesional(c, historiaId, pdf("rmn del profesional"));
		assertThat(subida.status()).as(subida.body()).isEqualTo(201);
		long adjuntoId = subida.json().get("id").asLong();

		assertThat(get(declarados(personaId) + "/adjuntos/" + adjuntoId + "/contenido",
				c.secretaria().token()).status()).as("abrir").isEqualTo(404);
		assertThat(post(declarados(personaId) + "/adjuntos/" + adjuntoId + "/baja",
				c.secretaria().token(), "{\"motivo\":\"error\"}").status()).as("baja").isEqualTo(404);
		assertThat(jdbc.queryForObject("SELECT active FROM adjunto_clinico WHERE id = ?",
				Boolean.class, adjuntoId)).isTrue();
	}

	@Test
	@DisplayName("declarar un archivo que ya cargo el profesional: 409 sin datos del existente")
	void mismoArchivo_queCargoElProfesional_409SinDatos() {
		Centro c = crearCentro("dupprof");
		long personaId = crearPaciente(c.profesional());
		long historiaId = abrirHistoria(c, personaId);
		byte[] archivo = pdf("estudio compartido");
		Respuesta subida = subirComoProfesional(c, historiaId, archivo);
		assertThat(subida.status()).as(subida.body()).isEqualTo(201);
		String idExistente = subida.json().get("id").asString();

		Respuesta r = declararAdjunto(c, personaId, archivo, "ESTUDIO", "RX que trajo");
		assertThat(r.status()).as(r.body()).isEqualTo(409);
		assertThat(tipo(r)).endsWith("adjunto-ya-en-historia");
		assertThat(r.body()).doesNotContain("Titulo del profesional").doesNotContain("\"id\":" + idExistente);
	}

	@Test
	@DisplayName("declarar dos veces el mismo archivo devuelve el mismo adjunto")
	void mismoArchivoDeclaradoDosVeces_idempotente() {
		Centro c = crearCentro("dupdecl");
		long personaId = crearPaciente(c.profesional());
		byte[] archivo = pdf("informe traido");

		Respuesta primera = declararAdjunto(c, personaId, archivo, "INFORME", "Informe");
		Respuesta segunda = declararAdjunto(c, personaId, archivo, "INFORME", "Informe");

		assertThat(primera.status()).as(primera.body()).isEqualTo(201);
		assertThat(segunda.status()).as(segunda.body()).isEqualTo(200);
		assertThat(segunda.json().get("id").asLong()).isEqualTo(primera.json().get("id").asLong());
		assertThat(primera.json().get("origen").asString()).isEqualTo("DECLARADO");
	}

	@Test
	@DisplayName("una categoria del profesional no se declara: 400")
	void categoriaNoDeclarable_400() {
		Centro c = crearCentro("categoria");
		long personaId = crearPaciente(c.profesional());

		Respuesta r = declararAdjunto(c, personaId, pdf("consentimiento"), "CONSENTIMIENTO_CLINICO", "Firma");
		assertThat(r.status()).as(r.body()).isEqualTo(400);
	}

	@Test
	@DisplayName("la secretaria abre su archivo declarado, antes y despues de la revision")
	void secretariaAbreSuArchivoDeclarado_revisadoONo() {
		Centro c = crearCentro("abrir");
		long personaId = crearPaciente(c.profesional());
		byte[] archivo = pdf("rx de rodilla");
		Respuesta alta = declararAdjunto(c, personaId, archivo, "IMAGEN", "RX rodilla");
		assertThat(alta.status()).as(alta.body()).isEqualTo(201);
		long adjuntoId = alta.json().get("id").asLong();
		long historiaId = alta.json().get("historiaClinicaId").asLong();
		String contenido = declarados(personaId) + "/adjuntos/" + adjuntoId + "/contenido";

		Respuesta antes = get(contenido, c.secretaria().token());
		assertThat(antes.status()).as(antes.body()).isEqualTo(200);
		assertThat(antes.body()).startsWith("%PDF-");

		assertThat(put("/api/v1/historias-clinicas/" + historiaId + "/adjuntos/" + adjuntoId + "/revision",
				c.profesional().token(), null).status()).isEqualTo(200);

		Respuesta despues = get(contenido, c.secretaria().token());
		assertThat(despues.status()).as(despues.body()).isEqualTo(200);
		assertThat(despues.body()).startsWith("%PDF-");
	}

	@Test
	@DisplayName("un archivo declarado dado de baja no se abre por id: 404")
	void archivoDeclaradoDadoDeBaja_404() {
		Centro c = crearCentro("abrirbaja");
		long personaId = crearPaciente(c.profesional());
		Respuesta alta = declararAdjunto(c, personaId, pdf("estudio equivocado"), "ESTUDIO", "Equivocado");
		assertThat(alta.status()).as(alta.body()).isEqualTo(201);
		long adjuntoId = alta.json().get("id").asLong();

		assertThat(post(declarados(personaId) + "/adjuntos/" + adjuntoId + "/baja",
				c.secretaria().token(), "{\"motivo\":\"se subio el archivo equivocado\"}").status())
				.isEqualTo(204);

		Respuesta r = get(declarados(personaId) + "/adjuntos/" + adjuntoId + "/contenido", c.secretaria().token());
		assertThat(r.status()).as(r.body()).isEqualTo(404);
	}

	// =================================================================================
	// Baja y revision
	// =================================================================================

	@Test
	@DisplayName("dar de baja un declarado ya revisado: 409")
	void bajaDeRevisado_409() {
		Centro c = crearCentro("bajarev");
		long personaId = crearPaciente(c.profesional());
		long id = declararAntecedente(c, personaId, "Hipertension").json().get("id").asLong();

		assertThat(revisarAntecedente(c, personaId, id).status()).isEqualTo(200);

		Respuesta r = post(declarados(personaId) + "/antecedentes/" + id + "/baja",
				c.secretaria().token(), "{\"motivo\":\"me equivoque\"}");
		assertThat(r.status()).as(r.body()).isEqualTo(409);
		assertThat(tipo(r)).endsWith("dato-declarado-revisado");
	}

	@Test
	@DisplayName("dar de baja un declarado sin revisar: 204 y deja de aparecer")
	void bajaDeSinRevisar_204() {
		Centro c = crearCentro("baja");
		long personaId = crearPaciente(c.profesional());
		long id = declararAntecedente(c, personaId, "Diabetes").json().get("id").asLong();

		Respuesta r = post(declarados(personaId) + "/antecedentes/" + id + "/baja",
				c.secretaria().token(), "{\"motivo\":\"era de otro paciente\"}");
		assertThat(r.status()).as(r.body()).isEqualTo(204);
		assertThat(get(declarados(personaId), c.secretaria().token()).json().get("antecedentes").size())
				.isZero();
	}

	@Test
	@DisplayName("revisar dos veces es idempotente: la version avanza una vez y hay un solo evento")
	void revisarDosVeces_idempotente() {
		Centro c = crearCentro("revdos");
		long personaId = crearPaciente(c.profesional());
		long id = declararAntecedente(c, personaId, "Alergia al latex").json().get("id").asLong();
		long versionInicial = versionDeAntecedente(id);

		Respuesta primera = revisarAntecedente(c, personaId, id);
		Respuesta segunda = revisarAntecedente(c, personaId, id);

		assertThat(primera.status()).as(primera.body()).isEqualTo(200);
		assertThat(segunda.status()).as(segunda.body()).isEqualTo(200);
		assertThat(segunda.json().get("revisadoEn").asString())
				.isEqualTo(primera.json().get("revisadoEn").asString());
		assertThat(primera.json().get("revisadoPorNombre").isNull()).isFalse();
		assertThat(versionDeAntecedente(id)).isEqualTo(versionInicial + 1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'DATO_DECLARADO_REVISADO'
				""", Integer.class, c.profesional().organizationId()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("revisar un dato del profesional: 409")
	void revisarDatoProfesional_409() {
		Centro c = crearCentro("revprof");
		long personaId = crearPaciente(c.profesional());
		abrirHistoria(c, personaId);
		long id = post(hc(personaId) + "/antecedentes", c.profesional().token(),
				"{\"tipo\":\"MEDICO\",\"descripcion\":\"Lumbalgia\"}").json().get("id").asLong();

		Respuesta r = revisarAntecedente(c, personaId, id);
		assertThat(r.status()).as(r.body()).isEqualTo(409);
		assertThat(tipo(r)).endsWith("dato-no-declarado");
	}

	@Test
	@DisplayName("la historia informa cuantos declarados quedan sin revisar")
	void historiaInformaDeclaradosSinRevisar() {
		Centro c = crearCentro("conteo");
		long personaId = crearPaciente(c.profesional());
		long id = declararAntecedente(c, personaId, "Alergia").json().get("id").asLong();
		assertThat(declararAdjunto(c, personaId, pdf("estudio conteo"), "ESTUDIO", "Estudio").status())
				.isEqualTo(201);

		assertThat(get(hc(personaId), c.profesional().token()).json().get("declaradosSinRevisar").asLong())
				.isEqualTo(2);
		assertThat(revisarAntecedente(c, personaId, id).status()).isEqualTo(200);
		assertThat(get(hc(personaId), c.profesional().token()).json().get("declaradosSinRevisar").asLong())
				.isEqualTo(1);
	}

	// =================================================================================
	// Fixture: centro con profesional y secretaria, pacientes y HTTP
	// =================================================================================

	private record Usuario(long organizationId, long consultorioId, long cuentaId, String token) {
	}

	private record Centro(Usuario profesional, Usuario secretaria) {
	}

	private record Respuesta(int status, String body) {

		JsonNode json() {
			return JSON.readTree(body);
		}
	}

	private static String hc(long personaId) {
		return "/api/v1/historias-clinicas/por-persona/" + personaId;
	}

	private static String declarados(long personaId) {
		return "/api/v1/personas/" + personaId + "/datos-declarados";
	}

	private static String tipo(Respuesta r) {
		return r.json().get("type").asString();
	}

	private Respuesta declararAntecedente(Centro c, long personaId, String descripcion) {
		return post(declarados(personaId) + "/antecedentes", c.secretaria().token(),
				"{\"tipo\":\"ALERGIA\",\"descripcion\":\"" + descripcion + "\"}");
	}

	private Respuesta revisarAntecedente(Centro c, long personaId, long antecedenteId) {
		return put(hc(personaId) + "/antecedentes/" + antecedenteId + "/revision",
				c.profesional().token(), null);
	}

	private Respuesta declararAdjunto(Centro c, long personaId, byte[] archivo, String categoria, String titulo) {
		return multipart(declarados(personaId) + "/adjuntos", c.secretaria().token(), archivo,
				categoria, titulo);
	}

	private Respuesta subirComoProfesional(Centro c, long historiaId, byte[] archivo) {
		return multipart("/api/v1/historias-clinicas/" + historiaId + "/adjuntos", c.profesional().token(),
				archivo, "ESTUDIO", "Titulo del profesional");
	}

	private long abrirHistoria(Centro c, long personaId) {
		Respuesta r = put(hc(personaId), c.profesional().token(), null);
		assertThat(r.status()).as(r.body()).isEqualTo(200);
		return r.json().get("id").asLong();
	}

	/** Un PDF sintetico: la lista blanca mira la firma {@code %PDF-} del byte cero. */
	private static byte[] pdf(String marca) {
		return ("%PDF-1.7\n% " + marca + " " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private long historiasDe(long personaId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM historia_clinica WHERE persona_id = ?", Long.class, personaId);
	}

	private long versionDeAntecedente(long id) {
		return jdbc.queryForObject(
				"SELECT version FROM historia_clinica_antecedente WHERE id = ?", Long.class, id);
	}

	/** El profesional funda el centro; la secretaria se registra aparte y se suma a su sede. */
	private Centro crearCentro(String etiqueta) {
		Usuario profesional = crearTenant(etiqueta, "PROFESIONAL");
		Usuario secretaria = crearMiembro(etiqueta + "-sec", profesional, "ADMINISTRATIVO");
		return new Centro(profesional, secretaria);
	}

	private Usuario crearTenant(String etiqueta, String rol) {
		String email = registrar(etiqueta);
		long cuentaId = cuentaDe(email);
		jdbc.update("UPDATE membership SET role_code = ? WHERE account_id = ?", rol, cuentaId);
		return iniciarSesion(email, cuentaId, null);
	}

	/**
	 * Una cuenta propia con membership en la sede del otro usuario. Su organizacion de alta queda
	 * sin usar; el contexto que se elige es el de {@code en}.
	 */
	private Usuario crearMiembro(String etiqueta, Usuario en, String rol) {
		String email = registrar(etiqueta);
		long cuentaId = cuentaDe(email);
		InsertarConId.insertar(jdbc, """
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, false, (CURRENT_TIMESTAMP - INTERVAL '1 day'),
				        'ACTIVA', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", en.organizationId(), en.consultorioId(), cuentaId, rol);
		return iniciarSesion(email, cuentaId, en);
	}

	private String registrar(String etiqueta) {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		Respuesta alta = postSinMotivo("/api/v1/auth/register",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
						+ "\"firstName\":\"Sintetica\",\"lastName\":\"DePrueba\","
						+ "\"organizationName\":\"Centro " + etiqueta + "\"}",
				UUID.randomUUID().toString());
		assertThat(alta.status()).as("alta self-service: %s", alta.body()).isEqualTo(202);
		jdbc.update("UPDATE cuenta SET estado = 'ACTIVA' WHERE email_normalizado = ?",
				email.strip().toLowerCase(Locale.ROOT));
		return email;
	}

	private long cuentaDe(String email) {
		return jdbc.queryForObject("SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class,
				email.strip().toLowerCase(Locale.ROOT));
	}

	/** Login y seleccion de contexto: el de {@code en} si viene, si no el primero. */
	private Usuario iniciarSesion(String email, long cuentaId, Usuario en) {
		Respuesta login = post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(login.status()).as("login: %s", login.body()).isEqualTo(200);
		String preContexto = login.json().get("accessToken").asString();

		JsonNode elegido = null;
		for (JsonNode contexto : get("/api/v1/me/contexts", preContexto).json()) {
			if (en == null || (contexto.get("organizationId").asLong() == en.organizationId()
					&& contexto.get("consultorioId").asLong() == en.consultorioId())) {
				elegido = contexto;
				break;
			}
		}
		assertThat(elegido).as("el contexto de la sede compartida esta disponible").isNotNull();
		long organizationId = elegido.get("organizationId").asLong();
		long consultorioId = elegido.get("consultorioId").asLong();

		Respuesta sesion = post("/api/v1/auth/context", preContexto,
				"{\"organizationId\":" + organizationId + ",\"consultorioId\":" + consultorioId + "}");
		assertThat(sesion.status()).as("seleccion de contexto: %s", sesion.body()).isEqualTo(200);
		return new Usuario(organizationId, consultorioId, cuentaId, sesion.json().get("accessToken").asString());
	}

	private long crearPaciente(Usuario usuario) {
		long personaId = crearPersonaSinPerfil(usuario);
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", usuario.organizationId(), personaId, usuario.cuentaId());
		return personaId;
	}

	private long crearPersonaSinPerfil(Usuario usuario) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		return InsertarConId.insertar(jdbc, """
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", usuario.organizationId(), "Paciente" + sufijo, ("PACIENTE" + sufijo).toUpperCase());
	}

	// ---------------------------------------------------------------------------------
	// HTTP
	// ---------------------------------------------------------------------------------

	private Respuesta get(String ruta, String token) {
		return enviar(constructor(ruta, token, MOTIVO).GET().build());
	}

	private Respuesta put(String ruta, String token, String cuerpo) {
		return enviar(constructor(ruta, token, MOTIVO)
				.PUT(HttpRequest.BodyPublishers.ofString(cuerpo == null ? "" : cuerpo)).build());
	}

	private Respuesta post(String ruta, String token, String cuerpo) {
		return enviar(constructor(ruta, token, MOTIVO)
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build());
	}

	private Respuesta postSinMotivo(String ruta, String cuerpo, String claveIdempotencia) {
		return enviar(constructor(ruta, null, null)
				.header("Idempotency-Key", claveIdempotencia)
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build());
	}

	/** Multipart a mano: {@code archivo} como parte binaria, {@code categoria} y {@code titulo} como campos. */
	private Respuesta multipart(String ruta, String token, byte[] archivo, String categoria, String titulo) {
		String limite = "----akine" + UUID.randomUUID();
		ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
		escribir(cuerpo, "--" + limite + "\r\nContent-Disposition: form-data; name=\"categoria\"\r\n\r\n"
				+ categoria + "\r\n");
		escribir(cuerpo, "--" + limite + "\r\nContent-Disposition: form-data; name=\"titulo\"\r\n\r\n"
				+ titulo + "\r\n");
		escribir(cuerpo, "--" + limite + "\r\nContent-Disposition: form-data; name=\"archivo\"; "
				+ "filename=\"documento.pdf\"\r\nContent-Type: application/pdf\r\n\r\n");
		cuerpo.writeBytes(archivo);
		escribir(cuerpo, "\r\n--" + limite + "--\r\n");

		HttpRequest.Builder builder = HttpRequest
				.newBuilder(URI.create("http://localhost:" + puerto + ruta))
				.header("Content-Type", "multipart/form-data; boundary=" + limite)
				.header("Authorization", "Bearer " + token)
				.header(JUSTIFICACION, MOTIVO);
		return enviar(builder.POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray())).build());
	}

	private static void escribir(ByteArrayOutputStream destino, String texto) {
		destino.writeBytes(texto.getBytes(StandardCharsets.UTF_8));
	}

	private HttpRequest.Builder constructor(String ruta, String token, String justificacion) {
		HttpRequest.Builder builder = HttpRequest
				.newBuilder(URI.create("http://localhost:" + puerto + ruta))
				.header("Content-Type", "application/json");
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		if (justificacion != null) {
			builder.header(JUSTIFICACION, justificacion);
		}
		return builder;
	}

	private Respuesta enviar(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return new Respuesta(response.statusCode(), response.body());
		}
		catch (IOException e) {
			throw new IllegalStateException("Fallo el request HTTP a " + request.uri(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Request interrumpido a " + request.uri(), e);
		}
	}
}
