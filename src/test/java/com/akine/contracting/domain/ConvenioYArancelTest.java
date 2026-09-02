package com.akine.contracting.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las invariantes de las dos entidades de M16, sin base de datos.
 *
 * <p>Lo que este test fija: que <b>ciclo de vida y vigencia son dos cosas</b>, que los <b>tres
 * importes cuadran o la entidad no se construye</b> (§37), y que la edicion valida los importes
 * <b>como terna</b> aunque llegue uno solo — que es el descuido con el que se rompe la invariante
 * economica sin darse cuenta.
 */
@DisplayName("Convenio y ConvenioArancel")
class ConvenioYArancelTest {

	private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
	private static final LocalDate JUNIO_30 = LocalDate.of(2026, 6, 30);
	private static final LocalDate DICIEMBRE = LocalDate.of(2026, 12, 31);

	@Nested
	@DisplayName("Convenio")
	class DelConvenio {

		@Test
		@DisplayName("nace ACTIVO y vigente dentro de su ventana")
		void nace_activo() {
			Convenio convenio = convenio(ENERO, DICIEMBRE);

			assertThat(convenio.isOperable()).isTrue();
			assertThat(convenio.aplicaEl(ENERO)).isTrue();
			assertThat(convenio.aplicaEl(DICIEMBRE)).as("hasta es INCLUSIVA").isTrue();
			assertThat(convenio.aplicaEl(DICIEMBRE.plusDays(1))).isFalse();
			assertThat(convenio.getMoneda()).isEqualTo("ARS");
		}

		@Test
		@DisplayName("la moneda se normaliza a mayusculas y es obligatoria")
		void moneda_obligatoria() {
			assertThat(new Convenio(7L, 20L, 31L, 88L, "C", "N", ModalidadConvenio.POR_SESION,
					ENERO, null, "ars", false, false, true, null, null, null).getMoneda())
					.isEqualTo("ARS");

			assertThatThrownBy(() -> convenioConMoneda("  "))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("necesita una moneda");
		}

		@Test
		@DisplayName("un tope mensual de cero no es 'sin tope': se rechaza")
		void tope_cero_se_rechaza() {
			// Sin tope pactado se expresa OMITIENDO el campo. Admitir cero dejaria en la base un
			// convenio que promete cero sesiones por mes, que nadie quiso pactar.
			assertThatThrownBy(() -> new Convenio(7L, 20L, 31L, 88L, "C", "N",
					ModalidadConvenio.POR_SESION, ENERO, null, "ARS", false, false, true,
					0, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("mayor que cero");
		}

		@Test
		@DisplayName("CERRAR LA VIGENCIA no es dar de baja: el convenio queda ACTIVO")
		void cerrar_vigencia_no_es_baja() {
			Convenio convenio = convenio(ENERO, null);

			convenio.updateDatos(null, null, null, JUNIO_30, null, null, null, null, null, null);

			assertThat(convenio.isOperable()).as("sigue en el ciclo de vida").isTrue();
			assertThat(convenio.aplicaEl(JUNIO_30)).isTrue();
			assertThat(convenio.aplicaEl(JUNIO_30.plusDays(1)))
					.as("y solo deja de aplicarse despues")
					.isFalse();
		}

		@Test
		@DisplayName("la vigencia se valida como PAR aunque llegue de a una")
		void vigencia_se_valida_como_par() {
			// Mandar solo vigenciaHasta se compara contra el vigenciaDesde guardado. Validar solo
			// el campo que llega deja pasar la inversion mas facil de cometer.
			Convenio convenio = convenio(JUNIO_30, DICIEMBRE);

			assertThatThrownBy(() -> convenio.updateDatos(
					null, null, null, ENERO, null, null, null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no puede terminar antes de empezar");
		}

		@Test
		@DisplayName("lo que llega en null no se toca")
		void los_nulos_no_tocan() {
			Convenio convenio = convenio(ENERO, DICIEMBRE);

			convenio.updateDatos(null, null, null, null, null, null, null, null, null, null);

			assertThat(convenio.getNombre()).isEqualTo("OSDE 210");
			assertThat(convenio.getModalidad()).isEqualTo(ModalidadConvenio.POR_PRESTACION);
			assertThat(convenio.getVigenciaDesde()).isEqualTo(ENERO);
			assertThat(convenio.isRequiereCredencial()).isTrue();
		}

		@Test
		@DisplayName("la edicion aplica todos los campos que si llegan")
		void la_edicion_aplica_lo_que_llega() {
			Convenio convenio = convenio(ENERO, DICIEMBRE);

			convenio.updateDatos("Nuevo nombre", ModalidadConvenio.MODULO, ENERO, DICIEMBRE,
					true, true, false, 12, "Orden y credencial", "Renegociado");

			assertThat(convenio.getNombre()).isEqualTo("Nuevo nombre");
			assertThat(convenio.getModalidad()).isEqualTo(ModalidadConvenio.MODULO);
			assertThat(convenio.isRequiereOrden()).isTrue();
			assertThat(convenio.isRequiereAutorizacion()).isTrue();
			assertThat(convenio.isRequiereCredencial()).isFalse();
			assertThat(convenio.getLimiteSesionesMensual()).isEqualTo(12);
			assertThat(convenio.getDocumentacionRequerida()).isEqualTo("Orden y credencial");
			assertThat(convenio.getObservaciones()).isEqualTo("Renegociado");
		}

		@Test
		@DisplayName("un convenio dado de baja no aplica NINGUN dia, aunque su ventana lo cubra")
		void la_baja_lo_saca_de_la_resolucion() {
			Convenio convenio = convenio(ENERO, DICIEMBRE);

			convenio.deactivate(Instant.now(), "El centro dejo de trabajar con este plan");

			assertThat(convenio.isOperable()).isFalse();
			assertThat(convenio.aplicaEl(ENERO)).isFalse();
			assertThat(convenio.vigencia().cubre(ENERO))
					.as("pero su VIGENCIA sigue diciendo la verdad: son dos cosas")
					.isTrue();
			assertThat(convenio.getDeactivationReason()).isNotBlank();
		}

		@Test
		@DisplayName("seSolapaCon delega en la vigencia y no mira el alcance")
		void solapamiento_no_mira_alcance() {
			// Quien decide que dos convenios COMPITEN es la consulta que los trajo. Meter ese
			// filtro en la entidad duplicaria el criterio en dos lugares.
			assertThat(convenio(ENERO, JUNIO_30).seSolapaCon(convenio(JUNIO_30, DICIEMBRE)))
					.isTrue();
			assertThat(convenio(ENERO, JUNIO_30)
					.seSolapaCon(convenio(JUNIO_30.plusDays(1), DICIEMBRE)))
					.isFalse();
		}
	}

	@Nested
	@DisplayName("ConvenioArancel")
	class DelArancel {

		@Test
		@DisplayName("las partes tienen que sumar EXACTAMENTE el total")
		void las_partes_suman_el_total() {
			assertThatCode(() -> arancel("12000.00", "9600.00", "2400.00"))
					.doesNotThrowAnyException();

			assertThatThrownBy(() -> arancel("12000.00", "9600.00", "2500.00"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("sumar exactamente");
		}

		@Test
		@DisplayName("la comparacion es por VALOR: 12000.0 y 12000.00 son el mismo importe")
		void compara_por_valor_y_no_por_escala() {
			// compareTo y no equals. Con equals, 12000.0 != 12000.00 y este arancel legitimo se
			// rechazaria.
			assertThatCode(() -> arancel("12000.0", "12000.00", "0.000"))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("ningun importe puede ser negativo; cero si es valido en los tres")
		void nada_negativo_pero_cero_vale() {
			assertThatCode(() -> arancel("0.00", "0.00", "0.00")).doesNotThrowAnyException();
			assertThatCode(() -> arancel("12000.00", "0.00", "12000.00"))
					.as("una practica que el financiador no cubre")
					.doesNotThrowAnyException();

			assertThatThrownBy(() -> arancel("12000.00", "13000.00", "-1000.00"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("no puede ser negativo");
		}

		@Test
		@DisplayName("un importe nulo es obligatorio, no cero por descuido")
		void importe_nulo_se_rechaza() {
			assertThatThrownBy(() -> new ConvenioArancel(7L, 20L, 140L, 412L,
					null, BigDecimal.ZERO, BigDecimal.ZERO, "ARS", ENERO, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("obligatorio");
		}

		@Test
		@DisplayName("un importe sin moneda no es un importe")
		void moneda_obligatoria() {
			assertThatThrownBy(() -> new ConvenioArancel(7L, 20L, 140L, 412L,
					BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, null, ENERO, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("sin moneda");
		}

		@Test
		@DisplayName("la edicion valida los importes COMO TERNA aunque llegue uno solo")
		void la_edicion_valida_la_terna() {
			// Es el descuido con el que se rompe la invariante economica sin darse cuenta: subir el
			// total y olvidarse de repartir la diferencia.
			ConvenioArancel arancel = arancel("12000.00", "9600.00", "2400.00");

			assertThatThrownBy(() -> arancel.updateDatos(
					new BigDecimal("13000.00"), null, null, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("sumar exactamente");

			assertThat(arancel.getImporteTotal())
					.as("y la entidad no queda a medio editar")
					.isEqualByComparingTo("12000.00");
		}

		@Test
		@DisplayName("subir el precio bien: los tres importes juntos")
		void subir_el_precio_bien() {
			ConvenioArancel arancel = arancel("12000.00", "9600.00", "2400.00");

			arancel.updateDatos(new BigDecimal("13000.00"), new BigDecimal("10400.00"),
					new BigDecimal("2600.00"), null, null);

			assertThat(arancel.getImporteTotal()).isEqualByComparingTo("13000.00");
			assertThat(arancel.getVigenciaDesde()).as("la vigencia no se toco").isEqualTo(ENERO);
		}

		@Test
		@DisplayName("ciclo de vida y vigencia son dos cosas tambien aca")
		void ciclo_de_vida_y_vigencia() {
			ConvenioArancel arancel = arancel("12000.00", "9600.00", "2400.00");

			assertThat(arancel.aplicaEl(ENERO)).isTrue();
			arancel.deactivate(Instant.now(), "Renegociado");

			assertThat(arancel.aplicaEl(ENERO)).isFalse();
			assertThat(arancel.vigencia().cubre(ENERO)).isTrue();
			assertThat(arancel.isOperable()).isFalse();
		}

		@Test
		@DisplayName("dos aranceles consecutivos NO se solapan")
		void consecutivos_no_se_solapan() {
			ConvenioArancel primero = arancelCon(ENERO, JUNIO_30);
			ConvenioArancel segundo = arancelCon(JUNIO_30.plusDays(1), DICIEMBRE);

			assertThat(primero.seSolapaCon(segundo)).isFalse();
		}
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static Convenio convenio(LocalDate desde, LocalDate hasta) {
		return new Convenio(7L, 20L, 31L, 88L, "OSDE-210", "OSDE 210",
				ModalidadConvenio.POR_PRESTACION, desde, hasta, "ARS",
				false, false, true, null, null, null);
	}

	private static Convenio convenioConMoneda(String moneda) {
		return new Convenio(7L, 20L, 31L, 88L, "C", "N", ModalidadConvenio.POR_SESION,
				ENERO, null, moneda, false, false, true, null, null, null);
	}

	private static ConvenioArancel arancel(String total, String financiador, String coseguro) {
		return new ConvenioArancel(7L, 20L, 140L, 412L,
				new BigDecimal(total), new BigDecimal(financiador), new BigDecimal(coseguro),
				"ARS", ENERO, DICIEMBRE);
	}

	private static ConvenioArancel arancelCon(LocalDate desde, LocalDate hasta) {
		return new ConvenioArancel(7L, 20L, 140L, 412L,
				new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO,
				"ARS", desde, hasta);
	}
}
