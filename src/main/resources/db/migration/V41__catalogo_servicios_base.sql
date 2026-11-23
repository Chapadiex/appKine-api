-- Catálogo global de servicios precargado: un servicio por cada práctica global del catálogo
-- clínico, para que un centro dé de alta sus actividades eligiendo del catálogo en vez de crearlas.
--
-- Cada servicio lleva el MISMO código que su práctica (KIN_SESION, FIS_LASER, ...). Así, al dar de
-- alta una actividad desde el servicio, la pantalla le asocia sola la práctica del mismo código y
-- la actividad queda lista para obra social sin pasar por "Prácticas".
--
-- Evaluaciones y primeras consultas van como CLINICO; las sesiones, como TERAPEUTICO. Todos son
-- individuales y generan registro clínico; ninguno exige caso clínico por defecto. Cada sede lo
-- ajusta en su actividad.
--
-- Idempotente: si un servicio global con ese código o nombre ya existe, no se toca.

INSERT INTO servicio (organization_id, codigo, nombre, descripcion, naturaleza, modalidad_default,
                      requiere_caso_clinico_default, genera_registro_clinico_default, active,
                      version, created_at, updated_at)
VALUES
    (NULL, 'KIN_EVAL', 'Evaluación kinésica', 'Primera evaluación del paciente en kinesiología.', 'CLINICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'KIN_SESION', 'Sesión de kinesiología', 'Sesión de tratamiento kinésico.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'KIN_RESPIRAT', 'Kinesiología respiratoria', 'Sesión de kinesiología respiratoria.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'KIN_DOMICILIO', 'Kinesiología a domicilio', 'Sesión de kinesiología en el domicilio del paciente.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'FIS_ELECTRO', 'Electroterapia', 'Sesión de fisioterapia con electroterapia.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'FIS_LASER', 'Laserterapia', 'Sesión de fisioterapia con láser.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'FIS_MAGNETO', 'Magnetoterapia', 'Sesión de fisioterapia con magnetoterapia.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'FIS_ULTRASONIDO', 'Ultrasonido terapéutico', 'Sesión de fisioterapia con ultrasonido.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'FON_EVAL', 'Evaluación fonoaudiológica', 'Primera evaluación en fonoaudiología.', 'CLINICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'FON_SESION', 'Sesión de fonoaudiología', 'Sesión de tratamiento fonoaudiológico.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'NUT_PRIMERA', 'Consulta nutricional inicial', 'Primera consulta con nutrición.', 'CLINICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'NUT_CONTROL', 'Control nutricional', 'Consulta de seguimiento nutricional.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'PSI_PRIMERA', 'Primera entrevista psicológica', 'Primera entrevista con psicología.', 'CLINICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'PSI_SESION', 'Sesión de psicoterapia', 'Sesión de psicoterapia.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'TO_EVAL', 'Evaluación de terapia ocupacional', 'Primera evaluación en terapia ocupacional.', 'CLINICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (NULL, 'TO_SESION', 'Sesión de terapia ocupacional', 'Sesión de terapia ocupacional.', 'TERAPEUTICO', 'INDIVIDUAL', false, true, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
