package com.akine.resource.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La unica regla de M04 que se puede decidir sin base: <b>cuando un espacio esta en servicio</b>.
 *
 * <h2>Por que estos casos y no veinte</h2>
 *
 * <p>Todo lo demas que hace esta entidad —el unique del nombre, la baja logica, la carrera de la
 * capacidad— es ESTADO PERSISTIDO, y un test unitario sobre un objeto en memoria no puede
 * probarlo: vive en {@code EspaciosIT}, contra MySQL de verdad. Lo que si es puramente
 * algebraico son los BORDES de la ventana operativa, y son exactamente donde un error no se ve:
 * un {@code isBefore} donde iba un {@code isAfter} deja pasar una reserva que empieza justo
 * cuando el box sale de servicio, y ningun test de camino feliz lo detecta.
 *
 * <p>La regla que estos casos protegen tiene una sola formulacion, y es la de RN-M04-002:
 * <b>en servicio = vigente administrativamente Y dentro de la ventana operativa</b>, con el
 * limite superior EXCLUSIVO.
 */
class EspacioTest {

	private static final Instant MARZO = Instant.parse("2026-03-01T00:00:00Z");
	private static final Instant JUNIO = Instant.parse("2026-06-01T00:00:00Z");

	private static Espacio box(Instant validFrom, Instant validUntil) {
		return new Espacio(1L, 1L, "Box 1", EspacioTipo.BOX, 1, null, validFrom, validUntil);
	}

	@Test
	@DisplayName("La ventana operativa incluye su inicio y EXCLUYE su fin")
	void los_bordes_de_la_ventana_no_son_simetricos() {
		Espacio espacio = box(MARZO, JUNIO);

		assertThat(espacio.estaEnServicio(MARZO.minusSeconds(1)))
				.as("un box que todavia no entro en servicio no se reserva, aunque este ACTIVO")
				.isFalse();
		assertThat(espacio.estaEnServicio(MARZO))
				.as("el inicio es inclusivo: el primer dia ya se puede reservar")
				.isTrue();
		assertThat(espacio.estaEnServicio(JUNIO.minusSeconds(1))).isTrue();

		// El caso que se rompe con un signo mal puesto. Si el fin fuera inclusivo, dos ventanas
		// consecutivas del mismo recurso se solaparian en ese microsegundo exacto.
		assertThat(espacio.estaEnServicio(JUNIO))
				.as("el fin es EXCLUSIVO: en el instante en que sale de servicio ya no se ofrece")
				.isFalse();
	}

	@Test
	@DisplayName("Sin fin de vigencia, el espacio esta en servicio para siempre hacia adelante")
	void sin_fin_de_vigencia_no_hay_limite_superior() {
		Espacio espacio = box(MARZO, null);

		assertThat(espacio.estaEnServicio(MARZO.minusSeconds(1))).isFalse();
		assertThat(espacio.estaEnServicio(Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
	}

	@Test
	@DisplayName("Una ventana pedida tiene que caber ENTERA, no solo empezar dentro")
	void la_ventana_pedida_se_evalua_completa() {
		Espacio espacio = box(MARZO, JUNIO);

		assertThat(espacio.estaEnServicioDurante(MARZO, JUNIO))
				.as("la ventana pedida termina justo cuando el recurso sale: es compatible")
				.isTrue();

		// El caso que importa, y el que un chequeo del instante inicial dejaria pasar: una
		// sesion que arranca el ultimo dia de servicio del box y termina despues.
		assertThat(espacio.estaEnServicioDurante(JUNIO.minusSeconds(1), JUNIO.plusSeconds(1)))
				.as("empieza dentro y termina fuera: NO se puede reservar ahi")
				.isFalse();
		assertThat(espacio.estaEnServicioDurante(MARZO.minusSeconds(1), MARZO.plusSeconds(1)))
				.as("empieza antes de entrar en servicio: tampoco")
				.isFalse();
	}

	@Test
	@DisplayName("La baja logica saca al espacio de servicio sin tocar su ventana ni su nombre")
	void la_baja_logica_gana_sobre_la_vigencia() {
		Espacio espacio = box(MARZO, null);
		espacio.deactivate(JUNIO, "Refaccion definitiva");

		assertThat(espacio.estaEnServicio(JUNIO.plusSeconds(1)))
				.as("RN-M04-002: un recurso dado de baja no se ofrece, este donde este su ventana")
				.isFalse();
		assertThat(espacio.getName())
				.as("RN-M04-003: el historico conserva su nombre")
				.isEqualTo("Box 1");
		assertThat(espacio.getValidFrom())
				.as("la baja no reescribe la ventana operativa: son dos ejes distintos")
				.isEqualTo(MARZO);
		assertThat(espacio.getDeactivationReason()).isEqualTo("Refaccion definitiva");
	}

	@Test
	@DisplayName("Capacidad cero y ventana invertida se rechazan en el dominio, no solo en la base")
	void los_invariantes_del_dominio_no_dependen_de_la_base() {
		// Capacidad cero no es "menos cupo": es una baja sin motivo y sin rastro (RN-M04-005).
		assertThatThrownBy(() -> box(MARZO, null).updateDatos(
				null, null, 0, null, null, null, false))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> box(JUNIO, MARZO))
				.isInstanceOf(IllegalArgumentException.class);

		// El fin EXCLUSIVO tambien excluye el empate: un recurso disponible cero microsegundos.
		assertThatThrownBy(() -> box(MARZO, MARZO))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("La baja exige motivo: sin el, la auditoria no responde por que")
	void la_baja_sin_motivo_se_rechaza() {
		assertThatThrownBy(() -> box(MARZO, null).deactivate(JUNIO, "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
