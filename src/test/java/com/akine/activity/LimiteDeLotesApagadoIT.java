package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.CarreraDirigida.Retencion;
import com.akine.activity.application.ResultadoDeLote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U-49 c (DP-30) con la propiedad sin setear: el comportamiento de siempre. Misma configuracion
 * que el resto de los ITs de {@code activity}, asi que comparte su contexto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class LimiteDeLotesApagadoIT extends EscenarioDeLotesPorSede {

	@Test
	@DisplayName("U-49 c · sin la propiedad: dos lotes de la misma sede corren a la vez, como antes, y el limite no cuenta nada")
	void no_hay_limite() {
		double antes = contador("inmediato") + contador("tras-espera") + contador("rechazado");

		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion retencion = Retencion.de(dataSource, "clase_programada", claseA)) {
			Future<Desenlace<ResultadoDeLote>> loteA =
					carrera.largarHastaBloquearse(() -> lote(claseA), LOCK_DE_LA_CLASE);

			ResultadoDeLote b = lote(claseB);

			assertThat(b.exitosos()).isEqualTo(2);
			assertThat(loteA.isDone()).as("B termino con A todavia en curso").isFalse();
			retencion.soltar();
			assertThat(CarreraDirigida.resultado(loteA).valor().exitosos()).isEqualTo(2);
		}

		assertThat(enEspera()).isZero();
		assertThat(contador("inmediato") + contador("tras-espera") + contador("rechazado"))
				.isEqualTo(antes);
	}
}
