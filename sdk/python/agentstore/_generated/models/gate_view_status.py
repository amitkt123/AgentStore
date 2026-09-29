from enum import StrEnum


class GateViewStatus(StrEnum):
    APPROVED = "APPROVED"
    EXPIRED = "EXPIRED"
    PENDING = "PENDING"
    REJECTED = "REJECTED"

    def __str__(self) -> str:
        return str(self.value)
