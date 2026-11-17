package com.akine.encounter.domain.evolucion.sugerencias;

/** Las reglas del validador (diseno fase 2, 6), en el orden en que se evaluan. */
public enum ReglaDeDescarte {
	ESQUEMA, HALLAZGO_NO_ENVIADO, SIN_CITAS, CITA_DESCONOCIDA, CITA_DE_OTRO_HALLAZGO, NUMERO_NO_ENVIADO,
	FORMA_PRESCRIPTIVA, LARGO, INSTRUCCION
}
