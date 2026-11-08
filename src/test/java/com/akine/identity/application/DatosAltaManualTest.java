package com.akine.identity.application;

import com.akine.identity.api.dto.CreateAltaManualRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Los datos del alta manual y las correcciones que el invitado hace al aceptar. */
class DatosAltaManualTest {

	private static final DatosAltaManual DATOS = new DatosAltaManual("Juan", "Perez", "DNI", "30111222",
			null, "Kinesiologia", "MP 1", "Cordoba", "Traumatologia", List.of("Deportiva"), List.of(3L),
			List.of(new DatosAltaManual.ServicioElegido(3L, 8L)), "2026-11-01", true, false, true);

	@Test
	@DisplayName("una correccion pisa solo lo que trae, recortado, y conserva alcance y servicios")
	void corregir_pisa_solo_lo_que_viene() {
		DatosAltaManual corregido = DATOS.conPerfil(new PerfilDeInvitacion(
				" Juan Carlos ", null, null, null, "3511234567", null, null, null, null, List.of("Neuro")));

		assertThat(corregido.nombre()).isEqualTo("Juan Carlos");
		assertThat(corregido.apellido()).isEqualTo("Perez");
		assertThat(corregido.telefono()).isEqualTo("3511234567");
		assertThat(corregido.especialidadesAdicionales()).containsExactly("Neuro");
		assertThat(corregido.sedeIds()).isEqualTo(DATOS.sedeIds());
		assertThat(corregido.servicios()).isEqualTo(DATOS.servicios());
		assertThat(corregido.perfil().profesion()).isEqualTo("Kinesiologia");
		assertThat(DATOS.conPerfil(new PerfilDeInvitacion(
				null, null, null, null, null, null, null, null, null, null)).especialidadesAdicionales())
				.containsExactly("Deportiva");
	}

	@Test
	@DisplayName("vaciar el nombre o el apellido al corregir se rechaza")
	void nombre_vacio_se_rechaza() {
		assertThatThrownBy(() -> DATOS.conPerfil(new PerfilDeInvitacion(
				"  ", null, null, null, null, null, null, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DATOS.conPerfil(new PerfilDeInvitacion(
				null, "", null, null, null, null, null, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("el pedido arma los datos con listas vacias donde no vino nada")
	void el_pedido_arma_los_datos() {
		CreateAltaManualRequest minimo = new CreateAltaManualRequest(" Ana ", " Gomez ", null, null, null,
				"ana@ejemplo.test", "ADMINISTRATIVO", null, null, null, null, null, List.of(3L), null, null,
				false, false, true);
		assertThat(minimo.datos().nombre()).isEqualTo("Ana");
		assertThat(minimo.datos().especialidadesAdicionales()).isEmpty();
		assertThat(minimo.datos().servicios()).isEmpty();
		assertThat(minimo.datos().fechaIncorporacion()).isNull();

		CreateAltaManualRequest completo = new CreateAltaManualRequest("Ana", "Gomez", "DNI", "1", "2",
				"ana@ejemplo.test", "PROFESIONAL", "Kine", "MP", "Cba", "Trauma", List.of("Neuro"), List.of(3L),
				List.of(new CreateAltaManualRequest.ServicioElegidoRequest(3L, 8L)), LocalDate.of(2026, 11, 1),
				true, true, true);
		assertThat(completo.datos().servicios()).containsExactly(new DatosAltaManual.ServicioElegido(3L, 8L));
		assertThat(completo.datos().fechaIncorporacion()).isEqualTo("2026-11-01");
	}
}
