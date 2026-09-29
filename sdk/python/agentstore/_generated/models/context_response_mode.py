from enum import StrEnum


class ContextResponseMode(StrEnum):
    PRODUCTION = "PRODUCTION"
    REVIEW = "REVIEW"
    SANDBOX = "SANDBOX"
    TRIAL = "TRIAL"

    def __str__(self) -> str:
        return str(self.value)
