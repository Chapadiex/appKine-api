package com.akine.organization.spi;

import java.math.BigDecimal;

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
 * <h2>Lo que agrego V140</h2>
 *
 * <p>{@code agendaHabilitada} es lo que {@code scheduling} mira antes de ocupar la agenda de una
 * sede. {@code direccionPublica}, {@code latitud} y {@code longitud} son lo que un aviso de turno
 * puede decirle al paciente: viajan <b>ya filtrados</b> por la decision de la sede de publicar o no
 * su direccion, asi que el consumidor no tiene que conocer esa regla. {@code null} significa "no
 * hay nada que mostrar", sea porque la sede no tiene direccion o porque eligio no publicarla.
 *
 * @param active           {@code true} si la sede admite hechos nuevos. Una sede dada de baja sigue
 *                         respondiendo a las consultas sobre lo que ya paso ahi (RF-M03-004), asi que
 *                         el estado viaja como dato y no como ausencia de resultado
 * @param agendaHabilitada {@code false} mientras la sede no este lista para turnos: reservar, crear
 *                         series o trasladar turnos hacia ella es 409 {@code consultorio-sin-agenda}
 * @param direccionPublica direccion fisica en una linea, o {@code null} si la sede no la publica o
 *                         no la tiene
 * @param latitud          coordenada publica, o {@code null} en los mismos casos que la direccion
 * @param longitud         idem
 */
public record ConsultorioSnapshot(
		long id,
		long organizationId,
		String name,
		String timezone,
		boolean active,
		boolean agendaHabilitada,
		String direccionPublica,
		BigDecimal latitud,
		BigDecimal longitud) {

	/**
	 * La forma anterior a V140: sede con agenda habilitada y sin direccion publica. La usan los
	 * consumidores y tests que no necesitan los datos nuevos.
	 */
	public ConsultorioSnapshot(
			long id, long organizationId, String name, String timezone, boolean active) {
		this(id, organizationId, name, timezone, active, true, null, null, null);
	}
}
