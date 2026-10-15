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
	@DisplayName("El esquema de cobro tiene vocabulario cerrado y deriva su momento de devengo; POR_CLASE espera decision")
	void esquema_de_cobro() {
		assertThat(new EsquemaCobro("POR_SESION").momentoDevengo()).isEqualTo("ASISTENCIA");
		assertThat(new EsquemaCobro("POR_PACK").momentoDevengo()).isEqualTo("VENTA");
		assertThat(new EsquemaCobro("POR_ABONO").momentoDevengo()).isEqualTo("VENTA");
		assertThatThrownBy(() -> new EsquemaCobro("SESION_SUELTA")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EsquemaCobro("POR_CLASE")).isInstanceOf(IllegalArgumentException.class);
	}

	private static ProductoServicio pack(int creditos, String precio, String moneda) {
		return ProductoServicio.pack(1L, 2L, 3L, "Pack Pilates", null, creditos, new BigDecimal(precio),
				moneda, 30, DESDE, null);
	}
}
