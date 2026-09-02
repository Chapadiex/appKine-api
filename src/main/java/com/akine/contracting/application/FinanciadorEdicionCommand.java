package com.akine.contracting.application;

import com.akine.contracting.domain.TipoFinanciador;

/**
 * Edicion parcial de un financiador (RF-M15-002).
 *
 * <p>Semantica de PATCH: los campos {@code null} no se tocan. Para borrar un opcional se manda
 * cadena vacia. Misma convencion que {@code ServicioEdicionCommand} y {@code PersonaEdicionCommand}.
 *
 * <p><b>El codigo no esta, y no es un olvido.</b> Es la clave estable con la que se referencia al
 * financiador: mutarlo haria que una referencia vieja pase a significar otra cosa sin que nadie lo
 * haya pedido, y las coberturas que la guardan no participan de esta edicion.
 *
 * <p>{@code expectedVersion} es obligatoria y se compara antes de mutar: sin ella dos ediciones
 * simultaneas se pisan y el segundo en guardar borra el cambio del primero sin que nadie se
 * entere. Una version desactualizada responde <b>409 con {@code type = conflict}</b> —el
 * {@code OptimisticLockingFailureException} plano que mapea el handler global—, <b>no</b>
 * {@code concurrent-modification}: esa inexactitud la arrastran los contratos publicados de 02.02
 * y 02.05 y esta etapa no la repite.
 */
public record FinanciadorEdicionCommand(
		String nombre,
		TipoFinanciador tipo,
		String cuit,
		String emailContacto,
		String telefonoContacto,
		String observaciones,
		long expectedVersion) {
}
