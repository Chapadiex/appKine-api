package com.akine.activity.domain;

import com.akine.activity.domain.exception.ProductoDadoDeBajaException;
import com.akine.offering.domain.EsquemaCobro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Producto, pase y esquema de cobro")
class ProductoYPaseTest {

	private static final LocalDate DESDE = LocalDate.of(2026, 10, 1);

	@Test
	@DisplayName("Un producto exige creditos, precio y vigencia positivos y moneda ISO 4217")
	void validaciones_del_producto() {
		assertThatThrownBy(() -> pack(0, "40000", "ARS")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> pack(8, "0", "ARS")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> pack(8, "40000", "PESOS")).isInstanceOf(IllegalArgumentException.class);
		assertThat(pack(8, "40000", "ars").getMoneda()).isEqualTo("ARS");
	}

	@Test
	@DisplayName("La baja exige motivo, es idempotente y deja de vender; un producto de baja no se edita")
	void baja() {
		ProductoServicio producto = pack(8, "40000", "ARS");
		assertThatThrownBy(() -> producto.darDeBaja(" ", Instant.now())).isInstanceOf(IllegalArgumentException.class);

		assertThat(producto.darDeBaja("Se discontinua", Instant.now())).isTrue();
		assertThat(producto.darDeBaja("Otra vez", Instant.now())).isFalse();
		assertThat(producto.getDeactivationReason()).isEqualTo("Se discontinua");
		assertThat(producto.impedimentoParaVenderEl(DESDE)).isPresent();
		assertThatThrownBy(() -> producto.editar("x", null, null, null, null, null, null, null, false))
				.isInstanceOf(ProductoDadoDeBajaException.class);
	}

	@Test
	@DisplayName("La ventana comercial decide si se vende, extremos inclusive")
	void ventana_comercial() {
		ProductoServicio producto = ProductoServicio.pack(1L, 2L, 3L, "Pack", null, 8,
				new BigDecimal("100"), "ARS", 30, DESDE, LocalDate.of(2026, 10, 31));
		assertThat(producto.impedimentoParaVenderEl(DESDE.minusDays(1))).isPresent();
		assertThat(producto.impedimentoParaVenderEl(DESDE)).isEmpty();
		assertThat(producto.impedimentoParaVenderEl(LocalDate.of(2026, 10, 31))).isEmpty();
		assertThat(producto.impedimentoParaVenderEl(LocalDate.of(2026, 11, 1))).isPresent();
	}

	@Test
	@DisplayName("El pase congela el snapshot y vale vigenciaDias dias contando el de la compra")
	void pase_congela_y_vence() {
		ProductoServicio producto = pack(8, "40000", "ARS");
		PaseServicio pase = PaseServicio.vender(producto, 512L, LocalDate.of(2026, 10, 14), null, null, 9L,
				Instant.now());
		producto.editar(null, null, 10, new BigDecimal("55000"), null, null, null, null, false);

		assertThat(pase.getSnapshotPrecio()).isEqualByComparingTo("40000");
		assertThat(pase.getCreditosDisponibles()).isEqualTo(8);
		assertThat(pase.getVigenciaHasta()).isEqualTo(LocalDate.of(2026, 11, 12));
		assertThat(pase.vigenteEl(LocalDate.of(2026, 11, 12))).isTrue();
		assertThat(pase.vigenteEl(LocalDate.of(2026, 11, 13))).isFalse();
		assertThat(pase.getEstado()).isEqualTo(EstadoPase.ACTIVO);
		assertThatThrownBy(() -> PaseServicio.vender(producto, 512L, DESDE, "clave", null, 9L, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("El esquema de cobro tiene vocabulario cerrado y deriva su momento de devengo; POR_CLASE devenga con la asistencia (U-39)")
	void esquema_de_cobro() {
		assertThat(new EsquemaCobro("POR_SESION").momentoDevengo()).isEqualTo("ASISTENCIA");
		assertThat(new EsquemaCobro("POR_PACK").momentoDevengo()).isEqualTo("VENTA");
		assertThat(new EsquemaCobro("POR_ABONO").momentoDevengo()).isEqualTo("VENTA");
		assertThatThrownBy(() -> new EsquemaCobro("SESION_SUELTA")).isInstanceOf(IllegalArgumentException.class);
		assertThat(new EsquemaCobro("POR_CLASE").momentoDevengo()).isEqualTo("ASISTENCIA");
	}

	@Test
	@DisplayName("AKINE-08.08: un mes calendario se renueva por el mes siguiente; si el producto no lo cubre, por su duracion")
	void periodo_siguiente_de_un_abono() {
		// Noviembre entero -> diciembre entero, no "31 dias desde el 1".
		assertThat(periodo(1, "2026-11-30", 31)).containsExactly(fecha("2026-12-01"), fecha("2026-12-31"));
		// Enero entero -> febrero entero, aunque dure 28.
		assertThat(periodo(1, "2027-01-31", 31)).containsExactly(fecha("2027-02-01"), fecha("2027-02-28"));
		// Si el producto no cubre el mes siguiente, la duracion del producto.
		assertThat(periodo(1, "2026-11-30", 30)).containsExactly(fecha("2026-12-01"), fecha("2026-12-30"));
		// Anclado al 5 y un producto de 30 dias: diciembre al 4 de enero son 31, no entran.
		assertThat(periodo(5, "2026-12-04", 30)).containsExactly(fecha("2026-12-05"), fecha("2027-01-03"));
		// Un abono semanal no se ancla a nada: dura lo que el producto.
		assertThat(periodo(5, "2026-11-11", 7)).containsExactly(fecha("2026-11-12"), fecha("2026-11-18"));
	}

	@Test
	@DisplayName("DP-40 (U-45): el periodo se ancla al dia de la primera venta, no a vigenciaDias corrido")
	void periodo_anclado_al_dia_de_la_primera_venta() {
		// Del 5 al 5: el mismo dia del mes siguiente, dure 28, 30 o 31 dias.
		assertThat(periodo(5, "2026-12-04", 31)).containsExactly(fecha("2026-12-05"), fecha("2027-01-04"));
		assertThat(periodo(5, "2027-02-04", 31)).containsExactly(fecha("2027-02-05"), fecha("2027-03-04"));
		assertThat(periodo(5, "2027-03-04", 31)).containsExactly(fecha("2027-03-05"), fecha("2027-04-04"));
	}

	@Test
	@DisplayName("DP-40 (U-45): anclado al 31, febrero y los meses de 30 toman su ultimo dia y el ancla vuelve al 31")
	void periodo_anclado_al_31() {
		// 31/01 -> el 31 de febrero no existe: arranca el 28/02 ...
		assertThat(periodo(31, "2027-01-30", 31)).containsExactly(fecha("2027-01-31"), fecha("2027-02-27"));
		// ... y desde el 28/02 el ancla sigue siendo 31: termina el 30/03, no el 27/03.
		assertThat(periodo(31, "2027-02-27", 31)).containsExactly(fecha("2027-02-28"), fecha("2027-03-30"));
		assertThat(periodo(31, "2027-03-30", 31)).containsExactly(fecha("2027-03-31"), fecha("2027-04-29"));
		// 30/04 es el 31 de abril que no existe; vuelve al 31 en mayo.
		assertThat(periodo(31, "2027-04-29", 31)).containsExactly(fecha("2027-04-30"), fecha("2027-05-30"));
		assertThat(periodo(31, "2027-05-30", 31)).containsExactly(fecha("2027-05-31"), fecha("2027-06-29"));
		// Diciembre cruza el año.
		assertThat(periodo(31, "2027-12-30", 31)).containsExactly(fecha("2027-12-31"), fecha("2028-01-30"));
	}

	@Test
	@DisplayName("DP-40 (U-45): en un bisiesto el ancla al 29, 30 o 31 cae el 29 de febrero")
	void periodo_anclado_en_bisiesto() {
		assertThat(periodo(31, "2028-01-30", 31)).containsExactly(fecha("2028-01-31"), fecha("2028-02-28"));
		assertThat(periodo(31, "2028-02-28", 31)).containsExactly(fecha("2028-02-29"), fecha("2028-03-30"));
		assertThat(periodo(30, "2028-01-29", 31)).containsExactly(fecha("2028-01-30"), fecha("2028-02-28"));
		assertThat(periodo(30, "2028-02-28", 31)).containsExactly(fecha("2028-02-29"), fecha("2028-03-29"));
		assertThat(periodo(29, "2028-01-28", 31)).containsExactly(fecha("2028-01-29"), fecha("2028-02-28"));
		assertThat(periodo(29, "2028-02-28", 31)).containsExactly(fecha("2028-02-29"), fecha("2028-03-28"));
		// El ancla al 29 en un año comun cae el 28 de febrero y vuelve al 29 en marzo.
		assertThat(periodo(29, "2027-01-28", 31)).containsExactly(fecha("2027-01-29"), fecha("2027-02-27"));
		assertThat(periodo(29, "2027-02-27", 31)).containsExactly(fecha("2027-02-28"), fecha("2027-03-28"));
	}

	@Test
	@DisplayName("DP-40 (U-45): un periodo que no arranca en el ancla se re-ancla en su propio inicio, sin hueco")
	void periodo_fuera_del_ancla() {
		// Primer periodo del 5 al 30 de noviembre: el siguiente es diciembre entero, no del 1 al 4.
		assertThat(periodo(5, "2026-11-30", 31)).containsExactly(fecha("2026-12-01"), fecha("2026-12-31"));
		assertThatThrownBy(() -> periodo(0, "2026-11-30", 31)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> periodo(32, "2026-11-30", 31)).isInstanceOf(IllegalArgumentException.class);
	}

	private static LocalDate[] periodo(int diaAncla, String hastaAnterior, int vigenciaDias) {
		return PaseServicio.periodoSiguiente(diaAncla, fecha(hastaAnterior), vigenciaDias);
	}

	private static LocalDate fecha(String iso) {
		return LocalDate.parse(iso);
	}

	private static ProductoServicio pack(int creditos, String precio, String moneda) {
		return ProductoServicio.pack(1L, 2L, 3L, "Pack Pilates", null, creditos, new BigDecimal(precio),
				moneda, 30, DESDE, null);
	}
}
