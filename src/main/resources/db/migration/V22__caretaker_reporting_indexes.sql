CREATE INDEX property_tasks_completion_report ON property_operations_tasks(property_id,completed_at,assignee_id) WHERE completed_at IS NOT NULL;
CREATE INDEX caretaker_events_report ON caretaker_events(property_id,created_at,actor_id);
CREATE INDEX inspection_actions_report ON lease_inspection_events(created_at,actor_id,lease_id);
CREATE INDEX maintenance_actions_report ON work_events(created_at,actor_id,work_id) WHERE party='COORDINATOR';
