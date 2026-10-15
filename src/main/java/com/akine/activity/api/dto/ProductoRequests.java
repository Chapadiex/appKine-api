package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cuerpos de las operaciones sobre productos de pack (AKINE-08.06). */
public final class ProductoRequests {

	private ProductoRequests() {
	}

	@Schema(
			name = "CrearProducto",
			description = "Alta de un producto de pack sobre una oferta de la sede que declara "
					+ "`esquemaCobro = POR_PACK`. **No crea saldo a nadie** (CA-M29-001-06).")
	public record Crear(

			@Schema(description = "Oferta de la sede cuyos creditos vende el producto", example = "42")
			@Positive long ofertaId,

			@Schema(description = "Unico entre los productos vigentes de la sede", example = "Pack Pilates 8 clases")
			@NotBlank @Size(max = 160) String nombre,

			@Schema(description = "Descripcion comercial. Nunca contenido clinico.")
			@Size(max = 500) String descripcion,

			@Schema(description = "Creditos que otorga la compra", example = "8")
			@Positive int creditos,

			@Schema(description = "Precio de venta, decimal exacto", example = "40000.00")
			@NotNull @Positive BigDecimal precio,

			@Schema(description = "ISO 4217", example = "ARS")
			@NotBlank @Size(min = 3, max = 3) String moneda,

			@Schema(description = "Dias corridos que vale el pase desde la compra, contando el dia "
					+ "de la compra", example = "60")
			@Positive int vigenciaDias,

			@Schema(description = "Desde cuando se puede vender", example = "2026-10-01")
			@NotNull LocalDate vigenciaDesde,

			@Schema(description = "Hasta cuando se puede vender, inclusive. Ausente: sin fin.")
			LocalDate vigenciaHasta) {
	}

	@Schema(
			name = "EditarProducto",
			description = "Edita el producto. Los campos ausentes se conservan. **Los pases ya "
					+ "vendidos no cambian**: llevan su propio snapshot.")
	public record Editar(

			@Size(max = 160) String nombre,

			@Size(max = 500) String descripcion,

			@Positive Integer creditos,

			@Positive BigDecimal precio,

			@Size(min = 3, max = 3) String moneda,

			@Positive Integer vigenciaDias,

			LocalDate vigenciaDesde,

			LocalDate vigenciaHasta,

			@Schema(description = "true deja la ventana comercial sin fin", example = "false")
			boolean limpiarVigenciaHasta,

			@Schema(description = "Version que el cliente leyo", example = "0")
			@NotNull @PositiveOrZero Long version) {
	}

	@Schema(
			name = "BajaDeProducto",
			description = "Baja logica con motivo. **No cascadea**: los pases vendidos conservan "
					+ "saldo y vigencia.")
	public record Baja(

			@Schema(description = "Obligatorio", example = "Se discontinua el pack de 8")
			@NotBlank @Size(max = 280) String motivo) {
	}

	@Schema(
			name = "VenderPase",
			description = "Vende un pase a una Persona: crea el pase con su saldo inicial y "
					+ "**una obligacion**, sin cobrar (DP-06). La persona no se convierte en paciente.")
	public record Vender(

			@Schema(description = "Producto vigente de la sede", example = "12")
			@Positive long productoId,

			@Schema(description = "Titular, del padron de la organizacion", example = "512")
			@Positive long personaId,

			@Schema(
					description = "Clave del cliente para que un reintento no venda dos veces. "
							+ "Reusarla con otro pedido es un 409.",
					example = "c0a8-venta-512")
			@Size(max = 80) String idempotencyKey) {
	}
}
