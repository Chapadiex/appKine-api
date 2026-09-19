package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La secuencia de sesiones <b>dentro de un caso</b> (regla maestra 3).
 *
 * <h2>Por que vive en {@code clinical} si numera sesiones</h2>
 *
 * <p>Porque cuenta <b>por caso</b>, y el caso es de este modulo. Si viviera en {@code encounter},
 * ese modulo tendria que conocer el ciclo de vida del caso —cuando empieza a contar, que pasa al
 * reabrir— que es justamente lo que el {@code spi} existe para no tener que saber. {@code encounter}
 * <b>pide</b> el numero por {@code CasoDirectory} dentro de su transaccion de cierre, y no lo
 * calcula (challenge seccion 1).
 *
 * <h2>Reabrir no lo reinicia</h2>
 *
 * <p>La sesion siguiente a una reapertura es la 9, no la 1. Renumerar seria reescribir historia
 * clinica, que ADR-0011 y la regla maestra 10 prohiben. Por eso el contador cuelga del caso y no
 * de un "periodo de actividad" del caso, que no existe como entidad y no deberia.
 *
 * <h2>El riesgo que introduce</h2>
 *
 * <p>El cierre de sesion ya pedia un numero —el de la historia— y ahora puede pedir <b>dos</b> en
 * la misma transaccion. Es la primera vez que ocurre en este sistema. El orden tiene que ser
 * SIEMPRE el mismo, historia primero y caso despues, o dos cierres concurrentes de sesiones de
 * casos cruzados se bloquean mutuamente. Ver el javadoc de {@code SesionService#cerrar}.
 */
@Entity
@Table(name = "caso_sesion_numerador")
public class CasoSesionNumerador {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "caso_id", nullable = false, updatable = false)
	private Long casoId;

	@Column(name = "ultimo_numero", nullable = false)
	private int ultimoNumero;

	protected CasoSesionNumerador() {
		// Requerido por JPA.
	}

	public Long getId() {
		return id;
	}

	public int getUltimoNumero() {
		return ultimoNumero;
	}
}
