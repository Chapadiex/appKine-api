package com.akine.billing.domain.port;

import com.akine.billing.domain.Presentacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de los lotes reclamados a financiadores.
 *
 * <p>Las dos escrituras que mueven el saldo son <b>UPDATE condicionales</b> y no metodos del
 * agregado: {@code WHERE ... AND saldo >= :importe}. No hay ventana entre leer y escribir porque
 * <b>no se lee</b>, y por eso no pueden deadlockear ni dejar el saldo en negativo aunque un debito
 * y un pago lleguen juntos. Es lo mismo que 07.02 hizo para imputar, 07.03 para el arqueo y 04.05
 * para consumir.
 */
public interface PresentacionRepositoryPort {

	Presentacion save(Presentacion presentacion);

	Optional<Presentacion> findByIdInScope(
			long organizationId, long consultorioId, long presentacionId);

	/**
	 * Las bandejas por estado (RF-M21-001 y siguientes), paginadas.
	 *
	 * @param estado        {@code null} no filtra
	 * @param financiadorId {@code null} no filtra
	 */
	List<Presentacion> buscar(
			long organizationId, long consultorioId, String estado, Long financiadorId,
			LocalDate desde, LocalDate hasta, int limite, int desplazamiento);

	/** Si ese numero de factura ya esta tomado por otro lote del mismo financiador. */
	boolean existeFactura(long organizationId, long financiadorId, String facturaNumero);

	/**
	 * Descuenta del saldo por un <b>pago</b> recibido (RF-M21-007).
	 *
	 * <p>Suma a {@code total_cobrado} y resta del saldo en la misma sentencia. Solo sobre un lote
	 * {@code PRESENTADA} o {@code FACTURADA}: un borrador no puede cobrar nada y un lote conciliado
	 * ya esta cerrado.
	 *
	 * @return filas afectadas. <b>Cero significa que el estado cambio o que no alcanza el saldo</b>,
	 *         y es un desenlace legitimo que el llamador traduce a 409 despues de releer para
	 *         distinguir cual de las dos fue
	 */
	int registrarCobro(long organizationId, long presentacionId, BigDecimal importe);

	/**
	 * Descuenta del saldo por un <b>debito</b> del financiador (RF-M21-006).
	 *
	 * <p>Misma forma y mismas condiciones que {@link #registrarCobro}, sobre otra columna. Son dos
	 * sentencias y no una con un parametro porque el total que se mueve decide el significado de la
	 * fila, y un solo metodo con un {@code CASE} haria que el llamador tuviera que leer SQL para
	 * saber que esta registrando.
	 */
	int registrarDebito(long organizationId, long presentacionId, BigDecimal importe);
}
