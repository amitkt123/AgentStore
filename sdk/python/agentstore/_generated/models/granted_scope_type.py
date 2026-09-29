from enum import StrEnum


class GrantedScopeType(StrEnum):
    READ = "READ"
    WRITE = "WRITE"

    def __str__(self) -> str:
        return str(self.value)
