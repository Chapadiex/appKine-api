package com.akine.diferidos;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.resource.application.CatalogoService;
import com.akine.resource.application.OperatingActor;
import com.akine.resource.application.VigenciaAltaCommand;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-02.05 de punta a punta: catalogo clinico global y contextual contra el stack real.
 *
 * <h2>Por que estos cuatro casos y no cuarenta</h2>
 *
 * <p>Lo que 02.05 introduce y ningun test unitario puede probar es <b>estado persistido</b>.
 * Cada metodo de abajo cubre una cosa que solo existe cuando hay una base de verdad, por el
 * camino real —HTTP, autenticacion Argon2id, contexto canjeado, MySQL— y afirma contra la BASE,
 * no contra el cuerpo de la respuesta. Un 201 no prueba que el dato quedo bien guardado.
 *
 * <ol>
 *   <li><b>Los tres discriminadores de los UNIQUE</b>, que son el riesgo tecnico de la etapa:
 *       el centinela de duenio {@code owner_key} —sin el, dos conceptos GLOBALES homonimos no
 *       colisionan, porque en MySQL varios NULL no chocan—, el centinela de fecha
 *       {@code deleted_key} y el NULL a proposito de {@code nombre_pendiente}.</li>
 *   <li><b>La baja logica y el aislamiento</b>: RN-M06-001 y RN-M06-002 contra el criterio de
 *       aceptacion literal de la etapa, mas el 404 cross-tenant.</li>
 *   <li><b>La carrera de las vigencias solapadas</b>, con hilos reales: es el unico invariante
 *       del modulo que la base no puede sostener sola.</li>
 *   <li><b>El circuito de solicitud</b>, que ejercita la tercera forma del unique y la
 *       terminalidad de la resolucion.</li>
 * </ol>
 *
 * <p>Las reglas que se pueden decidir sin base —los bordes de la ventana de vigencia, el motivo
 * obligatorio, la coherencia temporal— viven en el dominio y <b>no se repiten aca</b>:
 * repetirlas costaria minutos de Testcontainers por corrida y no encontraria nada nuevo.
 *
 * <p><b>Todos los codigos llevan un sufijo aleatorio.</b> El catalogo GLOBAL es compartido por
 * definicion: un codigo fijo haria que el segundo metodo que corriera chocara contra el primero,
 * y el fallo apareceria segun el orden de ejecucion.
 */
class CatalogosIT extends BaseEscenarioDiferido {

	private static final String CATALOGOS = "/api/v1/catalogos";
	private static final String SOLICITUDES = "/api/v1/catalogo-solicitudes";

	@Autowired
	private CatalogoService catalogoService;

	// =================================================================================
	// 1. Global contra contextual, y el centinela de duenio (migracion V20)
	// =================================================================================

	@Test
	@DisplayName("Dos conceptos GLOBALES con el mismo codigo colisionan; un tenant puede usar "
			+ "ese mismo codigo para el suyo, y no ve el de otro tenant")
	void el_centinela_de_duenio_protege_el_catalogo_global() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centroA = altaCompleta("catalogo-tenant-a");
		Sesion centroB = altaCompleta("catalogo-tenant-b");
		String codigo = "KIN-" + sufijo();

		// 1. El catalogo de plataforma protegido contra duplicados. ESTE es el caso que un
		//    UNIQUE (organization_id, codigo) habria dejado pasar: todas las filas globales
		//    tienen organization_id NULL y en MySQL varios NULL no colisionan.
		Respuesta global = crearEspecialidad(plataforma, "GLOBAL", codigo, "Kinesiologia " + codigo);
		assertThat(global.status()).as("alta global: %s", global.body()).isEqualTo(201);
		assertThat(global.texto("alcance")).isEqualTo("GLOBAL");
		// La API serializa con default-property-inclusion=non_null, asi que un concepto global
		// no trae la clave organizationId en absoluto —no viene con valor null—. Es lo que el
		// cliente tiene que esperar, y por eso se afirma sobre la AUSENCIA y no sobre un null.
		assertThat(global.json().get("organizationId"))
				.as("un concepto global no pertenece a ninguna organizacion, y la clave ni "
						+ "siquiera viaja")
				.isNull();
		assertThat(global.body()).doesNotContain("organizationId");
		long especialidadGlobal = global.json().get("id").asLong();

		Respuesta repetido = crearEspecialidad(
				plataforma, "GLOBAL", codigo, "Kinesiologia bis " + codigo);
		assertThat(repetido.status()).isEqualTo(409);
		assertThat(repetido.texto("type"))
				.isEqualTo("https://akine.app/problems/catalogo-code-taken");
		assertProblemaLimpio(repetido);

		// 2. El mismo codigo, para dos tenants distintos, no choca con el global ni entre si:
		//    son tres duenios distintos y el unique empieza por owner_key.
		assertThat(crearEspecialidad(centroA, "ORGANIZACION", codigo, "Kinesio A " + codigo)
				.status()).isEqualTo(201);
		assertThat(crearEspecialidad(centroB, "ORGANIZACION", codigo, "Kinesio B " + codigo)
				.status()).isEqualTo(201);

		assertThat(filasConCodigo("especialidad", codigo))
				.as("tres filas con el mismo codigo y tres duenios distintos")
				.isEqualTo(3);
		assertThat(duenios("especialidad", codigo))
				.as("el centinela 0 es el duenio de lo global")
				.hasSize(3)
				.contains(0L);

		// 3. Y cada centro ve el catalogo comun MAS el propio, nunca el del vecino.
		List<Long> visiblesParaA = idsDelListado(centroA, "especialidades?q=" + codigo);
		assertThat(visiblesParaA)
				.as("el catalogo de plataforma se ve desde cualquier tenant")
				.contains(especialidadGlobal);
		assertThat(visiblesParaA)
				.as("dos globales y una propia serian tres; se ven exactamente dos")
				.hasSize(2);

		// 4. Verlo no es poder tocarlo: el catalogo comun lo administra la plataforma.
		Respuesta edicionAjena = patch(
				CATALOGOS + "/especialidades/" + especialidadGlobal, centroA.token(),
				"{\"name\":\"Mia ahora\",\"version\":0}");
		assertThat(edicionAjena.status())
				.as("un admin de tenant VE el concepto global y no lo puede editar: %s",
						edicionAjena.body())
				.isEqualTo(403);
		assertThat(nombreDe("especialidad", especialidadGlobal))
				.as("y no lo toco")
				.isEqualTo("Kinesiologia " + codigo);
	}

	// =================================================================================
	// 2. Baja logica, historicos y aislamiento (RN-M06-001, RN-M06-002)
	// =================================================================================

	@Test
	@DisplayName("Una practica dada de baja se lee con 200 y sale de las selecciones nuevas; su "
			+ "codigo se reusa, y para otro tenant no existe")
	void los_historicos_siguen_resolviendo_y_lo_ajeno_es_404() {
		Sesion centro = altaCompleta("catalogo-historicos");
		Sesion ajeno = altaCompleta("catalogo-intruso");
		String sufijo = sufijo();

		long especialidad = crearEspecialidad(
				centro, "ORGANIZACION", "ESP-" + sufijo, "Fisioterapia " + sufijo)
				.json().get("id").asLong();

		String codigoPractica = "PRA-" + sufijo;
		Respuesta alta = crearPractica(
				centro, "ORGANIZACION", codigoPractica, "Sesion motora " + sufijo, especialidad);
		assertThat(alta.status()).as("alta de practica: %s", alta.body()).isEqualTo(201);
		long practica = alta.json().get("id").asLong();

		// Baja LOGICA: cero borrados fisicos.
		Respuesta baja = post(CATALOGOS + "/practicas/" + practica + "/deactivate", centro.token(),
				"{\"reason\":\"Reemplazada por el nomenclador 2026\"}");
		assertThat(baja.status()).as("baja de practica: %s", baja.body()).isEqualTo(200);
		assertThat(baja.texto("estado")).isEqualTo("INACTIVO");

		// RN-M06-002: el historico conserva su nombre y su codigo, y se sigue leyendo con 200.
		Respuesta lectura = get(CATALOGOS + "/practicas/" + practica, centro.token());
		assertThat(lectura.status()).isEqualTo(200);
		assertThat(lectura.texto("estado")).isEqualTo("INACTIVO");
		assertThat(lectura.texto("name")).isEqualTo("Sesion motora " + sufijo);
		assertThat(lectura.json().get("vigente").asBoolean())
				.as("estado y vigente son dos cosas distintas: un inactivo no se puede elegir")
				.isFalse();

		// EL CRITERIO DE ACEPTACION DE LA ETAPA, literal: "los catalogos historicos siguen
		// resolviendo y las nuevas selecciones excluyen inactivos", en las dos consultas que un
		// selector usaria.
		assertThat(idsDelListado(centro, "practicas?q=" + sufijo))
				.as("el selector por defecto no ofrece una practica dada de baja")
				.doesNotContain(practica);
		assertThat(idsDelListado(centro, "practicas?estado=TODOS&q=" + sufijo))
				.as("pero la pantalla de administracion la encuentra")
				.contains(practica);

		// El codigo queda libre: es lo que el centinela deleted_key hace posible.
		Respuesta reusado = crearPractica(
				centro, "ORGANIZACION", codigoPractica, "Sesion motora v2 " + sufijo,
				especialidad);
		assertThat(reusado.status())
				.as("el codigo de una practica dada de baja se puede reusar: %s", reusado.body())
				.isEqualTo(201);
		assertThat(filasConCodigo("practica", codigoPractica))
				.as("quedan las dos filas: la dada de baja y la nueva")
				.isEqualTo(2);

		// La especialidad no se puede dar de baja mientras sostenga algo VIGENTE. Ojo con la
		// distincion: la practica dada de baja NO la bloquea; la nueva si.
		Respuesta bajaBloqueada = post(
				CATALOGOS + "/especialidades/" + especialidad + "/deactivate", centro.token(),
				"{\"reason\":\"Se discontinua\"}");
		assertThat(bajaBloqueada.status()).isEqualTo(409);
		assertThat(bajaBloqueada.texto("type"))
				.isEqualTo("https://akine.app/problems/catalogo-has-active-references");
		assertThat(bajaBloqueada.json().get("referenceCount").asLong())
				.as("solo cuenta la practica vigente, no la historica")
				.isEqualTo(1);

		// Aislamiento: para el otro tenant, esos ids no existen. 404 y nunca 403.
		String ruta = CATALOGOS + "/practicas/" + practica;
		assertThat(get(ruta, ajeno.token()).status()).isEqualTo(404);
		assertThat(patch(ruta, ajeno.token(), "{\"name\":\"Intrusa\",\"version\":0}").status())
				.isEqualTo(404);
		assertThat(post(ruta + "/deactivate", ajeno.token(), "{\"reason\":\"x\"}").status())
				.isEqualTo(404);
		assertThat(nombreDe("practica", practica))
				.as("nada de lo anterior toco la practica ajena")
				.isEqualTo("Sesion motora " + sufijo);
	}

	// =================================================================================
	// 3. La carrera de las vigencias, con hilos reales contra MySQL real
	// =================================================================================

	/**
	 * El invariante que la base <b>no puede</b> sostener sola.
	 *
	 * <p>"Dos vigencias del mismo codigo no se pisan" es una restriccion de exclusion sobre
	 * rangos: MySQL 8.4 no las tiene y un UNIQUE no puede expresarla, porque dos ventanas
	 * distintas que se solapan difieren en toda columna. Lo unico que lo sostiene es el
	 * protocolo del servicio —lock exclusivo sobre el nomenclador padre como primera sentencia,
	 * consulta de solapamientos despues, {@code READ_COMMITTED} para que esa consulta vea el
	 * commit del competidor—, y ese protocolo solo se puede ejercer con dos transacciones
	 * reales solapandose.
	 *
	 * <p>Las dos ventanas del test se pisan a proposito. Si el protocolo funciona, gana
	 * exactamente una y la perdedora recibe un conflicto; si no, quedan dos filas vigentes del
	 * mismo codigo y la pregunta "que valor regia el dia D" pasa a tener dos respuestas, con
	 * cual gana dependiendo del orden del indice.
	 *
	 * <p><b>Se invoca el servicio y no el endpoint</b>, igual que en la carrera de espacios: por
	 * HTTP los dos requests comparten el pool de conexiones y el filtro de contexto, y lo que
	 * hay que forzar es que las dos TRANSACCIONES se solapen. La autorizacion no se saltea —el
	 * servicio evalua el permiso igual, contra la membership real—; lo que se saltea es el
	 * transporte.
	 *
	 * <p>La afirmacion es sobre el ESTADO FINAL de la base: un invariante que se cumple da el
	 * mismo resultado hayan corrido en paralelo o no, y lo que un test asi no puede hacer es dar
	 * verde con el invariante roto.
	 */
	@RepeatedTest(5)
	@DisplayName("Dos vigencias solapadas del mismo codigo creadas a la vez: entra exactamente "
			+ "una")
	void dos_vigencias_solapadas_simultaneas_no_entran_las_dos() {
		Sesion centro = altaCompleta("catalogo-carrera");
		String sufijo = sufijo();

		long especialidad = crearEspecialidad(
				centro, "ORGANIZACION", "ESP-" + sufijo, "Especialidad " + sufijo)
				.json().get("id").asLong();
		long practica = crearPractica(
				centro, "ORGANIZACION", "PRA-" + sufijo, "Practica " + sufijo, especialidad)
				.json().get("id").asLong();

		Respuesta nomenclador = crearEspecialidadEnRuta(
				centro, "nomencladores", "ORGANIZACION", "NOM-" + sufijo, "Nomenclador " + sufijo);
		assertThat(nomenclador.status())
				.as("alta de nomenclador: %s", nomenclador.body()).isEqualTo(201);
		long nomencladorId = nomenclador.json().get("id").asLong();

		OperatingActor actor = new OperatingActor(
				centro.cuentaId(), false, centro.organizationId(), centro.consultorioId());

		String codigo = "27.01." + sufijo;
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		// Dos ventanas que se pisan: [base, base+10d) y [base+5d, base+15d).
		List<Concurrencia.Resultado<Long>> resultados = Concurrencia.enParalelo(List.of(
				abrirVigencia(actor, nomencladorId, practica, codigo, base, base.plus(10, ChronoUnit.DAYS)),
				abrirVigencia(actor, nomencladorId, practica, codigo,
						base.plus(5, ChronoUnit.DAYS), base.plus(15, ChronoUnit.DAYS))));

		long exitosas = resultados.stream().filter(r -> !r.fallo()).count();

		assertThat(vigenciasActivas(nomencladorId, codigo))
				.as("una sola vigencia del codigo puede quedar viva: si quedaran dos, la "
						+ "resolucion historica tendria dos respuestas. Desenlaces: %s",
						describir(resultados))
				.isEqualTo(1);
		assertThat(exitosas)
				.as("y la perdedora tiene que enterarse: un conflicto, no un exito falso. "
								+ "Desenlaces: %s", describir(resultados))
				.isEqualTo(1);
	}

	// =================================================================================
	// 4. Solicitud de alta de concepto global (RF-M06-005)
	// =================================================================================

	@Test
	@DisplayName("Una solicitud pendiente no se duplica, la resolucion es terminal, y despues "
			+ "de un rechazo se puede volver a pedir")
	void el_circuito_de_solicitud_es_idempotente_mientras_esta_pendiente() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("catalogo-solicita");
		Sesion ajeno = altaCompleta("catalogo-mira");
		String nombre = "Terapia ocupacional " + sufijo();

		Respuesta pedido = solicitar(centro, nombre);
		assertThat(pedido.status()).as("alta de solicitud: %s", pedido.body()).isEqualTo(201);
		assertThat(pedido.texto("estado")).isEqualTo("PENDIENTE");
		long solicitudId = pedido.json().get("id").asLong();

		// El reintento por timeout no crea una segunda solicitud identica. Lo sostiene el
		// unique sobre la columna generada nombre_pendiente, no una comprobacion previa.
		Respuesta reintento = solicitar(centro, nombre);
		assertThat(reintento.status()).isEqualTo(409);
		assertThat(reintento.texto("type"))
				.isEqualTo("https://akine.app/problems/catalogo-solicitud-duplicada");
		assertThat(solicitudesConNombre(nombre)).isEqualTo(1);

		// La bandeja de plataforma es cross-tenant; la del vecino no ve nada de esto.
		assertThat(idsDeSolicitudes(plataforma)).contains(solicitudId);
		assertThat(idsDeSolicitudes(ajeno)).doesNotContain(solicitudId);

		// Un admin de tenant no resuelve: eso es de la plataforma.
		assertThat(resolver(centro, solicitudId, "RECHAZADA", 0).status()).isEqualTo(403);

		Respuesta rechazo = resolver(plataforma, solicitudId, "RECHAZADA", 0);
		assertThat(rechazo.status()).as("resolucion: %s", rechazo.body()).isEqualTo(200);
		assertThat(rechazo.texto("estado")).isEqualTo("RECHAZADA");

		// Terminal: dos administradores sobre la misma bandeja tienen que enterarse.
		Respuesta segundaResolucion = resolver(plataforma, solicitudId, "APROBADA", 1);
		assertThat(segundaResolucion.status()).isEqualTo(409);
		assertThat(segundaResolucion.texto("type"))
				.isEqualTo("https://akine.app/problems/catalogo-solicitud-ya-resuelta");

		// Y volver a pedir algo ya rechazado SI se puede: es la razon por la que el
		// discriminador del unique es NULL a proposito y no un centinela.
		Respuesta insistencia = solicitar(centro, nombre);
		assertThat(insistencia.status())
				.as("una solicitud resuelta sale del unique: %s", insistencia.body())
				.isEqualTo(201);
		assertThat(solicitudesConNombre(nombre)).isEqualTo(2);
	}

	// =================================================================================
	// Fixtures y utilidades
	// =================================================================================

	private Sesion administradorDePlataforma() {
		Sesion sesion = altaCompleta("catalogo-plataforma");
		sembrarRolDePlataforma(sesion.cuentaId());
		return sesion;
	}

	private static String sufijo() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private Callable<Long> abrirVigencia(
			OperatingActor actor,
			long nomencladorId,
			long practicaId,
			String codigo,
			Instant desde,
			Instant hasta) {

		return () -> catalogoService.crearVigencia(actor, nomencladorId, new VigenciaAltaCommand(
				practicaId, codigo, "Vigencia " + desde, null, null, desde, hasta)).id();
	}

	private Respuesta crearEspecialidad(
			Sesion sesion, String alcance, String codigo, String name) {
		return crearEspecialidadEnRuta(sesion, "especialidades", alcance, codigo, name);
	}

	private Respuesta crearEspecialidadEnRuta(
			Sesion sesion, String segmento, String alcance, String codigo, String name) {

		return post(CATALOGOS + "/" + segmento, sesion.token(),
				"{\"alcance\":\"" + alcance + "\",\"codigo\":\"" + codigo
						+ "\",\"name\":\"" + name + "\"}");
	}

	private Respuesta crearPractica(
			Sesion sesion, String alcance, String codigo, String name, long especialidadId) {

		return post(CATALOGOS + "/practicas", sesion.token(),
				"{\"alcance\":\"" + alcance + "\",\"codigo\":\"" + codigo
						+ "\",\"name\":\"" + name + "\",\"especialidadId\":" + especialidadId
						+ "}");
	}

	private Respuesta solicitar(Sesion sesion, String nombre) {
		return post(SOLICITUDES, sesion.token(),
				"{\"tipo\":\"ESPECIALIDAD\",\"nombrePropuesto\":\"" + nombre
						+ "\",\"justificacion\":\"Tres profesionales del centro la ejercen\"}");
	}

	private Respuesta resolver(Sesion sesion, long solicitudId, String estado, long version) {
		return post(SOLICITUDES + "/" + solicitudId + "/resolve", sesion.token(),
				"{\"estado\":\"" + estado + "\",\"nota\":\"Decision sintetica de prueba\","
						+ "\"version\":" + version + "}");
	}

	private List<Long> idsDelListado(Sesion sesion, String consulta) {
		Respuesta listado = get(CATALOGOS + "/" + consulta, sesion.token());
		assertThat(listado.status()).as("listado %s: %s", consulta, listado.body()).isEqualTo(200);
		return idsDe(listado.json().get("content"));
	}

	private List<Long> idsDeSolicitudes(Sesion sesion) {
		Respuesta listado = get(SOLICITUDES, sesion.token());
		assertThat(listado.status()).as("bandeja: %s", listado.body()).isEqualTo(200);
		return idsDe(listado.json());
	}

	/** Recorrido por indice: no depende de que iterador expone la version de Jackson en uso. */
	private static List<Long> idsDe(JsonNode array) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			ids.add(array.get(i).get("id").asLong());
		}
		return ids;
	}

	private long filasConCodigo(String tabla, String codigo) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM " + tabla + " WHERE codigo = ?", Long.class, codigo);
		return total == null ? 0L : total;
	}

	private List<Long> duenios(String tabla, String codigo) {
		return jdbc.queryForList(
				"SELECT DISTINCT owner_key FROM " + tabla + " WHERE codigo = ?",
				Long.class, codigo);
	}

	private String nombreDe(String tabla, long id) {
		return jdbc.queryForObject(
				"SELECT name FROM " + tabla + " WHERE id = ?", String.class, id);
	}

	private long solicitudesConNombre(String nombre) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM catalogo_solicitud WHERE nombre_propuesto = ?",
				Long.class, nombre);
		return total == null ? 0L : total;
	}

	private long vigenciasActivas(long nomencladorId, String codigo) {
		Long total = jdbc.queryForObject("""
				SELECT COUNT(*) FROM nomenclador_item
				 WHERE nomenclador_id = ? AND codigo = ? AND active = 1
				""", Long.class, nomencladorId, codigo);
		return total == null ? 0L : total;
	}

	private static String describir(List<Concurrencia.Resultado<Long>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo() ? "ERROR " + r.error() : "OK " + r.valor())
				.toList()
				.toString();
	}
}
