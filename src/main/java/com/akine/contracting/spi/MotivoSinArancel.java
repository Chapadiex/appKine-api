package com.akine.contracting.spi;

/**
 * Por que no hay arancel para esa consulta.
 *
 * <h2>Por que la ausencia viaja con motivo y no como un {@code empty} a secas</h2>
 *
 * <p>Los tres casos exigen acciones opuestas de quien pregunta, y colapsarlos en "no hay" obliga a
 * adivinar cual es. Es la misma leccion que 05.01 dejo escrita con {@code MotivoSinSlots}: un dia
 * sin slots nunca viaja sin su motivo, porque "no hay turnos" y "no hay turnos porque el
 * profesional no trabaja los martes" mandan al usuario a lugares distintos.
 *
 * <p>Y es tambien lo que hace ejecutable RN-M16-005 —"sin convenio valido no se debe asumir
 * cobertura"—: quien recibe {@link #SIN_CONVENIO_VIGENTE} sabe que tiene que cobrar como
 * particular, no que hubo un error.
 */
public enum MotivoSinArancel {

	/**
	 * No hay ningun convenio activo de esa sede con ese plan que cubra esa fecha.
	 *
	 * <p>Puede ser que nunca se haya firmado, que se haya dado de baja, o que la fecha caiga fuera
	 * de su vigencia —el caso borde "convenio vencido" de la etapa—. <b>La prestacion no tiene
	 * cobertura pactada</b>: RN-M16-005.
	 */
	SIN_CONVENIO_VIGENTE,

	/**
	 * Hay convenio, pero esa practica no tiene arancel vigente en el.
	 *
	 * <p>Es distinto del anterior y la diferencia es operativa: el acuerdo existe y lo que falta es
	 * cargar el precio de esa practica, que es algo que el administrador puede resolver. Tambien
	 * cubre el caso borde "atencion retroactiva": la practica tiene arancel hoy pero no lo tenia el
	 * dia en que se atendio.
	 */
	SIN_ARANCEL_VIGENTE
}
