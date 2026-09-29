from enum import StrEnum


class GateCreatedResponseStatus(StrEnum):
    APPROVED = "APPROVED"
    EXPIRED = "EXPIRED"
    PENDING = "PENDING"
    REJECTED = "REJECTED"

    def __str__(self) -> str:
        return str(self.value)
