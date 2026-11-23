# Evaluación del planificador y autonomía

El archivo original `planner.py` se conserva. El nuevo flujo v1 utiliza un envoltorio que identifica sesiones futuras todavía válidas y las fija temporalmente al llamar al mismo motor; restaura después su estado real de completado. Una prueba comparativa verifica que el motor original reubica una sesión y el envoltorio la conserva sin conflictos. Las sesiones realmente completadas nunca se mueven.

REST v1 ofrece `/api/v1/planning/preview`, `/proposals`, `/proposals/{id}/approve` y `/proposals/{id}/undo`. Las propuestas de servidor vencen en una hora, están aisladas por usuario y se cifran cuando hay clave de datos. Aprobar requiere permiso schedule:approve y confirmed=true. La revisión debe coincidir; revertir se permite únicamente sin modificaciones posteriores. Android conserva su confirmación local de la previsualización, con comprobación de que los datos no hayan cambiado.

## CP-SAT experimental

Instalar `backend/requirements-optimization.txt` y ejecutar:

```sh
PYTHONPATH=backend python backend/experiments/compare_cpsat.py
```

Resultados reales en [benchmarks/cpsat-evaluation.json](benchmarks/cpsat-evaluation.json): veinte escenarios, ocho tareas independientes por escenario y una ventana de tres horas. El motor actual programa **71** tareas en total y CP-SAT **83**. El objetivo CP-SAT prioriza cobertura y después prioridad; no coincide con el orden de prioridad del motor de producción. Las soluciones reportadas OPTIMAL lo son solo para ese modelo simplificado.

Esta evaluación no cubre recurrencias, dependencias, buffers, zona horaria, historial completado, estabilidad ni distribución entre objetivos. No prueba superioridad general. **CP-SAT no se activa en producción**. Para adoptarlo faltan el modelo completo y una batería comparativa con criterios de aceptación para todos esos casos.

## Autonomía y comportamiento

LearningPreferences prepara suggestions y bounded, con duración, descanso, carga y máximo de movimientos. No existe una opción fully_autonomous válida. Esta entrega conserva la aprobación de planes; todavía no activa un trabajador que aplique adaptaciones por su cuenta. Es una limitación explícita, no un optimizador autónomo ya terminado.

`GET /api/v1/learning/insights` y la herramienta get_behavior_insights proponen ajustes cuando hay al menos tres sesiones comparables, tres omisiones o una dificultad repetida. Devuelven las evidencias, identifican las estimaciones y exigen revisión. No generan duplicados ni tareas acumuladas de forma automática, no penalizan al usuario y no modifican sus registros.
