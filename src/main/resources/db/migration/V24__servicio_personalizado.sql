-- Personalizacion de un servicio del catalogo global por una organizacion.
--
-- Un servicio GLOBAL (organization_id NULL) lo comparten todas las organizaciones: editarlo desde
-- un centro le cambiaria el dato a todos los demas. Lo que un administrador de organizacion edita
-- es una COPIA propia de su organizacion, que apunta al global del que salio. La copia vale para
-- todas las sedes de esa organizacion, no para una sede: el servicio es un concepto de la
-- organizacion, y la sede solo lo publica como oferta.
--
-- Aditiva: columna nullable, sin datos que migrar.

ALTER TABLE servicio ADD COLUMN personaliza_servicio_id bigint;

ALTER TABLE servicio
    ADD CONSTRAINT fk_servicio_personaliza FOREIGN KEY (personaliza_servicio_id) REFERENCES servicio (id);

-- Solo una copia de organizacion personaliza algo: un global no personaliza a otro global.
ALTER TABLE servicio
    ADD CONSTRAINT ck_servicio_personaliza_es_de_organizacion
    CHECK (personaliza_servicio_id IS NULL OR organization_id IS NOT NULL);

-- Una sola personalizacion vigente por organizacion y servicio global. Dos altas simultaneas
-- chocan aca y no en la aplicacion.
CREATE UNIQUE INDEX uk_servicio_personalizacion_vigente
    ON servicio (organization_id, personaliza_servicio_id, deleted_key)
    WHERE personaliza_servicio_id IS NOT NULL;

CREATE INDEX fk_servicio_personaliza ON servicio (personaliza_servicio_id);

COMMENT ON COLUMN servicio.personaliza_servicio_id IS 'Servicio GLOBAL que esta copia personaliza para su organizacion (todas sus sedes). NULL en los globales y en los servicios propios creados desde cero. Mientras la copia este vigente, el global no se lista para esa organizacion y sus ofertas apuntan a la copia';
