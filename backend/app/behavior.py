"""Evidence-based, non-punitive suggestions. Never silently alters plans."""
from collections import Counter
from statistics import median
from uuid import UUID
from pydantic import Field
from .learning_models import Contract
from .learning import normalized

class BehaviorInsight(Contract):
    goalId: UUID
    kind: str
    explanation: str
    recommendation: str
    evidenceIds: list[UUID]
    isEstimate: bool = True
    requiresReview: bool = True

class InsightsResult(Contract):
    items: list[BehaviorInsight]


def insights(memory):
    result = []
    for goal in memory.goals:
        sessions = [s for s in memory.sessions if s.goalId == goal.id]
        comparable = [s for s in sessions if s.status == 'completed' and s.actualMinutes is not None and s.plannedMinutes]
        if len(comparable) >= 3:
            ratio = median(s.actualMinutes / s.plannedMinutes for s in comparable)
            if ratio > 1.25:
                result.append(BehaviorInsight(goalId=goal.id, kind='duration',
                    explanation=f'En {len(comparable)} sesiones con tiempo previsto y real, la mediana fue {ratio:.2f} veces lo estimado.',
                    recommendation='Podés ampliar la estimación o dividir la próxima sesión. Revisá cuál alternativa te resulta más cómoda.',
                    evidenceIds=[s.id for s in comparable]))
        skipped = [s for s in sessions if s.status == 'skipped']
        if len(skipped) >= 3:
            result.append(BehaviorInsight(goalId=goal.id, kind='load',
                explanation=f'Hay {len(skipped)} sesiones registradas como omitidas; no implica falta de esfuerzo.',
                recommendation='Podés probar sesiones más cortas, cambiar de horario o reducir temporalmente la carga. No se acumulan tareas automáticamente.',
                evidenceIds=[s.id for s in skipped]))
        events = [e for e in memory.events if e.goalId == goal.id and e.evidenceType == 'confirmed']
        counts = Counter(normalized(d) for e in events for d in set(e.difficulties))
        for difficulty, count in counts.items():
            if count >= 2:
                evidence = [e.id for e in events if any(normalized(d) == difficulty for d in e.difficulties)]
                result.append(BehaviorInsight(goalId=goal.id, kind='review',
                    explanation=f'La dificultad «{difficulty}» aparece en {len(evidence)} registros confirmados.',
                    recommendation='Podés dedicar una sesión breve de repaso o pedir ejercicios sobre este punto.', evidenceIds=evidence))
    return InsightsResult(items=result[:50])
