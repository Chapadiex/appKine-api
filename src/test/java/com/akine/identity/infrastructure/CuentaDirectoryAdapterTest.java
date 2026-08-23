package com.akine.identity.infrastructure;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.spi.AccountState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;
import static com.akine.identity.IdentityFixtures.cuentaActiva;
import static com.akine.identity.IdentityFixtures.cuentaBloqueada;
import static com.akine.identity.IdentityFixtures.cuentaPendiente;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/** Contrato de lectura que otros modulos usan para consultar cuentas. */
@ExtendWith(MockitoExtension.class)
class CuentaDirectoryAdapterTest {

	@Mock
	private CuentaRepository cuentaRepository;

	@InjectMocks
	private CuentaDirectoryAdapter adapter;

	@Test
	@DisplayName("devuelve un record del spi, jamas la entity")
	void devuelve_un_record_del_spi() {
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuentaActiva()));

		assertThat(adapter.cuenta(CUENTA_ID)).hasValueSatisfying(foto -> {
			assertThat(foto.accountId()).isEqualTo(CUENTA_ID);
			assertThat(foto.nombreCompleto()).isEqualTo("Ana Gomez");
			assertThat(foto.estado()).isEqualTo(AccountState.ACTIVA);
			assertThat(foto.habilitada()).isTrue();
		});
	}

	@Test
	@DisplayName("busca por la forma canonica del email, no por como se escribio")
	void busca_por_la_forma_canonica_del_email() {
		given(cuentaRepository.findByEmailNormalizado("ana.gomez@ejemplo.test"))
				.willReturn(Optional.of(cuentaActiva()));

		assertThat(adapter.existeCuentaCon("  Ana.Gomez@Ejemplo.Test ")).isTrue();
		assertThat(adapter.cuentaCon("ANA.GOMEZ@EJEMPLO.TEST")).isPresent();
	}

	@Test
	@DisplayName("un email vacio no consulta la base: no tiene cuenta y punto")
	void un_email_vacio_no_consulta_la_base() {
		assertThat(adapter.existeCuentaCon(null)).isFalse();
		assertThat(adapter.existeCuentaCon("   ")).isFalse();
		assertThat(adapter.cuentaCon(null)).isEmpty();

		verifyNoInteractions(cuentaRepository);
	}

	@Test
	@DisplayName("una cuenta inexistente devuelve vacio")
	void una_cuenta_inexistente_devuelve_vacio() {
		given(cuentaRepository.findById(999L)).willReturn(Optional.empty());

		assertThat(adapter.cuenta(999L)).isEmpty();
	}

	@Test
	@DisplayName("los cuatro estados se traducen al enum del spi")
	void los_estados_se_traducen() {
		assertThat(estadoDe(cuentaPendiente())).isEqualTo(AccountState.PENDIENTE_ACTIVACION);
		assertThat(estadoDe(cuentaActiva())).isEqualTo(AccountState.ACTIVA);
		assertThat(estadoDe(cuentaBloqueada())).isEqualTo(AccountState.BLOQUEADA);

		Cuenta desactivada = cuentaActiva();
		desactivada.transicionarA(
				com.akine.identity.domain.EstadoCuenta.DESACTIVADA, "baja pedida",
				java.time.Instant.now());
		assertThat(estadoDe(desactivada)).isEqualTo(AccountState.DESACTIVADA);
		// Estado y baja logica son cosas distintas y las dos viajan.
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(desactivada));
		assertThat(adapter.cuenta(CUENTA_ID)).hasValueSatisfying(foto -> {
			assertThat(foto.active()).isFalse();
			assertThat(foto.habilitada()).isFalse();
		});
	}

	private AccountState estadoDe(Cuenta cuenta) {
		given(cuentaRepository.findById(CUENTA_ID)).willReturn(Optional.of(cuenta));
		return adapter.cuenta(CUENTA_ID).orElseThrow().estado();
	}
}
