package com.akine.assistant;

import com.akine.TestcontainersConfiguration;
import com.akine.assistant.domain.BotDeTelegram;
import com.akine.assistant.domain.VinculoDeTelegram;
import com.akine.assistant.domain.port.BotsDeTelegram;
import com.akine.assistant.domain.port.VinculosDeTelegram;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code telegram_bot} y {@code telegram_vinculo} contra PostgreSQL: el alquiler del sondeo, el
 * cifrado del token y las reglas del codigo de vinculacion, que viven en SQL y ningun unitario ve.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramPersistenciaIT {

	private static final String TOKEN = "1234567890:AAHtokenSinteticoSoloParaTests_0123456";

	@Autowired JdbcTemplate jdbc;
	@Autowired BotsDeTelegram bots;
	@Autowired VinculosDeTelegram vinculos;

	private long organizationId;
	private long sedeA;
	private long sedeB;
	private long cuentaA;
	private long cuentaB;

	@BeforeAll
	void sembrar() {
		String s = UUID.randomUUID().toString().substring(0, 8);
		organizationId = InsertarConId.insertar(jdbc, """
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
				"Centro " + s, "telegram-it-" + s);
		sedeA = sede("Sede A " + s);
		sedeB = sede("Sede B " + s);
		cuentaA = cuenta("tg-a-" + s + "@ejemplo.test");
		cuentaB = cuenta("tg-b-" + s + "@ejemplo.test");
	}

	private long sede(String nombre) {
		return InsertarConId.insertar(jdbc, """
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
				organizationId, nombre);
	}

	private long cuenta(String email) {
		return InsertarConId.insertar(jdbc, """
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active, version, created_at,
				                    updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
				email, email);
	}

	@Test
	void el_bot_guarda_el_token_cifrado_y_lo_alquila_una_sola_instancia_a_la_vez() {
		Instant ahora = Instant.now();
		bots.guardar(organizationId, TOKEN, 111L, "kine_it_bot", cuentaA, ahora);

		assertThat(jdbc.queryForObject("SELECT token_cifrado FROM telegram_bot WHERE organization_id = ?",
				String.class, organizationId)).doesNotContain("AAH");
		BotDeTelegram bot = bots.buscar(organizationId).orElseThrow();
		assertThat(bot.token()).isEqualTo(TOKEN);
		assertThat(bots.activos()).contains(organizationId);

		assertThat(bots.tomar(organizationId, ahora, ahora.plus(Duration.ofMinutes(10)))).isTrue();
		assertThat(bots.tomar(organizationId, ahora, ahora.plus(Duration.ofMinutes(10)))).isFalse();
		bots.soltar(organizationId);
		assertThat(bots.tomar(organizationId, ahora, ahora.plus(Duration.ofMinutes(10)))).isTrue();
		bots.soltar(organizationId);

		bots.avanzar(organizationId, 50, ahora);
		bots.avanzar(organizationId, 40, ahora);
		assertThat(bots.buscar(organizationId).orElseThrow().ultimoUpdateId()).isEqualTo(50);

		// El mismo bot conserva el offset; otro bot empieza de cero.
		bots.guardar(organizationId, TOKEN, 111L, "kine_it_bot", cuentaA, ahora);
		assertThat(bots.buscar(organizationId).orElseThrow().ultimoUpdateId()).isEqualTo(50);
		bots.guardar(organizationId, TOKEN, 222L, "otro_it_bot", cuentaA, ahora);
		assertThat(bots.buscar(organizationId).orElseThrow().ultimoUpdateId()).isZero();

		bots.desconectar(organizationId, ahora);
		BotDeTelegram desconectado = bots.buscar(organizationId).orElseThrow();
		assertThat(desconectado.token()).isNull();
		assertThat(desconectado.activo()).isFalse();
		assertThat(bots.activos()).doesNotContain(organizationId);
		assertThat(bots.tomar(organizationId, ahora, ahora.plusSeconds(60))).isFalse();
	}

	@Test
	void el_codigo_vincula_una_vez_mueve_la_sede_al_usarse_y_el_usuario_queda_en_una_sola_cuenta() {
		Instant ahora = Instant.now();
		Instant vence = ahora.plus(Duration.ofMinutes(15));
		long usuario = 900_000L + (organizationId % 1000);

		assertThat(vinculos.nuevoCodigo(organizationId, cuentaA, sedeA, "111111", vence, ahora)).isTrue();
		// El mismo codigo para otra cuenta del centro no se puede: se reintenta con otro.
		assertThat(vinculos.nuevoCodigo(organizationId, cuentaB, sedeA, "111111", vence, ahora)).isFalse();

		VinculoDeTelegram a = vinculos.usarCodigo(organizationId, "111111", usuario, "Juan", ahora).orElseThrow();
		assertThat(a.cuentaId()).isEqualTo(cuentaA);
		assertThat(a.consultorioId()).isEqualTo(sedeA);
		assertThat(a.codigo()).isNull();
		// De un solo uso.
		assertThat(vinculos.usarCodigo(organizationId, "111111", usuario, "Juan", ahora)).isEmpty();

		// Un codigo nuevo desde otra sede no mueve la carga hasta usarlo.
		vinculos.nuevoCodigo(organizationId, cuentaA, sedeB, "222222", vence, ahora);
		assertThat(vinculos.delUsuario(organizationId, usuario).orElseThrow().consultorioId()).isEqualTo(sedeA);
		vinculos.usarCodigo(organizationId, "222222", usuario, "Juan", ahora);
		assertThat(vinculos.delUsuario(organizationId, usuario).orElseThrow().consultorioId()).isEqualTo(sedeB);

		// El mismo usuario de Telegram vinculado a otra cuenta suelta la primera.
		vinculos.nuevoCodigo(organizationId, cuentaB, sedeA, "333333", vence, ahora);
		vinculos.usarCodigo(organizationId, "333333", usuario, "Juan", ahora);
		assertThat(vinculos.delUsuario(organizationId, usuario).orElseThrow().cuentaId()).isEqualTo(cuentaB);
		assertThat(vinculos.deLaCuenta(organizationId, cuentaA).orElseThrow().vinculado()).isFalse();

		// Vencido no vincula.
		vinculos.nuevoCodigo(organizationId, cuentaA, sedeA, "444444", ahora.minusSeconds(1), ahora.minusSeconds(60));
		assertThat(vinculos.usarCodigo(organizationId, "444444", usuario, "Juan", ahora)).isEmpty();

		vinculos.desvincular(organizationId, cuentaB, ahora);
		assertThat(vinculos.delUsuario(organizationId, usuario)).isEmpty();
	}
}
