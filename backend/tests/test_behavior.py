from datetime import datetime, timezone
from app.behavior import insights
from app.learning_models import LearningGoal, LearningMemory, StudySession, ProgressEvent


def test_no_adaptation_without_evidence():
    goal = LearningGoal(title='Objetivo')
    assert insights(LearningMemory(goals=[goal])).items == []


def test_duration_suggestions_require_three_actual_measurements_and_are_reviewable():
    goal = LearningGoal(title='Objetivo')
    sessions = [StudySession(goalId=goal.id, status='completed', completedAt=datetime.now(timezone.utc), plannedMinutes=30, actualMinutes=60) for _ in range(3)]
    memory = LearningMemory(goals=[goal], sessions=sessions)
    before = memory.model_dump_json()
    result = insights(memory)
    assert len(result.items) == 1 and result.items[0].requiresReview
    assert len(result.items[0].evidenceIds) == 3
    assert memory.model_dump_json() == before
    sessions[0].actualMinutes = None
    assert not insights(memory).items


def test_repeated_difficulty_is_not_a_claim_of_mastery():
    goal = LearningGoal(title='Objetivo')
    events = [ProgressEvent(goalId=goal.id, description='Sesión', occurredAt=datetime.now(timezone.utc), difficulties=['Recursión']) for _ in range(2)]
    result = insights(LearningMemory(goals=[goal], events=events))
    assert result.items[0].kind == 'review'
    assert result.items[0].isEstimate and result.items[0].requiresReview
