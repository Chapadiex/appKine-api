package com.akine.diferidos;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.organization.application.ConsultorioService;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.domain.exception.LastConsultorioException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-02.01 de punta a punta: alta, edicion y baja de sedes contra el stack real.
 *
 * <h2>Por que estos casos y no treinta</h2>
 *
 * <p>Lo que 02.01 introduce y ningun test unitario puede probar es <b>estado persistido</b>:
 * un unique con una columna generada, un invariante de conteo bajo concurrencia y un rollback.
 * Cada metodo de abajo cubre uno de esos, por el camino real —HTTP, autenticacion Argon2id,
 * contexto canjeado, MySQL de verdad— y afirma contra la BASE, no contra el cuerpo de la
 * respuesta. Un 200 no prueba que el dato quedo bien guardado.
 *
 * <p>Las reglas que si se pueden decidir sin base —validacion de zona horaria, motivo
 * obligatorio, campos vacios— viven en los unitarios de {@code ConsultorioTest} y
 * {@code ZonasHorariasTest}, y no se repiten aca: repetirlas costaria minutos de Testcontainers
 * por corrida y no encontraria nada nuevo.
 */
class ConsultoriosIT extends BaseEscenarioDiferido {

	@Autowired
	private ConsultorioService consultorioService;

	// =================================================================================
	// El unique de nombre y la trampa de los NULL (migracion V18)
	// =================================================================================

	@Test
	@DisplayName("Dos sedes activas con el mismo nombre colisionan, pero el nombre de una sede "
			+ "dada de baja se puede reusar")
	void el_unique_protege_lo_vigente_y_libera_lo_historico() {
		Sesion sesion = altaCompleta("sedes-unique");
		habilitarMasDeUnaSede(sesion);

		Respuesta primera = crearSede(sesion, "Sede Norte");
		assertThat(primera.status()).as("alta de la sede: %s", primera.body()).isEqualTo(201);

		// 1. Dos VIGENTES con el mismo nombre: colisionan. Es el caso que importa, y es el que
		//    un UNIQUE (organization_id, name, deleted_at) ingenuo habria dejado pasar, porque
		//    en MySQL dos NULL no colisionan.
		Respuesta repetida = crearSede(sesion, "Sede Norte");
		assertThat(repetida.status()).isEqualTo(409);
		assertThat(repetida.texto("type"))
				.isEqualTo("https://akine.app/problems/consultorio-name-taken");
		assertProblemaLimpio(repetida);

		// 2. Se da de baja y el nombre queda libre: la fila vieja no se toca y la nueva entra.
		long sedeId = primera.json().get("id").asLong();
		Respuesta baja = post(
				"/api/v1/organizations/" + sesion.organizationId() + "/consultorios/" + sedeId
						+ "/deactivate",
				sesion.token(),
				"{\"reason\":\"Mudanza de la sucursal\"}");
		assertThat(baja.status()).as("baja de la sede: %s", baja.body()).isEqualTo(200);

		Respuesta reusada = crearSede(sesion, "Sede Norte");
		assertThat(reusada.status())
				.as("el nombre de una sede dada de baja se puede reusar: %s", reusada.body())
				.isEqualTo(201);

		// 3. Y la historica sigue ahi, sin tocar. Baja LOGICA: cero borrados fisicos.
		assertThat(sedesConNombre(sesion.organizationId(), "Sede Norte"))
				.as("quedan las dos filas: la dada de baja y la nueva")
				.isEqualTo(2);
		assertThat(motivoDeBaja(sedeId)).isEqualTo("Mudanza de la sucursal");
	}

	// =================================================================================
	// Estados de la sede (RN-M03-003, RF-M03-004)
	// =================================================================================

	@Test
	@DisplayName("Una sede inactiva se lee con 200 y rechaza edicion y segunda baja con 409")
	void la_sede_inactiva_se_lee_pero_no_se_muta() {
		Sesion sesion = altaCompleta("sedes-estado");
		habilitarMasDeUnaSede(sesion);
		long sedeId = crearSede(sesion, "Sede Sur").json().get("id").asLong();
		String base = "/api/v1/organizations/" + sesion.organizationId() + "/consultorios/" + sedeId;

		assertThat(post(base + "/deactivate", sesion.token(), "{\"reason\":\"Cierre\"}").status())
				.isEqualTo(200);

		// Legible: RF-M03-004 exige que los historicos previos permanezcan disponibles. Un 404
		// aca borraria historia por la puerta de atras.
		Respuesta lectura = get(base, sesion.token());
		assertThat(lectura.status()).isEqualTo(200);
		assertThat(lectura.texto("estado")).isEqualTo("INACTIVO");

		Respuesta edicion = patch(base, sesion.token(), "{\"name\":\"Sede Sur 2\",\"version\":1}");
		assertThat(edicion.status()).isEqualTo(409);
		assertThat(edicion.texto("type"))
				.isEqualTo("https://akine.app/problems/consultorio-inactive");

		Respuesta segundaBaja =
				post(base + "/deactivate", sesion.token(), "{\"reason\":\"Otra vez\"}");
		assertThat(segundaBaja.status()).isEqualTo(409);
		assertThat(segundaBaja.texto("type"))
				.isEqualTo("https://akine.app/problems/consultorio-already-inactive");
	}

	// =================================================================================
	// Aislamiento de tenant
	// =================================================================================

	@Test
	@DisplayName("Una sede del tenant A responde 404 al token del tenant B, nunca 403")
	void cross_tenant_es_404() {
		Sesion duenio = altaCompleta("sedes-tenant-a");
		Sesion ajeno = altaCompleta("sedes-tenant-b");

		long sedeAjena = duenio.consultorioId();
		// La ruta lleva la organizacion del OTRO tenant: es el ataque literal, un id ajeno
		// inyectado en la URL. Un 403 confirmaria que existe y bastaria recorrer ids.
		String ruta = "/api/v1/organizations/" + duenio.organizationId()
				+ "/consultorios/" + sedeAjena;

		assertThat(get(ruta, ajeno.token()).status()).isEqualTo(404);
		assertThat(patch(ruta, ajeno.token(), "{\"name\":\"Robada\",\"version\":0}").status())
				.isEqualTo(404);
		assertThat(post(ruta + "/deactivate", ajeno.token(), "{\"reason\":\"x\"}").status())
				.isEqualTo(404);

		assertThat(sigueActiva(sedeAjena)).as("nada de lo anterior toco la sede ajena").isTrue();
	}

	// =================================================================================
	// Idempotencia del alta (CA-M03-001-05)
	// =================================================================================

	@Test
	@DisplayName("El reintento con la misma clave devuelve la misma sede; con otro cuerpo, 409")
	void la_clave_de_idempotencia_cubre_el_reintento_por_timeout() {
		Sesion sesion = altaCompleta("sedes-idem");
		habilitarMasDeUnaSede(sesion);
		String clave = UUID.randomUUID().toString();

		Respuesta primera = crearSede(sesion, "Sede Este", clave);
		assertThat(primera.status()).isEqualTo(201);

		Respuesta reintento = crearSede(sesion, "Sede Este", clave);
		assertThat(reintento.status()).isEqualTo(201);
		assertThat(reintento.json().get("id").asLong())
				.as("el reintento devuelve la MISMA sede, no una segunda")
				.isEqualTo(primera.json().get("id").asLong());
		assertThat(sedesConNombre(sesion.organizationId(), "Sede Este")).isEqualTo(1);

		// Misma clave, otro contenido: es un error del cliente, no un reintento. Devolverle el
		// resultado viejo lo dejaria creyendo que se creo lo que pidio ahora.
		Respuesta otroCuerpo = crearSede(sesion, "Sede Oeste", clave);
		assertThat(otroCuerpo.status()).isEqualTo(409);
		assertThat(otroCuerpo.texto("type"))
				.isEqualTo("https://akine.app/problems/idempotency-key-conflict");
	}

	// =================================================================================
	// El invariante de la ultima sede activa (D-7) y su carrera
	// =================================================================================

	@Test
	@DisplayName("La baja de la unica sede activa responde 409 y la sede sigue activa EN LA BASE")
	void la_ultima_sede_no_se_puede_dar_de_baja() {
		Sesion sesion = altaCompleta("sedes-ultima");

		Respuesta baja = post(
				"/api/v1/organizations/" + sesion.organizationId()
						+ "/consultorios/" + sesion.consultorioId() + "/deactivate",
				sesion.token(),
				"{\"reason\":\"Cierro todo\"}");

		assertThat(baja.status()).isEqualTo(409);
		assertThat(baja.texto("type"))
				.isEqualTo("https://akine.app/problems/last-consultorio-required");
		assertProblemaLimpio(baja);
		assertThat(sigueActiva(sesion.consultorioId()))
				.as("el rechazo tiene que verse en la BASE, no solo en la respuesta")
				.isTrue();
	}

	/**
	 * La carrera de D-7, con hilos reales contra MySQL real.
	 *
	 * <h2>Que rompe si el protocolo esta mal</h2>
	 *
	 * <p>El tenant tiene DOS sedes activas y dos hilos intentan dar de baja una cada uno. Cada
	 * transaccion cuenta "queda otra activa ademas de la que estoy bajando", las dos ven a la
	 * otra, las dos pasan, y el tenant queda <b>sin ninguna sede activa</b>: nadie puede canjear
	 * contexto, {@code GET /me/contexts} devuelve vacio para todos y solo se recupera con SQL
	 * manual contra la base.
	 *
	 * <p>Lo que lo cierra son dos cosas, y hacen falta las dos:
	 * <ul>
	 *   <li>el lock de {@code subscription} como PRIMERA sentencia, que serializa el acceso;</li>
	 *   <li>el conteo con {@code FOR SHARE}, que serializa la VISIBILIDAD. Sin el, el lock ya
	 *       tomado no alcanza: en {@code REPEATABLE READ} la lectura consistente del conteo
	 *       devuelve datos anteriores al commit del competidor. Es exactamente el bug que
	 *       {@code LimiteDePlanConcurrenteIT} encontro sobre el limite de plan.</li>
	 * </ul>
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
	 * proceso de test, asi que se corre varias veces. Con el {@code FOR SHARE} quitado, falla.
	 */
	@RepeatedTest(5)
	@DisplayName("Dos bajas simultaneas con dos sedes activas: sobrevive exactamente una")
	void dos_bajas_simultaneas_no_dejan_al_tenant_sin_sedes() {
		Sesion sesion = altaCompleta("sedes-carrera");
		habilitarMasDeUnaSede(sesion);
		long segunda = crearSede(sesion, "Sede Paralela " + UUID.randomUUID()).json()
				.get("id").asLong();

		OperatingActor actor = new OperatingActor(sesion.cuentaId(), false, sesion.consultorioId());
		List<Concurrencia.Resultado<Long>> resultados = Concurrencia.enParalelo(List.of(
				baja(actor, sesion.organizationId(), sesion.consultorioId()),
				baja(actor, sesion.organizationId(), segunda)));

		long rechazos = resultados.stream()
				.filter(Concurrencia.Resultado::fallo)
				.filter(r -> causaEs(r.error(), LastConsultorioException.class))
				.count();

		assertThat(sedesActivas(sesion.organizationId()))
				.as("el tenant NUNCA puede quedarse sin sedes activas. Desenlaces: %s",
						describir(resultados))
				.isEqualTo(1);
		assertThat(rechazos)
				.as("la perdedora recibe last-consultorio-required, no un error de "
						+ "infraestructura. Desenlaces: %s", describir(resultados))
				.isEqualTo(1);
	}

	// =================================================================================
	// Utilidades
	// =================================================================================

	private Callable<Long> baja(OperatingActor actor, long organizationId, long consultorioId) {
		return () -> consultorioService
				.deactivate(actor, organizationId, consultorioId, "Baja concurrente sintetica")
				.id();
	}

	private Respuesta crearSede(Sesion sesion, String nombre) {
		return crearSede(sesion, nombre, UUID.randomUUID().toString());
	}

	private Respuesta crearSede(Sesion sesion, String nombre, String clave) {
		return post(
				"/api/v1/organizations/" + sesion.organizationId() + "/consultorios",
				sesion.token(),
				"{\"name\":\"" + nombre + "\",\"timezone\":\"America/Argentina/Ushuaia\"}",
				Map.of("Idempotency-Key", clave));
	}

	private long sedesActivas(long organizationId) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM consultorio WHERE organization_id = ? AND active = 1",
				Long.class, organizationId);
		return total == null ? 0L : total;
	}

	private long sedesConNombre(long organizationId, String nombre) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
		return total == null ? 0L : total;
	}

	private boolean sigueActiva(long consultorioId) {
		Long activa = jdbc.queryForObject(
				"SELECT active FROM consultorio WHERE id = ?", Long.class, consultorioId);
		return activa != null && activa == 1L;
	}

	private String motivoDeBaja(long consultorioId) {
		return jdbc.queryForObject(
				"SELECT deactivation_reason FROM consultorio WHERE id = ?",
				String.class, consultorioId);
	}

	private static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	private static String describir(List<Concurrencia.Resultado<Long>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo() ? "ERROR " + r.error() : "OK " + r.valor())
				.toList()
				.toString();
	}

	/**
	 * Deja al tenant en un plan sin tope de sedes.
	 *
	 * <p><b>Hace falta, y decirlo es parte del resultado de la etapa:</b> el plan por defecto del
	 * alta self-service es BASICO, y BASICO tiene {@code MAX_CONSULTORIOS = 1} (seed de la
	 * migracion V4). O sea que <b>un tenant recien registrado no puede crear ni una sola sede
	 * adicional</b> hasta cambiar de plan. No es un bug de 02.01 —el limite se respeta como
	 * corresponde— pero si es lo primero que va a encontrar cualquiera que pruebe la pantalla
	 * nueva con una cuenta nueva.
	 *
	 * <p>El cambio de plan va por el <b>endpoint real</b> y no por SQL: es una operacion del
	 * producto y tiene su propio camino. Lo unico que se siembra es el rol de plataforma, que es
	 * lo que ese endpoint exige y lo que en produccion entra por el seed de V15; el mismo atajo,
	 * con el mismo motivo, que ya documenta {@code BaseEscenarioDiferido}.
	 *
	 * <p>El rol se le da a una cuenta APARTE y nunca a la fundadora del tenant bajo prueba: si se
	 * lo diera a ella, sus operaciones sobre sedes pasarian a evaluarse como
	 * {@code PLATFORM_ADMIN} con alcance GLOBAL y estos tests dejarian de ejercitar el camino del
	 * {@code ORG_ADMIN}, que es el que importa.
	 */
	private void habilitarMasDeUnaSede(Sesion sesion) {
		Sesion plataforma = altaCompleta("plataforma");
		sembrarRolDePlataforma(plataforma.cuentaId());

		Respuesta cambio = post(
				"/api/v1/organizations/" + sesion.organizationId() + "/subscription/plan-changes",
				plataforma.token(),
				"{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":0}");

		assertThat(cambio.status())
				.as("el tenant necesita un plan sin tope de sedes: %s", cambio.body())
				.isEqualTo(200);
	}
}
