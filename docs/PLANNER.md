# Planificación, autonomía y experimento CP-SAT

Producción usa el motor voraz determinista; no promete óptimo global. planning_rules es la fuente común de restricciones para candidatos y validador: días permitidos, límites, recurrencias, omisiones, disponibilidad, exclusiones, compromisos fijos, buffers, colisiones, duración, dependencias y carga diaria. El generador busca comienzos cada cinco minutos; un horario fijo puede usar cualquier minuto y la duración es exacta. Horizonte máximo 93 días/10000 ocurrencias y presupuesto de 200000 candidatos; se rechaza con explicación si se excede, sin aplicar un resultado parcial.

Las actividades únicas flexibles pueden moverse desde startDate hasta deadline/horizonte; deben respetar días permitidos y until. Las fijas permanecen en su fecha original. Mensuales ajustan el día 31 al último día del mes. Cada dependencia debe existir y no formar ciclos; requiere una ocurrencia anterior finalizada/planificada o una actividad globalmente completada. Una dependencia recurrente se satisface por una ocurrencia previa, no exige finalizar una serie infinita.

Los slots usan fecha y minutos locales en la zona IANA del usuario. El generador compara anticipación en instantes UTC; rechaza horas inexistentes e intervalos cuya duración real cambia al cruzar DST. En una hora ambigua se usa fold=0 (primera ocurrencia); no se soporta escoger la segunda desde UI. Las sesiones completadas se conservan como historia aun si cambian preferencias; siguen bloqueando sesiones activas. El validador detecta superposición con historia independientemente del orden de la lista.

stable_plan conserva sesiones futuras válidas, con validación final. Previsualizar no guarda. Las propuestas del servidor vencen en una hora y exigen revisión coincidente, permiso schedule:approve y confirmación. Revertir requiere que no existan cambios de agenda posteriores. Los pendientes explican las restricciones involucradas; no se crean copias nuevas ni se acumulan tareas extra por omisiones. Terminar una sesión vinculada actualiza memoria y agenda en una transacción.

## Autonomía inicial

Desactivada por defecto, incluso para memorias antiguas que usaban bounded. Configuración permite autorización explícita, máximo desplazamiento (5–60 minutos) y máximo de movimientos (0–5 en siete días). `PUT /api/v1/learning/preferences` exige preferences:write y confirmación. No se puede eludir ese scope importando memoria completa.

WorkManager solicita `POST /api/v1/planning/automatic` después de sincronizar. El servidor solo mueve sesiones flexibles existentes del día actual, dentro de maxShiftMinutes y del presupuesto de siete días; no añade/elimina sesiones ni cambia fecha, compromiso fijo, prioridad, deadline o disponibilidad. Respeta dailyStudyLimitMinutes y todas las reglas. Relee/bloquea el consentimiento y usa CAS de agenda. Guarda propuesta reversible y auditoría. Cambios mayores devuelven requiresApproval, sin aplicar; generar/revisar una propuesta manual es el siguiente paso. El modo totalmente autónomo no existe.

Replanificar responde al estado actual: disponibilidad, prioridades, omisiones explícitas, sesiones completadas y restricciones. Las sugerencias de duración requieren al menos tres sesiones con tiempo previsto/real; son revisables, no alteran duraciones automáticamente. Repasos se recomiendan por evidencia. No se implementa una política automática de olvido ni una medida de concentración.

## Comparación reproducible

```sh
pip install -r backend/requirements-optimization.txt
PYTHONPATH=backend python backend/experiments/compare_equivalent.py
PYTHONPATH=backend pytest -q backend/experiments/test_equivalence.py
```

Nueve casos con las mismas entradas, reglas y función de puntuación para ambos motores: dependencias, recurrencias, fijos/bloqueos/buffers, carga, deadlines/exclusiones, estabilidad, historial, DST y fin de mes. Resultados con entradas completas en benchmarks/equivalent-evaluation.json. Ambos dieron cero conflictos en la ejecución local. La puntuación publicada combina prioridad, cantidad, estabilidad, desvío horario y pico diario; cada métrica se muestra por separado. El experimento anterior simplificado se conserva como evidencia histórica, no es el fundamento de adopción.

CP-SAT usa candidatos de las reglas comunes, NoOverlap, dependencias y carga; tiene límite de 100 ocurrencias/31 días y tres segundos por escenario comparativo. OPTIMAL se refiere a ese modelo discreto y función objetivo, FEASIBLE no declara óptimo. La muestra no demuestra equivalencia general ni mejora sostenida; se mantiene el motor actual. Falta ampliar corpus y calibrar los pesos con uso real antes de cualquier sustitución.
