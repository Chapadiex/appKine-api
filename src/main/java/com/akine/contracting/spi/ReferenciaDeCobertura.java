package com.akine.contracting.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * La referencia CONGELADA a un plan de cobertura, para que el consumidor la copie a sus propias
 * columnas.
 *
 * <h2>Por que existe: el requisito que sostiene M08 y M16</h2>
 *
 * <p>Una cobertura firmada ayer <b>no puede cambiar porque hoy alguien edito el plan</b>. Si M08
 * guardara solo un {@code plan_id} y resolviera el nombre al mostrar, renombrar "Plan 210" a
 * "Plan 210 Premium" reescribiria retroactivamente todas las coberturas firmadas bajo el nombre
 * viejo; cerrar la vigencia del plan haria que una cobertura del año pasado se muestre como si
 * nunca hubiera sido valida; y bajar el copago cambiaria liquidaciones ya presentadas
 * (CA-M15-008-06 lo dice con todas las letras: "un cambio de cobertura futuro no altera
 * liquidaciones historicas").
 *
 * <p>La solucion NO es una columna de esta tabla ni un trigger: es que el consumidor <b>copie</b>
 * estos valores a sus propias columnas en el momento de firmar, y no vuelva a leerlos. Es
 * exactamente lo que {@code obligacion} hace con el precio de la oferta en AKINE-07.01 —"snapshot
 * congelado, con importe congelado y DECIMAL"— y la razon por la que este tipo es un
 * {@code record} de valores y no un puntero al agregado.
 *
 * <h2>Como se usa, y como NO se usa</h2>
 *
 * <pre>
 *   BIEN  al firmar:   congelar(...) -> copiar los campos a las columnas propias -> guardar.
 *         al mostrar:  leer las columnas propias. Nunca volver a llamar al spi.
 *
 *   MAL   guardar solo planId y llamar a findPlan(...) cada vez que se muestra la cobertura.
 *         Eso es una lectura viva disfrazada de referencia, y es el defecto que este tipo
 *         existe para hacer imposible de escribir por descuido.
 * </pre>
 *
 * <p><b>Lo que este record NO puede garantizar por si solo.</b> Nada impide tecnicamente que un
 * consumidor futuro ignore la copia y lea vivo: la garantia final es de quien escribe M08 y M16.
 * Lo que si esta hecho es que el camino correcto sea el mas corto —{@code congelar} devuelve todo
 * lo que hay que copiar, en un solo objeto— y que el incorrecto quede escrito como incorrecto acá.
 *
 * <h2>Que incluye y que no</h2>
 *
 * <p>Incluye la <b>identidad estable</b> (los ids y los dos codigos, que son inmutables), el
 * <b>texto de aquel momento</b> (los dos nombres, que si cambian) y las <b>condiciones
 * economicas y operativas de aquel momento</b> (copago, moneda, si exigia autorizacion y
 * credencial). No incluye la vigencia del plan: la vigencia dice cuando se PODIA elegir, y una
 * vez elegido lo que vale es la vigencia de la cobertura, que la fija M08 y no este modulo.
 *
 * @param capturadaEl instante UTC en que se congelo. Es lo que permite explicar, seis meses
 *                    despues, por que esta copia dice algo distinto de lo que el plan dice hoy
 */
public record ReferenciaDeCobertura(
		long financiadorId,
		String financiadorCodigo,
		String financiadorNombre,
		String financiadorTipo,
		long planId,
		String planCodigo,
		String planNombre,
		boolean requeriaAutorizacion,
		boolean requeriaCredencial,
		BigDecimal copago,
		String moneda,
		LocalDate vigenteEl,
		Instant capturadaEl) {
}
