package com.akine.diferidos;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akine.organization.application.IdempotencyKeyConflictException;
import com.akine.organization.spi.InitialOrganizationCommand;
import com.akine.organization.spi.InitialOrganizationProvisioning;
import com.akine.organization.spi.ProvisioningResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenarios 6, 7 y 9 de {@code docs/tests-diferidos.md}.
 *
 * <ul>
 *   <li><b>6</b> — CA-001-05, caso QA 4, test 8: dos hilos con la misma clave de idempotencia
 *       crean UN solo tenant.</li>
 *   <li><b>7</b> — diseno sec.10, test 9: {@code Idempotency-Key} repetida por HTTP.</li>
 *   <li><b>9</b> — ADR-0004, test 15: los uniques llevan el alcance tenant.</li>
 * </ul>
 */
class IdempotenciaYUniquesIT extends BaseEscenarioDiferido {

	@Autowired
	private InitialOrganizationProvisioning organizationProvisioning;

	@Autowired
	private PlatformTransactionManager transactionManager;

	// =================================================================================
	// Escenario 6 — reintento concurrente del alta compuesta
	// =================================================================================

	@Test
	@DisplayName("6. Dos hilos con la misma clave de idempotencia crean un solo tenant")
	void el_reintento_concurrente_crea_un_solo_tenant() {
		String clave = UUID.randomUUID().toString();
		String email = "concurrente-" + UUID.randomUUID() + "@ejemplo.test";
		String slug = "concurrente-" + UUID.randomUUID().toString().substring(0, 8);
		String cuerpo = cuerpoDeRegistro(email, "Concurrente", slug, null);

		long organizacionesAntes = contarFilas("organization");

		Callable<Respuesta> intento = () -> post("/api/v1/auth/register", null, cuerpo,
				java.util.Map.of("Idempotency-Key", clave));

		List<Concurrencia.Resultado<Respuesta>> resultados =
				Concurrencia.enParalelo(List.of(intento, intento));

		// El invariante del escenario, que no depende de en que orden respondieron.
		assertThat(contarOrganizaciones(slug))
				.as("un solo tenant, no dos. Respuestas: %s", describir(resultados))
				.isEqualTo(1);
		assertThat(contarFilas("organization"))
				.as("y ninguna organizacion de mas en el resto de la base")
				.isEqualTo(organizacionesAntes + 1);
		assertThat(contarCuentas(email))
				.as("una sola cuenta para ese email")
				.isEqualTo(1);
		assertThat(contarOnboardingsDeIdentidad(clave))
				.as("un solo registro de idempotencia del lado de identity")
				.isEqualTo(1);

		// El invariante de arriba se cumplia incluso con el bug; lo que no se cumplia era la
		// respuesta. Ver el escenario 6-bis, aca abajo.
	}

	@Test
	@DisplayName("6-bis. Los dos hilos del reintento concurrente acusan recibo igual (202)")
	void el_reintento_concurrente_responde_202_a_los_dos() {
		String clave = UUID.randomUUID().toString();
		String email = "concurrente-202-" + UUID.randomUUID() + "@ejemplo.test";
		String slug = "concurrente-202-" + UUID.randomUUID().toString().substring(0, 8);
		String cuerpo = cuerpoDeRegistro(email, "Concurrente202", slug, null);

		Callable<Respuesta> intento = () -> post("/api/v1/auth/register", null, cuerpo,
				java.util.Map.of("Idempotency-Key", clave));

		List<Concurrencia.Resultado<Respuesta>> resultados =
				Concurrencia.enParalelo(List.of(intento, intento));

		assertThat(resultados.stream().map(r -> r.valor().status()).toList())
				.as("el 202 del alta self-service es uniforme por ADR-0018, tambien bajo "
						+ "concurrencia. Respuestas: %s", describir(resultados))
				.containsExactly(202, 202);

		// Mismo status no alcanza: dos cuerpos distintos vuelven a distinguir "se creo" de
		// "ese email ya estaba", que es justo el oraculo que el 202 uniforme cierra.
		assertThat(resultados.stream().map(r -> r.valor().body()).distinct().count())
				.as("y con el MISMO cuerpo: %s", describir(resultados))
				.isEqualTo(1);
	}

	// =================================================================================
	// Escenario 7 — Idempotency-Key repetida por HTTP
	// =================================================================================

	@Test
	@DisplayName("7a. Misma clave y mismo payload: replay, sin crear un segundo tenant")
	void la_misma_clave_con_el_mismo_payload_hace_replay() {
		String clave = UUID.randomUUID().toString();
		String email = "replay-" + UUID.randomUUID() + "@ejemplo.test";
		String slug = "replay-" + UUID.randomUUID().toString().substring(0, 8);

		Respuesta primera = registrar(clave, email, "Replay", slug, null);
		assertThat(primera.status()).isEqualTo(202);
		assertThat(contarOrganizaciones(slug)).isEqualTo(1);

		Respuesta segunda = registrar(clave, email, "Replay", slug, null);

		assertThat(segunda.status())
				.as("el reintento se acusa igual que el original: %s", segunda.body())
				.isEqualTo(202);
		assertThat(segunda.body()).isEqualTo(primera.body());
		assertThat(contarOrganizaciones(slug))
				.as("el replay no crea un segundo tenant")
				.isEqualTo(1);
		assertThat(contarCuentas(email)).isEqualTo(1);
	}

	@Disabled("""
			HALLAZGO ABIERTO — la mitad "payload distinto" del escenario 7 NO esta implementada
			por HTTP. La deteccion existe, pero es inalcanzable desde el endpoint.

			Resultado observado (23/08/2026): la segunda alta, con la MISMA Idempotency-Key y
			otro email, otro nombre y otro slug, responde
			  202 {"message":"Recibimos tu solicitud. ..."}
			en vez de 409 idempotency-key-conflict. No crea nada —eso esta bien— pero le dice
			al cliente que recibio un pedido que en realidad ignoro: la persona queda esperando
			un correo de activacion para una cuenta que nunca se dio de alta.

			CAUSA RAIZ, y es una DECISION DOCUMENTADA, no un descuido:
			  src/main/java/com/akine/identity/api/AccountRegistrationController.java:147
			    // requestHash null: la deduplicacion por contenido no se implementa en 01.02.
			    // La clave sola ya impide el doble submit, que es el caso real.

			El controller pasa requestHash = null, y ademas la tabla onboarding_registro de
			identity (migracion V8) NO tiene columna request_hash: no hay donde guardarlo. El
			replay de identity, en OnboardingService.java:137, encuentra la clave y devuelve el
			desenlace anterior SIN comparar nada. Como identity corta primero, la comparacion
			de hash que SI existe en organization —OnboardingService.replayDe, que lanza
			IdempotencyKeyConflictException— nunca llega a ejecutarse por este camino.

			Que el mecanismo funciona esta probado por el test de al lado,
			el_alta_compuesta_rechaza_la_clave_reusada_con_otro_contenido(), que ejercita el
			alta compuesta por el spi y si obtiene el conflicto. Lo que falta es cablearlo:
			calcular el SHA-256 del payload en el controller y persistirlo en identity, que es
			una migracion mas un cambio de API.

			El otro camino candidato tampoco lo cumple: POST /api/v1/organizations documenta
			explicitamente que su Idempotency-Key no registra idempotencia y que el alta
			duplicada la cierra el unique del slug.

			SIGUE DIFERIDO. Etapa destino sugerida: AKINE-01.03, junto con la deduplicacion por
			contenido. NO SE ARREGLA DESDE ACA.""")
	@Test
	@DisplayName("7b. Misma clave y payload distinto: 409 idempotency-key-conflict")
	void la_misma_clave_con_otro_payload_es_conflicto() {
		String clave = UUID.randomUUID().toString();
		String slug = "conflicto-" + UUID.randomUUID().toString().substring(0, 8);

		Respuesta primera = registrar(
				clave, "conflicto-a-" + UUID.randomUUID() + "@ejemplo.test", "Conflicto", slug, null);
		assertThat(primera.status()).isEqualTo(202);

		// Misma clave, contenido distinto: no es un reintento, es un error del cliente.
		Respuesta segunda = registrar(
				clave, "conflicto-b-" + UUID.randomUUID() + "@ejemplo.test", "OtraCosa",
				slug + "-otro", null);

		assertThat(segunda.status())
				.as("misma clave con otro contenido no puede responder como si fuera el mismo "
						+ "pedido. Respuesta real: %s %s", segunda.status(), segunda.body())
				.isEqualTo(409);
		assertThat(segunda.texto("type"))
				.isEqualTo("https://akine.app/problems/idempotency-key-conflict");
	}

	@Test
	@DisplayName("7c. El conflicto de clave existe y funciona en el alta compuesta de organization")
	void el_alta_compuesta_rechaza_la_clave_reusada_con_otro_contenido() {
		TransactionTemplate plantilla = new TransactionTemplate(transactionManager);
		String clave = UUID.randomUUID().toString();
		long cuentaSintetica = 950_000_000L + System.nanoTime() % 1_000_000L;

		ProvisioningResult primera = plantilla.execute(status -> organizationProvisioning.provision(
				comando(clave, "hash-del-payload-original", cuentaSintetica, "Compuesta A")));
		assertThat(primera).isNotNull();
		assertThat(primera.created()).isTrue();

		// Misma clave, mismo hash: replay, sin crear nada.
		ProvisioningResult replay = plantilla.execute(status -> organizationProvisioning.provision(
				comando(clave, "hash-del-payload-original", cuentaSintetica, "Compuesta A")));
		assertThat(replay).isNotNull();
		assertThat(replay.created()).isFalse();
		assertThat(replay.organizationId()).isEqualTo(primera.organizationId());

		// Misma clave, otro hash: conflicto.
		assertThatThrownBy(() -> plantilla.execute(status -> organizationProvisioning.provision(
				comando(clave, "hash-de-OTRO-payload", cuentaSintetica, "Compuesta B"))))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	private InitialOrganizationCommand comando(
			String clave, String hash, long accountId, String nombre) {

		return new InitialOrganizationCommand(
				clave, hash, accountId, nombre + " " + UUID.randomUUID().toString().substring(0, 8),
				null, null, null);
	}

	// =================================================================================
	// Escenario 9 — uniques con alcance tenant
	// =================================================================================

	@Test
	@DisplayName("9. El mismo nombre de consultorio vale en dos organizaciones y choca dentro de una")
	void el_unique_de_consultorio_tiene_alcance_tenant() {
		String nombreCompartido = "Sede Central " + UUID.randomUUID().toString().substring(0, 8);

		Sesion a = altaCompleta("uniquea", null, nombreCompartido);
		Sesion b = altaCompleta("uniqueb", null, nombreCompartido);

		assertThat(a.organizationId()).isNotEqualTo(b.organizationId());

		// Las dos organizaciones tienen una sede con el MISMO nombre: es valido.
		assertThat(consultoriosLlamados(a.organizationId(), nombreCompartido)).isEqualTo(1);
		assertThat(consultoriosLlamados(b.organizationId(), nombreCompartido)).isEqualTo(1);

		// Dentro de la MISMA organizacion, repetirlo viola uk_consultorio_org_name_vigente.
		// El discriminador deleted_key vale el centinela 1970-01-01 en las dos filas activas,
		// asi que colisionan: es justo lo que un unique sobre deleted_at habria dejado pasar.
		assertThatThrownBy(() -> insertarConsultorio(a.organizationId(), nombreCompartido))
				.as("uk_consultorio_org_name_vigente = (organization_id, name, deleted_key): repetir "
						+ "entre las sedes VIGENTES del tenant tiene que fallar")
				.isInstanceOf(DuplicateKeyException.class);

		assertThat(consultoriosLlamados(a.organizationId(), nombreCompartido))
				.as("el rechazo no dejo ninguna fila a medias")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("9-bis. Una cuenta puede tener una membership por sede, pero una sola por alcance")
	void el_unique_de_membership_admite_una_por_sede() {
		// RN-M02-002: el mismo usuario puede tener roles distintos en consultorios distintos.
		// El unique de V3 —(organization_id, account_id)— lo prohibia; V10 lo expande con el
		// alcance materializado.
		Sesion fundadora = altaCompleta("membresias");
		long otraSede = insertarConsultorio(
				fundadora.organizationId(), "Sede Norte " + UUID.randomUUID().toString().substring(0, 8));

		// La membership del fundador es de alcance ORGANIZACION (consultorio_id NULL). Agregar
		// una acotada a una sede, con OTRO rol, tiene que ser posible.
		insertarMembership(fundadora.organizationId(), otraSede, fundadora.cuentaId(), "PROFESIONAL");
		assertThat(membershipsDe(fundadora.organizationId(), fundadora.cuentaId())).isEqualTo(2);

		// Repetir el MISMO alcance sigue prohibido.
		assertThatThrownBy(() -> insertarMembership(
				fundadora.organizationId(), otraSede, fundadora.cuentaId(), "ADMINISTRATIVO"))
				.as("dos memberships para la misma cuenta en la MISMA sede no pueden convivir")
				.isInstanceOf(DuplicateKeyException.class);

		// Y el caso que un unique ingenuo sobre (organization_id, account_id, consultorio_id)
		// dejaria pasar: en MySQL varios NULL no colisionan, asi que sin el centinela se
		// podrian crear dos memberships de alcance ORGANIZACION para la misma cuenta.
		assertThatThrownBy(() -> insertarMembership(
				fundadora.organizationId(), null, fundadora.cuentaId(), "ADMINISTRATIVO"))
				.as("dos memberships de alcance ORGANIZACION para la misma cuenta tampoco")
				.isInstanceOf(DuplicateKeyException.class);

		// Lo que estaba tapado por el unique viejo: con dos memberships, la resolucion de
		// contexto devolvia IncorrectResultSizeDataAccessException, o sea un 500 en el camino
		// que corre en CADA request.
		Respuesta contextos = get("/api/v1/me/contexts", fundadora.tokenPreContexto());
		assertThat(contextos.status())
				.as("la resolucion de contexto no puede romperse con dos memberships: %s",
						contextos.body())
				.isEqualTo(200);

		Respuesta conContexto = get("/api/v1/organizations/" + fundadora.organizationId(),
				fundadora.token());
		assertThat(conContexto.status())
				.as("y el request autenticado con contexto tampoco: %s", conContexto.body())
				.isEqualTo(200);
	}

	/**
	 * {@code valid_from} un minuto en el pasado, por la misma razon que
	 * {@code sembrarRolDePlataforma}: el reloj del contenedor de MySQL y el de la JVM se
	 * desfasan —hasta un segundo, y en los dos sentidos, dentro de una misma corrida—. Con
	 * {@code UTC_TIMESTAMP(6)} exacto la membership sembrada puede quedar vigente recien un
	 * segundo despues, y la resolucion de contexto no la ve: el escenario de las DOS
	 * memberships pasaria a verificar una sola sin que nadie se entere.
	 */
	private void insertarMembership(
			Long organizationId, Long consultorioId, Long accountId, String rol) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId, rol);
	}

	private long membershipsDe(long organizationId, long accountId) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, organizationId, accountId);
		return total == null ? 0L : total;
	}

	private long insertarConsultorio(long organizationId, String nombre) {
		insertarConsultorioSinId(organizationId, nombre);
		Long id = jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
		return id == null ? 0L : id;
	}

	private void insertarConsultorioSinId(long organizationId, String nombre) {
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre);
	}

	// =================================================================================
	// Consultas de apoyo
	// =================================================================================

	private long contarOrganizaciones(String slug) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM organization WHERE slug = ?", Long.class, slug);
		return total == null ? 0L : total;
	}

	private long contarCuentas(String email) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE email_normalizado = ?",
				Long.class, normalizar(email));
		return total == null ? 0L : total;
	}

	private long contarOnboardingsDeIdentidad(String clave) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM onboarding_registro WHERE clave_idempotencia = ?",
				Long.class, clave);
		return total == null ? 0L : total;
	}

	private long consultoriosLlamados(long organizationId, String nombre) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
		return total == null ? 0L : total;
	}

	private static String describir(List<Concurrencia.Resultado<Respuesta>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo()
						? "ERROR " + r.error()
						: r.valor().status() + " " + r.valor().body())
				.toList()
				.toString();
	}
}
