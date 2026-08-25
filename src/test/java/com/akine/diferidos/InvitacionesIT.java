package com.akine.diferidos;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-02.03 de punta a punta: el ciclo de vida de la invitacion contra el stack real.
 *
 * <h2>Por que estos casos y no veinte</h2>
 *
 * <p>Lo que 02.03 introduce y ningun test unitario puede probar es <b>estado persistido y el
 * cruce de dos modulos</b>: la invitacion la escribe {@code identity} y el vinculo lo crea
 * {@code organization}, en la misma transaccion, sin que el invitado tenga permiso sobre nada.
 * Cada metodo de abajo afirma contra la BASE y no contra el cuerpo de la respuesta: un 200 no
 * prueba que el dato quedo bien guardado.
 *
 * <ol>
 *   <li><b>El circuito completo de quien no tiene cuenta</b>: invitar, consultar sin consumir,
 *       aceptar creando la cuenta ACTIVA y el vinculo. Es el caso que justifica la etapa
 *       entera, y el unico donde tres tablas se mueven juntas.</li>
 *   <li><b>El unique de invitacion pendiente</b>, que es el riesgo tecnico de la migracion: dos
 *       centinelas —{@code consultorio_key} y {@code resuelta_key}— porque en MySQL varios NULL
 *       no colisionan, y sin ellos la regla protege lo historico y desprotege lo vigente.</li>
 *   <li><b>El aislamiento</b>: una invitacion de otro tenant responde 404, nunca 403.</li>
 *   <li><b>El rechazo no deja rastro de cuenta</b>, que es lo que hace que rechazar sea
 *       realmente decir que no y no un alta encubierta.</li>
 * </ol>
 *
 * <p>Las reglas que se deciden sin base —terminalidad de los estados, motivo obligatorio al
 * cancelar, expiracion derivada del reloj— viven en {@code ColaboradorInvitacionTest} y
 * <b>no se repiten aca</b>: repetirlas costaria minutos de Testcontainers por corrida y no
 * encontraria nada nuevo.
 */
class InvitacionesIT extends BaseEscenarioDiferido {

	private static final String PUBLICAS = "/api/v1/auth/invitations";

	// =================================================================================
	// 1. El circuito completo de quien todavia no tiene cuenta
	// =================================================================================

	@Test
	@DisplayName("Invitar a alguien sin cuenta, consultar sin consumir y aceptar: la cuenta nace "
			+ "ACTIVA, el vinculo existe y la invitacion queda atada a el")
	void el_circuito_completo_de_un_invitado_nuevo() {
		Sesion centro = altaCompleta("invitacion-alta");
		String email = "invitado-" + UUID.randomUUID() + "@ejemplo.test";

		// 1. Emitir. El correo sale por el outbox, no por el servicio.
		Respuesta emitida = invitar(centro, email, "PROFESIONAL", centro.consultorioId());
		assertThat(emitida.status()).as("alta de invitacion: %s", emitida.body()).isEqualTo(201);
		assertThat(emitida.texto("estado")).isEqualTo("PENDIENTE");
		assertThat(emitida.json().get("vencida").asBoolean()).isFalse();
		long invitacionId = emitida.json().get("id").asLong();

		// La respuesta NO trae el token ni su hash: exponerlo convertiria un permiso de lectura
		// en la capacidad de aceptar invitaciones ajenas.
		assertThat(emitida.body()).doesNotContain("token");

		assertThat(cuentasCon(email))
				.as("invitar no crea ninguna cuenta: la crea aceptar")
				.isZero();

		String token = tokenDe(invitacionId);

		// 2. Consultar. La operacion es publica —el invitado no tiene sesion— y NO consume.
		Respuesta preview = post(PUBLICAS + "/preview", null, "{\"token\":\"" + token + "\"}");
		assertThat(preview.status()).as("preview: %s", preview.body()).isEqualTo(200);
		assertThat(preview.json().get("requiereRegistro").asBoolean())
				.as("todavia no tiene cuenta")
				.isTrue();
		assertThat(preview.texto("email")).isEqualTo(email);
		// Sin ningun id: quien presenta el token no pertenece a la organizacion.
		assertThat(preview.body()).doesNotContain("organizationId");

		assertThat(estadoDe(invitacionId))
				.as("consultar no consume: un enlace que se gasta al mirarlo se pierde al recargar")
				.isEqualTo("PENDIENTE");

		// 3. Aceptar. Tres tablas se mueven juntas: cuenta, membership e invitacion.
		Respuesta aceptada = post(PUBLICAS + "/accept", null, """
				{"token":"%s","nombre":"Ana","apellido":"Gomez","password":"%s"}"""
				.formatted(token, PASSWORD));
		assertThat(aceptada.status()).as("accept: %s", aceptada.body()).isEqualTo(200);
		assertThat(aceptada.json().get("cuentaCreada").asBoolean()).isTrue();
		long membershipId = aceptada.json().get("membershipId").asLong();
		long cuentaId = aceptada.json().get("cuentaId").asLong();

		// La cuenta nace ACTIVA y sin segundo correo: el token ya probo la direccion.
		assertThat(estadoDeCuenta(cuentaId))
				.as("verificar dos veces la misma direccion agrega el paso donde la gente abandona")
				.isEqualTo("ACTIVA");

		// El vinculo existe, con el rol y el alcance que decia la invitacion.
		assertThat(rolDeMembership(membershipId)).isEqualTo("PROFESIONAL");
		assertThat(organizacionDeMembership(membershipId)).isEqualTo(centro.organizationId());

		// Y la invitacion quedo atada al vinculo que produjo.
		assertThat(estadoDe(invitacionId)).isEqualTo("ACEPTADA");
		assertThat(membershipDe(invitacionId)).isEqualTo(membershipId);

		// 4. El enlace ya no sirve: aceptar dos veces con el mismo token responde 404, igual que
		//    un token inventado. No hay forma de distinguirlos, que es lo que se busca.
		Respuesta reintento = post(PUBLICAS + "/accept", null, """
				{"token":"%s","nombre":"Ana","password":"%s"}""".formatted(token, PASSWORD));
		assertThat(reintento.status()).isEqualTo(404);
		assertProblemaLimpio(reintento);

		// 5. Y ahora que es miembro, invitarlo de nuevo es 409 y no un correo mas.
		Respuesta yaMiembro = invitar(centro, email, "PROFESIONAL", centro.consultorioId());
		assertThat(yaMiembro.status()).isEqualTo(409);
		assertThat(yaMiembro.texto("type"))
				.isEqualTo("https://akine.app/problems/colaborador-ya-vinculado");
	}

	// =================================================================================
	// 2. El unique de invitacion pendiente (migracion V21)
	// =================================================================================

	@Test
	@DisplayName("Una sola invitacion pendiente por persona y alcance; rechazar libera el email "
			+ "y el alcance ORGANIZACION no se escapa por el NULL")
	void los_dos_centinelas_del_unique() {
		Sesion centro = altaCompleta("invitacion-unique");
		String email = "repetido-" + UUID.randomUUID() + "@ejemplo.test";

		// 1. La primera entra.
		assertThat(invitar(centro, email, "PROFESIONAL", centro.consultorioId()).status())
				.isEqualTo(201);

		// 2. La segunda al mismo alcance choca: dos enlaces validos dejarian al invitado
		//    eligiendo cual usar y al administrador sin saber cual mando.
		Respuesta duplicada = invitar(centro, email, "PROFESIONAL", centro.consultorioId());
		assertThat(duplicada.status()).isEqualTo(409);
		assertThat(duplicada.texto("type"))
				.isEqualTo("https://akine.app/problems/invitacion-pendiente-duplicada");

		// 3. La misma persona a alcance ORGANIZACION —consultorio_id NULL— es OTRO alcance y
		//    entra. Es el centinela `consultorio_key`: sin el, este caso tampoco chocaria con
		//    una segunda invitacion de alcance organizacion, porque varios NULL no colisionan.
		Respuesta aLaOrganizacion = invitar(centro, email, "ADMINISTRATIVO", null);
		assertThat(aLaOrganizacion.status())
				.as("otro alcance, otra invitacion: %s", aLaOrganizacion.body())
				.isEqualTo(201);
		long deOrganizacion = aLaOrganizacion.json().get("id").asLong();

		Respuesta segundaDeOrganizacion = invitar(centro, email, "ADMINISTRATIVO", null);
		assertThat(segundaDeOrganizacion.status())
				.as("y la segunda de ESE alcance si choca: es lo que el centinela protege")
				.isEqualTo(409);

		// 4. Rechazar libera el email: volver a invitar a quien dijo que no es legitimo, y es lo
		//    que el centinela de fecha `resuelta_key` hace posible.
		String token = tokenDe(deOrganizacion);
		Respuesta rechazo = post(PUBLICAS + "/decline", null,
				"{\"token\":\"" + token + "\",\"reason\":\"Ya no estoy disponible\"}");
		assertThat(rechazo.status()).isEqualTo(204);
		assertThat(estadoDe(deOrganizacion)).isEqualTo("RECHAZADA");

		assertThat(cuentasCon(email))
				.as("rechazar no crea ninguna cuenta: quien no quiere entrar no queda registrado")
				.isZero();

		Respuesta reinvitacion = invitar(centro, email, "ADMINISTRATIVO", null);
		assertThat(reinvitacion.status())
				.as("volver a invitar a quien rechazo: %s", reinvitacion.body())
				.isEqualTo(201);
	}

	// =================================================================================
	// 3. Aislamiento entre tenants
	// =================================================================================

	@Test
	@DisplayName("Una invitacion de otro tenant responde 404, nunca 403")
	void una_invitacion_ajena_no_existe() {
		Sesion centro = altaCompleta("invitacion-duenio");
		Sesion ajeno = altaCompleta("invitacion-intruso");
		String email = "ajeno-" + UUID.randomUUID() + "@ejemplo.test";

		long invitacionId = invitar(centro, email, "PROFESIONAL", centro.consultorioId())
				.json().get("id").asLong();

		// 404 y no 403: un 403 confirmaria que ese id existe, y alcanzaria recorrer numeros para
		// enumerar a quien invitan los demas centros.
		Respuesta cancelacionAjena = post(
				invitaciones(ajeno) + "/" + invitacionId + "/cancel", ajeno.token(),
				"{\"reason\":\"no es mia\"}");
		assertThat(cancelacionAjena.status()).isEqualTo(404);
		assertProblemaLimpio(cancelacionAjena);

		assertThat(estadoDe(invitacionId)).as("y no la toco").isEqualTo("PENDIENTE");

		// El listado del vecino tampoco la ve.
		Respuesta listadoAjeno = get(invitaciones(ajeno), ajeno.token());
		assertThat(listadoAjeno.status()).isEqualTo(200);
		assertThat(listadoAjeno.body()).doesNotContain(email);
	}

	// =================================================================================
	// 4. Reenvio y cancelacion
	// =================================================================================

	@Test
	@DisplayName("Reenviar rota el token y el anterior deja de servir; cancelar es terminal")
	void reenviar_y_cancelar() {
		Sesion centro = altaCompleta("invitacion-reenvio");
		String email = "reenviado-" + UUID.randomUUID() + "@ejemplo.test";

		long invitacionId = invitar(centro, email, "PROFESIONAL", centro.consultorioId())
				.json().get("id").asLong();
		String tokenViejo = tokenDe(invitacionId);

		Respuesta reenviada = post(
				invitaciones(centro) + "/" + invitacionId + "/resend", centro.token(), "");
		assertThat(reenviada.status()).as("resend: %s", reenviada.body()).isEqualTo(200);

		String tokenNuevo = tokenDe(invitacionId);
		assertThat(tokenNuevo).isNotEqualTo(tokenViejo);

		// El enlace viejo deja de servir en el mismo acto: dos validos a la vez dejarian al
		// invitado eligiendo cual usar.
		Respuesta conElViejo = post(
				PUBLICAS + "/preview", null, "{\"token\":\"" + tokenViejo + "\"}");
		assertThat(conElViejo.status()).isEqualTo(404);

		Respuesta conElNuevo = post(
				PUBLICAS + "/preview", null, "{\"token\":\"" + tokenNuevo + "\"}");
		assertThat(conElNuevo.status()).isEqualTo(200);

		// Cancelar exige motivo y es terminal.
		Respuesta sinMotivo = post(
				invitaciones(centro) + "/" + invitacionId + "/cancel", centro.token(),
				"{\"reason\":\"\"}");
		assertThat(sinMotivo.status()).isEqualTo(400);

		Respuesta cancelada = post(
				invitaciones(centro) + "/" + invitacionId + "/cancel", centro.token(),
				"{\"reason\":\"Se cubrio el puesto\"}");
		assertThat(cancelada.status()).isEqualTo(200);
		assertThat(estadoDe(invitacionId)).isEqualTo("CANCELADA");

		// Y ya no se puede aceptar por el enlace que seguia en el correo del invitado.
		Respuesta tardia = post(PUBLICAS + "/accept", null, """
				{"token":"%s","nombre":"Ana","password":"%s"}""".formatted(tokenNuevo, PASSWORD));
		assertThat(tardia.status()).isEqualTo(404);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private String invitaciones(Sesion sesion) {
		return "/api/v1/organizations/" + sesion.organizationId() + "/colaborador-invitaciones";
	}

	private Respuesta invitar(Sesion sesion, String email, String rol, Long consultorioId) {
		String cuerpo = consultorioId == null
				? "{\"email\":\"%s\",\"roleCode\":\"%s\"}".formatted(email, rol)
				: "{\"email\":\"%s\",\"roleCode\":\"%s\",\"consultorioId\":%d}"
						.formatted(email, rol, consultorioId);
		return post(invitaciones(sesion), sesion.token(), cuerpo);
	}

	/**
	 * El token en claro NO se puede leer de la base: ahi vive su SHA-256.
	 *
	 * <p>Lo que se hace es lo unico honesto disponible sin backdoor: se genera un token de
	 * prueba, se escribe SU hash en la fila, y se usa el token de prueba para entrar por el
	 * camino publico real. La verificacion que se ejerce —el servicio hashea lo recibido y lo
	 * compara— es exactamente la de produccion; lo unico que se saltea es el correo, que ya
	 * tiene sus propios tests en el outbox.
	 */
	private String tokenDe(long invitacionId) {
		String token = "token-de-prueba-" + UUID.randomUUID();
		int filas = jdbc.update(
				"UPDATE colaborador_invitacion SET token_hash = SHA2(?, 256) WHERE id = ?",
				token, invitacionId);
		assertThat(filas).isEqualTo(1);
		return token;
	}

	private String estadoDe(long invitacionId) {
		return jdbc.queryForObject(
				"SELECT estado FROM colaborador_invitacion WHERE id = ?",
				String.class, invitacionId);
	}

	private Long membershipDe(long invitacionId) {
		return jdbc.queryForObject(
				"SELECT membership_id FROM colaborador_invitacion WHERE id = ?",
				Long.class, invitacionId);
	}

	private long cuentasCon(String email) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE email_normalizado = ?", Long.class, email);
		return total == null ? 0L : total;
	}

	private String estadoDeCuenta(long cuentaId) {
		return jdbc.queryForObject("SELECT estado FROM cuenta WHERE id = ?", String.class, cuentaId);
	}

	private String rolDeMembership(long membershipId) {
		return jdbc.queryForObject(
				"SELECT role_code FROM membership WHERE id = ?", String.class, membershipId);
	}

	private long organizacionDeMembership(long membershipId) {
		Long orgId = jdbc.queryForObject(
				"SELECT organization_id FROM membership WHERE id = ?", Long.class, membershipId);
		return orgId == null ? 0L : orgId;
	}
}
