from __future__ import annotations

import datetime
from collections.abc import Mapping
from typing import Any, TypeVar
from uuid import UUID

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..models.gate_view_status import GateViewStatus
from ..types import UNSET, Unset

T = TypeVar("T", bound="GateView")


@_attrs_define
class GateView:
    """
    Attributes:
        gate_id (UUID | Unset):
        status (GateViewStatus | Unset):
        note (str | Unset):
        expires_at (datetime.datetime | Unset):
        decided_at (datetime.datetime | Unset):
    """

    gate_id: UUID | Unset = UNSET
    status: GateViewStatus | Unset = UNSET
    note: str | Unset = UNSET
    expires_at: datetime.datetime | Unset = UNSET
    decided_at: datetime.datetime | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        gate_id: str | Unset = UNSET
        if not isinstance(self.gate_id, Unset):
            gate_id = str(self.gate_id)

        status: str | Unset = UNSET
        if not isinstance(self.status, Unset):
            status = self.status.value

        note = self.note

        expires_at: str | Unset = UNSET
        if not isinstance(self.expires_at, Unset):
            expires_at = self.expires_at.isoformat()

        decided_at: str | Unset = UNSET
        if not isinstance(self.decided_at, Unset):
            decided_at = self.decided_at.isoformat()

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if gate_id is not UNSET:
            field_dict["gateId"] = gate_id
        if status is not UNSET:
            field_dict["status"] = status
        if note is not UNSET:
            field_dict["note"] = note
        if expires_at is not UNSET:
            field_dict["expiresAt"] = expires_at
        if decided_at is not UNSET:
            field_dict["decidedAt"] = decided_at

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        d = dict(src_dict)
        _gate_id = d.pop("gateId", UNSET)
        gate_id: UUID | Unset
        if isinstance(_gate_id, Unset):
            gate_id = UNSET
        else:
            gate_id = UUID(_gate_id)

        _status = d.pop("status", UNSET)
        status: GateViewStatus | Unset
        if isinstance(_status, Unset):
            status = UNSET
        else:
            status = GateViewStatus(_status)

        note = d.pop("note", UNSET)

        _expires_at = d.pop("expiresAt", UNSET)
        expires_at: datetime.datetime | Unset
        if isinstance(_expires_at, Unset):
            expires_at = UNSET
        else:
            expires_at = datetime.datetime.fromisoformat(_expires_at)

        _decided_at = d.pop("decidedAt", UNSET)
        decided_at: datetime.datetime | Unset
        if isinstance(_decided_at, Unset):
            decided_at = UNSET
        else:
            decided_at = datetime.datetime.fromisoformat(_decided_at)

        gate_view = cls(
            gate_id=gate_id,
            status=status,
            note=note,
            expires_at=expires_at,
            decided_at=decided_at,
        )

        gate_view.additional_properties = d
        return gate_view

    @property
    def additional_keys(self) -> list[str]:
        return list(self.additional_properties.keys())

    def __getitem__(self, key: str) -> Any:
        return self.additional_properties[key]

    def __setitem__(self, key: str, value: Any) -> None:
        self.additional_properties[key] = value

    def __delitem__(self, key: str) -> None:
        del self.additional_properties[key]

    def __contains__(self, key: str) -> bool:
        return key in self.additional_properties
