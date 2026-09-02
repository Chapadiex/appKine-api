package com.akine.contracting.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * El snapshot economico CONGELADO de un arancel, para que el consumidor lo COPIE a sus propias
 * columnas (RN-M16-004).
 *
 * <h2>Por que existe: RN-M16-003 con todas las letras</h2>
 *
 * <p>"Cambiar arancel no recalcula sesiones historicas". Si {@code obligacion} guardara solo un
 * {@code arancelId} y resolviera el importe al mostrar la deuda, subir el arancel en enero
 * reescribiria lo que el paciente debia en diciembre —y lo que ya se presento al financiador— sin
 * que ninguna de esas dos cosas participe de la edicion. Peor: dar de baja el convenio dejaria
 * deudas historicas sin importe.
 *
 * <p>La solucion NO es una columna de {@code convenio_arancel} ni un trigger: es que el consumidor
 * <b>copie</b> estos valores a sus propias columnas en el momento de devengar, y no vuelva a
 * leerlos. Es exactamente lo que {@code obligacion} ya hace con el precio de la oferta desde
 * AKINE-07.01 —"snapshot congelado, con importe congelado y DECIMAL"— y lo que
 * {@code ReferenciaDeCobertura} hace con el plan desde 03.03. Por eso este tipo es un
 * {@code record} de valores y no un puntero al agregado.
 *
 * <h2>Como se usa, y como NO se usa</h2>
 *
 * <pre>
 *   BIEN  al devengar:  congelar(...) -&gt; copiar los campos a las columnas propias -&gt; guardar.
 *         al mostrar:   leer las columnas propias. Nunca volver a llamar al spi.
 *
 *   MAL   guardar solo arancelId y llamar a resolver(...) cada vez que se muestra la deuda.
 *         Eso es una lectura viva disfrazada de referencia, y es el defecto que este tipo
 *         existe para hacer imposible de escribir por descuido.
 * </pre>
 *
 * <p><b>Lo que este record NO puede garantizar por si solo.</b> Nada impide tecnicamente que un
 * consumidor futuro ignore la copia y lea vivo: la garantia final es de quien escriba M17, M18 y
 * M21. Lo que si esta hecho es que el camino correcto sea el mas corto —{@code congelar} devuelve
 * todo lo que hay que copiar, en un solo objeto— y que el incorrecto quede escrito como incorrecto
 * aca.
 *
 * <h2>Que incluye y que no</h2>
 *
 * <p>Incluye la <b>identidad estable</b> (los ids y el codigo del convenio, que es inmutable), el
 * <b>texto de aquel momento</b> (el nombre del convenio, que si cambia) y los <b>importes de aquel
 * momento</b>. Incluye tambien los requisitos vigentes ese dia, porque una presentacion rechazada
 * seis meses despues necesita poder decir si en aquel momento se exigia autorizacion.
 *
 * <p>NO incluye las vigencias del convenio ni del arancel: dicen cuando se PODIA aplicar, y una
 * vez aplicado lo que vale es la fecha de la prestacion, que la fija el consumidor y no este
 * modulo. {@link #vigenteEl} es esa fecha.
 *
 * @param vigenteEl   dia de la prestacion contra el que se resolvio. Es la unica fecha que un
 *                    historico necesita para explicarse
 * @param capturadoEl instante UTC en que se congelo. Es lo que permite explicar, seis meses
 *                    despues, por que esta copia dice algo distinto de lo que el convenio dice hoy
 */
public record ArancelCongelado(
		long convenioId,
		String convenioCodigo,
		String convenioNombre,
		String modalidad,
		long financiadorId,
		long planId,
		long practicaId,
		long arancelId,
		BigDecimal importeTotal,
		BigDecimal importeFinanciador,
		BigDecimal coseguro,
		String moneda,
		boolean requeriaOrden,
		boolean requeriaAutorizacion,
		boolean requeriaCredencial,
		LocalDate vigenteEl,
		Instant capturadoEl) {
}
