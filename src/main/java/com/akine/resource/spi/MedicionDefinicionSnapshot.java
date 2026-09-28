package com.akine.resource.spi;

import java.math.BigDecimal;

/**
 * Lo que otro modulo puede saber de una definicion de medida (M06).
 *
 * <p>Es lo que {@code encounter} necesita para <b>registrar</b> una medicion, y nada mas: la
 * entidad, su repositorio y su ciclo de vida se quedan en {@code resource} (AGENT.md seccion 4,
 * regla 1).
 *
 * <h2>Lo que el consumidor tiene que COPIAR en su fila</h2>
 *
 * <p>{@code codigo}, {@code nombre}, {@code unidad}, {@code tipo} y {@code version}. No es una
 * comodidad de lectura: si el consumidor guardara solo el {@code id} y resolviera el significado
 * por este spi al leer, un {@code UPDATE} sobre el catalogo —cambiar la unidad de cm a mm,
 * corregir el nombre de un test— <b>reescribiria el significado de todas las mediciones
 * pasadas</b> sin tocar una sola fila de {@code sesion_medicion}. Es el mismo snapshot que
 * congela el importe en la obligacion (07.01) y el nombre de la oferta en el item del plan
 * (04.04).
 *
 * <h2>{@code minimo} y {@code maximo} valen AL REGISTRAR, jamas al leer</h2>
 *
 * <p>El rango se evalua contra la version vigente en el instante del registro. Si manana el
 * catalogo lo estrecha, las mediciones viejas <b>no se vuelven invalidas</b>: fueron validas
 * cuando se tomaron. Por eso el rango no se copia en la fila de la medicion — no hay ninguna
 * lectura que deba volver a usarlo— y si se copia {@code version}, que es lo que permite saber
 * contra que redaccion del test se valido.
 *
 * <h2>Resuelve tambien las inactivas, a proposito</h2>
 *
 * <p>Igual que {@code CatalogoDirectory}: una definicion dada de baja <b>sigue resolviendo</b>,
 * porque las mediciones que ya la usaron tienen que seguir siendo legibles (RN-M06-001,
 * RN-M06-002). Lo que {@code activa == false} impide es <b>registrar nuevas</b>, y esa decision
 * la toma el consumidor, no este record.
 *
 * @param organizationId {@code null} cuando la definicion es global de plataforma
 * @param unidad         {@code null} solo cuando {@code tipo} es {@code TEXTO} o {@code BOOLEANO}
 * @param minimo         {@code null} = sin piso. Solo en los tipos numericos
 * @param maximo         {@code null} = sin techo. INCLUSIVE cuando esta
 */
public record MedicionDefinicionSnapshot(

		long id,

		Long organizationId,

		String codigo,

		String nombre,

		MedicionTipo tipo,

		String unidad,

		BigDecimal minimo,

		BigDecimal maximo,

		boolean activa,

		long version) {

	/** {@code true} si la definicion es del catalogo comun y la ve todo el SaaS. */
	public boolean esGlobal() {
		return organizationId == null;
	}

	/**
	 * {@code true} si {@code valor} cae dentro del rango declarado.
	 *
	 * <p>Los dos extremos son <b>inclusivos</b>: un EVA de 0 y uno de 10 son valores legitimos de
	 * la escala, y dejarlos afuera convertiria los dos casos mas frecuentes —sin dolor, dolor
	 * maximo— en errores. Es la diferencia deliberada con las ventanas de vigencia de M06, donde
	 * el tope es exclusivo porque ahi lo que se evita es el solape de dos intervalos.
	 *
	 * <p>Vive aca y no en el servicio que la llama porque es aritmetica del propio concepto: el
	 * rango lo declara esta definicion, y quien lo evaluara es otro modulo.
	 */
	public boolean admiteValor(BigDecimal valor) {
		if (valor == null) {
			return false;
		}
		if (minimo != null && valor.compareTo(minimo) < 0) {
			return false;
		}
		return maximo == null || valor.compareTo(maximo) <= 0;
	}
}
