package com.akine.resource.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResumenDeAgendaParaAuditoriaTest {

	private static final LocalDate DIA = LocalDate.of(2026, 11, 12);

	@Test
	@DisplayName("una excepcion de un dia muestra una sola fecha: fecha_hasta es exclusiva")
	void un_dia_una_fecha() {
		assertThat(ResumenDeAgendaParaAuditoria.de(cierre(DIA, DIA.plusDays(1))))
				.isEqualTo("Cierre por licencia, 12/11/2026, dia completo");
	}

	@Test
	@DisplayName("un rango muestra el ultimo dia afectado, no el fin exclusivo")
	void rango_hasta_el_ultimo_dia() {
		assertThat(ResumenDeAgendaParaAuditoria.de(cierre(DIA, DIA.plusDays(3))))
				.isEqualTo("Cierre por licencia, 12/11/2026 al 14/11/2026, dia completo");
	}

	private static DisponibilidadExcepcion cierre(LocalDate desde, LocalDate hasta) {
		return new DisponibilidadExcepcion(1L, 2L, 3L, TipoExcepcion.CIERRE, MotivoExcepcion.LICENCIA,
				desde, hasta, null, null, null, null);
	}
}
