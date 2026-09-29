from __future__ import annotations

import datetime
from collections.abc import Mapping
from typing import TYPE_CHECKING, Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

if TYPE_CHECKING:
    from ..models.llm import Llm


T = TypeVar("T", bound="Limits")


@_attrs_define
class Limits:
    """
    Attributes:
        deadline_at (datetime.datetime | Unset):
        cost_cap_remaining (float | Unset):
        currency (str | Unset):
        llm (Llm | Unset):
    """

    deadline_at: datetime.datetime | Unset = UNSET
    cost_cap_remaining: float | Unset = UNSET
    currency: str | Unset = UNSET
    llm: Llm | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        deadline_at: str | Unset = UNSET
        if not isinstance(self.deadline_at, Unset):
            deadline_at = self.deadline_at.isoformat()

        cost_cap_remaining = self.cost_cap_remaining

        currency = self.currency

        llm: dict[str, Any] | Unset = UNSET
        if not isinstance(self.llm, Unset):
            llm = self.llm.to_dict()

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update({})
        if deadline_at is not UNSET:
            field_dict["deadlineAt"] = deadline_at
        if cost_cap_remaining is not UNSET:
            field_dict["costCapRemaining"] = cost_cap_remaining
        if currency is not UNSET:
            field_dict["currency"] = currency
        if llm is not UNSET:
            field_dict["llm"] = llm

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        from ..models.llm import Llm

        d = dict(src_dict)
        _deadline_at = d.pop("deadlineAt", UNSET)
        deadline_at: datetime.datetime | Unset
        if isinstance(_deadline_at, Unset):
            deadline_at = UNSET
        else:
            deadline_at = datetime.datetime.fromisoformat(_deadline_at)

        cost_cap_remaining = d.pop("costCapRemaining", UNSET)

        currency = d.pop("currency", UNSET)

        _llm = d.pop("llm", UNSET)
        llm: Llm | Unset
        if isinstance(_llm, Unset):
            llm = UNSET
        else:
            llm = Llm.from_dict(_llm)

        limits = cls(
            deadline_at=deadline_at,
            cost_cap_remaining=cost_cap_remaining,
            currency=currency,
            llm=llm,
        )

        limits.additional_properties = d
        return limits

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
