package com.akine.diferidos;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import com.akine.resource.application.EspacioEdicionCommand;
import com.akine.resource.application.EspacioService;
import com.akine.resource.application.OperatingActor;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-02.02 de punta a punta: catalogo fisico de una sede contra el stack real.
 *
 * <h2>Por que estos casos y no treinta</h2>
 *
 * <p>Lo que 02.02 introduce y ningun test unitario puede probar es <b>estado persistido</b>: un
 * unique con centinela sobre una columna generada, el aislamiento de tenant sobre una ruta
 * anidada, y una actualizacion perdida bajo concurrencia. Cada metodo de abajo cubre uno de
 * esos, por el camino real —HTTP, autenticacion Argon2id, contexto canjeado, MySQL de verdad— y
 * afirma contra la BASE, no contra el cuerpo de la respuesta. Un 200 no prueba que el dato
 * quedo bien guardado.
 *
 * <p>Las reglas que si se pueden decidir sin base —los bordes de la ventana operativa, la
 * capacidad positiva, el motivo obligatorio— viven en {@code EspacioTest} y <b>no se repiten
 * aca</b>: repetirlas costaria minutos de Testcontainers por corrida y no encontraria nada
 * nuevo.
 *
 * <p><b>El tenant NO necesita cambiar de plan para estos tests</b>, a diferencia de los de
 * sedes: <b>no existe ningun limite de plan sobre los espacios</b> y 02.02 deliberadamente no
 * creo uno. Ningun RF de M04 declara un tope, e inventarlo bloquearia en silencio a los tenants
 * que ya existen.
 */
class EspaciosIT extends BaseEscenarioDiferido {

	@Autowired
	private EspacioService espacioService;

	// =================================================================================
	// El unique con centinela y la baja logica (migracion V19, RN-M04-003, RF-M04-006)
	// =================================================================================

	@Test
	@DisplayName("Dos espacios activos homonimos colisionan; el nombre de uno dado de baja se "
			+ "reusa y el historico conserva nombre y estado")
	void el_unique_protege_lo_vigente_y_libera_lo_historico() {
		Sesion sesion = altaCompleta("espacios-unique");

		Respuesta primero = crearEspacio(sesion, "Box 1", 1);
		assertThat(primero.status()).as("alta del espacio: %s", primero.body()).isEqualTo(201);
		long boxId = primero.json().get("id").asLong();

		// 1. Dos VIGENTES con el mismo nombre en la MISMA sede: colisionan. Es el caso que
		//    importa, y es el que un UNIQUE (..., name, deleted_at) ingenuo habria dejado pasar,
		//    porque en MySQL dos NULL no colisionan.
		Respuesta repetido = crearEspacio(sesion, "Box 1", 1);
		assertThat(repetido.status()).isEqualTo(409);
		assertThat(repetido.texto("type"))
				.isEqualTo("https://akine.app/problems/espacio-name-taken");
		assertProblemaLimpio(repetido);

		// 2. Se da de baja y el nombre queda libre: la fila vieja no se toca y la nueva entra.
		Respuesta baja = post(rutaEspacio(sesion, boxId) + "/deactivate", sesion.token(),
				"{\"reason\":\"Refaccion definitiva del ala oeste\"}");
		assertThat(baja.status()).as("baja del espacio: %s", baja.body()).isEqualTo(200);
		assertThat(baja.texto("estado")).isEqualTo("INACTIVO");

		Respuesta reusado = crearEspacio(sesion, "Box 1", 1);
		assertThat(reusado.status())
				.as("el nombre de un espacio dado de baja se puede reusar: %s", reusado.body())
				.isEqualTo(201);

		// 3. Y el historico sigue ahi, sin tocar. Baja LOGICA: cero borrados fisicos, y el
		//    nombre y el estado se conservan (RN-M04-003).
		assertThat(espaciosConNombre(sesion.consultorioId(), "Box 1"))
				.as("quedan las dos filas: la dada de baja y la nueva")
				.isEqualTo(2);
		assertThat(motivoDeBaja(boxId)).isEqualTo("Refaccion definitiva del ala oeste");
		assertThat(nombreDe(boxId)).as("el historico conserva su nombre").isEqualTo("Box 1");
		assertThat(sigueActivo(boxId)).isFalse();
	}

	// =================================================================================
	// Estados del espacio (RN-M04-002, RN-M04-003) y el criterio de aceptacion de la etapa
	// =================================================================================

	@Test
	@DisplayName("Un espacio inactivo se lee con 200, rechaza edicion y segunda baja con 409, y "
			+ "desaparece del listado por defecto y de la disponibilidad")
	void el_espacio_inactivo_se_lee_pero_no_se_muta_ni_se_ofrece() {
		Sesion sesion = altaCompleta("espacios-estado");
		long boxId = crearEspacio(sesion, "Box Sur", 1).json().get("id").asLong();
		String base = rutaEspacio(sesion, boxId);

		assertThat(post(base + "/deactivate", sesion.token(), "{\"reason\":\"Cierre\"}").status())
				.isEqualTo(200);

		// Legible: RN-M04-003 exige que los historicos conserven nombre y estado. Un 404 aca
		// borraria historia por la puerta de atras.
		Respuesta lectura = get(base, sesion.token());
		assertThat(lectura.status()).isEqualTo(200);
		assertThat(lectura.texto("estado")).isEqualTo("INACTIVO");
		assertThat(lectura.texto("name")).isEqualTo("Box Sur");
		assertThat(lectura.json().get("enServicio").asBoolean())
				.as("estado y enServicio son dos cosas distintas, y un inactivo no se ofrece")
				.isFalse();

		Respuesta edicion = patch(base, sesion.token(), "{\"capacidad\":2,\"version\":1}");
		assertThat(edicion.status()).as("edicion de inactivo: %s", edicion.body()).isEqualTo(409);
		assertThat(edicion.texto("type"))
				.isEqualTo("https://akine.app/problems/espacio-inactive");

		Respuesta segundaBaja = post(base + "/deactivate", sesion.token(), "{\"reason\":\"Otra\"}");
		assertThat(segundaBaja.status()).isEqualTo(409);
		assertThat(segundaBaja.texto("type"))
				.isEqualTo("https://akine.app/problems/espacio-already-inactive");

		// EL CRITERIO DE ACEPTACION DE LA ETAPA, literal: "los recursos historicos no se pierden
		// y las nuevas selecciones excluyen inactivos". Las dos mitades, en las dos consultas
		// que un selector usaria.
		assertThat(idsDelListado(sesion, "ACTIVO"))
				.as("el listado por defecto no ofrece un espacio dado de baja")
				.doesNotContain(boxId);
		assertThat(idsDelListado(sesion, "TODOS"))
				.as("pero sigue estando, y la pantalla de administracion lo encuentra")
				.contains(boxId);
		assertThat(idsDeDisponibilidad(sesion))
				.as("y la consulta de disponibilidad tampoco lo ofrece (RN-M04-002)")
				.doesNotContain(boxId);
	}

	@Test
	@DisplayName("Un espacio fuera de su ventana operativa esta ACTIVO pero no se ofrece")
	void la_ventana_operativa_es_un_eje_distinto_de_la_baja() {
		Sesion sesion = altaCompleta("espacios-vigencia");

		// Entra en servicio dentro de un año: activo, pero no reservable hoy. Es la diferencia
		// que la pantalla tiene que poder explicar, y la que un unico booleano no expresa.
		Instant futuro = Instant.now().plus(365, ChronoUnit.DAYS);
		Respuesta alta = post(rutaEspacios(sesion), sesion.token(),
				"{\"name\":\"Sala Futura\",\"tipo\":\"SALA_GRUPAL\",\"capacidad\":12,"
						+ "\"validFrom\":\"" + futuro + "\"}");
		assertThat(alta.status()).as("alta con vigencia futura: %s", alta.body()).isEqualTo(201);

		long id = alta.json().get("id").asLong();
		assertThat(alta.texto("estado")).isEqualTo("ACTIVO");
		assertThat(alta.json().get("enServicio").asBoolean()).isFalse();

		assertThat(idsDelListado(sesion, "ACTIVO"))
				.as("sigue en el catalogo: no esta dado de baja")
				.contains(id);
		assertThat(idsDeDisponibilidad(sesion))
				.as("pero no se ofrece para una ventana en la que todavia no existe")
				.doesNotContain(id);
	}

	// =================================================================================
	// Aislamiento de tenant
	// =================================================================================

	@Test
	@DisplayName("Un espacio del tenant A responde 404 al token del tenant B, nunca 403")
	void cross_tenant_es_404() {
		Sesion duenio = altaCompleta("espacios-tenant-a");
		Sesion ajeno = altaCompleta("espacios-tenant-b");

		long espacioAjeno = crearEspacio(duenio, "Box Privado", 1).json().get("id").asLong();

		// La ruta lleva la organizacion Y la sede del OTRO tenant: es el ataque literal, ids
		// ajenos inyectados en la URL. Un 403 confirmaria que existen y bastaria recorrerlos.
		String ruta = rutaEspacio(duenio, espacioAjeno);

		assertThat(get(ruta, ajeno.token()).status()).isEqualTo(404);
		assertThat(patch(ruta, ajeno.token(), "{\"capacidad\":99,\"version\":0}").status())
				.isEqualTo(404);
		assertThat(post(ruta + "/deactivate", ajeno.token(), "{\"reason\":\"x\"}").status())
				.isEqualTo(404);
		assertThat(get(rutaEspacios(duenio) + "?estado=TODOS", ajeno.token()).status())
				.isEqualTo(404);
		assertThat(post(rutaEspacios(duenio), ajeno.token(),
				"{\"name\":\"Box Intruso\"}").status()).isEqualTo(404);

		assertThat(sigueActivo(espacioAjeno))
				.as("nada de lo anterior toco el espacio ajeno")
				.isTrue();
		assertThat(capacidadDe(espacioAjeno)).isEqualTo(1);
		assertThat(espaciosConNombre(duenio.consultorioId(), "Box Intruso"))
				.as("y el alta cross-tenant no creo ninguna fila")
				.isZero();
	}

	// =================================================================================
	// La carrera de la capacidad, con hilos reales contra MySQL real
	// =================================================================================

	/**
	 * El caso borde <b>"capacidad reducida bajo ocupacion"</b> de la etapa, en la mitad que hoy
	 * se puede ejercer.
	 *
	 * <h2>Que se prueba y que NO, dicho antes de leer las aserciones</h2>
	 *
	 * <p>La comprobacion contra la OCUPACION —el pico de lugares ya comprometidos— no se puede
	 * ejercer todavia: la declara {@code resource.spi.EspacioOccupancyProbe} y <b>no existe
	 * ninguna implementacion</b>, porque {@code scheduling} llega en F5 y {@code activity} en
	 * M28. Escribir un test con una sonda de mentira probaria el mock, no el sistema.
	 *
	 * <p>Lo que SI existe hoy, y es lo que puede romper hoy, es la <b>actualizacion perdida</b>:
	 * dos reducciones simultaneas de la misma capacidad donde la segunda pisa a la primera y
	 * el administrador que bajo de 10 a 4 termina con 6 sin enterarse. Eso es lo que este test
	 * ejerce, y es el mismo protocolo —lock de la fila como primera lectura, comparacion de
	 * version despues— que en F5 va a sostener la comprobacion de ocupacion.
	 *
	 * <p><b>Se invoca el servicio y no el endpoint</b> a proposito: por HTTP los dos requests
	 * comparten el pool de conexiones y el filtro de contexto, y lo que hay que forzar es que
	 * las dos TRANSACCIONES se solapen. La autorizacion no se saltea —el servicio evalua el
	 * permiso igual, contra la membership real de la sesion—; lo que se saltea es el transporte.
	 *
	 * <p>La afirmacion es sobre el ESTADO FINAL de la base y no sobre el orden de las
	 * respuestas: un invariante que se cumple da el mismo resultado hayan corrido en paralelo o
	 * no, y lo que un test asi no puede hacer es dar verde con el invariante roto.
	 *
	 * <p>{@code @RepeatedTest}: la concurrencia no se puede forzar de forma absoluta desde el
	 * proceso de test, asi que se corre varias veces.
	 */
	@RepeatedTest(5)
	@DisplayName("Dos reducciones simultaneas de capacidad: gana exactamente una y la perdedora "
			+ "recibe un conflicto de version, no un pisotón silencioso")
	void dos_reducciones_simultaneas_no_se_pisan() {
		Sesion sesion = altaCompleta("espacios-carrera");
		Respuesta alta = post(rutaEspacios(sesion), sesion.token(),
				"{\"name\":\"Gimnasio " + UUID.randomUUID() + "\",\"tipo\":\"GIMNASIO\","
						+ "\"capacidad\":10}");
		assertThat(alta.status()).as("alta del gimnasio: %s", alta.body()).isEqualTo(201);
		long espacioId = alta.json().get("id").asLong();

		OperatingActor actor = new OperatingActor(
				sesion.cuentaId(), false, sesion.organizationId(), sesion.consultorioId());

		List<Concurrencia.Resultado<Integer>> resultados = Concurrencia.enParalelo(List.of(
				reducir(actor, sesion, espacioId, 4),
				reducir(actor, sesion, espacioId, 6)));

		long conflictos = resultados.stream()
				.filter(Concurrencia.Resultado::fallo)
				.filter(r -> causaEs(r.error(), OptimisticLockingFailureException.class))
				.count();

		assertThat(capacidadDe(espacioId))
				.as("la capacidad final tiene que ser la de UNA de las dos, nunca una mezcla ni "
						+ "el valor original. Desenlaces: %s", describir(resultados))
				.isIn(4, 6);
		assertThat(versionDe(espacioId))
				.as("una sola escritura llego a la base: dos habrian dejado version 2. "
						+ "Desenlaces: %s", describir(resultados))
				.isEqualTo(1L);
		assertThat(conflictos)
				.as("la perdedora recibe un conflicto de version —409 para el cliente—, no un "
						+ "error de infraestructura ni un exito falso. Desenlaces: %s",
						describir(resultados))
				.isEqualTo(1);
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private Callable<Integer> reducir(
			OperatingActor actor, Sesion sesion, long espacioId, int capacidad) {

		return () -> espacioService.update(
				actor, sesion.organizationId(), sesion.consultorioId(), espacioId,
				new EspacioEdicionCommand(null, null, capacidad, null, null, null, false, 0L))
				.capacidad();
	}

	private String rutaEspacios(Sesion sesion) {
		return "/api/v1/organizations/" + sesion.organizationId()
				+ "/consultorios/" + sesion.consultorioId() + "/espacios";
	}

	private String rutaEspacio(Sesion sesion, long espacioId) {
		return rutaEspacios(sesion) + "/" + espacioId;
	}

	private Respuesta crearEspacio(Sesion sesion, String nombre, int capacidad) {
		return post(rutaEspacios(sesion), sesion.token(),
				"{\"name\":\"" + nombre + "\",\"tipo\":\"BOX\",\"capacidad\":" + capacidad + "}");
	}

	private List<Long> idsDelListado(Sesion sesion, String estado) {
		Respuesta listado = get(rutaEspacios(sesion) + "?estado=" + estado, sesion.token());
		assertThat(listado.status()).as("listado %s: %s", estado, listado.body()).isEqualTo(200);
		return idsDe(listado.json().get("content"), "id");
	}

	private List<Long> idsDeDisponibilidad(Sesion sesion) {
		Instant desde = Instant.now();
		Respuesta disponibilidad = get(rutaEspacios(sesion) + "/availability"
				+ "?desde=" + desde + "&hasta=" + desde.plus(1, ChronoUnit.HOURS),
				sesion.token());
		assertThat(disponibilidad.status())
				.as("disponibilidad: %s", disponibilidad.body()).isEqualTo(200);
		return idsDe(disponibilidad.json(), "espacioId");
	}

	/** Recorrido por indice: no depende de que iterador expone la version de Jackson en uso. */
	private static List<Long> idsDe(JsonNode array, String campo) {
		List<Long> ids = new java.util.ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			ids.add(array.get(i).get(campo).asLong());
		}
		return ids;
	}

	private long espaciosConNombre(long consultorioId, String nombre) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM espacio WHERE consultorio_id = ? AND name = ?",
				Long.class, consultorioId, nombre);
		return total == null ? 0L : total;
	}

	private boolean sigueActivo(long espacioId) {
		Long activo = jdbc.queryForObject(
				"SELECT active FROM espacio WHERE id = ?", Long.class, espacioId);
		return activo != null && activo == 1L;
	}

	private int capacidadDe(long espacioId) {
		Integer capacidad = jdbc.queryForObject(
				"SELECT capacidad FROM espacio WHERE id = ?", Integer.class, espacioId);
		return capacidad == null ? -1 : capacidad;
	}

	private long versionDe(long espacioId) {
		Long version = jdbc.queryForObject(
				"SELECT version FROM espacio WHERE id = ?", Long.class, espacioId);
		return version == null ? -1L : version;
	}

	private String nombreDe(long espacioId) {
		return jdbc.queryForObject("SELECT name FROM espacio WHERE id = ?", String.class, espacioId);
	}

	private String motivoDeBaja(long espacioId) {
		return jdbc.queryForObject(
				"SELECT deactivation_reason FROM espacio WHERE id = ?", String.class, espacioId);
	}

	private static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	private static String describir(List<Concurrencia.Resultado<Integer>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo() ? "ERROR " + r.error() : "OK " + r.valor())
				.toList()
				.toString();
	}
}
