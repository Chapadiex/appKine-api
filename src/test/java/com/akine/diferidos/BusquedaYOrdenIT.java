package com.akine.diferidos;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AdjuntoClinicoRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionEventoRepositoryPort;
import com.akine.person.support.CoberturaFixtures;
import com.akine.platform.spi.listado.TextoDeBusqueda;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-45, punto 1: {@code q} y {@code sort} en los listados, contra MySQL real y por HTTP.
 *
 * <p>Lo que importa probar aca y no en un unitario es que <b>la consulta corre</b> —las ramas
 * {@code CASE} de las nativas, las propiedades del {@code Sort} de las JPQL, la collation que hace
 * "contiene" sin acentos— y que un {@code sort} fuera del enum es 400 {@code validation-error} y
 * no un 500. Sin {@code q} ni {@code sort} cada listado sigue con sus ITs de siempre: este no los
 * repite. Cross-tenant con los parametros nuevos sigue siendo 404.
 */
class BusquedaYOrdenIT extends BaseEscenarioDiferido {

	private static final String VALIDATION_ERROR = "https://akine.app/problems/validation-error";

	@Autowired private AutorizacionEventoRepositoryPort eventosDeAutorizacion;
	@Autowired private AdjuntoClinicoRepositoryPort adjuntosClinicos;
	@Autowired private MovimientoPaseRepositoryPort movimientosDePase;

	// =================================================================================
	// Listados en memoria: espacios, sedes, catalogo, medidas
	// =================================================================================

	@Test
	@DisplayName("Espacios: q sin acentos ni mayusculas, sort por capacidad y nombre, 400 y 404")
	void espacios() {
		Sesion sesion = altaCompleta("dp45-espacios");
		Sesion ajeno = altaCompleta("dp45-espacios-ajeno");
		String ruta = "/api/v1/organizations/" + sesion.organizationId()
				+ "/consultorios/" + sesion.consultorioId() + "/espacios";
		crear(ruta, sesion, "Box Ñandú", 2);
		crear(ruta, sesion, "Sala Grande", 12);
		crear(ruta, sesion, "box chico", 1);

		Respuesta buscados = get(ruta + "?q=" + url("NANDU"), sesion.token());
		assertThat(buscados.status()).as(buscados.body()).isEqualTo(200);
		assertThat(textos(buscados, "name")).containsExactly("Box Ñandú");

		Respuesta porCapacidad = get(ruta + "?sort=capacidad,desc", sesion.token());
		assertThat(porCapacidad.status()).as(porCapacidad.body()).isEqualTo(200);
		assertThat(textos(porCapacidad, "name"))
				.containsExactly("Sala Grande", "Box Ñandú", "box chico");

		Respuesta porNombre = get(ruta + "?q=box&sort=nombre,desc", sesion.token());
		assertThat(textos(porNombre, "name")).containsExactly("Box Ñandú", "box chico");

		assertValidacion(get(ruta + "?sort=notes,asc", sesion.token()));
		assertValidacion(get(ruta + "?sort=nombre", sesion.token()));
		assertValidacion(get(ruta + "?sort=nombre,asc&sort=tipo,desc", sesion.token()));
		assertThat(get(ruta + "?q=box&sort=nombre,asc", ajeno.token()).status())
				.as("cross-tenant con los parametros nuevos sigue siendo 404").isEqualTo(404);
	}

	@Test
	@DisplayName("Sedes y catalogo: q, sort y 400")
	void sedes_y_catalogo() {
		Sesion sesion = altaCompleta("dp45-sedes");
		String sedes = "/api/v1/organizations/" + sesion.organizationId() + "/consultorios";

		Respuesta todas = get(sedes + "?sort=nombre,desc", sesion.token());
		assertThat(todas.status()).as(todas.body()).isEqualTo(200);
		String nombre = todas.json().get("content").get(0).get("name").asString();
		assertThat(get(sedes + "?q=" + url(nombre.toUpperCase()), sesion.token())
				.json().get("totalElements").asLong()).isEqualTo(1);
		assertThat(get(sedes + "?q=zzz-no-existe", sesion.token())
				.json().get("totalElements").asLong()).isZero();
		assertValidacion(get(sedes + "?sort=timezone,asc", sesion.token()));

		String especialidades = "/api/v1/catalogos/especialidades";
		String sufijo = UUID.randomUUID().toString().substring(0, 6);
		for (String codigo : List.of("DP45A" + sufijo, "DP45C" + sufijo, "DP45B" + sufijo)) {
			Respuesta alta = post(especialidades, sesion.token(),
					"{\"codigo\":\"" + codigo + "\",\"name\":\"Especialidad " + codigo + "\"}");
			assertThat(alta.status()).as("alta de especialidad: %s", alta.body()).isEqualTo(201);
		}
		Respuesta porCodigo = get(especialidades + "?alcance=ORGANIZACION&q=DP45&sort=codigo,desc",
				sesion.token());
		assertThat(porCodigo.status()).as(porCodigo.body()).isEqualTo(200);
		assertThat(textos(porCodigo, "codigo"))
				.containsExactly("DP45C" + sufijo, "DP45B" + sufijo, "DP45A" + sufijo);
		assertValidacion(get(especialidades + "?sort=descripcion,asc", sesion.token()));

		Respuesta medidas = get("/api/v1/mediciones/definiciones?sort=codigo,desc&size=100",
				sesion.token());
		assertThat(medidas.status()).as(medidas.body()).isEqualTo(200);
		List<String> codigos = textos(medidas, "codigo");
		assertThat(codigos).isSortedAccordingTo(
				Comparator.comparing(TextoDeBusqueda::plegar, Comparator.reverseOrder()));
		assertValidacion(get("/api/v1/mediciones/definiciones?sort=unidad,asc", sesion.token()));
	}

	// =================================================================================
	// organization: colaboradores, auditoria, historico de suscripcion, plataforma
	// =================================================================================

	@Test
	@DisplayName("Colaboradores, auditoria y suscripcion: q, sort, 400 y 404")
	void colaboradores_auditoria_y_suscripcion() {
		Sesion sesion = altaCompleta("dp45-org");
		Sesion ajeno = altaCompleta("dp45-org-ajeno");
		String org = "/api/v1/organizations/" + sesion.organizationId();

		String parteDelCorreo = sesion.email().substring(0, 10).toUpperCase();
		Respuesta porCorreo = get(org + "/memberships?q=" + url(parteDelCorreo) + "&sort=email,asc",
				sesion.token());
		assertThat(porCorreo.status()).as(porCorreo.body()).isEqualTo(200);
		assertThat(textos(porCorreo, "accountEmail")).containsExactly(sesion.email());
		assertThat(get(org + "/memberships?q=nadie-se-llama-asi", sesion.token())
				.json().get("totalElements").asLong()).isZero();
		assertThat(get(org + "/memberships?sort=rol,desc", sesion.token()).status()).isEqualTo(200);
		assertValidacion(get(org + "/memberships?sort=accountId,asc", sesion.token()));
		assertThat(get(org + "/memberships?q=a&sort=nombre,asc", ajeno.token()).status())
				.isEqualTo(404);

		String periodo = "?from=" + java.time.Instant.now().minusSeconds(3600)
				+ "&to=" + java.time.Instant.now().plusSeconds(60);
		Respuesta hechos = get(org + "/audit-events" + periodo + "&sort=tipoEvento,asc&size=100",
				sesion.token());
		assertThat(hechos.status()).as(hechos.body()).isEqualTo(200);
		List<String> tipos = textos(hechos, "eventType");
		// La collation de la base pesa el guion bajo como puntuacion, debajo de las letras.
		assertThat(tipos).isNotEmpty().isSortedAccordingTo(
				Comparator.comparing((String tipo) -> tipo.replace('_', ' ')));

		String unTipo = tipos.get(0);
		String pedazo = unTipo.substring(1, unTipo.length() - 1).toLowerCase();
		Respuesta porTipo = get(org + "/audit-events" + periodo + "&q=" + url(pedazo),
				sesion.token());
		assertThat(porTipo.status()).as(porTipo.body()).isEqualTo(200);
		assertThat(porTipo.json().get("content")).isNotEmpty();
		for (JsonNode fila : porTipo.json().get("content")) {
			assertThat(TextoDeBusqueda.algunoContiene(pedazo,
					fila.path("eventType").asString(), fila.path("entityType").asString()))
					.as("q es 'contiene' en el tipo de evento o de entidad").isTrue();
		}
		Respuesta porActor = get(org + "/audit-events" + periodo + "&q=" + sesion.cuentaId(),
				sesion.token());
		assertThat(porActor.status()).as(porActor.body()).isEqualTo(200);
		for (JsonNode fila : porActor.json().get("content")) {
			assertThat(fila.path("actorAccountId").asLong() == sesion.cuentaId()
					|| fila.path("entityId").asLong() == sesion.cuentaId())
					.as("un q numerico es el id de la cuenta actora o de la entidad").isTrue();
		}
		assertValidacion(get(org + "/audit-events" + periodo + "&sort=reason,asc", sesion.token()));
		assertThat(get(org + "/audit-events" + periodo + "&sort=ocurridoEn,asc", ajeno.token())
				.status()).isEqualTo(404);

		Respuesta transiciones = get(org + "/subscription/transitions?sort=ocurridoEn,asc",
				sesion.token());
		assertThat(transiciones.status()).as(transiciones.body()).isEqualTo(200);
		assertThat(transiciones.json().get("totalElements").asLong()).isPositive();
		assertThat(get(org + "/subscription/transitions?sort=estadoNuevo,desc", sesion.token())
				.status()).isEqualTo(200);
		assertValidacion(get(org + "/subscription/transitions?sort=reason,asc", sesion.token()));
	}

	@Test
	@DisplayName("Plataforma: sort por nombre descendente dentro de q, y 400")
	void centros_de_la_plataforma() {
		String etiqueta = "dp45plat" + UUID.randomUUID().toString().substring(0, 6);
		Sesion a = altaCompleta(etiqueta + "-a");
		Sesion b = altaCompleta(etiqueta + "-b");
		Sesion plataforma = altaCompleta(etiqueta + "-z");
		sembrarRolDePlataforma(plataforma.cuentaId());

		String ruta = "/api/v1/platform/organizations?q=" + etiqueta;
		Respuesta porDefecto = get(ruta, plataforma.token());
		assertThat(ids(porDefecto)).as("sin sort, por nombre como siempre")
				.containsExactly(a.organizationId(), b.organizationId(), plataforma.organizationId());
		Respuesta descendente = get(ruta + "&sort=nombre,desc", plataforma.token());
		assertThat(descendente.status()).as(descendente.body()).isEqualTo(200);
		assertThat(ids(descendente))
				.containsExactly(plataforma.organizationId(), b.organizationId(), a.organizationId());
		assertThat(get(ruta + "&sort=creadaEn,asc", plataforma.token()).status()).isEqualTo(200);
		assertValidacion(get(ruta + "&sort=planCode,asc", plataforma.token()));
	}

	@Test
	@DisplayName("Notificaciones: q por tipo y por destinatario exacto, nunca parcial")
	void notificaciones() {
		Sesion sesion = altaCompleta("dp45-notif");
		String ruta = "/api/v1/organizations/" + sesion.organizationId()
				+ "/notifications?estado=PENDIENTE,PROCESANDO,ENVIADA,FALLIDA,REINTENTABLE,AGOTADA";

		Respuesta todas = get(ruta + "&sort=creadaEn,asc", sesion.token());
		assertThat(todas.status()).as(todas.body()).isEqualTo(200);
		long total = todas.json().get("totalElements").asLong();
		assertThat(total).as("la activacion del fundador es de la organizacion").isPositive();

		assertThat(get(ruta + "&q=activacion", sesion.token()).json().get("totalElements").asLong())
				.isEqualTo(total);
		assertThat(get(ruta + "&q=" + url(sesion.email().toUpperCase()), sesion.token())
				.json().get("totalElements").asLong())
				.as("el destinatario exacto, sin distinguir mayusculas").isEqualTo(total);
		assertThat(get(ruta + "&q=" + url(sesion.email().substring(0, 8)), sesion.token())
				.json().get("totalElements").asLong())
				.as("una parte del correo no encuentra nada: el listado lo enmascara").isZero();
		assertThat(get(ruta + "&sort=intentos,desc", sesion.token()).status()).isEqualTo(200);
		assertValidacion(get(ruta + "&sort=destinatario,asc", sesion.token()));
	}

	// =================================================================================
	// Consultas nativas con CASE: padron, adjuntos, historial de autorizacion
	// =================================================================================

	@Test
	@DisplayName("Padron y adjuntos: el CASE de la nativa ordena, y el desempate es estable")
	void padron_y_adjuntos() {
		Sesion sesion = altaCompleta("dp45-padron");
		Sesion ajeno = altaCompleta("dp45-padron-ajeno");
		CoberturaFixtures datos = new CoberturaFixtures(jdbc);
		long beto = datos.persona(sesion.organizationId(), "Garcia", "Beto", "41000002");
		long ana = datos.persona(sesion.organizationId(), "Garcia", "Ana", "41000003");
		long zoe = datos.persona(sesion.organizationId(), "Alvarez", "Zoe", "41000001");

		assertThat(ids(get("/api/v1/personas", sesion.token())))
				.as("sin sort, apellido y nombre como siempre").containsExactly(zoe, ana, beto);
		assertThat(ids(get("/api/v1/personas?sort=nombre,desc", sesion.token())))
				.containsExactly(zoe, beto, ana);
		assertThat(ids(get("/api/v1/personas?sort=documento,asc", sesion.token())))
				.containsExactly(zoe, beto, ana);
		assertThat(ids(get("/api/v1/personas?sort=apellido,desc", sesion.token())))
				.as("apellido descendente, y detras nombre ascendente").containsExactly(ana, beto, zoe);
		for (String orden : List.of("nacimiento,asc", "nacimiento,desc", "alta,asc", "alta,desc")) {
			assertThat(get("/api/v1/personas?sort=" + orden, sesion.token()).status())
					.as("sort=%s", orden).isEqualTo(200);
		}
		assertValidacion(get("/api/v1/personas?sort=email,asc", sesion.token()));
		assertValidacion(get("/api/v1/personas?sort=apellido;drop,asc", sesion.token()));

		String adjuntos = "/api/v1/personas/" + ana + "/adjuntos";
		for (String orden : List.of("subidoEn,asc", "subidoEn,desc", "titulo,asc", "titulo,desc",
				"categoria,asc", "categoria,desc", "tamano,asc", "tamano,desc")) {
			Respuesta pagina = get(adjuntos + "?sort=" + orden, sesion.token());
			assertThat(pagina.status()).as("sort=%s: %s", orden, pagina.body()).isEqualTo(200);
		}
		assertValidacion(get(adjuntos + "?sort=storageKey,asc", sesion.token()));
		assertThat(get(adjuntos + "?sort=titulo,asc", ajeno.token()).status()).isEqualTo(404);

		// Las otras dos nativas con CASE no tienen un camino HTTP barato de sembrar (una
		// autorizacion, una historia clinica con justificacion): se ejecutan contra el motor por su
		// puerto, que es lo que puede fallar —una rama del CASE mal escrita es un error de SQL—.
		for (String orden : List.of("", "ocurridoEn,asc", "ocurridoEn,desc", "tipo,asc", "tipo,desc")) {
			assertThat(eventosDeAutorizacion.pagina(sesion.organizationId(), 999_999L, orden, 0, 10))
					.isEmpty();
		}
		for (String orden : List.of("", "subidoEn,asc", "titulo,desc", "categoria,asc", "tamano,desc")) {
			assertThat(adjuntosClinicos.listar(
					sesion.organizationId(), 999_999L, null, null, -1, orden, 0, 10)).isEmpty();
		}
		for (String propiedad : List.of("ocurridoEn", "tipo", "cantidad")) {
			assertThat(movimientosDePase.historial(sesion.organizationId(), 999_999L,
					PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, propiedad).and(Sort.by("id")))))
					.isEmpty();
		}
	}

	// =================================================================================
	// Series de turnos, egresos y presentaciones: la consulta corre con q y sort
	// =================================================================================

	@Test
	@DisplayName("Series, egresos y presentaciones: q y sort corren contra el motor, 400 y 404")
	void series_egresos_y_presentaciones() {
		Sesion sesion = altaCompleta("dp45-sede");
		Sesion ajeno = altaCompleta("dp45-sede-ajeno");
		String sede = "/api/v1/consultorios/" + sesion.consultorioId();

		for (String parametros : List.of("?q=perez", "?sort=fechaDesde,asc", "?q=kine&sort=creadaEn,desc",
				"?sort=fechaHasta,desc")) {
			Respuesta series = get(sede + "/series-de-turnos" + parametros, sesion.token());
			assertThat(series.status()).as("%s: %s", parametros, series.body()).isEqualTo(200);
			assertThat(series.json().get("totalElements").asLong()).isZero();
		}
		assertValidacion(get(sede + "/series-de-turnos?sort=frecuencia,asc", sesion.token()));
		assertThat(get(sede + "/series-de-turnos?q=perez", ajeno.token()).status()).isEqualTo(404);

		Respuesta egresos = get(sede + "/egresos?q=" + url("alquiler 50%"), sesion.token());
		assertThat(egresos.status()).as(egresos.body()).isEqualTo(200);
		assertThat(egresos.json().size()).isZero();
		assertThat(get(sede + "/egresos?q=x", ajeno.token()).status()).isEqualTo(404);

		for (String q : List.of("A-0001", "17")) {
			Respuesta lotes = get(sede + "/presentaciones?q=" + q, sesion.token());
			assertThat(lotes.status()).as("q=%s: %s", q, lotes.body()).isEqualTo(200);
			assertThat(lotes.json().size()).isZero();
		}
		assertThat(get(sede + "/presentaciones?q=17", ajeno.token()).status()).isEqualTo(404);
	}

	// =================================================================================
	// Soporte
	// =================================================================================

	private void crear(String ruta, Sesion sesion, String nombre, int capacidad) {
		Respuesta alta = post(ruta, sesion.token(),
				"{\"name\":\"" + nombre + "\",\"tipo\":\"BOX\",\"capacidad\":" + capacidad + "}");
		assertThat(alta.status()).as("alta de %s: %s", nombre, alta.body()).isEqualTo(201);
	}

	private void assertValidacion(Respuesta respuesta) {
		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(400);
		assertThat(respuesta.texto("type")).isEqualTo(VALIDATION_ERROR);
		assertThat(respuesta.body()).as("no refleja el valor recibido").doesNotContain("drop");
	}

	private static String url(String texto) {
		return URLEncoder.encode(texto, StandardCharsets.UTF_8);
	}

	private static List<String> textos(Respuesta respuesta, String campo) {
		JsonNode filas = respuesta.json().get("content");
		List<String> valores = new ArrayList<>();
		for (int i = 0; i < filas.size(); i++) {
			valores.add(filas.get(i).get(campo).asString());
		}
		return valores;
	}

	private static List<Long> ids(Respuesta respuesta) {
		JsonNode filas = respuesta.json().get("content");
		List<Long> valores = new ArrayList<>();
		for (int i = 0; i < filas.size(); i++) {
			valores.add(filas.get(i).get("id").asLong());
		}
		return valores;
	}
}
