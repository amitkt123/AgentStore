from enum import StrEnum


class RunStateResponseStatus(StrEnum):
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    CANCELLED = "CANCELLED"
    COMPLETED = "COMPLETED"
    EXPIRED = "EXPIRED"
    FAILED = "FAILED"
    PENDING = "PENDING"
    PENDING_BUILD = "PENDING_BUILD"
    RUNNING = "RUNNING"
    TIMED_OUT = "TIMED_OUT"

    def __str__(self) -> str:
        return str(self.value)
