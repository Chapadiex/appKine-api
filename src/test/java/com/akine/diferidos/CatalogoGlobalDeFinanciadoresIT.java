package com.akine.diferidos;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-43 de punta a punta, contra el stack real: la plataforma administra el catalogo global de
 * financiadores y planes, y un centro lo ADOPTA como copia propia.
 *
 * <p>Lo que se afirma contra la BASE y no contra el 200: que adoptar dos veces deja una sola
 * copia, que la copia es del tenant que adopta y de ningun otro, y que editar la copia o el
 * catalogo no toca al otro lado.
 *
 * <p>Los codigos llevan sufijo aleatorio: el catalogo es global y compartido por todos los tests
 * del contexto.
 */
class CatalogoGlobalDeFinanciadoresIT extends BaseEscenarioDiferido {

	private static final String CONSOLA = "/api/v1/platform/catalogo-financiadores";
	private static final String CATALOGO = "/api/v1/catalogo-financiadores";

	@Test
	@DisplayName("La plataforma carga el catalogo y un centro no puede; el centro ve solo lo activo")
	void la_plataforma_administra_y_el_centro_lee_lo_activo() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("dp43-lee");
		String codigo = "OS-" + sufijo();

		long catalogoId = crearEntrada(plataforma, codigo);
		long planActivo = crearPlan(plataforma, catalogoId, "210");
		long planDadoDeBaja = crearPlan(plataforma, catalogoId, "310");
		assertThat(borrar(CONSOLA + "/" + catalogoId + "/planes/" + planDadoDeBaja, plataforma.token(),
				"{\"reason\":\"Discontinuado\"}").status()).isEqualTo(204);

		Respuesta intrusa = post(CONSOLA, centro.token(), entrada("X-" + codigo));
		assertThat(intrusa.status()).as("un centro cargando el catalogo: %s", intrusa.body())
				.isEqualTo(403);
		assertThat(get(CONSOLA, centro.token()).status()).isEqualTo(403);

		JsonNode entrada = entradaDelCentro(centro, codigo);
		assertThat(entrada.get("id").asLong()).isEqualTo(catalogoId);
		assertThat(entrada.hasNonNull("financiadorAdoptadoId")).isFalse();
		assertThat(entrada.get("planes")).hasSize(1);
		assertThat(entrada.get("planes").get(0).get("id").asLong()).isEqualTo(planActivo);

		// La consola ve los dos planes, el dado de baja incluido.
		Respuesta consola = get(CONSOLA + "/" + catalogoId, plataforma.token());
		assertThat(consola.status()).isEqualTo(200);
		assertThat(consola.json().get("planes")).hasSize(2);
	}

	@Test
	@DisplayName("Adoptar crea la copia del tenant con sus planes activos; adoptar otra vez no duplica")
	void adoptar_es_idempotente() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("dp43-idem");
		String codigo = "OS-" + sufijo();
		long catalogoId = crearEntrada(plataforma, codigo);
		long planCatalogo = crearPlan(plataforma, catalogoId, "210");

		Respuesta primera = adoptar(centro, catalogoId);
		assertThat(primera.status()).as("primera adopcion: %s", primera.body()).isEqualTo(201);
		assertThat(primera.json().get("creado").asBoolean()).isTrue();
		long copiaId = primera.json().get("financiador").get("id").asLong();
		assertThat(primera.json().get("financiador").get("catalogoId").asLong()).isEqualTo(catalogoId);
		assertThat(primera.json().get("planes")).hasSize(1);

		Respuesta segunda = adoptar(centro, catalogoId);
		assertThat(segunda.status()).as("segunda adopcion: %s", segunda.body()).isEqualTo(200);
		assertThat(segunda.json().get("creado").asBoolean()).isFalse();
		assertThat(segunda.json().get("financiador").get("id").asLong()).isEqualTo(copiaId);

		assertThat(jdbc.queryForMap("""
				SELECT organization_id, codigo, catalogo_id, active FROM financiador WHERE id = ?
				""", copiaId))
				.containsEntry("organization_id", centro.organizationId())
				.containsEntry("codigo", codigo)
				.containsEntry("catalogo_id", catalogoId);
		assertThat(copiasDe(centro, catalogoId)).isEqualTo(1L);
		assertThat(jdbc.queryForMap("""
				SELECT organization_id, financiador_id, codigo, catalogo_id
				  FROM plan_cobertura WHERE financiador_id = ?
				""", copiaId))
				.containsEntry("organization_id", centro.organizationId())
				.containsEntry("codigo", "210")
				.containsEntry("catalogo_id", planCatalogo);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE event_type = 'FINANCIADOR_ADOPTADO' AND entity_id = ? AND organization_id = ?
				""", Long.class, copiaId, centro.organizationId())).isEqualTo(1L);

		// El centro ve que ya la adopto.
		assertThat(entradaDelCentro(centro, codigo).get("financiadorAdoptadoId").asLong())
				.isEqualTo(copiaId);
	}

	@Test
	@DisplayName("Dos adopciones simultaneas del mismo centro dejan una sola copia")
	void dos_adopciones_simultaneas_dejan_una_copia() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("dp43-carrera");
		long catalogoId = crearEntrada(plataforma, "OS-" + sufijo());
		crearPlan(plataforma, catalogoId, "210");

		Callable<Integer> adopcion = () -> adoptar(centro, catalogoId).status();
		List<Integer> codigos = Concurrencia.enParalelo(List.of(adopcion, adopcion)).stream()
				.map(Concurrencia.Resultado::valor)
				.toList();

		assertThat(codigos).as("una crea y la otra recibe la misma copia: %s", codigos)
				.containsExactlyInAnyOrder(201, 200);
		assertThat(copiasDe(centro, catalogoId)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM plan_cobertura p JOIN financiador f ON f.id = p.financiador_id
				 WHERE f.organization_id = ? AND f.catalogo_id = ?
				""", Long.class, centro.organizationId(), catalogoId))
				.as("los planes del perdedor se deshicieron con su transaccion")
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("Dos centros adoptan el mismo: dos copias, cada una de su tenant y sin cruzarse")
	void la_adopcion_aisla_entre_centros() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centroA = altaCompleta("dp43-a");
		Sesion centroB = altaCompleta("dp43-b");
		String codigo = "OS-" + sufijo();
		long catalogoId = crearEntrada(plataforma, codigo);

		long copiaA = adoptar(centroA, catalogoId).json().get("financiador").get("id").asLong();
		Respuesta deB = adoptar(centroB, catalogoId);
		assertThat(deB.status()).as("adopcion de B: %s", deB.body()).isEqualTo(201);
		long copiaB = deB.json().get("financiador").get("id").asLong();

		assertThat(copiaB).isNotEqualTo(copiaA);
		assertThat(jdbc.queryForObject(
				"SELECT organization_id FROM financiador WHERE id = ?", Long.class, copiaB))
				.isEqualTo(centroB.organizationId());
		assertThat(entradaDelCentro(centroA, codigo).get("financiadorAdoptadoId").asLong())
				.isEqualTo(copiaA);
		assertThat(entradaDelCentro(centroB, codigo).get("financiadorAdoptadoId").asLong())
				.isEqualTo(copiaB);
		assertThat(get("/api/v1/financiadores/" + copiaA, centroB.token()).status())
				.as("la copia de A no existe para B").isEqualTo(404);
	}

	@Test
	@DisplayName("La copia es independiente: editarla no toca el catalogo y editar el catalogo no la "
			+ "toca, solo avisa que hay una version mas nueva")
	void la_copia_es_independiente_del_catalogo() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("dp43-indep");
		String codigo = "OS-" + sufijo();
		long catalogoId = crearEntrada(plataforma, codigo);
		long planCatalogo = crearPlan(plataforma, catalogoId, "210");
		JsonNode adopcion = adoptar(centro, catalogoId).json();
		long copiaId = adopcion.get("financiador").get("id").asLong();
		long planCopia = adopcion.get("planes").get(0).get("id").asLong();
		assertThat(entradaDelCentro(centro, codigo).get("actualizacionDisponible").asBoolean())
				.isFalse();

		// El centro edita su copia: el catalogo no cambia.
		Respuesta edicionDelCentro = put("/api/v1/financiadores/" + copiaId, centro.token(),
				"{\"nombre\":\"Mi " + codigo + "\",\"emailContacto\":\"mesa@centro.test\","
						+ "\"expectedVersion\":0}");
		assertThat(edicionDelCentro.status()).as("edicion del centro: %s", edicionDelCentro.body())
				.isEqualTo(200);
		assertThat(jdbc.queryForObject(
				"SELECT nombre FROM financiador_catalogo WHERE id = ?", String.class, catalogoId))
				.isEqualTo("Catalogo " + codigo);

		// La plataforma edita el catalogo y su plan: la copia no cambia.
		assertThat(put(CONSOLA + "/" + catalogoId, plataforma.token(),
				"{\"nombre\":\"Nuevo " + codigo + "\",\"expectedVersion\":0}").status()).isEqualTo(200);
		assertThat(put(CONSOLA + "/" + catalogoId + "/planes/" + planCatalogo, plataforma.token(),
				"{\"copago\":9999.00,\"moneda\":\"ARS\",\"expectedVersion\":0}").status()).isEqualTo(200);

		assertThat(jdbc.queryForMap(
				"SELECT nombre, email_contacto FROM financiador WHERE id = ?", copiaId))
				.containsEntry("nombre", "Mi " + codigo)
				.containsEntry("email_contacto", "mesa@centro.test");
		assertThat(jdbc.queryForMap("SELECT copago FROM plan_cobertura WHERE id = ?", planCopia)
				.get("copago")).isNull();

		JsonNode entrada = entradaDelCentro(centro, "Nuevo " + codigo);
		assertThat(entrada.get("actualizacionDisponible").asBoolean()).isTrue();
		assertThat(entrada.get("financiadorAdoptadoId").asLong()).isEqualTo(copiaId);
	}

	@Test
	@DisplayName("Una entrada dada de baja no se adopta, pero quien ya la tenia sigue recibiendo su "
			+ "copia; un financiador propio con el mismo codigo es 409")
	void baja_del_catalogo_y_choque_con_un_financiador_propio() {
		Sesion plataforma = administradorDePlataforma();
		Sesion conCopia = altaCompleta("dp43-baja-a");
		Sesion sinCopia = altaCompleta("dp43-baja-b");
		long catalogoId = crearEntrada(plataforma, "OS-" + sufijo());
		long copiaId = adoptar(conCopia, catalogoId).json().get("financiador").get("id").asLong();

		assertThat(borrar(CONSOLA + "/" + catalogoId, plataforma.token(),
				"{\"reason\":\"Ya no opera\"}").status()).isEqualTo(204);

		Respuesta tarde = adoptar(sinCopia, catalogoId);
		assertThat(tarde.status()).as("adopcion de una baja: %s", tarde.body()).isEqualTo(409);
		assertThat(tarde.texto("type")).isEqualTo("https://akine.app/problems/financiador-inactivo");
		assertProblemaLimpio(tarde);

		Respuesta otraVez = adoptar(conCopia, catalogoId);
		assertThat(otraVez.status()).isEqualTo(200);
		assertThat(otraVez.json().get("financiador").get("id").asLong()).isEqualTo(copiaId);
		assertThat(jdbc.queryForObject(
				"SELECT active FROM financiador WHERE id = ?", Boolean.class, copiaId)).isTrue();

		// Un financiador cargado a mano con el mismo codigo choca con la copia.
		String codigo = "OS-" + sufijo();
		long otraEntrada = crearEntrada(plataforma, codigo);
		assertThat(post("/api/v1/financiadores", sinCopia.token(),
				"{\"codigo\":\"" + codigo + "\",\"nombre\":\"Propio " + codigo + "\",\"tipo\":\"OBRA_SOCIAL\"}")
				.status()).isEqualTo(201);
		Respuesta choque = adoptar(sinCopia, otraEntrada);
		assertThat(choque.status()).as("choque con el propio: %s", choque.body()).isEqualTo(409);
		assertThat(choque.texto("type")).isEqualTo("https://akine.app/problems/financiador-codigo-taken");
		assertThat(copiasDe(sinCopia, otraEntrada)).isZero();
	}

	// =================================================================================
	// Fixtures y utilidades
	// =================================================================================

	private Sesion administradorDePlataforma() {
		Sesion sesion = altaCompleta("dp43-plataforma");
		sembrarRolDePlataforma(sesion.cuentaId());
		return sesion;
	}

	private static String sufijo() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static String entrada(String codigo) {
		return "{\"codigo\":\"" + codigo + "\",\"nombre\":\"Catalogo " + codigo + "\","
				+ "\"tipo\":\"OBRA_SOCIAL\",\"emailContacto\":\"general@os.test\"}";
	}

	private long crearEntrada(Sesion plataforma, String codigo) {
		Respuesta alta = post(CONSOLA, plataforma.token(), entrada(codigo));
		assertThat(alta.status()).as("alta en el catalogo: %s", alta.body()).isEqualTo(201);
		return alta.json().get("id").asLong();
	}

	private long crearPlan(Sesion plataforma, long catalogoId, String codigo) {
		Respuesta alta = post(CONSOLA + "/" + catalogoId + "/planes", plataforma.token(),
				"{\"codigo\":\"" + codigo + "\",\"nombre\":\"Plan " + codigo + "\","
						+ "\"vigenciaDesde\":\"2026-01-01\"}");
		assertThat(alta.status()).as("alta de plan en el catalogo: %s", alta.body()).isEqualTo(201);
		return alta.json().get("id").asLong();
	}

	private Respuesta adoptar(Sesion centro, long catalogoId) {
		return post(CATALOGO + "/" + catalogoId + "/adopcion", centro.token(), null);
	}

	private JsonNode entradaDelCentro(Sesion centro, String texto) {
		Respuesta listado = get(CATALOGO + "?q=" + texto.replace(" ", "%20"), centro.token());
		assertThat(listado.status()).as("catalogo del centro: %s", listado.body()).isEqualTo(200);
		assertThat(listado.json()).hasSize(1);
		return listado.json().get(0);
	}

	private long copiasDe(Sesion centro, long catalogoId) {
		Long total = jdbc.queryForObject("""
				SELECT COUNT(*) FROM financiador
				 WHERE organization_id = ? AND catalogo_id = ? AND deleted_at IS NULL
				""", Long.class, centro.organizationId(), catalogoId);
		return total == null ? 0L : total;
	}

	private Respuesta put(String ruta, String token, String cuerpo) {
		return ejecutar(HttpRequest.newBuilder(uri(ruta))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.PUT(HttpRequest.BodyPublishers.ofString(cuerpo))
				.build());
	}

	private Respuesta borrar(String ruta, String token, String cuerpo) {
		return ejecutar(HttpRequest.newBuilder(uri(ruta))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.method("DELETE", HttpRequest.BodyPublishers.ofString(cuerpo))
				.build());
	}
}
