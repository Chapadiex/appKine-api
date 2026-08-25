package com.akine.organization.spi;

/**
 * Lo minimo que otro modulo necesita saber de una sede, sin poder tocar su entity.
 *
 * <p><b>Por que un record y no el {@code Consultorio}.</b> La entity vive en
 * {@code organization.domain} y ArchUnit prohibe que otro modulo la importe
 * ({@code modulos_solo_se_alcanzan_por_su_spi}). Devolverla ademas dejaria que el consumidor
 * la modificara dentro de una transaccion ajena, que es la forma mas silenciosa de romper el
 * ownership de una tabla.
 *
 * <p>Se expone {@code timezone} porque es la zona EFECTIVA de toda regla local de la sede
 * (AKINE-02.01): el modulo que proyecte una ventana de disponibilidad a horario de pared la
 * necesita, y buscarla por su cuenta significaria leer {@code consultorio} desde afuera.
 *
 * @param active {@code true} si la sede admite hechos nuevos. Una sede dada de baja sigue
 *               respondiendo a las consultas sobre lo que ya paso ahi (RF-M03-004), asi que
 *               el estado viaja como dato y no como ausencia de resultado
 */
public record ConsultorioSnapshot(
		long id,
		long organizationId,
		String name,
		String timezone,
		boolean active) {
}
