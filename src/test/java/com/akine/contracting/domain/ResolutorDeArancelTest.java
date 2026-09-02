package com.akine.contracting.domain;

import com.akine.contracting.spi.ArancelCongelado;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ResolucionDeArancel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RF-M16-006 y RF-M16-010: para una fecha y un contexto, <b>un resultado unico y explicable</b>.
 *
 * <p>Los casos borde que la etapa nombra estan todos aca: convenio vencido, atencion retroactiva,
 * plan sin arancel cargado, y arancel futuro que todavia no rige.
 */
@DisplayName("ResolutorDeArancel")
class ResolutorDeArancelTest {

	private static final long PRACTICA = 412L;
	private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
	private static final LocalDate MARZO = LocalDate.of(2026, 3, 15);
	private static final LocalDate JUNIO_30 = LocalDate.of(2026, 6, 30);
	private static final LocalDate JULIO = LocalDate.of(2026, 7, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2026, 12, 31);

	@Test
	@DisplayName("con convenio y arancel vigentes devuelve el importe Y la derivacion completa")
	void resuelve_con_explicacion() {
		Convenio convenio = convenio(140L, ENERO, DICIEMBRE);
		ConvenioArancel arancel = arancel(901L, "12000.00", "9600.00", "2400.00", ENERO, JUNIO_30);

		ResolucionDeArancel resolucion =
				ResolutorDeArancel.resolver(List.of(convenio), List.of(arancel), PRACTICA, MARZO);

		assertThat(resolucion.estaResuelta()).isTrue();
		assertThat(resolucion.arancel().importeTotal()).isEqualByComparingTo("12000.00");
		assertThat(resolucion.arancel().coseguro()).isEqualByComparingTo("2400.00");
		assertThat(resolucion.arancel().moneda()).isEqualTo("ARS");

		// La derivacion: sin esto, un numero inesperado no se puede explicar.
		assertThat(resolucion.arancel().convenioId()).isEqualTo(140L);
		assertThat(resolucion.arancel().convenioCodigo()).isEqualTo("OSDE-210");
		assertThat(resolucion.arancel().arancelId()).isEqualTo(901L);
		assertThat(resolucion.arancel().convenioVigenciaHasta()).isEqualTo(DICIEMBRE);
		assertThat(resolucion.arancel().arancelVigenciaHasta()).isEqualTo(JUNIO_30);
		assertThat(resolucion.arancel().resueltoPara()).isEqualTo(MARZO);
		assertThat(resolucion.arancel().practicaId()).isEqualTo(PRACTICA);
		assertThat(resolucion.arancel().modalidad()).isEqualTo("POR_PRESTACION");
	}

	@Test
	@DisplayName("sin convenio que cubra la fecha: SIN_CONVENIO_VIGENTE, y eso NO es un error")
	void sin_convenio() {
		// RN-M16-005: sin convenio valido no se asume cobertura. El paciente se cobra como
		// particular, que es el desenlace mas frecuente de todos.
		ResolucionDeArancel resolucion =
				ResolutorDeArancel.resolver(List.of(), List.of(), PRACTICA, MARZO);

		assertThat(resolucion.estaResuelta()).isFalse();
		assertThat(resolucion.motivo()).isEqualTo(MotivoSinArancel.SIN_CONVENIO_VIGENTE);
		assertThat(resolucion.valor()).isEmpty();
	}

	@Test
	@DisplayName("convenio VENCIDO: la fecha cae fuera de su ventana y no resuelve")
	void convenio_vencido() {
		Convenio vencido = convenio(140L, ENERO, JUNIO_30);
		ConvenioArancel arancel = arancel(901L, "12000.00", "12000.00", "0.00", ENERO, JUNIO_30);

		ResolucionDeArancel resolucion = ResolutorDeArancel.resolver(
				List.of(vencido), List.of(arancel), PRACTICA, JULIO);

		assertThat(resolucion.motivo()).isEqualTo(MotivoSinArancel.SIN_CONVENIO_VIGENTE);
	}

	@Test
	@DisplayName("hay convenio pero la practica no esta tarifada: SIN_ARANCEL_VIGENTE, que es OTRA "
			+ "cosa")
	void convenio_sin_arancel() {
		// La diferencia es operativa: el acuerdo existe y lo que falta es cargar el precio, que es
		// algo que el administrador puede resolver. Colapsar los dos motivos lo dejaria sin saberlo.
		ResolucionDeArancel resolucion = ResolutorDeArancel.resolver(
				List.of(convenio(140L, ENERO, DICIEMBRE)), List.of(), PRACTICA, MARZO);

		assertThat(resolucion.motivo()).isEqualTo(MotivoSinArancel.SIN_ARANCEL_VIGENTE);
	}

	@Test
	@DisplayName("ATENCION RETROACTIVA: se resuelve con el arancel de ENTONCES, no con el de hoy")
	void atencion_retroactiva() {
		// Es el caso borde que RN-M16-003 protege. Los dos aranceles llegan en el orden del
		// repositorio —del mas nuevo al mas viejo— y gana el que cubre la fecha consultada.
		Convenio convenio = convenio(140L, ENERO, DICIEMBRE);
		ConvenioArancel viejo = arancel(901L, "12000.00", "12000.00", "0.00", ENERO, JUNIO_30);
		ConvenioArancel nuevo = arancel(902L, "18000.00", "18000.00", "0.00", JULIO, DICIEMBRE);

		ResolucionDeArancel enMarzo = ResolutorDeArancel.resolver(
				List.of(convenio), List.of(nuevo, viejo), PRACTICA, MARZO);
		ResolucionDeArancel enAgosto = ResolutorDeArancel.resolver(
				List.of(convenio), List.of(nuevo, viejo), PRACTICA, LocalDate.of(2026, 8, 10));

		assertThat(enMarzo.arancel().importeTotal()).isEqualByComparingTo("12000.00");
		assertThat(enAgosto.arancel().importeTotal()).isEqualByComparingTo("18000.00");
	}

	@Test
	@DisplayName("un arancel FUTURO no se aplica antes de tiempo")
	void arancel_futuro() {
		ResolucionDeArancel resolucion = ResolutorDeArancel.resolver(
				List.of(convenio(140L, ENERO, DICIEMBRE)),
				List.of(arancel(902L, "18000.00", "18000.00", "0.00", JULIO, DICIEMBRE)),
				PRACTICA,
				MARZO);

		assertThat(resolucion.motivo()).isEqualTo(MotivoSinArancel.SIN_ARANCEL_VIGENTE);
	}

	@Test
	@DisplayName("un convenio dado de baja no resuelve aunque su ventana cubra la fecha")
	void convenio_dado_de_baja() {
		Convenio baja = convenio(140L, ENERO, DICIEMBRE);
		baja.deactivate(Instant.now(), "Renegociado");

		assertThat(ResolutorDeArancel.resolver(
				List.of(baja),
				List.of(arancel(901L, "1.00", "1.00", "0.00", ENERO, DICIEMBRE)),
				PRACTICA, MARZO).motivo())
				.isEqualTo(MotivoSinArancel.SIN_CONVENIO_VIGENTE);
	}

	@Test
	@DisplayName("congelar copia los valores y les pone el instante de captura")
	void congelar_copia() {
		// La copia deja de depender del convenio: editarlo despues no la cambia. Es lo que hace
		// RN-M16-003 ejecutable, y lo unico que separa una deuda estable de una que se reescribe.
		Convenio convenio = convenio(140L, ENERO, DICIEMBRE);
		ConvenioArancel arancel = arancel(901L, "12000.00", "9600.00", "2400.00", ENERO, DICIEMBRE);

		ResolucionDeArancel resolucion =
				ResolutorDeArancel.resolver(List.of(convenio), List.of(arancel), PRACTICA, MARZO);
		Instant capturadoEl = Instant.parse("2026-03-15T10:00:00Z");
		ArancelCongelado copia = ResolutorDeArancel.congelar(resolucion.arancel(), capturadoEl);

		assertThat(copia.importeTotal()).isEqualByComparingTo("12000.00");
		assertThat(copia.convenioNombre()).isEqualTo("OSDE 210");
		assertThat(copia.vigenteEl()).isEqualTo(MARZO);
		assertThat(copia.capturadoEl()).isEqualTo(capturadoEl);

		// Se edita el convenio DESPUES de congelar: la copia no se entera, que es todo el punto.
		convenio.updateDatos("OSDE 210 Premium", null, null, null, null, null, null, null, null,
				null);
		arancel.updateDatos(new BigDecimal("99999.00"), new BigDecimal("99999.00"),
				BigDecimal.ZERO, null, null);

		assertThat(copia.convenioNombre()).isEqualTo("OSDE 210");
		assertThat(copia.importeTotal()).isEqualByComparingTo("12000.00");
	}

	@Test
	@DisplayName("una resolucion no puede traer las dos cosas ni ninguna")
	void la_resolucion_es_excluyente() {
		// Sin esto existiria la instancia que dice "no hay arancel" sin decir por que, que es
		// exactamente el resultado inutil que un Optional pelado produce.
		assertThatThrownBy(() -> new ResolucionDeArancel(null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static Convenio convenio(long id, LocalDate desde, LocalDate hasta) {
		Convenio convenio = new Convenio(7L, 20L, 31L, 88L, "OSDE-210", "OSDE 210",
				ModalidadConvenio.POR_PRESTACION, desde, hasta, "ARS",
				true, false, true, 20, null, null);
		ReflectionTestUtils.setField(convenio, "id", id);
		return convenio;
	}

	private static ConvenioArancel arancel(
			long id, String total, String financiador, String coseguro,
			LocalDate desde, LocalDate hasta) {

		ConvenioArancel arancel = new ConvenioArancel(7L, 20L, 140L, PRACTICA,
				new BigDecimal(total), new BigDecimal(financiador), new BigDecimal(coseguro),
				"ARS", desde, hasta);
		ReflectionTestUtils.setField(arancel, "id", id);
		return arancel;
	}
}
