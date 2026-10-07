package com.akine.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Cuenta#prepararBootstrapDePlataforma(String)} (DP-14, AKINE-A-4). */
class CuentaBootstrapDePlataformaTest {

	private static final Instant AHORA = Instant.parse("2026-10-07T12:00:00Z");

	@Test
	@DisplayName("la cuenta sembrada (ACTIVA, sin credencial) pasa al email nuevo y queda pendiente")
	void sembrada_pasa_a_pendiente_con_el_email_nuevo() {
		Cuenta cuenta = sinCredencial();
		cuenta.transicionarA(EstadoCuenta.ACTIVA, null, AHORA);
		assertThat(cuenta.admiteBootstrapDePlataforma()).isTrue();

		cuenta.prepararBootstrapDePlataforma("  Operaciones@Ejemplo.TEST ");

		assertThat(cuenta.getEmail()).isEqualTo("Operaciones@Ejemplo.TEST");
		assertThat(cuenta.getEmailNormalizado()).isEqualTo("operaciones@ejemplo.test");
		assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.PENDIENTE_ACTIVACION);
		assertThat(cuenta.puedeAutenticarse()).isFalse();
	}

	@Test
	@DisplayName("sobre una cuenta ya pendiente solo cambia el email")
	void pendiente_sigue_pendiente() {
		Cuenta cuenta = sinCredencial();

		cuenta.prepararBootstrapDePlataforma("otra@ejemplo.test");

		assertThat(cuenta.getEstado()).isEqualTo(EstadoCuenta.PENDIENTE_ACTIVACION);
		assertThat(cuenta.getEmailNormalizado()).isEqualTo("otra@ejemplo.test");
	}

	@Test
	@DisplayName("una cuenta con credencial nunca es destino del bootstrap")
	void con_credencial_se_rechaza() {
		Cuenta cuenta = new Cuenta("ana@ejemplo.test", "Ana", "Gomez", "{hash}x");
		cuenta.transicionarA(EstadoCuenta.ACTIVA, null, AHORA);

		assertThat(cuenta.admiteBootstrapDePlataforma()).isFalse();
		assertThatThrownBy(() -> cuenta.prepararBootstrapDePlataforma("x@ejemplo.test"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("sin credencial");
		assertThat(cuenta.getEmail()).isEqualTo("ana@ejemplo.test");
	}

	@Test
	@DisplayName("una cuenta bloqueada o desactivada no se revierte por un arranque")
	void bloqueada_o_desactivada_se_rechaza() {
		Cuenta bloqueada = sinCredencial();
		bloqueada.transicionarA(EstadoCuenta.ACTIVA, null, AHORA);
		bloqueada.transicionarA(EstadoCuenta.BLOQUEADA, "prueba", AHORA);
		Cuenta desactivada = sinCredencial();
		desactivada.transicionarA(EstadoCuenta.DESACTIVADA, "baja", AHORA);

		assertThat(bloqueada.admiteBootstrapDePlataforma()).isFalse();
		assertThat(desactivada.admiteBootstrapDePlataforma()).isFalse();
		assertThatThrownBy(() -> bloqueada.prepararBootstrapDePlataforma("x@ejemplo.test"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> desactivada.prepararBootstrapDePlataforma("x@ejemplo.test"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(bloqueada.getEstado()).isEqualTo(EstadoCuenta.BLOQUEADA);
	}

	@Test
	@DisplayName("un email vacio se rechaza sin tocar la cuenta")
	void email_vacio() {
		Cuenta cuenta = sinCredencial();

		assertThatThrownBy(() -> cuenta.prepararBootstrapDePlataforma(" "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(cuenta.getEmail()).isEqualTo("plataforma@akine.app");
	}

	private static Cuenta sinCredencial() {
		return new Cuenta("plataforma@akine.app", "Administracion", "de Plataforma", null);
	}
}
